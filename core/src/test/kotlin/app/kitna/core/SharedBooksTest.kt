package app.kitna.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SharedBooksTest {
    @Test fun theSameUuidFriendCanBelongToTwoSharedGroupsWithoutParticipantCollisions() {
        val personId = "33333333-3333-4333-8333-333333333333"
        val first = Group("44444444-4444-4444-8444-444444444444", "First trip", listOf("self", personId))
        val second = first.copy(id = "55555555-5555-4555-8555-555555555555", name = "Second trip")
        val book = Book(people = listOf(Person("self", "You"), Person(personId, "Alex")), groups = listOf(first, second))
        val shared = SharedBooks.prepareShare(SharedBooks.prepareShare(book, first.id, owner), second.id, owner)
        assertEquals(4, shared.sharedGroups.flatMap { it.people.values }.toSet().size)
        assertNotEquals(personId, shared.sharedGroups.first().people.getValue(personId))
        val otherOwner = SharedBooks.prepareShare(book, first.id, friend)
        assertNotEquals(shared.sharedGroups.first().remoteId, otherOwner.sharedGroups.single().remoteId)
        BookValidation.validate(shared)
    }

    @Test fun anUnresolvedConflictBlocksAnotherEditAndCorruptIdentityMetadataFailsClosed() {
        val (shared, _) = accepted()
        val queued = SharedBooks.queueChanges(shared, BookActions.saveExpense(shared, bill.copy(notes = "My saved draft")), owner)
        val conflicted = queued.copy(pendingChanges = queued.pendingChanges.map { it.copy(error = "Review conflict", conflict = true) })
        rejected { SharedBooks.queueChanges(conflicted, BookActions.saveExpense(conflicted, bill.copy(notes = "Another edit")), owner) }
        rejected { BookValidation.validate(shared.copy(sharedGroups = shared.sharedGroups.map { it.copy(people = it.people - "self") })) }
        rejected { BookValidation.validate(queued.copy(pendingChanges = queued.pendingChanges.map { it.copy(accountId = friend) })) }
    }
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val friend = "22222222-2222-4222-8222-222222222222"
    private val trip = Group("road-trip", "Goa trip", listOf("self", "alex-local"), kind = "Trip", icon = "plane", color = "blue", budget = 30000)
    private val privateGroup = Group("home-private", "Private home", listOf("self", "casey-local"))
    private val previousPhoto = ReceiptPhoto("receipt-old", "Earlier receipt", sizeBytes = 100, sha256 = "ab".repeat(32))
    private val currentPhoto = ReceiptPhoto("receipt-current", "Current receipt", sizeBytes = 200, sha256 = "cd".repeat(32))
    private val bill = Expense("bill:2026-11", trip.id, "Hotel room", 10001,
        mapOf("self" to 10001), mapOf("self" to 5000, "alex-local" to 5001),
        category = "Travel", date = "2026-11-15", notes = "Original notes", receipts = listOf(currentPhoto))
    private val privateBill = Expense("private-milk", privateGroup.id, "Milk", 500,
        mapOf("self" to 500), mapOf("self" to 250, "casey-local" to 250), date = "2026-11-14")
    private val payment = Settlement("paid:upi", trip.id, "alex-local", "self", 2000, "UPI", "2026-11-16", reference = "original reference")
    private val comment = Comment("comment:old", bill.id, "Thanks!", time = 123, authorId = "alex-local")
    private val history = ExpenseRevision("revision:old", bill.copy(description = "Hotel", date = "2026-11-13", receipts = listOf(previousPhoto)), "Before edit", time = 99)
    private val event = Activity("activity:old", trip.id, "Added hotel room", time = 100)

    private fun sample() = Book(
        people = listOf(Person("self", "Taylor"), Person("alex-local", "Alex"), Person("casey-local", "Casey")),
        groups = listOf(trip, privateGroup), expenses = listOf(bill, privateBill), settlements = listOf(payment),
        comments = listOf(comment), revisions = listOf(history), activity = listOf(event)
    ).also(BookValidation::validate)

    /** Interpret the initial share command exactly as separate server records. */
    private fun snapshot(prepared: Book): SharedSnapshot {
        val change = prepared.pendingChanges.single { it.kind == "share" }
        val payload = SharedBooks.json.parseToJsonElement(change.payload).jsonObject
        val records = payload.getValue("p_records").jsonArray.map { it.jsonObject }
        return SharedSnapshot(
            group = SharedBooks.json.decodeFromJsonElement<RemoteGroup>(payload.getValue("p_group")).copy(ownerId = owner),
            participants = payload.getValue("p_participants").jsonArray.map {
                val row = it.jsonObject
                RemoteParticipant(row.getValue("id").jsonPrimitive.content, row.getValue("name").jsonPrimitive.content,
                    if (row.getValue("isSelf").jsonPrimitive.boolean) owner else friend)
            },
            records = records.filter { it.getValue("kind").jsonPrimitive.content in setOf("expense", "settlement", "comment") }.map {
                RemoteRecord(it.getValue("kind").jsonPrimitive.content, it.getValue("id").jsonPrimitive.content, it.getValue("payload").jsonObject, 1)
            },
            revisions = records.filter { it.getValue("kind").jsonPrimitive.content == "revision" }.map {
                val revision = SharedBooks.json.decodeFromJsonElement<ExpenseRevision>(it.getValue("payload"))
                RemoteRevision(revision.id, revision.expense, revision.reason, revision.time)
            },
            activity = records.filter { it.getValue("kind").jsonPrimitive.content == "activity" }.map {
                val activity = SharedBooks.json.decodeFromJsonElement<Activity>(it.getValue("payload"))
                RemoteActivity(activity.id, activity.message, activity.time)
            },
            myParticipantId = prepared.sharedGroups.single().myParticipantId, revision = 1
        )
    }

    private fun accepted(): Pair<Book, SharedSnapshot> {
        val prepared = SharedBooks.prepareShare(sample(), trip.id, owner)
        val initial = snapshot(prepared)
        return SharedBooks.applySnapshot(prepared, initial, owner, prepared.pendingChanges.single().id) to initial
    }

    private fun serverAccept(snapshot: SharedSnapshot, command: SharedChange): SharedSnapshot {
        val replacement = RemoteRecord(command.kind, command.entityId,
            SharedBooks.json.parseToJsonElement(command.payload).jsonObject, command.expectedVersion + 1)
        return snapshot.copy(records = snapshot.records.filterNot { it.kind == command.kind && it.id == command.entityId } + replacement,
            revision = snapshot.revision + 1)
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected the operation to be rejected") } catch (_: IllegalArgumentException) { }
    }

    @Test fun sharingCanonicalizesOnlyTheChosenGroupAndPreservesDatesAllocationsAndHistory() {
        val original = sample()
        val prepared = SharedBooks.prepareShare(original, trip.id, owner)
        val link = prepared.sharedGroups.single()
        UUID.fromString(link.remoteId)
        link.people.values.forEach { UUID.fromString(it) }
        link.entities.values.forEach { UUID.fromString(it) }
        assertEquals(original.groups, prepared.groups)
        assertEquals(original.expenses, prepared.expenses)
        assertEquals(original.revisions, prepared.revisions)
        assertTrue(original.sharedGroups.isEmpty())
        val remote = snapshot(prepared)
        val remoteBill = SharedBooks.json.decodeFromJsonElement<Expense>(remote.records.single { it.kind == "expense" }.payload)
        assertEquals("2026-11-15", remoteBill.date)
        assertEquals(mapOf(link.people.getValue("self") to 10001L), remoteBill.payers)
        assertEquals(mapOf(link.people.getValue("self") to 5000L, link.people.getValue("alex-local") to 5001L), remoteBill.shares)
        assertEquals(listOf(currentPhoto), remoteBill.receipts)
        assertEquals("2026-11-13", remote.revisions.single().expense.date)
        assertEquals(listOf(previousPhoto), remote.revisions.single().expense.receipts)
        assertEquals(99L, remote.revisions.single().time)
        assertFalse(prepared.pendingChanges.single().payload.contains("private-milk"))
        assertFalse(prepared.pendingChanges.single().payload.contains("Milk"))
        val retry = SharedBooks.prepareShare(original, trip.id, owner)
        assertEquals(link.remoteId, retry.sharedGroups.single().remoteId)
        assertEquals(prepared.pendingChanges.single().payload, retry.pendingChanges.single().payload)
    }

    @Test fun twoAccountsProjectTheirOwnSelfWithoutChangingWhoPaidOrOwes() {
        val (ownerBook, originalSnapshot) = accepted()
        assertEquals(bill, ownerBook.expenses.single { it.groupId == trip.id })
        assertEquals(payment, ownerBook.settlements.single())
        assertEquals(comment, ownerBook.comments.single())
        assertEquals(history, ownerBook.revisions.single())
        val friendParticipant = originalSnapshot.participants.single { it.userId == friend }.id
        val friendSnapshot = originalSnapshot.copy(myParticipantId = friendParticipant)
        val friendBook = SharedBooks.applySnapshot(Book(people = listOf(Person("self", "Alex"))), friendSnapshot, friend)
        val friendGroup = friendBook.groups.single()
        val friendBill = friendBook.expenses.single()
        val ownerLocalId = friendBook.sharedGroups.single().people.entries.single { it.value == originalSnapshot.myParticipantId }.key
        assertNotEquals("self", ownerLocalId)
        assertEquals(mapOf(ownerLocalId to 10001L), friendBill.payers)
        assertEquals(5001L, friendBill.shares["self"])
        assertEquals("self", friendBook.settlements.single().from)
        assertEquals(ownerLocalId, friendBook.settlements.single().to)
        assertEquals("self", friendBook.comments.single().authorId)
        assertEquals(3001L, Accounting.balances(ownerBook, trip.id).getValue("self"))
        assertEquals(-3001L, Accounting.balances(friendBook, friendGroup.id).getValue("self"))
        assertEquals("2026-11-15", friendBill.date)
        assertEquals("2026-11-13", friendBook.revisions.single().expense.date)
        BookValidation.validate(friendBook)
    }

    @Test fun queuedEntityVersionsStaySequentialThroughShareAndAcknowledgedEdits() {
        val prepared = SharedBooks.prepareShare(sample(), trip.id, owner)
        val firstEdit = BookActions.saveExpense(prepared, bill.copy(notes = "First offline edit"))
        val firstQueued = SharedBooks.queueChanges(prepared, firstEdit, owner)
        val secondEdit = BookActions.saveExpense(firstQueued, firstQueued.expenses.single { it.id == bill.id }.copy(description = "Second offline edit"))
        val secondQueued = SharedBooks.queueChanges(firstQueued, secondEdit, owner)
        assertEquals(listOf(1L, 2L), secondQueued.pendingChanges.filter { it.kind == "expense" }.map { it.expectedVersion })
        val baseSnapshot = snapshot(prepared)
        val shareAck = SharedBooks.applySnapshot(secondQueued, baseSnapshot, owner, prepared.pendingChanges.single().id)
        assertEquals(2, shareAck.pendingChanges.size)
        assertEquals("Second offline edit", shareAck.expenses.single { it.id == bill.id }.description)
        val firstCommand = shareAck.pendingChanges.first()
        val afterFirst = SharedBooks.applySnapshot(shareAck, serverAccept(baseSnapshot, firstCommand), owner, firstCommand.id)
        assertEquals(listOf(2L), afterFirst.pendingChanges.map { it.expectedVersion })
        assertEquals("Second offline edit", afterFirst.expenses.single { it.id == bill.id }.description)
        val thirdEdit = BookActions.saveExpense(afterFirst, afterFirst.expenses.single { it.id == bill.id }.copy(notes = "Third offline edit"))
        val thirdQueued = SharedBooks.queueChanges(afterFirst, thirdEdit, owner)
        assertEquals(listOf(2L, 3L), thirdQueued.pendingChanges.map { it.expectedVersion })
        assertEquals(3, thirdQueued.pendingChanges.map { it.id }.plus(firstCommand.id).toSet().size)
    }

    @Test fun freshSnapshotsRetainPendingDraftAndItsOriginalConflictVersion() {
        val (base, remote) = accepted()
        val edited = BookActions.saveExpense(base, bill.copy(notes = "My unsent notes"))
        val pending = SharedBooks.queueChanges(base, edited, owner)
        val command = pending.pendingChanges.single()
        val record = remote.records.single { it.kind == "expense" }
        val concurrentBill = SharedBooks.json.decodeFromJsonElement<Expense>(record.payload).copy(notes = "Friend's saved notes")
        val concurrent = remote.copy(records = remote.records.map { if (it == record) it.copy(payload = SharedBooks.json.encodeToJsonElement(concurrentBill).jsonObject, version = 2) else it }, revision = 2)
        val refreshed = SharedBooks.applySnapshot(pending, concurrent, owner)
        assertEquals("My unsent notes", refreshed.expenses.single { it.id == bill.id }.notes)
        assertEquals(command.id, refreshed.pendingChanges.single().id)
        assertEquals(1L, refreshed.pendingChanges.single().expectedVersion)
        assertEquals(2L, refreshed.sharedGroups.single().versions.getValue("expense:${record.id}"))
        val saved = refreshed.sharedGroups.single().snapshot!!.records.single { it.kind == "expense" }
        assertEquals("Friend's saved notes", SharedBooks.json.decodeFromJsonElement<Expense>(saved.payload).notes)
    }

    @Test fun portableBackupsKeepRecordsButNeverRestoreCloudCommands() {
        val prepared = SharedBooks.prepareShare(sample(), trip.id, owner)
        val portable = SharedBooks.portable(prepared)
        assertTrue(portable.sharedGroups.isEmpty())
        assertTrue(portable.pendingChanges.isEmpty())
        assertEquals(sample(), portable)
        assertEquals(1, prepared.pendingChanges.size)
        BookValidation.validate(portable)
    }

    @Test fun signingOutDropsOnlyThatAccountsSharedCacheAndLeavesPrivateRecords() {
        val (shared, _) = accepted()
        val cleared = SharedBooks.withoutSharedCache(shared, owner)
        assertEquals(listOf(privateGroup), cleared.groups)
        assertEquals(listOf(privateBill), cleared.expenses)
        assertTrue(cleared.settlements.isEmpty())
        assertTrue(cleared.comments.isEmpty())
        assertTrue(cleared.revisions.isEmpty())
        assertTrue(cleared.activity.isEmpty())
        assertTrue(cleared.sharedGroups.isEmpty())
        assertTrue(cleared.pendingChanges.isEmpty())
        assertEquals("Taylor", cleared.people.single { it.id == "self" }.name)
        assertTrue(cleared.people.any { it.id == "casey-local" })
        assertEquals(shared, SharedBooks.withoutSharedCache(shared, friend))
    }

    @Test fun unauthorisedAccountsMembershipEditsAndCommentImpersonationAreRejected() {
        val (shared, _) = accepted()
        val expenseEdit = BookActions.saveExpense(shared, bill.copy(notes = "An edit"))
        rejected { SharedBooks.queueChanges(shared, expenseEdit, friend) }
        val membershipEdit = shared.copy(groups = shared.groups.map { if (it.id == trip.id) it.copy(members = it.members + "casey-local") else it })
        rejected { SharedBooks.queueChanges(shared, membershipEdit, owner) }
        val postedAsFriend = BookActions.saveComment(shared, Comment("new-comment", bill.id, "Impersonated", authorId = "alex-local"))
        rejected { SharedBooks.queueChanges(shared, postedAsFriend, owner) }
        val revoked = shared.copy(sharedGroups = shared.sharedGroups.map { it.copy(revoked = true) })
        rejected { SharedBooks.queueChanges(revoked, BookActions.saveExpense(revoked, bill.copy(notes = "Stale edit")), owner) }
    }
}
