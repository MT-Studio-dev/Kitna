package app.kitna.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

@Serializable data class RemoteGroup(
    val id: String, val name: String, val kind: String = "Group", val archived: Boolean = false,
    val simplifyDebts: Boolean = true, val icon: String = "group", val color: String = "teal",
    val budget: Long? = null, val version: Long = 1, val ownerId: String = ""
)
@Serializable data class RemoteParticipant(val id: String, val name: String, val userId: String? = null, val active: Boolean = true)
@Serializable data class RemoteRecord(val kind: String, val id: String, val payload: JsonObject, val version: Long)
@Serializable data class RemoteRevision(val id: String, val expense: Expense, val reason: String, val time: Long, val actorId: String? = null)
@Serializable data class RemoteActivity(val id: String, val message: String, val time: Long, val actorId: String? = null)
@Serializable data class SharedSnapshot(
    val group: RemoteGroup, val participants: List<RemoteParticipant>, val records: List<RemoteRecord> = emptyList(),
    val revisions: List<RemoteRevision> = emptyList(), val activity: List<RemoteActivity> = emptyList(),
    val myParticipantId: String, val revision: Long = 0
)
@Serializable data class SharedGroupLink(
    val groupId: String, val remoteId: String, val accountId: String, val myParticipantId: String,
    val people: Map<String, String>, val entities: Map<String, String> = emptyMap(),
    val userIds: Map<String, String> = emptyMap(), val versions: Map<String, Long> = emptyMap(),
    val snapshot: SharedSnapshot? = null, val revoked: Boolean = false
)
@Serializable data class SharedChange(
    val id: String = newId(), val accountId: String, val groupId: String, val kind: String,
    val entityId: String, val expectedVersion: Long = 0, val payload: String,
    val error: String? = null, val conflict: Boolean = false
)

/** Maps a shared group's stable identities into this device's legacy local ledger. */
object SharedBooks {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun uuid(scope: String, id: String): String = runCatching { UUID.fromString(id).toString() }
        .getOrElse { UUID.nameUUIDFromBytes("kitna:$scope:$id".toByteArray(Charsets.UTF_8)).toString() }
    private fun entityKey(kind: String, id: String) = "$kind:$id"
    private fun recordId(link: SharedGroupLink, kind: String, id: String) = link.entities[entityKey(kind, id)]
        ?: if (kind in setOf("revision", "activity")) UUID.nameUUIDFromBytes("kitna:${link.remoteId}:$kind:$id".toByteArray(Charsets.UTF_8)).toString()
        else uuid("${link.remoteId}:$kind", id)
    private fun expense(value: Expense, groupId: String, ids: Map<String, String>, id: String): Expense = value.copy(
        id = id, groupId = groupId,
        payers = value.payers.mapKeys { ids.getValue(it.key) }, shares = value.shares.mapKeys { ids.getValue(it.key) },
        splitWeights = value.splitWeights.mapKeys { ids.getValue(it.key) }
    )
    private fun settlement(value: Settlement, groupId: String, ids: Map<String, String>, id: String) = value.copy(
        id = id, groupId = groupId, from = ids.getValue(value.from), to = ids.getValue(value.to)
    )
    private fun groupPayload(group: Group, link: SharedGroupLink) = buildJsonObject {
        put("id", link.remoteId); put("name", group.name); put("kind", group.kind); put("archived", group.archived)
        put("simplifyDebts", group.simplifyDebts); put("icon", group.icon); put("color", group.color)
        put("budget", group.budget?.let(::JsonPrimitive) ?: JsonNull)
    }

