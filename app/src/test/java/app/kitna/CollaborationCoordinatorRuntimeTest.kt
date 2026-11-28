package app.kitna

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.kitna.core.*
import app.kitna.data.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Real coordinator + Room, with deterministic transport. This is not hosted OAuth/device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class CollaborationCoordinatorRuntimeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val friend = "22222222-2222-4222-8222-222222222222"
    private val session = CloudSession(owner, "owner@example.com", "Owner")
    private val json = SharedBooks.json
    private val token = "ab".repeat(32)
    private val invitationId = "77777777-7777-4777-8777-777777777777"
    @Before fun clean() {
        context.deleteDatabase("kitna.db")
        context.getSharedPreferences("pending_invitations", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("friend_connections", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private class MemoryVault : CredentialVault {
        private val entries = mutableMapOf<String, String>()
        override fun read(name: String) = entries[name]
        override fun write(name: String, value: String?) { if (value == null) entries.remove(name) else entries[name] = value }
    }
    private data class Reply(val path: String, val body: String = "{}", val offline: Boolean = false)
    private class Connection(url: URL, private val reply: Reply) : HttpURLConnection(url) {
        val sent = ByteArrayOutputStream()
        val headers = mutableMapOf<String, String>()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun setRequestProperty(key: String, value: String) { headers[key] = value }
        override fun getOutputStream(): OutputStream = sent
        override fun getResponseCode(): Int { if (reply.offline) throw IOException("Response lost after server accepted"); return 200 }
        override fun getInputStream(): InputStream = ByteArrayInputStream(reply.body.toByteArray())
    }
    private class Network(vararg replies: Reply) {
        private val pending = ArrayDeque(replies.toList())
        val calls = mutableListOf<Connection>()
        fun open(url: URL): HttpURLConnection {
            check(pending.isNotEmpty()) { "Unexpected HTTP request: ${url.path}" }
            val reply = pending.removeFirst()
            assertEquals(reply.path, url.path)
            return Connection(url, reply).also(calls::add)
        }
    }
    private suspend fun cloud(store: LocalStore, scope: CoroutineScope, network: Network, vault: MemoryVault = MemoryVault(), signedIn: Boolean = true): CloudCoordinator {
        val http = CloudHttp("https://kitna-collaboration-test.supabase.co", "sb_publishable_test", network::open)
        val auth = CloudAuth(context, http, vault)
        if (signedIn && vault.read("session") == null) auth.accept(AuthTokens("user-access", "user-refresh", System.currentTimeMillis() / 1000 + 3600, session))
        return CloudCoordinator(context, store, scope, CloudServices(http, auth)).also { it.setForeground(false); it.initialize() }
    }
    private suspend fun store(book: Book) = LocalStore(context).also { it.load(); it.replace(book) }
    private fun sync(snapshot: SharedSnapshot? = null) = buildJsonObject {
        put("groups", JsonArray(emptyList()))
        put("memberGroupIds", JsonArray(listOfNotNull(snapshot?.group?.id?.let(::JsonPrimitive))))
    }.toString()
    private fun accepted(local: Book, groupId: String, peerAccount: Boolean = false): Pair<Book, SharedSnapshot> {
        val prepared = SharedBooks.prepareShare(local, groupId, owner)
        val link = prepared.sharedGroups.single()
        val group = local.groups.single { it.id == groupId }
        val snapshot = SharedSnapshot(RemoteGroup(link.remoteId, group.name, kind = group.kind, ownerId = owner),
            group.members.map { id -> RemoteParticipant(link.people.getValue(id), local.people.single { it.id == id }.name, if (id == "self") owner else if (peerAccount) friend else null) },
            myParticipantId = link.myParticipantId, revision = 1)
        return SharedBooks.applySnapshot(prepared, snapshot, owner, prepared.pendingChanges.single().id) to snapshot
    }

    @Test fun pendingInvitationSurvivesRestartAndGoogleCallbackWithoutAutomaticAcceptance() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = store(Book())
        val vault = MemoryVault()
        val authBody = """{"access_token":"user-access","refresh_token":"user-refresh","expires_at":${System.currentTimeMillis()/1000+3600},"user":{"id":"$owner","email":"owner@example.com","user_metadata":{"full_name":"Owner"}}}"""
        val network = Network(Reply("/auth/v1/token", authBody), Reply("/rest/v1/rpc/kitna_sync", sync()))
        try {
            val first = cloud(store, scope, network, vault, signedIn = false)
            first.receive(Uri.parse(InvitationLinks.url("friend", token)))
            first.beginSignIn().getOrThrow()
            val reopened = cloud(store, scope, network, vault, signedIn = false)
            assertEquals(token, reopened.state.value.pendingInvite)
            assertEquals("friend", reopened.state.value.pendingInviteKind)
            reopened.receive(Uri.parse("app.kitna://auth/callback?code=authorization-code"))
            scope.coroutineContext[Job]!!.children.toList().joinAll()
            assertEquals(session, reopened.state.value.session)
            assertEquals(token, reopened.state.value.pendingInvite)
            assertTrue(store.book.value!!.people.none { it.id != "self" })
            assertTrue(network.calls.none { it.url.path.contains("accept") })
            val afterLoginRestart = cloud(store, scope, Network(), vault)
            assertEquals(token, afterLoginRestart.state.value.pendingInvite)
            afterLoginRestart.dismissInvitation()
            assertNull(cloud(store, scope, Network(), vault).state.value.pendingInvite)
        } finally { scope.cancel(); store.close() }
    }

    @Test fun unaddressedFriendRequiresSenderConfirmationBeforeMergingHistoricalIdentity() = runBlocking {
        val local = Book(people = listOf(Person("self", "Owner"), Person("historical", "Rahul"), Person("registered", "Rahul account", accountId = friend)),
            groups = listOf(Group("dinner", "Rahul", listOf("self", "historical"), kind = "Direct"), Group("trip", "Trip", listOf("self", "registered"))),
            expenses = listOf(Expense("old-bill", "dinner", "Dinner", 101, mapOf("self" to 101), mapOf("self" to 50, "historical" to 51))))
        val (book,snapshot) = accepted(local,"trip",true)
        val peer = """{"id":"$invitationId","userId":"$friend","name":"Rahul Google","email":"friend@example.com","status":"accepted"}"""
        val network = Network(Reply("/rest/v1/rpc/kitna_friend_invite", """{"id":"$invitationId","token":"$token","status":"pending"}"""),
            Reply("/rest/v1/rpc/kitna_friends","""{"friends":[],"invitations":[]}"""),
            Reply("/rest/v1/rpc/kitna_status", """{"ready":true,"schemaVersion":3}"""),
            Reply("/rest/v1/rpc/kitna_friends","""{"friends":[$peer],"invitations":[]}"""), Reply("/rest/v1/rpc/kitna_sync",sync(snapshot)),
            Reply("/rest/v1/rpc/kitna_friends","""{"friends":[$peer],"invitations":[]}"""),
            Reply("/rest/v1/rpc/kitna_status", """{"ready":true,"schemaVersion":3}"""),
            Reply("/rest/v1/rpc/kitna_friends","""{"friends":[$peer],"invitations":[]}"""), Reply("/rest/v1/rpc/kitna_sync",sync(snapshot)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = store(book)
        try {
            val cloud = cloud(store,scope,network)
            cloud.createFriendInvite("historical").getOrThrow()
            cloud.sync().getOrThrow()
            assertEquals(3,store.book.value!!.people.size)
            assertEquals("",store.book.value!!.people.single { it.id == "historical" }.accountId)
            assertEquals(invitationId,cloud.state.value.friendClaims.single().invitationId)
            assertEquals("friend@example.com",cloud.state.value.friendClaims.single().email)
            cloud.confirmFriendClaim(invitationId).getOrThrow()
            assertTrue(cloud.state.value.friendClaims.isEmpty())
            cloud.sync().getOrThrow()
            val result = store.book.value!!
            assertEquals(listOf("self","historical"),result.people.map { it.id })
            assertEquals(friend,result.people.single { it.id == "historical" }.accountId)
            assertEquals(local.expenses,result.expenses)
            assertEquals(listOf("self","historical"),result.groups.single { it.id == "trip" }.members)
            assertEquals(snapshot.participants.single { it.userId == friend }.id,result.sharedGroups.single().people["historical"])
        } finally { scope.cancel(); store.close() }
    }

    @Test fun acceptingVerifiedEmailFriendReusesLocalHistoryAndRepeatedAcceptanceDoesNotDuplicate() = runBlocking {
        val local = Book(people = listOf(Person("self","Owner"),Person("rahul","Rahul","friend@example.com")),
            groups = listOf(Group("dinner","Rahul",listOf("self","rahul"),kind = "Direct")),
            expenses = listOf(Expense("bill","dinner","Dinner",101,mapOf("self" to 101),mapOf("self" to 50,"rahul" to 51))))
        val peer = """{"id":"$invitationId","userId":"$friend","name":"Rahul Google","email":"friend@example.com","status":"accepted"}"""
        val network = Network(Reply("/rest/v1/rpc/kitna_accept_friend_invite",peer),Reply("/rest/v1/rpc/kitna_friends","""{"friends":[$peer],"invitations":[]}"""),
            Reply("/rest/v1/rpc/kitna_accept_friend_invite",peer),Reply("/rest/v1/rpc/kitna_friends","""{"friends":[$peer],"invitations":[]}"""))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = store(local)
        try {
            val cloud = cloud(store,scope,network)
            cloud.receive(Uri.parse(InvitationLinks.url("friend",token)))
            cloud.acceptFriendInvitation(token).getOrThrow()
            cloud.acceptFriendInvitation(token).getOrThrow()
            assertEquals(2,store.book.value!!.people.size)
            assertEquals(friend,store.book.value!!.people.single { it.id == "rahul" }.accountId)
            assertEquals(local.expenses,store.book.value!!.expenses)
            assertEquals(Accounting.friendBalances(local),Accounting.friendBalances(store.book.value!!))
            assertNull(cloud.state.value.pendingInvite)
            assertTrue(cloud.state.value.friendClaims.isEmpty())
        } finally { scope.cancel(); store.close() }
    }

    @Test fun batchRetryUsesStableParticipantIdsAndDoesNotEraseAnAcceptedFriendAccount() = runBlocking {
        val local = Book(people = listOf(Person("self","Owner"), Person("rahul","Rahul",accountId = friend)),groups = listOf(Group("trip","Trip",listOf("self"))))
        val (book,initial) = accepted(local,"trip")
        val stableId = UUID.nameUUIDFromBytes("kitna:${initial.group.id}:participant:rahul".toByteArray(Charsets.UTF_8)).toString()
        val added = initial.copy(participants = initial.participants + RemoteParticipant(stableId,"Rahul"),revision = 2)
        val network = Network(Reply("/rest/v1/rpc/kitna_add_members",offline = true), Reply("/rest/v1/rpc/kitna_add_members",json.encodeToString(added)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = store(book)
        try {
            val cloud = cloud(store,scope,network)
            assertTrue(cloud.addMembers("trip",setOf("rahul")).isFailure)
            assertEquals(book,store.book.value)
            cloud.addMembers("trip",setOf("rahul")).getOrThrow()
            cloud.addMembers("trip",setOf("rahul")).getOrThrow()
            assertEquals(2,network.calls.size)
            assertEquals(network.calls[0].sent.toString("UTF-8"),network.calls[1].sent.toString("UTF-8"))
            assertEquals(stableId,store.book.value!!.sharedGroups.single().people["rahul"])
            assertEquals(friend,store.book.value!!.people.single { it.id == "rahul" }.accountId)
            assertEquals(2,store.book.value!!.groups.single().members.size)
        } finally { scope.cancel(); store.close() }
    }

    @Test fun joiningAnAlreadyImportedGroupOpensTheSameLocalGroupAndClearsPersistedInvitation() = runBlocking {
        val local = Book(groups = listOf(Group("trip","Trip",listOf("self"))))
        val (book,snapshot) = accepted(local,"trip")
        val network = Network(Reply("/rest/v1/rpc/kitna_accept_invite",json.encodeToString(snapshot)),Reply("/rest/v1/rpc/kitna_sync",sync(snapshot)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = store(book)
        try {
            val cloud = cloud(store,scope,network)
            cloud.receive(Uri.parse(InvitationLinks.url("group",token)))
            cloud.join(token).getOrThrow()
            assertEquals("trip",cloud.state.value.openGroupId)
            assertNull(cloud.state.value.pendingInvite)
            assertEquals(listOf("trip"),store.book.value!!.groups.map { it.id })
            assertEquals(book.sharedGroups.single().people,store.book.value!!.sharedGroups.single().people)
            assertNull(cloud(store,scope,Network()).state.value.pendingInvite)
        } finally { scope.cancel(); store.close() }
    }
}
