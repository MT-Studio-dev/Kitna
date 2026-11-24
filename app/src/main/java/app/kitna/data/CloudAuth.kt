package app.kitna.data

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.kitna.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable data class CloudSession(val id: String, val email: String, val name: String)
@Serializable internal data class AuthTokens(val accessToken: String, val refreshToken: String, val expiresAt: Long, val user: CloudSession)
class CloudFailure(val status: Int, val code: String, message: String) : IllegalStateException(message)

internal class CloudHttp(private val base: String = BuildConfig.SUPABASE_URL, private val key: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    val configured get() = base.startsWith("https://") && key.startsWith("sb_publishable_")
    val projectUrl get() = base.trimEnd('/')
    suspend fun request(method: String, path: String, token: String? = null, json: JsonElement? = null,
        bytes: ByteArray? = null, maxBytes: Int = 20 * 1024 * 1024): ByteArray = withContext(Dispatchers.IO) {
        check(configured) { "Supabase is not configured in this build." }
        require(path.startsWith("/"))
        val connection = connectionFactory(URL(projectUrl + path))
        try {
            connection.requestMethod = method; connection.connectTimeout = 15_000; connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("apikey", key)
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            val body = bytes ?: json?.toString()?.toByteArray(Charsets.UTF_8)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", if (bytes != null) "image/jpeg" else "application/json")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val content = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break
                    require(output.size().toLong() + count <= maxBytes) { "The server response exceeds this device's supported size." }
                    output.write(buffer, 0, count)
                }; output.toByteArray()
            } ?: byteArrayOf()
            if (status !in 200..299) {
                val error = runCatching { Json.parseToJsonElement(content.toString(Charsets.UTF_8)).jsonObject }.getOrNull()
                val code = error?.get("code")?.jsonPrimitive?.contentOrNull ?: error?.get("error_code")?.jsonPrimitive?.contentOrNull ?: "http_$status"
                val message = (error?.get("message") ?: error?.get("error_description") ?: error?.get("msg"))?.jsonPrimitive?.contentOrNull
                throw CloudFailure(status, code, when {
                    code == "PGRST202" -> "Shared groups need the Kitna database setup. Open the cloud deployment guide and apply its migration."
                    status == 429 -> "Too many requests. Wait a little and retry; your pending changes are saved."
                    else -> message?.take(300) ?: "The server could not complete this request ($status)."
                })
            }
            content
        } finally { connection.disconnect() }
    }
    suspend fun json(method: String, path: String, token: String? = null, body: JsonElement? = null): JsonElement {
        val bytes = request(method, path, token, body)
        return if (bytes.isEmpty()) JsonNull else Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
    }
}

/** Only ciphertext is kept in preferences. The AES key never leaves Android Keystore. */
internal interface CredentialVault { fun read(name: String): String?; fun write(name: String, value: String?) }
internal class SessionVault(context: Context) : CredentialVault {
    private val preferences = context.getSharedPreferences("cloud_credentials", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("kitna-cloud-session", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("kitna-cloud-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun read(name: String): String? {
        val text = preferences.getString(name, null) ?: return null
        val pieces = text.split(':', limit = 2); require(pieces.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
    override fun write(name: String, value: String?) {
        if (value == null) { check(preferences.edit().remove(name).commit()); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(preferences.edit().putString(name, encoded).commit()) { "Could not securely save the account on this device." }
    }
}

internal class CloudAuth(context: Context, private val http: CloudHttp, private val vault: CredentialVault = SessionVault(context.applicationContext)) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    var tokens: AuthTokens? = null; private set
    suspend fun load(): CloudSession? = withContext(Dispatchers.IO) {
        tokens = vault.read("session")?.let { json.decodeFromString<AuthTokens>(it) }; tokens?.user
    }
    suspend fun begin(): String = withContext(Dispatchers.IO) {
        check(http.configured) { "Supabase is not configured in this build." }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val verifier = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        vault.write("pkce", buildJsonObject { put("verifier", verifier); put("created", System.currentTimeMillis()) }.toString())
        Uri.parse(http.projectUrl + "/auth/v1/authorize").buildUpon().appendQueryParameter("provider", "google")
            .appendQueryParameter("redirect_to", "app.kitna://auth/callback").appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "s256").appendQueryParameter("prompt", "select_account").build().toString()
    }
    suspend fun exchange(uri: Uri): AuthTokens = mutex.withLock {
        require(uri.scheme == "app.kitna" && uri.host == "auth" && uri.path == "/callback") { "Unexpected sign-in callback." }
        uri.getQueryParameter("error_description")?.let { throw IllegalArgumentException(it.take(200)) }
        val code = requireNotNull(uri.getQueryParameter("code")) { "Google sign-in did not return an authorization code." }
        val pending = withContext(Dispatchers.IO) { vault.read("pkce") }?.let { json.parseToJsonElement(it).jsonObject }
            ?: throw IllegalStateException("Start Google sign-in again from this device.")
        require(System.currentTimeMillis() - pending.getValue("created").jsonPrimitive.long in 0..600_000) { "Sign-in expired. Start Google sign-in again." }
        val body = buildJsonObject { put("auth_code", code); put("code_verifier", pending.getValue("verifier")) }
        val result = parse(http.json("POST", "/auth/v1/token?grant_type=pkce", body = body).jsonObject)
        withContext(Dispatchers.IO) { vault.write("pkce", null) }
        result
    }
    suspend fun accept(value: AuthTokens) = withContext(Dispatchers.IO) { vault.write("session", json.encodeToString(value)); tokens = value }
    suspend fun token(forceRefresh: Boolean = false): String = mutex.withLock {
        val current = checkNotNull(tokens) { "Sign in with Google to use shared groups." }
        if (!forceRefresh && current.expiresAt > System.currentTimeMillis() / 1000 + 60) return@withLock current.accessToken
        val body = buildJsonObject { put("refresh_token", current.refreshToken) }
        val refreshed = parse(http.json("POST", "/auth/v1/token?grant_type=refresh_token", body = body).jsonObject)
        require(refreshed.user.id == current.user.id) { "The refreshed account identity did not match. Sign in with Google again." }
        accept(refreshed); refreshed.accessToken
    }
    suspend fun clear() = withContext(Dispatchers.IO) { vault.write("session", null); vault.write("pkce", null); tokens = null }
    private fun parse(value: JsonObject): AuthTokens {
        val user = value.getValue("user").jsonObject
        val id = user.getValue("id").jsonPrimitive.content.also { UUID.fromString(it) }
        val email = user["email"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val metadata = user["user_metadata"]?.jsonObject
        val name = (metadata?.get("full_name") ?: metadata?.get("name"))?.jsonPrimitive?.contentOrNull
            ?.take(100)?.takeIf { it.isNotBlank() } ?: email.substringBefore('@').take(100).ifBlank { "Kitna member" }
        val expires = value["expires_at"]?.jsonPrimitive?.longOrNull ?: (System.currentTimeMillis() / 1000 + value.getValue("expires_in").jsonPrimitive.long)
        return AuthTokens(value.getValue("access_token").jsonPrimitive.content, value.getValue("refresh_token").jsonPrimitive.content, expires, CloudSession(id, email, name))
    }
}
