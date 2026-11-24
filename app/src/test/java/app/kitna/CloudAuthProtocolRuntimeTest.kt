package app.kitna

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import app.kitna.data.AuthTokens
import app.kitna.data.CloudAuth
import app.kitna.data.CloudFailure
import app.kitna.data.CloudHttp
import app.kitna.data.CloudSession
import app.kitna.data.CredentialVault
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Protocol tests use scripted connections and an in-memory vault: no network or Android Keystore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CloudAuthProtocolRuntimeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val project = "https://kitna-protocol-test.supabase.co"
    private val publicKey = "sb_publishable_protocol_test"
    private val account = CloudSession("11111111-1111-4111-8111-111111111111", "alex@example.com", "Alex")

    private class MemoryVault : CredentialVault {
        val values = mutableMapOf<String, String>()
        override fun read(name: String): String? = values[name]
        override fun write(name: String, value: String?) { if (value == null) values.remove(name) else values[name] = value }
    }
    private data class Response(val status: Int = 200, val body: String = "")
    private class FakeConnection(url: URL, private val reply: Response) : HttpURLConnection(url) {
        val headers = linkedMapOf<String, String>()
        val sent = ByteArrayOutputStream()
        var disconnected = false
        var fixedLength: Int? = null
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun setRequestProperty(key: String, value: String) { headers[key] = value }
        override fun setFixedLengthStreamingMode(contentLength: Int) { fixedLength = contentLength }
        override fun getOutputStream(): OutputStream = sent
        override fun getResponseCode() = reply.status
        override fun getInputStream(): InputStream = ByteArrayInputStream(reply.body.toByteArray(Charsets.UTF_8))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(reply.body.toByteArray(Charsets.UTF_8))
    }
    private class ScriptedNetwork(vararg responses: Response) {
        private val replies = ArrayDeque(responses.toList())
        val calls = mutableListOf<FakeConnection>()
        fun open(url: URL): HttpURLConnection {
            check(replies.isNotEmpty()) { "Unexpected extra HTTP request in the protocol test." }
            return FakeConnection(url, replies.removeFirst()).also(calls::add)
        }
    }
    private fun http(network: ScriptedNetwork) = CloudHttp(project, publicKey, network::open)
    private fun tokens(access: String = "access-one", refresh: String = "refresh-one", expiresAt: Long = System.currentTimeMillis() / 1000 + 3600): String = buildJsonObject {
        put("access_token", access); put("refresh_token", refresh); put("expires_at", expiresAt)
        putJsonObject("user") { put("id", account.id); put("email", account.email); putJsonObject("user_metadata") { put("full_name", account.name) } }
    }.toString()
    private fun pending(vault: MemoryVault): JsonObject = Json.parseToJsonElement(vault.values.getValue("pkce")).jsonObject
    private suspend fun failure(action: suspend () -> Unit): Throwable {
        try { action() } catch (problem: Throwable) { return problem }
        throw AssertionError("Expected the operation to fail")
    }

    @Test fun beginCreatesS256PkceWithoutPuttingTheVerifierOrApiKeyInTheBrowserUrl() = runBlocking {
        val vault = MemoryVault()
        val network = ScriptedNetwork()
        val auth = CloudAuth(context, http(network), vault)
        val startedAt = System.currentTimeMillis()
        val url = auth.begin()
        val finishedAt = System.currentTimeMillis()
        val uri = Uri.parse(url)
        val stored = pending(vault)
        val verifier = stored.getValue("verifier").jsonPrimitive.content
        val expectedChallenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        assertEquals("https", uri.scheme)
        assertEquals("kitna-protocol-test.supabase.co", uri.host)
        assertEquals("/auth/v1/authorize", uri.path)
        assertEquals("google", uri.getQueryParameter("provider"))
        assertEquals("app.kitna://auth/callback", uri.getQueryParameter("redirect_to"))
        assertEquals("s256", uri.getQueryParameter("code_challenge_method"))
        assertEquals(expectedChallenge, uri.getQueryParameter("code_challenge"))
        assertTrue(verifier.matches(Regex("[A-Za-z0-9_-]{43,128}")))
        assertFalse(url.contains(verifier))
        assertFalse(url.contains(publicKey))
        assertTrue(stored.getValue("created").jsonPrimitive.long in startedAt..finishedAt)
        assertTrue(network.calls.isEmpty())
    }

    @Test fun exchangeSendsCodeAndVerifierInJsonThenRejectsAReplayedCallback() = runBlocking {
        val vault = MemoryVault()
        val network = ScriptedNetwork(Response(body = tokens()))
        val auth = CloudAuth(context, http(network), vault)
        auth.begin()
        val verifier = pending(vault).getValue("verifier").jsonPrimitive.content
        val callback = Uri.parse("app.kitna://auth/callback?code=authorization-code")
        val result = auth.exchange(callback)
        val request = network.calls.single()
        val body = Json.parseToJsonElement(request.sent.toString("UTF-8")).jsonObject
        assertEquals("POST", request.requestMethod)
        assertEquals("/auth/v1/token?grant_type=pkce", request.url.file)
        assertEquals(publicKey, request.headers["apikey"])
        assertEquals("application/json", request.headers["Content-Type"])
        assertNull(request.headers["Authorization"])
        assertEquals(setOf("auth_code", "code_verifier"), body.keys)
        assertEquals("authorization-code", body.getValue("auth_code").jsonPrimitive.content)
        assertEquals(verifier, body.getValue("code_verifier").jsonPrimitive.content)
        assertEquals(request.sent.size(), request.fixedLength)
        assertEquals(account, result.user)
        assertEquals("access-one", result.accessToken)
        assertNull(vault.read("pkce"))
        assertTrue(request.disconnected)
        assertTrue(failure { auth.exchange(callback) } is IllegalStateException)
        assertEquals(1, network.calls.size)
    }

    @Test fun unexpectedMissingOrExpiredCallbacksNeverReachTheTokenEndpoint() = runBlocking {
        val vault = MemoryVault()
        val network = ScriptedNetwork()
        val auth = CloudAuth(context, http(network), vault)
        auth.begin()
        for (url in listOf("https://auth/callback?code=x", "app.kitna://other/callback?code=x", "app.kitna://auth/elsewhere?code=x", "app.kitna://auth/callback")) {
            assertTrue(failure { auth.exchange(Uri.parse(url)) } is IllegalArgumentException)
        }
        val current = pending(vault)
        for (created in listOf(System.currentTimeMillis() - 600001, System.currentTimeMillis() + 60000)) {
            vault.write("pkce", JsonObject(current.toMutableMap().also { it["created"] = JsonPrimitive(created) }).toString())
            assertTrue(failure { auth.exchange(Uri.parse("app.kitna://auth/callback?code=x")) } is IllegalArgumentException)
        }
        vault.write("pkce", null)
        assertTrue(failure { auth.exchange(Uri.parse("app.kitna://auth/callback?code=x")) } is IllegalStateException)
        assertTrue(network.calls.isEmpty())
    }

    @Test fun acceptedSessionReopensAndRefreshRotationReplacesThePersistedCredentials() = runBlocking {
        val vault = MemoryVault()
        val network = ScriptedNetwork(Response(body = tokens("access-two", "refresh-two")), Response(body = tokens("access-three", "refresh-three")))
        val auth = CloudAuth(context, http(network), vault)
        auth.accept(AuthTokens("access-one", "refresh-one", 1, account))
        val reopened = CloudAuth(context, http(network), vault)
        assertEquals(account, reopened.load())
        assertEquals("access-two", reopened.token())
        val request = network.calls.single()
        assertEquals("/auth/v1/token?grant_type=refresh_token", request.url.file)
        assertEquals("refresh-one", Json.parseToJsonElement(request.sent.toString("UTF-8")).jsonObject.getValue("refresh_token").jsonPrimitive.content)
        assertEquals("refresh-two", reopened.tokens!!.refreshToken)
        val later = CloudAuth(context, http(network), vault)
        assertEquals(account, later.load())
        assertEquals("refresh-two", later.tokens!!.refreshToken)
        assertEquals("access-two", later.token()) // A still-valid token is reused without a second request.
        assertEquals(1, network.calls.size)
        assertEquals("access-three", later.token(forceRefresh = true))
        assertEquals("refresh-two", Json.parseToJsonElement(network.calls.last().sent.toString("UTF-8")).jsonObject.getValue("refresh_token").jsonPrimitive.content)
        assertEquals("refresh-three", later.tokens!!.refreshToken)
        assertEquals(2, network.calls.size)
        assertTrue(request.disconnected)
    }

    @Test fun aRejectedRefreshKeepsTheLastSavedSessionInsteadOfPartiallyReplacingIt() = runBlocking {
        val vault = MemoryVault()
        val network = ScriptedNetwork(Response(401, """{"error_code":"refresh_token_not_found","msg":"Sign in again"}"""))
        val auth = CloudAuth(context, http(network), vault)
        val original = AuthTokens("old-access", "old-refresh", 1, account)
        auth.accept(original)
        val before = vault.read("session")
        val rejected = failure { auth.token() }
        assertTrue(rejected is CloudFailure)
        assertEquals(401, (rejected as CloudFailure).status)
        assertEquals("refresh_token_not_found", rejected.code)
        assertEquals(before, vault.read("session"))
        assertEquals(original, auth.tokens)
    }

    @Test fun clearingTheAccountRemovesBothSessionAndPendingBrowserSignIn() = runBlocking {
        val vault = MemoryVault()
        val auth = CloudAuth(context, http(ScriptedNetwork()), vault)
        auth.accept(AuthTokens("access", "refresh", 1, account)); auth.begin()
        assertNotNull(vault.read("pkce")); assertNotNull(vault.read("session"))
        auth.clear()
        assertNull(auth.tokens)
        assertTrue(vault.values.isEmpty())
        assertNull(CloudAuth(context, http(ScriptedNetwork()), vault).load())
    }

    @Test fun authenticatedJsonAndPhotoRequestsUseDifferentContentTypesAndNeverFollowRedirects() = runBlocking {
        val network = ScriptedNetwork(Response(body = "{}"), Response(201), Response(204))
        val http = http(network)
        http.json("POST", "/rest/v1/rpc/test", "user-access", buildJsonObject { put("amount", 12345) })
        http.request("POST", "/storage/v1/object/receipts/test.jpg", "user-access", bytes = byteArrayOf(1, 2, 3))
        assertEquals(JsonNull, http.json("GET", "/rest/v1/empty", "user-access"))
        assertEquals("application/json", network.calls[0].headers["Content-Type"])
        assertEquals("image/jpeg", network.calls[1].headers["Content-Type"])
        assertArrayEquals(byteArrayOf(1, 2, 3), network.calls[1].sent.toByteArray())
        network.calls.forEach { request ->
            assertEquals(publicKey, request.headers["apikey"])
            assertEquals("Bearer user-access", request.headers["Authorization"])
            assertFalse(request.instanceFollowRedirects)
            assertEquals(15000, request.connectTimeout)
            assertEquals(20000, request.readTimeout)
            assertTrue(request.disconnected)
        }
    }

    @Test fun httpFailureMappingPreservesCodesAndLimitsRemoteMessageSize() = runBlocking {
        val network = ScriptedNetwork(
            Response(404, """{"code":"PGRST202","message":"Missing function"}"""),
            Response(429, """{"message":"Limit reached"}"""),
            Response(409, buildJsonObject { put("code", "conflict"); put("message", "x".repeat(500)) }.toString()),
            Response(500, "not-json"), Response(302)
        )
        val client = http(network)
        val missing = failure { client.json("POST", "/rest/v1/rpc/missing") } as CloudFailure
        assertEquals("PGRST202", missing.code); assertTrue(missing.message!!.contains("database setup"))
        val limited = failure { client.json("GET", "/limited") } as CloudFailure
        assertEquals(429, limited.status); assertTrue(limited.message!!.contains("pending changes are saved"))
        val conflict = failure { client.json("GET", "/conflict") } as CloudFailure
        assertEquals("conflict", conflict.code); assertEquals(300, conflict.message!!.length)
        val malformed = failure { client.json("GET", "/malformed") } as CloudFailure
        assertEquals("http_500", malformed.code)
        val redirect = failure { client.json("GET", "/redirect") } as CloudFailure
        assertEquals(302, redirect.status)
        network.calls.forEach { assertTrue(it.disconnected) }
    }

    @Test fun oversizedResponsesAndInvalidConfigurationFailBeforeAnyStateCanBeAccepted() = runBlocking {
        val network = ScriptedNetwork(Response(body = "123456789"))
        val client = http(network)
        val oversized = failure { client.request("GET", "/large", maxBytes = 8) }
        assertTrue(oversized is IllegalArgumentException)
        assertTrue(oversized.message!!.contains("supported size"))
        assertTrue(network.calls.single().disconnected)
        val unused = ScriptedNetwork()
        val misconfigured = CloudHttp("http://insecure.invalid", publicKey, unused::open)
        assertFalse(misconfigured.configured)
        assertTrue(failure { misconfigured.request("GET", "/test") } is IllegalStateException)
        assertTrue(failure { client.request("GET", "not-a-path") } is IllegalArgumentException)
        assertTrue(unused.calls.isEmpty())
        assertEquals(1, network.calls.size)
    }
}