    fun prepareShare(book: Book, groupId: String, accountId: String): Book {
        require(book.sharedGroups.none { it.groupId == groupId }) { "This group is already shared." }
        val group = book.groups.first { it.id == groupId }
        require(!group.archived) { "Restore this group before sharing it." }
        val remoteId = UUID.nameUUIDFromBytes("kitna:group:$accountId:${group.id}".toByteArray(Charsets.UTF_8)).toString()
        val people = group.members.associateWith { UUID.nameUUIDFromBytes("kitna:$remoteId:participant:$it".toByteArray(Charsets.UTF_8)).toString() }
        var link = SharedGroupLink(groupId, remoteId, accountId, people.getValue("self"), people)
        val records = mutableListOf<JsonObject>()
        val entities = mutableMapOf<String, String>()
        fun add(kind: String, localId: String, payload: JsonObject) {
            val id = recordId(link, kind, localId); entities[entityKey(kind, localId)] = id
            records += buildJsonObject { put("kind", kind); put("id", id); put("payload", payload) }
        }
        val expenses = book.expenses.filter { it.groupId == groupId }
        expenses.forEach { add("expense", it.id, json.encodeToJsonElement(expense(it, remoteId, people, recordId(link, "expense", it.id))).jsonObject) }
        book.settlements.filter { it.groupId == groupId }.forEach { add("settlement", it.id, json.encodeToJsonElement(settlement(it, remoteId, people, recordId(link, "settlement", it.id))).jsonObject) }
        book.comments.filter { c -> expenses.any { it.id == c.expenseId } }.forEach {
            add("comment", it.id, json.encodeToJsonElement(it.copy(id = recordId(link, "comment", it.id), expenseId = recordId(link, "expense", it.expenseId), authorId = people.getValue(it.authorId))).jsonObject)
        }
        book.revisions.filter { it.expense.groupId == groupId }.forEach {
            add("revision", it.id, json.encodeToJsonElement(it.copy(id = recordId(link, "revision", it.id), expense = expense(it.expense, remoteId, people, recordId(link, "expense", it.expense.id)))).jsonObject)
        }
        book.activity.filter { it.groupId == groupId }.forEach {
            add("activity", it.id, json.encodeToJsonElement(it.copy(id = recordId(link, "activity", it.id), groupId = remoteId)).jsonObject)
        }
        link = link.copy(entities = entities, versions = records.filter { it.getValue("kind").jsonPrimitive.content in setOf("expense", "settlement", "comment") }
            .associate { entityKey(it.getValue("kind").jsonPrimitive.content, it.getValue("id").jsonPrimitive.content) to 1L } + (entityKey("group", remoteId) to 1L))
        val payload = buildJsonObject {
            put("p_group", groupPayload(group, link))
            put("p_participants", JsonArray(group.members.map { id -> buildJsonObject {
                val person = book.people.first { it.id == id }
                put("id", people.getValue(id)); put("name", person.name); put("isSelf", id == "self")
                if (id != "self" && person.email.isNotBlank()) put("email", EmailAddress.normalize(person.email))
            } }))
            put("p_records", JsonArray(records))
        }
        return book.copy(sharedGroups = book.sharedGroups + link, pendingChanges = book.pendingChanges + SharedChange(
            accountId = accountId, groupId = groupId, kind = "share", entityId = remoteId, payload = payload.toString()
        ))
    }

    /** The optimistic ledger and retryable commands are saved in one LocalStore transaction. */
    fun queueChanges(before: Book, changed: Book, accountId: String?): Book {
        var result = changed
        val pending = changed.pendingChanges.toMutableList()
        val links = changed.sharedGroups.toMutableList()
        for ((index, original) in before.sharedGroups.withIndex()) {
            val old = before.groups.firstOrNull { it.id == original.groupId } ?: continue
            val group = changed.groups.firstOrNull { it.id == original.groupId }
            require(group != null) { "Shared groups cannot be deleted locally. Archive the group instead." }
            val oldExpenses = before.expenses.filter { it.groupId == group.id }.associateBy { it.id }
            val newExpenses = changed.expenses.filter { it.groupId == group.id }.associateBy { it.id }
            val oldPayments = before.settlements.filter { it.groupId == group.id }.associateBy { it.id }
            val newPayments = changed.settlements.filter { it.groupId == group.id }.associateBy { it.id }
            val oldComments = before.comments.filter { it.expenseId in oldExpenses }.associateBy { it.id }
            val newComments = changed.comments.filter { it.expenseId in newExpenses }.associateBy { it.id }
            val edited = group != old || newExpenses != oldExpenses || newPayments != oldPayments || newComments != oldComments
            if (!edited) continue
            require(before.pendingChanges.none { it.groupId == group.id && it.error != null }) { "Resolve this group's pending sync issue before editing it again. Your draft is kept in Shared groups." }
            require(accountId == original.accountId && !original.revoked) { "Sign in with this group's account to edit shared records." }
            require(group.members == old.members) { "Use shared-group invitations to add members. Existing ledger participants must stay in the group." }
            require(newExpenses.keys.containsAll(oldExpenses.keys) && newPayments.keys.containsAll(oldPayments.keys)) { "Use the delete/restore controls to keep shared history." }
            val entities = original.entities.toMutableMap()
            fun enqueue(kind: String, id: String, payload: JsonObject) {
                val remote = if (kind == "group") original.remoteId else recordId(original, kind, id)
                entities[entityKey(kind, id)] = remote
                val versionKey = entityKey(kind, remote)
                val earlier = pending.count { it.groupId == group.id && it.kind == kind && it.entityId == remote }
                pending += SharedChange(accountId = original.accountId, groupId = group.id, kind = kind, entityId = remote,
                    expectedVersion = (original.versions[versionKey] ?: 0) + earlier, payload = payload.toString())
            }
            if (group != old) enqueue("group", group.id, groupPayload(group, original))
            newExpenses.values.filter { oldExpenses[it.id] != it }.forEach {
                enqueue("expense", it.id, json.encodeToJsonElement(expense(it, original.remoteId, original.people, recordId(original, "expense", it.id))).jsonObject)
            }
            newPayments.values.filter { oldPayments[it.id] != it }.forEach {
                enqueue("settlement", it.id, json.encodeToJsonElement(settlement(it, original.remoteId, original.people, recordId(original, "settlement", it.id))).jsonObject)
            }
            newComments.values.filter { oldComments[it.id] != it }.forEach {
                require(it.authorId == "self") { "Shared comments are posted as your signed-in account." }
                enqueue("comment", it.id, json.encodeToJsonElement(it.copy(id = recordId(original, "comment", it.id),
                    expenseId = recordId(original, "expense", it.expenseId), authorId = original.myParticipantId)).jsonObject)
            }
            links[index] = original.copy(entities = entities)
        }
        result = result.copy(sharedGroups = links, pendingChanges = pending)
        return result.also(BookValidation::validate)
    }

