package app.kitna

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.kitna.core.*
import app.kitna.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Exercises the actual coordinator loop with durable Room books and deterministic HTTP responses. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CloudCoordinatorRuntimeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val friend = "22222222-2222-4222-8222-222222222222"
    private val session = CloudSession(owner, "owner@example.com", "Owner")
    private val json = SharedBooks.json

    @Before fun clearPreviousDatabase() { context.deleteDatabase("kitna.db") }

    private class MemoryVault : CredentialVault {
        private val values = mutableMapOf<String, String>()
        override fun read(name: String): String? = values[name]
        override fun write(name: String, value: String?) { if (value == null) values.remove(name) else values[name] = value }
    }
    private data class Reply(val path: String, val status: Int = 200, val body: String = "{}", val offline: Boolean = false)
    private class Connection(url: URL, private val reply: Reply) : HttpURLConnection(url) {
        val sent = ByteArrayOutputStream()
        val headers = mutableMapOf<String, String>()
        var disconnected = false
        override fun connect() {}
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun setRequestProperty(key: String, value: String) { headers[key] = value }
        override fun getOutputStream(): OutputStream = sent
        override fun getResponseCode(): Int { if (reply.offline) throw IOException("Test phone is offline"); return reply.status }
        override fun getInputStream(): InputStream = ByteArrayInputStream(reply.body.toByteArray())
        override fun getErrorStream(): InputStream = ByteArrayInputStream(reply.body.toByteArray())
    }
    private class Network(vararg replies: Reply) {
        private val remaining = ArrayDeque(replies.toList())
        val calls = mutableListOf<Connection>()
        fun open(url: URL): HttpURLConnection {
            check(remaining.isNotEmpty()) { "Unexpected extra coordinator request: ${url.path}" }
            val reply = remaining.removeFirst()
            assertEquals("Coordinator request order", reply.path, url.path)
            return Connection(url, reply).also(calls::add)
        }
    }
    private data class Fixture(val queued: Book, val initial: SharedSnapshot)
    private fun fixture(): Fixture {
        val trip = Group("goa", "Goa trip", listOf("self", "alex"), kind = "Trip", budget = 100000)
        val expense = Expense("hotel", trip.id, "Hotel room", 50000, mapOf("self" to 50000), mapOf("self" to 25000, "alex" to 25000), date = "2026-11-24", notes = "Original saved note")
        val local = Book(people = listOf(Person("self", "Owner"), Person("alex", "Alex")), groups = listOf(trip), expenses = listOf(expense))
        val prepared = SharedBooks.prepareShare(local, trip.id, owner)
        val share = prepared.pendingChanges.single()
        val payload = json.parseToJsonElement(share.payload).jsonObject
        val snapshot = SharedSnapshot(
            group = json.decodeFromJsonElement<RemoteGroup>(payload.getValue("p_group")).copy(ownerId = owner),
            participants = payload.getValue("p_participants").jsonArray.map { item ->
                val row = item.jsonObject
                RemoteParticipant(row.getValue("id").jsonPrimitive.content, row.getValue("name").jsonPrimitive.content, if (row.getValue("isSelf").jsonPrimitive.boolean) owner else friend)
            },
            records = payload.getValue("p_records").jsonArray.map { item ->
                val row = item.jsonObject
                RemoteRecord(row.getValue("kind").jsonPrimitive.content, row.getValue("id").jsonPrimitive.content, row.getValue("payload").jsonObject, 1)
            },
            myParticipantId = prepared.sharedGroups.single().myParticipantId, revision = 1
        )
        val accepted = SharedBooks.applySnapshot(prepared, snapshot, owner, share.id)
        val first = SharedBooks.queueChanges(accepted, BookActions.saveExpense(accepted, accepted.expenses.single().copy(notes = "First offline draft")), owner)
        val second = SharedBooks.queueChanges(first, BookActions.saveExpense(first, first.expenses.single().copy(notes = "Latest offline draft")), owner)
        assertEquals(listOf(1L, 2L), second.pendingChanges.map { it.expectedVersion })
        return Fixture(second, snapshot)
    }
    private fun serverSaved(snapshot: SharedSnapshot, notes: String): SharedSnapshot {
        val old = snapshot.records.single { it.kind == "expense" }
        val expense = json.decodeFromJsonElement<Expense>(old.payload).copy(notes = notes)
        return snapshot.copy(records = listOf(old.copy(payload = json.encodeToJsonElement(expense).jsonObject, version = old.version + 1)), revision = snapshot.revision + 1)
    }
    private fun serverAccepted(snapshot: SharedSnapshot, change: SharedChange): SharedSnapshot = snapshot.copy(
        records = listOf(RemoteRecord(change.kind, change.entityId, json.parseToJsonElement(change.payload).jsonObject, change.expectedVersion + 1)), revision = snapshot.revision + 1
    )
    private fun syncResponse(snapshot: SharedSnapshot, includeSnapshot: Boolean = true): String = buildJsonObject {
        put("groups", JsonArray(if (includeSnapshot) listOf(json.encodeToJsonElement(snapshot)) else emptyList()))
        put("memberGroupIds", JsonArray(listOf(JsonPrimitive(snapshot.group.id))))
    }.toString()
    private suspend fun coordinator(store: LocalStore, scope: CoroutineScope, network: Network): CloudCoordinator {
        val http = CloudHttp("https://kitna-coordinator-test.supabase.co", "sb_publishable_test", network::open)
        val auth = CloudAuth(context, http, MemoryVault())
        auth.accept(AuthTokens("user-access", "user-refresh", System.currentTimeMillis() / 1000 + 3600, session))
        return CloudCoordinator(context, store, scope, CloudServices(http, auth)).also {
            it.setForeground(false)
            it.initialize()
            assertEquals(session, it.state.value.session)
        }
    }
    private suspend fun loadBook(expected: Book): LocalStore = LocalStore(context).also { it.load(); it.replace(expected) }
    private fun calls(network: Network, name: String) = network.calls.filter { it.url.path == "/rest/v1/rpc/$name" }

    @Test fun shortCodeUsesTheAuthenticatedOwnerRpcAfterProtocolTwoSync() = runBlocking {
        val fixture = fixture()
        val accepted = SharedBooks.applySnapshot(fixture.queued.copy(pendingChanges = emptyList()), fixture.initial, owner)
        val network = Network(Reply("/rest/v1/rpc/kitna_status", body = """{"ready":true,"schemaVersion":2}"""), Reply("/rest/v1/rpc/kitna_sync", body = syncResponse(fixture.initial, false)), Reply("/rest/v1/rpc/kitna_group_code", body = """{"code":"ABCD-EFGH-2345","expiresAt":"2026-12-01T00:00:00Z"}"""))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = loadBook(accepted)
        try { val cloud = coordinator(store,scope,network);cloud.sync().getOrThrow();assertTrue(cloud.state.value.invitationsReady);assertEquals("ABCD-EFGH-2345",cloud.groupCode(accepted.groups.single().id).getOrThrow().first);assertEquals("Bearer user-access",network.calls.last().headers["Authorization"]);assertEquals(fixture.initial.group.id,json.parseToJsonElement(network.calls.last().sent.toString("UTF-8")).jsonObject["p_group_id"]?.jsonPrimitive?.content) }
        finally { scope.cancel();store.close() }
    }
    @Test fun invalidCodeJsonResponseKeepsTheBookAndShowsTheServerExplanation() = runBlocking {
        val network = Network(Reply("/rest/v1/rpc/kitna_accept_invite",body="""{"error":"rate_limited","message":"Too many code attempts. Try again in 15 minutes."}"""))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = loadBook(Book())
        try { val cloud=coordinator(store,scope,network);val result=cloud.join("ABCD-EFGH-2345");assertTrue(result.isFailure);assertTrue(result.exceptionOrNull()!!.message!!.contains("15 minutes"));assertEquals(Book(),store.book.value) }
        finally {scope.cancel();store.close()}
    }
    @Test fun firstConflictStopsLaterQueuedEditsWhileRefreshingTheServerSnapshotAndKeepingTheDraft() = runBlocking {
        val fixture = fixture()
        val saved = serverSaved(fixture.initial, "Another member's saved note")
        val network = Network(
            Reply("/rest/v1/rpc/kitna_status", body = """{"ready":true}"""),
            Reply("/rest/v1/rpc/kitna_apply", 409, """{"code":"40001","message":"Stale record version"}"""),
            Reply("/rest/v1/rpc/kitna_sync", body = syncResponse(saved))
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = loadBook(fixture.queued)
        try {
            val cloud = coordinator(store, scope, network)
            cloud.sync().getOrThrow()
            assertEquals("A conflicted earlier command must block the next queued version", 1, calls(network, "kitna_apply").size)
            val after = store.book.value!!
            assertEquals(fixture.queued.pendingChanges.map { it.id }, after.pendingChanges.map { it.id })
            assertTrue(after.pendingChanges.all { it.conflict && it.error?.contains("draft is saved") == true })
            assertEquals("Latest offline draft", after.expenses.single().notes)
            val cachedServer = after.sharedGroups.single().snapshot!!
            assertEquals(2L, cachedServer.revision)
            assertEquals("Another member's saved note", json.decodeFromJsonElement<Expense>(cachedServer.records.single().payload).notes)
            assertFalse(cloud.state.value.busy)
            assertFalse(after.sharedGroups.single().revoked)
        } finally { scope.cancel(); store.close() }
        val reopened = LocalStore(context)
        try {
            reopened.load()
            assertEquals("Latest offline draft", reopened.book.value!!.expenses.single().notes)
            assertTrue(reopened.book.value!!.pendingChanges.all { it.conflict })
        } finally { reopened.close() }
    }

    @Test fun aNetworkFailureKeepsTheEntireDurableQueueAndOptimisticLedgerForRetry() = runBlocking {
        val fixture = fixture()
        val network = Network(Reply("/rest/v1/rpc/kitna_status", body = """{"ready":true}"""), Reply("/rest/v1/rpc/kitna_apply", offline = true))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = loadBook(fixture.queued)
        try {
            val cloud = coordinator(store, scope, network)
            assertTrue(cloud.sync().isFailure)
            assertEquals(fixture.queued, store.book.value)
            assertEquals(1, calls(network, "kitna_apply").size)
            assertTrue(calls(network, "kitna_sync").isEmpty())
            assertTrue(store.book.value!!.pendingChanges.all { it.error == null && !it.conflict })
            assertFalse(cloud.state.value.busy)
            assertTrue(network.calls.all { it.disconnected })
        } finally { scope.cancel(); store.close() }
        val reopened = LocalStore(context)
        try { reopened.load(); assertEquals(fixture.queued, reopened.book.value) } finally { reopened.close() }
    }

    @Test fun reconnectAfterReopeningUsesTheOriginalCommandIdsAndAcknowledgesVersionsInOrder() = runBlocking {
        val fixture = fixture()
        val initialStore = loadBook(fixture.queued)
        initialStore.close() // Represents stopping and reopening the app with its pending queue intact.
        val savedFirst = serverAccepted(fixture.initial, fixture.queued.pendingChanges[0])
        val savedSecond = serverAccepted(savedFirst, fixture.queued.pendingChanges[1])
        val network = Network(
            Reply("/rest/v1/rpc/kitna_status", body = """{"ready":true}"""),
            Reply("/rest/v1/rpc/kitna_apply", body = json.encodeToString(savedFirst)),
            Reply("/rest/v1/rpc/kitna_apply", body = json.encodeToString(savedSecond)),
            Reply("/rest/v1/rpc/kitna_sync", body = syncResponse(savedSecond, includeSnapshot = false))
        )
        val store = LocalStore(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            store.load()
            val cloud = coordinator(store, scope, network)
            cloud.sync().getOrThrow()
            val commands = calls(network, "kitna_apply").map { json.parseToJsonElement(it.sent.toString("UTF-8")).jsonObject }
            assertEquals(fixture.queued.pendingChanges.map { it.id }, commands.map { it.getValue("p_command_id").jsonPrimitive.content })
            assertEquals(listOf(1L, 2L), commands.map { it.getValue("p_expected_version").jsonPrimitive.long })
            assertTrue(store.book.value!!.pendingChanges.isEmpty())
            assertEquals("Latest offline draft", store.book.value!!.expenses.single().notes)
            assertEquals(3L, store.book.value!!.sharedGroups.single().snapshot!!.records.single().version)
            assertNotNull(cloud.state.value.lastSync)
        } finally { scope.cancel(); store.close() }
        val reopened = LocalStore(context)
        try {
            reopened.load()
            assertTrue(reopened.book.value!!.pendingChanges.isEmpty())
            assertEquals("Latest offline draft", reopened.book.value!!.expenses.single().notes)
        } finally { reopened.close() }
    }
}
