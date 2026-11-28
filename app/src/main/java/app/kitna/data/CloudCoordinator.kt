package app.kitna.data

import android.content.Context
import android.net.Uri
import app.kitna.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileOutputStream

data class CloudUiState(
    val session: CloudSession? = null, val busy: Boolean = false, val ready: Boolean? = null,
    val message: String? = null, val lastSync: Long? = null, val pendingInvite: String? = null,
    val invitationsReady: Boolean = false, val openGroupId: String? = null,
    val collaborationReady: Boolean = false, val pendingInviteKind: String = "group",
    val friendInvitations: List<FriendInvitationStatus> = emptyList(),
    val friendClaims: List<FriendIdentityClaim> = emptyList()
)
data class FriendInvitationStatus(val id: String, val name: String, val email: String, val incoming: Boolean, val status: String, val expiresAt: String)
data class FriendIdentityClaim(val invitationId: String, val localPersonId: String, val accountName: String, val email: String, val userId: String)

internal data class CloudServices(val http: CloudHttp, val auth: CloudAuth) {
    companion object { fun create(context: Context): CloudServices { val http = CloudHttp(); return CloudServices(http, CloudAuth(context, http)) } }
}
class CloudCoordinator internal constructor(context: Context, private val store: LocalStore, private val scope: CoroutineScope, services: CloudServices) {
    constructor(context: Context, store: LocalStore, scope: CoroutineScope) : this(context, store, scope, CloudServices.create(context))
    private val app = context.applicationContext
    private val http = services.http
    private val auth = services.auth
    private val receipts = ReceiptStore(app)
    private val notifications = ActivityNotifications(app)
    private val mutable = MutableStateFlow(CloudUiState())
    val state = mutable.asStateFlow()
    private val syncMutex = Mutex()
    private var poll: Job? = null
    private var foreground = false
    private val json = SharedBooks.json
    private val invitationPreferences = app.getSharedPreferences("pending_invitations", Context.MODE_PRIVATE)
    private val friendPreferences = app.getSharedPreferences("friend_connections", Context.MODE_PRIVATE)