    fun applySnapshot(book: Book, snapshot: SharedSnapshot, accountId: String, acknowledged: String? = null): Book {
        require(snapshot.participants.any { it.id == snapshot.myParticipantId && it.active }) { "This account is not a member of the group." }
        val old = book.sharedGroups.firstOrNull { it.remoteId == snapshot.group.id && it.accountId == accountId }
        val localGroupId = old?.groupId ?: snapshot.group.id.takeUnless { id -> book.groups.any { it.id == id } } ?: "shared:${snapshot.group.id}"
        val peopleMap = old?.people?.toMutableMap() ?: mutableMapOf()
        val userIds = old?.userIds?.toMutableMap() ?: mutableMapOf()
        val people = book.people.toMutableList()
        for (participant in snapshot.participants) {
            val existing = peopleMap.entries.firstOrNull { it.value == participant.id }?.key
            val localId = if (participant.id == snapshot.myParticipantId) "self" else existing
                ?: participant.userId?.let { user -> people.firstOrNull { it.accountId == user }?.id }
                ?: participant.userId?.let { user -> book.sharedGroups.flatMap { it.userIds.entries }.firstOrNull { it.value == user }?.key }
                ?: "shared-person:${participant.id}"
            peopleMap[localId] = participant.id
            participant.userId?.let { userIds[localId] = it }
            if (localId != "self") {
                val at = people.indexOfFirst { it.id == localId }
                val previous = people.getOrNull(at)
                require(participant.userId == null || previous?.accountId.isNullOrEmpty() || previous?.accountId == participant.userId) { "This historical participant is linked to a different verified account." }
                val person = (previous ?: Person(localId, participant.name)).copy(name = participant.name, accountId = participant.userId ?: previous?.accountId.orEmpty())
                if (at < 0) people += person else people[at] = person
            }
        }
        val reverse = peopleMap.entries.associate { it.value to it.key }
        require(reverse[snapshot.myParticipantId] == "self") { "Shared identity mapping is invalid." }
        val entities = old?.entities?.toMutableMap() ?: mutableMapOf()
        fun localId(kind: String, remote: String): String {
            val key = entities.entries.firstOrNull { it.key.startsWith("$kind:") && it.value == remote }?.key
            if (key != null) return key.removePrefix("$kind:")
            val id = "shared:$kind:${snapshot.group.id}:$remote"
            entities[entityKey(kind, id)] = remote
            return id
        }
        fun localExpense(value: Expense) = expense(value, localGroupId, reverse, localId("expense", value.id))
        val expenses = snapshot.records.filter { it.kind == "expense" }.map { localExpense(json.decodeFromJsonElement<Expense>(it.payload)) }
        val payments = snapshot.records.filter { it.kind == "settlement" }.map {
            val value = json.decodeFromJsonElement<Settlement>(it.payload)
            settlement(value, localGroupId, reverse, localId("settlement", value.id))
        }
        val comments = snapshot.records.filter { it.kind == "comment" }.map {
            val value = json.decodeFromJsonElement<Comment>(it.payload)
            value.copy(id = localId("comment", value.id), expenseId = localId("expense", value.expenseId), authorId = reverse.getValue(value.authorId))
        }
        val group = Group(localGroupId, snapshot.group.name, snapshot.participants.map { reverse.getValue(it.id) },
            snapshot.group.archived, snapshot.group.kind, snapshot.group.simplifyDebts, snapshot.group.icon, snapshot.group.color, snapshot.group.budget)
        val versions = snapshot.records.associate { entityKey(it.kind, it.id) to it.version } + (entityKey("group", snapshot.group.id) to snapshot.group.version)
        val link = SharedGroupLink(localGroupId, snapshot.group.id, accountId, snapshot.myParticipantId, peopleMap, entities, userIds, versions, snapshot)
        val oldExpenseIds = book.expenses.filter { it.groupId == localGroupId }.map { it.id }.toSet()
        var result = book.copy(people = people, groups = book.groups.filterNot { it.id == localGroupId } + group,
            expenses = book.expenses.filterNot { it.groupId == localGroupId } + expenses,
            settlements = book.settlements.filterNot { it.groupId == localGroupId } + payments,
            comments = book.comments.filterNot { it.expenseId in oldExpenseIds } + comments,
            revisions = book.revisions.filterNot { it.expense.groupId == localGroupId } + snapshot.revisions.map { ExpenseRevision(localId("revision", it.id), localExpense(it.expense), it.reason, it.time) },
            activity = book.activity.filterNot { it.groupId == localGroupId } + snapshot.activity.map { Activity(localId("activity", it.id), localGroupId, it.message, it.time) },
            sharedGroups = book.sharedGroups.filterNot { it.groupId == localGroupId } + link,
            pendingChanges = book.pendingChanges.filterNot { it.id == acknowledged })
        // Apply retained drafts over the latest accepted server snapshot. Their
        // original expected versions remain intact so concurrent edits conflict.
        for (change in result.pendingChanges.filter { it.groupId == localGroupId && it.kind != "share" }) {
            val payload = json.parseToJsonElement(change.payload).jsonObject
            when (change.kind) {
                "expense" -> { val value = localExpense(json.decodeFromJsonElement<Expense>(payload)); result = result.copy(expenses = result.expenses.filterNot { it.id == value.id } + value) }
                "settlement" -> { val raw = json.decodeFromJsonElement<Settlement>(payload); val value = settlement(raw, localGroupId, reverse, localId("settlement", raw.id)); result = result.copy(settlements = result.settlements.filterNot { it.id == value.id } + value) }
                "comment" -> { val raw = json.decodeFromJsonElement<Comment>(payload); val value = raw.copy(id = localId("comment", raw.id), expenseId = localId("expense", raw.expenseId), authorId = reverse.getValue(raw.authorId)); result = result.copy(comments = result.comments.filterNot { it.id == value.id } + value) }
                "group" -> { val raw = json.decodeFromJsonElement<RemoteGroup>(payload); result = result.copy(groups = result.groups.map { if (it.id == localGroupId) it.copy(name = raw.name, kind = raw.kind, archived = raw.archived, simplifyDebts = raw.simplifyDebts, icon = raw.icon, color = raw.color, budget = raw.budget) else it }) }
            }
        }
        return result.also(BookValidation::validate)
    }

    fun withoutSharedCache(book: Book, accountId: String? = null): Book {
        val removed = book.sharedGroups.filter { accountId == null || it.accountId == accountId }.map { it.groupId }.toSet()
        val expenses = book.expenses.filterNot { it.groupId in removed }
        val result = book.copy(groups = book.groups.filterNot { it.id in removed }, expenses = expenses,
            settlements = book.settlements.filterNot { it.groupId in removed }, comments = book.comments.filter { c -> expenses.any { it.id == c.expenseId } },
            revisions = book.revisions.filterNot { it.expense.groupId in removed }, activity = book.activity.filterNot { it.groupId in removed },
            sharedGroups = book.sharedGroups.filterNot { it.groupId in removed }, pendingChanges = book.pendingChanges.filterNot { it.groupId in removed })
        val used = result.groups.flatMap { it.members }.toSet()
        return result.copy(people = result.people.filter { it.id == "self" || it.id in used || !it.id.startsWith("shared-person:") }).also(BookValidation::validate)
    }

    /** A portable backup restores local records; it never replays cloud commands. */
    fun portable(book: Book) = book.copy(sharedGroups = emptyList(), pendingChanges = emptyList())
}
