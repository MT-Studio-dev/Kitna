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
    val message: String? = null, val lastSync: Long? = null, val pendingInvite: String? = null
)

internal data class CloudServices(val http: CloudHttp, val auth: CloudAuth) {
    companion object { fun create(context: Context): CloudServices { val http = CloudHttp(); return CloudServices(http, CloudAuth(context, http)) } }
}
class CloudCoordinator internal constructor(context: Context, private val store: LocalStore, private val scope: CoroutineScope, services: CloudServices) {
    constructor(context: Context, store: LocalStore, scope: CoroutineScope) : this(context, store, scope, CloudServices.create(context))
    private val app = context.applicationContext
    private val http = services.http
    private val auth = services.auth
    private val receipts = ReceiptStore(app)
    private val mutable = MutableStateFlow(CloudUiState())
    val state = mutable.asStateFlow()
    private val syncMutex = Mutex()
    private var poll: Job? = null
    private var foreground = true
    private val json = SharedBooks.json

    suspend fun initialize() {
        try {
            val session = auth.load()
            mutable.value = mutable.value.copy(session = session)
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
        if (uri.scheme != "app.kitna") return
        if (uri.host == "join") {
            val token = uri.getQueryParameter("token")?.takeIf { it.length in 20..300 }
            mutable.value = mutable.value.copy(pendingInvite = token, message = if (token == null) "This group invitation is invalid." else "Review and accept the group invitation in Shared groups.")
        } else if (uri.host == "auth" && uri.path == "/callback") scope.launch {
            perform {
                val fresh = auth.exchange(uri)
                val oldAccount = mutable.value.session?.id
                val current = checkNotNull(store.book.value)
                require(current.pendingChanges.none { it.accountId != fresh.user.id }) { "Sync or discard the previous account's pending changes before switching accounts." }
                if (oldAccount != fresh.user.id) store.update { SharedBooks.withoutSharedCache(it) }
                auth.accept(fresh)
                if (store.book.value?.people?.firstOrNull { it.id == "self" }?.name == "You") store.update { book -> book.copy(people = book.people.map { if (it.id == "self") it.copy(name = fresh.user.name) else it }) }
                mutable.value = mutable.value.copy(session = fresh.user)
                startPolling()
                syncWork()
            }
        }
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
        mutable.value = mutable.value.copy(ready = true)
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
        require(token.length in 20..300) { "Paste a complete Kitna invite code." }
        val result = rpc("kitna_accept_invite", buildJsonObject { put("p_token", token) })
        val snapshot = json.decodeFromJsonElement<SharedSnapshot>(result)
        apply(snapshot)
        mutable.value = mutable.value.copy(pendingInvite = null)
        downloadPhotos(snapshot)
        syncWork()
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
        mutable.value = mutable.value.copy(session = null, message = "Signed out. Local groups have been kept.", lastSync = null)
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