    suspend fun initialize() {
        val pendingToken = invitationPreferences.getString("token", null)
        if (pendingToken != null) mutable.value = mutable.value.copy(pendingInvite = pendingToken, pendingInviteKind = invitationPreferences.getString("kind", "group") ?: "group")
        try {
            val session = auth.load()
            mutable.value = mutable.value.copy(session = session)
            notifications.setActiveAccount(session?.id)
            if (session != null) {
                store.update { current -> current.sharedGroups.filter { it.accountId != session.id }.map { it.accountId }.distinct()
                    .fold(current) { book, account -> SharedBooks.withoutSharedCache(book, account) } }
                startPolling()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = mutable.value.copy(message = "The saved account could not be opened securely. Sign in with Google again; your ledger has been kept.") }
    }
    suspend fun beginSignIn(): Result<String> = perform { auth.begin() }
    fun receive(uri: Uri) {
        val invitation = InvitationLinks.parse(uri)
        if (invitation != null) {
            val persisted = invitationPreferences.edit().putString("token", invitation.token).putString("kind", invitation.kind).commit()
            mutable.value = mutable.value.copy(pendingInvite = invitation.token, pendingInviteKind = invitation.kind, message = if (persisted) null else "This invitation could not be kept for restart. Keep the original link to open again.")
            return
        }
        if (uri.scheme != "app.kitna") return
        if (uri.host == "group") {
            mutable.value = mutable.value.copy(openGroupId = uri.getQueryParameter("id")?.takeIf { it.length in 1..200 })
        } else if (uri.host in setOf("join", "friend")) {
            mutable.value = mutable.value.copy(message = "This group invitation is invalid.")
        } else if (uri.host == "auth" && uri.path == "/callback") scope.launch {
            perform {
                val fresh = auth.exchange(uri)
                val oldAccount = mutable.value.session?.id
                val current = checkNotNull(store.book.value)
                require(current.pendingChanges.none { it.accountId != fresh.user.id }) { "Sync or discard the previous account's pending changes before switching accounts." }
                if (oldAccount != fresh.user.id) store.update { SharedBooks.withoutSharedCache(it) }
                auth.accept(fresh)
                if (store.book.value?.people?.firstOrNull { it.id == "self" }?.name == "You") store.update { book -> book.copy(people = book.people.map { if (it.id == "self") it.copy(name = fresh.user.name) else it }) }
                mutable.value = mutable.value.copy(session = fresh.user, friendClaims = emptyList(), friendInvitations = emptyList())
                notifications.setActiveAccount(fresh.user.id)
                startPolling()
                syncWork()
            }
        }
    }
    fun clearGroupNavigation() { mutable.value = mutable.value.copy(openGroupId = null) }
    fun dismissInvitation() {
        invitationPreferences.edit().clear().commit()
        mutable.value = mutable.value.copy(pendingInvite = null)
    }
    fun setForeground(value: Boolean) { foreground = value; if (value) startPolling() else { poll?.cancel(); poll = null } }
    private fun startPolling() {
        if (!foreground || mutable.value.session == null || poll?.isActive == true) return
        poll = scope.launch {
            while (isActive) {
                val result = sync()
                delay(if (result.isSuccess) 20_000 else 60_000)
            }
        }
    }
    fun requestSync() { if (mutable.value.session != null) scope.launch { sync() } }
    suspend fun <T> exclusive(action: suspend () -> T): T = syncMutex.withLock { action() }

    suspend fun sync(): Result<Unit> = perform {
        val status = http.json("POST", "/rest/v1/rpc/kitna_status", body = buildJsonObject {}).jsonObject
        check(status["ready"]?.jsonPrimitive?.booleanOrNull == true) { "Shared-group database setup is incomplete." }
        mutable.value = mutable.value.copy(ready = true, invitationsReady = (status["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1) >= 2, collaborationReady = (status["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1) >= 3)
        if (mutable.value.session != null) syncWork()
    }
    private suspend fun rpc(name: String, body: JsonObject): JsonElement {
        val token = auth.token()
        return try { http.json("POST", "/rest/v1/rpc/$name", token, body) }
        catch (failure: CloudFailure) {
            if (failure.status != 401) throw failure
            http.json("POST", "/rest/v1/rpc/$name", auth.token(forceRefresh = true), body)
        }
    }
    suspend fun shareGroup(groupId: String): Result<Unit> = perform {
        val account = checkNotNull(mutable.value.session) { "Sign in with Google first." }
        val current = checkNotNull(store.book.value)
        require(current.groups.find { it.id == groupId }?.members.orEmpty().none { id -> id != "self" && current.people.find { it.id == id }?.email?.isNotBlank() == true } || mutable.value.invitationsReady) { "Apply the v0.6 invitation database update before sharing a group with email invitations. Your local group is saved." }
        store.update { book ->
            saveBeforeSharing(book, groupId)
            SharedBooks.prepareShare(book, groupId, account.id)
        }
        syncWork()
    }
    suspend fun invite(groupId: String, localPersonId: String?): Result<String> = perform {
        val account = checkNotNull(mutable.value.session) { "Sign in with Google first." }
        val link = checkNotNull(store.book.value?.sharedGroups?.firstOrNull { it.groupId == groupId && it.accountId == account.id }) { "Share this group first." }
        require(store.book.value?.pendingChanges?.none { it.groupId == groupId } == true) { "Sync this group before creating an invitation." }
        val result = rpc("kitna_invite", buildJsonObject {
            put("p_group_id", link.remoteId)
            put("p_participant_id", localPersonId?.let { JsonPrimitive(link.people.getValue(it)) } ?: JsonNull)
        }).jsonObject
        result.getValue("token").jsonPrimitive.content
    }
    suspend fun join(token: String): Result<Unit> = perform {
        checkNotNull(mutable.value.session) { "Sign in with Google before accepting the invitation." }
        require(token.length in 12..300) { "Enter a complete Kitna group code." }
        val result = rpc("kitna_accept_invite", buildJsonObject { put("p_token", token) }).jsonObject
        require(result["error"] == null) { result["message"]?.jsonPrimitive?.contentOrNull ?: "This group code is invalid or expired." }
        val snapshot = json.decodeFromJsonElement<SharedSnapshot>(result)
        apply(snapshot)
        dismissInvitation()
        val localId = store.book.value?.sharedGroups?.find { it.remoteId == snapshot.group.id }?.groupId
        mutable.value = mutable.value.copy(openGroupId = localId)
        downloadPhotos(snapshot)
        syncWork()
    }
    suspend fun previewInvitation(token: String, kind: String): Result<JsonObject> = perform {
        rpc(if (kind == "friend") "kitna_preview_friend_invite" else "kitna_preview_invite", buildJsonObject { put("p_token", token) }).jsonObject.also {
            require(it["error"] == null) { it["message"]?.jsonPrimitive?.contentOrNull ?: "This invitation is invalid or expired." }
        }
    }
    suspend fun groupInvitations(): Result<JsonArray> = perform { rpc("kitna_group_invitations", buildJsonObject {}).jsonArray }
    suspend fun acceptGroupEmail(groupId: String): Result<Unit> = perform {
        val snapshot = json.decodeFromJsonElement<SharedSnapshot>(rpc("kitna_accept_group_email", buildJsonObject { put("p_group_id", groupId) }))
        apply(snapshot)
        val localId = store.book.value?.sharedGroups?.find { it.remoteId == snapshot.group.id }?.groupId
        mutable.value = mutable.value.copy(openGroupId = localId)
    }
    suspend fun addMembers(groupId: String, personIds: Set<String>): Result<Unit> = perform {
        val link = memberLink(groupId)
        val current = requireNotNull(store.book.value)
        require(current.pendingChanges.none { it.groupId == groupId }) { "Finish syncing this group before adding members." }
        val newPeople = personIds.filterNot { it in link.people }.map { id -> requireNotNull(current.people.find { it.id == id }) { "A selected friend no longer exists." } }
        if (newPeople.isNotEmpty()) {
            val mapping = newPeople.associate { it.id to java.util.UUID.nameUUIDFromBytes("kitna:${link.remoteId}:participant:${it.id}".toByteArray(Charsets.UTF_8)).toString() }
            val result = rpc("kitna_add_members", buildJsonObject {
                put("p_group_id", link.remoteId)
                put("p_members", JsonArray(newPeople.map { person -> buildJsonObject { put("id", mapping.getValue(person.id)); put("name", person.name); if (person.email.isNotBlank()) put("email", EmailAddress.normalize(person.email)) } }))
            })
            val snapshot = json.decodeFromJsonElement<SharedSnapshot>(result)
            downloadPhotos(snapshot)
            store.update { book -> SharedBooks.applySnapshot(book.copy(sharedGroups = book.sharedGroups.map { if (it.groupId == groupId) it.copy(people = it.people + mapping) else it }), snapshot, link.accountId) }
        }
    }
    suspend fun createFriendInvite(localPersonId: String? = null): Result<String> = perform {
        val person = localPersonId?.let { id -> requireNotNull(store.book.value?.people?.find { it.id == id && id != "self" }) { "Choose a friend." } }
        val result = rpc("kitna_friend_invite", buildJsonObject { put("p_name", person?.name ?: "Friend"); put("p_email", person?.email?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull) }).jsonObject
        val id = result.getValue("id").jsonPrimitive.content
        if (localPersonId != null) check(friendPreferences.edit().putString("${mutable.value.session!!.id}:$id", localPersonId).commit())
        refreshFriends()
        InvitationLinks.url("friend", result.getValue("token").jsonPrimitive.content)
    }
    suspend fun sendFriendEmail(localPersonId: String, email: String): Result<Unit> = perform {
        val person = requireNotNull(store.book.value?.people?.find { it.id == localPersonId && it.id != "self" }) { "Choose a friend." }
        val address = EmailAddress.normalize(email).also { require(it.isNotBlank()) { "Enter an email address." } }
        val result = rpc("kitna_friend_invite", buildJsonObject { put("p_name", person.name); put("p_email", address) }).jsonObject
        check(friendPreferences.edit().putString("${mutable.value.session!!.id}:${result.getValue("id").jsonPrimitive.content}", localPersonId).commit())
        store.update { BookActions.savePerson(it, person.copy(email = address)) }
        refreshFriends()
    }
    suspend fun acceptFriendInvitation(token: String? = null, invitationId: String? = null): Result<Unit> = perform {
        val peer = if (token != null) rpc("kitna_accept_friend_invite", buildJsonObject { put("p_token", token) }).jsonObject
            else rpc("kitna_accept_friend_email", buildJsonObject { put("p_id", requireNotNull(invitationId)) }).jsonObject
        connectFriend(peer)
        if (token != null) dismissInvitation()
        refreshFriends()
    }
    suspend fun revokeFriendInvitation(id: String): Result<Unit> = perform {
        rpc("kitna_revoke_friend_invite", buildJsonObject { put("p_id", id) }); refreshFriends()
    }
    suspend fun confirmFriendClaim(invitationId: String): Result<Unit> = perform {
        val account = requireNotNull(mutable.value.session) { "Sign in with Google first." }
        val claim = requireNotNull(mutable.value.friendClaims.find { it.invitationId == invitationId }) { "Refresh friends to review this connection again." }
        val person = requireNotNull(store.book.value?.people?.find { it.id == claim.localPersonId && it.id != "self" }) { "The local friend no longer exists." }
        require(person.accountId.isEmpty() || person.accountId == claim.userId) { "This friend is already connected to a different account." }
        check(friendPreferences.edit().putString("${account.id}:confirmed:$invitationId", claim.userId).commit()) { "Could not save this connection. Try again." }
        refreshFriends()
    }
    private suspend fun connectFriend(peer: JsonObject) {
        val account = requireNotNull(mutable.value.session)
        val remoteId = peer.getValue("userId").jsonPrimitive.content
        if (remoteId == account.id) return
        val email = peer["email"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val inviteId = peer["id"]?.jsonPrimitive?.contentOrNull
        val mapped = inviteId?.let { friendPreferences.getString("${account.id}:$it", null) }
        val label = peer["name"]?.jsonPrimitive?.contentOrNull?.take(100)?.ifBlank { "Kitna friend" } ?: "Kitna friend"
        val historical = store.book.value?.people?.find { it.id == mapped && it.id != "self" }
        if (historical != null) {
            require(historical.accountId.isEmpty() || historical.accountId == remoteId) { "This historical friend is connected to another verified account." }
            val verifiedEmailMatches = email.isNotEmpty() && historical.email.isNotEmpty() && historical.email.equals(email, true)
            val confirmed = inviteId?.let { friendPreferences.getString("${account.id}:confirmed:$it", null) } == remoteId
            if (historical.accountId.isEmpty() && !verifiedEmailMatches && !confirmed) {
                val claim = FriendIdentityClaim(requireNotNull(inviteId), historical.id, label, email, remoteId)
                mutable.value = mutable.value.copy(friendClaims = mutable.value.friendClaims.filterNot { it.invitationId == inviteId } + claim)
                return
            }
        }
        store.update { book ->
            val mappedPerson = book.people.find { it.id == mapped && it.id != "self" && (it.accountId.isEmpty() || it.accountId == remoteId) }
            val accountPerson = book.people.find { it.accountId == remoteId }
            val emailPerson = book.people.find { it.id != "self" && email.isNotEmpty() && it.email.equals(email, true) && (it.accountId.isEmpty() || it.accountId == remoteId) }
            val canonical = mappedPerson ?: emailPerson ?: accountPerson
            var connected = book
            if (canonical != null) {
                listOfNotNull(accountPerson, emailPerson).distinctBy { it.id }.filter { it.id != canonical.id }.forEach {
                    connected = BookActions.mergePeople(connected, canonical.id, it.id)
                }
            }
            val existing = canonical?.let { value -> connected.people.find { it.id == value.id } }
            val uniqueLabel = if (connected.people.any { it.id != existing?.id && it.name.equals(label, true) }) "${label.take(60)} (${email.ifBlank { remoteId.take(8) }.take(35)})" else label
            BookActions.savePerson(connected, (existing ?: Person(name = uniqueLabel)).copy(accountId = remoteId, email = email.ifBlank { existing?.email.orEmpty() }))
        }
    }
    private suspend fun refreshFriends() {
        val result = rpc("kitna_friends", buildJsonObject {}).jsonObject
        mutable.value = mutable.value.copy(friendClaims = emptyList())
        result["friends"]?.jsonArray?.forEach { connectFriend(it.jsonObject) }
        val pending = result["invitations"]?.jsonArray.orEmpty().map { value -> val row = value.jsonObject
            FriendInvitationStatus(row.getValue("id").jsonPrimitive.content, row["name"]?.jsonPrimitive?.contentOrNull ?: "Friend", row["email"]?.jsonPrimitive?.contentOrNull.orEmpty(), row["incoming"]?.jsonPrimitive?.booleanOrNull == true, row["status"]?.jsonPrimitive?.contentOrNull ?: "pending", row["expiresAt"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
        mutable.value = mutable.value.copy(friendInvitations = pending)
    }
    suspend fun groupCode(groupId: String): Result<Pair<String, String>> = perform {
        require(mutable.value.invitationsReady) { "Group codes need the v0.6 database update. Ask the project owner to run the invitation migration, then sync again." }
        val link = memberLink(groupId)
        require(store.book.value?.pendingChanges?.none { it.groupId == groupId } == true) { "Sync this group before creating its code." }
        val result = rpc("kitna_group_code", buildJsonObject { put("p_group_id", link.remoteId) }).jsonObject
        result.getValue("code").jsonPrimitive.content to result.getValue("expiresAt").jsonPrimitive.content
    }
    suspend fun inviteEmail(groupId: String, localPersonId: String?, name: String, email: String): Result<Unit> = perform {
        require(mutable.value.invitationsReady) { "Email invitations need the v0.6 database update. Sync again after applying it." }
        val cleanEmail = EmailAddress.normalize(email)
        require(cleanEmail.isNotBlank()) { "Enter your friend's Google email." }
        val link = memberLink(groupId)
        require(store.book.value?.pendingChanges?.none { it.groupId == groupId } == true) { "Sync or discard pending changes before inviting a friend." }
        val snapshot = rpc("kitna_invite_email", buildJsonObject {
            put("p_group_id", link.remoteId); put("p_participant_id", localPersonId?.let { JsonPrimitive(link.people.getValue(it)) } ?: JsonNull)
            put("p_name", name.trim()); put("p_email", cleanEmail)
        })
        apply(json.decodeFromJsonElement(snapshot))
    }
    suspend fun discardPending(groupId: String): Result<Unit> = perform {
        store.update { book ->
            val link = checkNotNull(book.sharedGroups.find { it.groupId == groupId }) { "Group no longer exists." }
            require(link.accountId == mutable.value.session?.id) { "Sign in with the group's account first." }
            val clean = book.copy(pendingChanges = book.pendingChanges.filterNot { it.groupId == groupId })
            val snapshot = link.snapshot
            require(snapshot == null || book.pendingChanges.none { it.groupId == groupId && it.kind == "share" }) { "The group is saved on the server. Finish its pending receipt uploads before discarding this operation." }
            if (snapshot != null) SharedBooks.applySnapshot(clean, snapshot, link.accountId)
            else clean.copy(sharedGroups = clean.sharedGroups.filterNot { it.groupId == groupId })
        }
    }
    suspend fun signOut(): Result<Unit> = perform {
        require(store.book.value?.pendingChanges.isNullOrEmpty()) { "Sync or discard pending changes before signing out." }
        poll?.cancel(); poll = null
        store.update { SharedBooks.withoutSharedCache(it) }
        auth.clear()
        notifications.setActiveAccount(null)
        mutable.value = mutable.value.copy(session = null, message = "Signed out. Local groups have been kept.", lastSync = null, friendInvitations = emptyList(), friendClaims = emptyList())
    }
    suspend fun removeMember(groupId: String, localPersonId: String): Result<Unit> = perform {
        val link = memberLink(groupId)
        val snapshot = rpc("kitna_remove_member", buildJsonObject { put("p_group_id", link.remoteId); put("p_participant_id", link.people.getValue(localPersonId)) })
        apply(json.decodeFromJsonElement(snapshot))
    }
    suspend fun revokeInvites(groupId: String): Result<Unit> = perform {
        val link = memberLink(groupId)
        rpc("kitna_revoke_invites", buildJsonObject { put("p_group_id", link.remoteId) }); Unit
    }
    suspend fun leaveGroup(groupId: String): Result<Unit> = perform {
        val link = memberLink(groupId)
        require(store.book.value?.pendingChanges?.none { it.groupId == groupId } == true) { "Sync or discard this group's pending changes before leaving." }
        rpc("kitna_leave_group", buildJsonObject { put("p_group_id", link.remoteId) })
        notifications.cancelGroup(link.accountId, groupId)
        store.update { book ->
            val others = book.sharedGroups.filterNot { it.groupId == groupId }
            // Reuse the account-cache removal logic with only this link selected.
            SharedBooks.withoutSharedCache(book.copy(sharedGroups = listOf(link)), link.accountId).copy(sharedGroups = others)
        }
    }
    private fun memberLink(groupId: String): SharedGroupLink {
        val account = checkNotNull(mutable.value.session) { "Sign in with Google first." }
        return checkNotNull(store.book.value?.sharedGroups?.find { it.groupId == groupId && it.accountId == account.id && !it.revoked }) { "This account cannot access the shared group." }
    }
    private suspend fun syncWork() {
        val account = checkNotNull(mutable.value.session)
        if (mutable.value.collaborationReady) refreshFriends()
        val pending = store.book.value?.pendingChanges.orEmpty().filter { it.accountId == account.id && it.error == null }
        for (change in pending) {
            if (store.book.value?.pendingChanges?.find { it.id == change.id }?.error != null) continue
            val link = store.book.value?.sharedGroups?.find { it.groupId == change.groupId && it.accountId == account.id } ?: continue
            try {
                val result: JsonElement
                if (change.kind == "share") {
                    val photosToUpload = photos(requireNotNull(store.book.value), link.groupId)
                    withContext(Dispatchers.IO) { ReceiptStore.withFileLock { photosToUpload.forEach { receipts.verify(it) } } }
                    val payload = json.parseToJsonElement(change.payload).jsonObject
                    result = rpc("kitna_share_group", JsonObject(payload + ("p_command_id" to JsonPrimitive(change.id))))
                    apply(json.decodeFromJsonElement(result))
                    // The group must exist before membership-scoped receipt uploads.
                    uploadPhotos(link, photosToUpload)
                } else {
                    if (change.kind == "expense") {
                        val value = json.decodeFromString<Expense>(change.payload)
                        val already = link.snapshot?.let { snapshot -> snapshot.records.filter { it.kind == "expense" }.flatMap { json.decodeFromJsonElement<Expense>(it.payload).receipts }.map { it.id }.toSet() }.orEmpty()
                        uploadPhotos(link, value.receipts.filterNot { it.id in already })
                    }
                    result = rpc("kitna_apply", buildJsonObject {
                        put("p_command_id", change.id); put("p_group_id", link.remoteId); put("p_kind", change.kind)
                        put("p_entity_id", change.entityId); put("p_expected_version", change.expectedVersion)
                        put("p_payload", json.parseToJsonElement(change.payload))
                    })
                }
                apply(json.decodeFromJsonElement(result), change.id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: CloudFailure) {
                if (failure.code == "40001" || failure.status == 403 || failure.code == "22023") {
                    store.update { book -> book.copy(pendingChanges = book.pendingChanges.map {
                        if (it.groupId == change.groupId) it.copy(error = if (failure.code == "40001") "Another member changed this record. Your draft is saved; review the latest group before discarding it." else failure.message,
                            conflict = failure.code == "40001") else it
                    }) }
                } else throw failure
            }
        }
        val known = store.book.value?.sharedGroups.orEmpty().filter { it.accountId == account.id && it.snapshot != null }
            .associate { it.remoteId to JsonPrimitive(it.snapshot!!.revision) }
        val update = rpc("kitna_sync", buildJsonObject { put("p_revisions", JsonObject(known)) }).jsonObject
        val snapshots = update.getValue("groups").jsonArray.map { json.decodeFromJsonElement<SharedSnapshot>(it) }
        snapshots.forEach { snapshot -> apply(snapshot); downloadPhotos(snapshot) }
        val active = update.getValue("memberGroupIds").jsonArray.map { it.jsonPrimitive.content }.toSet()
        store.book.value?.sharedGroups.orEmpty().filter { it.accountId == account.id && it.remoteId !in active }.forEach { notifications.cancelGroup(account.id, it.groupId) }
        store.update { book -> book.copy(sharedGroups = book.sharedGroups.map {
            if (it.accountId == account.id && it.snapshot != null) it.copy(revoked = it.remoteId !in active) else it
        }) }
        // Retry receipt downloads even if the group's metadata revision is unchanged.
        store.book.value?.sharedGroups.orEmpty().filter { it.accountId == account.id && !it.revoked }.mapNotNull { it.snapshot }.forEach { downloadPhotos(it) }
        mutable.value = mutable.value.copy(ready = true, lastSync = System.currentTimeMillis())
    }
    private suspend fun apply(snapshot: SharedSnapshot, acknowledged: String? = null) {
        val id = checkNotNull(mutable.value.session).id
        // Make the group's private images available before publishing metadata.
        // A gallery already displaying a missing file otherwise keeps its failed
        // decode until reopened. A failed fetch retains the previous cached book
        // and the retryable command, including when the server already committed.
        downloadPhotos(snapshot)
        store.update { SharedBooks.applySnapshot(it, snapshot, id, acknowledged) }
        store.book.value?.sharedGroups?.find { it.accountId == id && it.remoteId == snapshot.group.id }?.let { notifications.onAcceptedSnapshot(id, it.groupId, snapshot) }
    }
    private fun photos(book: Book, groupId: String) = BookBackup.referencedPhotos(book.copy(
        expenses = book.expenses.filter { it.groupId == groupId }, revisions = book.revisions.filter { it.expense.groupId == groupId }))
    private suspend fun uploadPhotos(link: SharedGroupLink, photos: List<ReceiptPhoto>) {
        for (photo in photos) {
            ReceiptStore.withFileLock { receipts.verify(photo) }
            val path = "/storage/v1/object/kitna-receipts/${link.remoteId}/${photo.id}.jpg"
            try { http.request("POST", path, auth.token(), bytes = withContext(Dispatchers.IO) { receipts.fileFor(photo).readBytes() }) }
            catch (failure: CloudFailure) {
                if (failure.status !in setOf(400, 409) || !failure.message.orEmpty().contains("already exists", true)) throw failure
                // An existing ID must contain the same immutable image, not different bytes.
                val bytes = http.request("GET", "/storage/v1/object/authenticated/kitna-receipts/${link.remoteId}/${photo.id}.jpg", auth.token(), maxBytes = ReceiptStore.MAX_PHOTO_BYTES.toInt())
                require(java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == photo.sha256) { "A shared receipt identifier refers to different image bytes." }
            }
        }
    }
    private suspend fun downloadPhotos(snapshot: SharedSnapshot) {
        val photos = (snapshot.records.filter { it.kind == "expense" }.flatMap { json.decodeFromJsonElement<Expense>(it.payload).receipts }
            + snapshot.revisions.flatMap { it.expense.receipts }).distinctBy { it.id }
        for (photo in photos) {
            val exists = withContext(Dispatchers.IO) { receipts.fileFor(photo).isFile }
            if (exists) {
                withContext(Dispatchers.IO) { ReceiptStore.withFileLock { receipts.verify(photo) } }
                continue
            }
            val bytes = http.request("GET", "/storage/v1/object/authenticated/kitna-receipts/${snapshot.group.id}/${photo.id}.jpg", auth.token(), maxBytes = ReceiptStore.MAX_PHOTO_BYTES.toInt())
            withContext(Dispatchers.IO) {
                val temporary = File.createTempFile("cloud-receipt-", ".jpg", app.cacheDir)
                try {
                    temporary.writeBytes(bytes)
                    ReceiptStore.withFileLock { receipts.install(photo, temporary) }
                } finally { temporary.delete() }
            }
        }
    }
    private fun saveBeforeSharing(book: Book, groupId: String) {
        val folder = File(app.noBackupFilesDir, "sharing-backups")
        check(folder.isDirectory || folder.mkdirs()) { "Could not save the local copy before sharing." }
        val id = java.util.UUID.nameUUIDFromBytes(groupId.toByteArray()).toString()
        val target = File(folder, "$id.json")
        if (target.exists()) return
        val pending = File(folder, "$id.pending")
        try {
            FileOutputStream(pending).use { output -> output.write(store.json.encodeToString(SharedBooks.portable(book)).toByteArray(Charsets.UTF_8)); output.flush(); output.fd.sync() }
            check(pending.renameTo(target)) { "Could not finish the local safety copy." }
        } finally { pending.delete() }
    }
    private suspend fun <T> perform(action: suspend () -> T): Result<T> = syncMutex.withLock {
        mutable.value = mutable.value.copy(busy = true, message = null)
        try { Result.success(withContext(Dispatchers.IO) { action() }) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val text = when (failure) {
                is java.io.IOException -> "Could not connect. Your saved records and pending changes are kept; retry when online."
                else -> failure.message ?: "Could not complete the shared-group request."
            }
            mutable.value = mutable.value.copy(message = text, ready = if (failure is CloudFailure && failure.code == "PGRST202") false else mutable.value.ready)
            Result.failure(IllegalStateException(text, failure))
        } finally { mutable.value = mutable.value.copy(busy = false) }
    }
}
