package app.kitna.core

import org.junit.Assert.*
import org.junit.Test

class FriendIdentityTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val friend = "22222222-2222-4222-8222-222222222222"
    private val remoteGroup = "33333333-3333-4333-8333-333333333333"
    private val selfSlot = "44444444-4444-4444-8444-444444444444"
    private val friendSlot = "55555555-5555-4555-8555-555555555555"
    private fun fixture(): Book {
        val local = Group("local", "Dinner", listOf("self", "historical"), kind = "Direct")
        val shared = Group("shared", "Trip", listOf("self", "registered"))
        val bill = Expense("bill", local.id, "Dinner", 10001, mapOf("self" to 10001), mapOf("self" to 5000, "historical" to 5001))
        val cloudBill = Expense("cloud-bill", shared.id, "Taxi", 501, mapOf("registered" to 501), mapOf("self" to 251, "registered" to 250))
        val link = SharedGroupLink(shared.id, remoteGroup, owner, selfSlot, mapOf("self" to selfSlot, "registered" to friendSlot), userIds = mapOf("registered" to friend))
        return Book(people = listOf(Person("self", "You"), Person("historical", "Rahul", "friend@example.com"), Person("registered", "Rahul account", accountId = friend)),
            groups = listOf(local, shared), expenses = listOf(bill, cloudBill),
            settlements = listOf(Settlement("payment", shared.id, "self", "registered", 100)),
            comments = listOf(Comment("comment", cloudBill.id, "Thanks", authorId = "registered")),
            revisions = listOf(ExpenseRevision("revision", cloudBill.copy(notes = "Earlier note"), "Before edit")),
            sharedGroups = listOf(link), pendingChanges = listOf(SharedChange(accountId = owner, groupId = shared.id, kind = "expense", entityId = newId(), payload = "{}")))
            .also(BookValidation::validate)
    }
    @Test fun verifiedFriendMergePreservesHistoryAndRemotePendingCommands() {
        val before = fixture()
        val merged = BookActions.mergePeople(before, "historical", "registered")
        assertEquals(2, merged.people.size)
        assertEquals(friend, merged.people.single { it.id == "historical" }.accountId)
        assertEquals(before.expenses.map { it.id to it.amount }, merged.expenses.map { it.id to it.amount })
        assertEquals(before.pendingChanges, merged.pendingChanges)
        assertEquals("historical", merged.comments.single().authorId)
        assertEquals("historical", merged.settlements.single().to)
        assertEquals(mapOf("historical" to 501L), merged.revisions.single().expense.payers)
        assertEquals(friendSlot, merged.sharedGroups.single().people["historical"])
        assertEquals(friend, merged.sharedGroups.single().userIds["historical"])
        val oldBalances = Accounting.friendBalances(before)
        assertEquals(Math.addExact(oldBalances.getValue("historical"), oldBalances.getValue("registered")), Accounting.friendBalances(merged).getValue("historical"))
    }
    @Test fun mergeCombinesIntegerAllocationsWithoutRoundingAgain() {
        val before = Book(people = listOf(Person("self", "You"), Person("a", "A"), Person("b", "B", accountId = friend)),
            groups = listOf(Group("g", "Dinner", listOf("self", "a", "b"))), expenses = listOf(Expense("e", "g", "Dinner", 101,
                mapOf("a" to 30, "b" to 71), mapOf("self" to 33, "a" to 34, "b" to 34), splitMode = "Shares", splitWeights = mapOf("self" to 1, "a" to 1, "b" to 1))))
        val merged = BookActions.mergePeople(before, "a", "b")
        assertEquals(mapOf("a" to 101L), merged.expenses.single().payers)
        assertEquals(mapOf("self" to 33L, "a" to 68L), merged.expenses.single().shares)
        assertEquals(mapOf("self" to 1L, "a" to 2L), merged.expenses.single().splitWeights)
    }
    @Test fun ambiguousRemoteSlotsAndAliasRepaymentsCannotBeSilentlyMerged() {
        val before = fixture()
        val group = before.groups.single { it.id == "shared" }
        val conflict = before.copy(groups = before.groups.map { if (it.id == group.id) it.copy(members = it.members + "historical") else it }, sharedGroups = before.sharedGroups.map { it.copy(people = it.people + ("historical" to newId())) })
        assertTrue(runCatching { BookActions.mergePeople(conflict, "historical", "registered") }.isFailure)
        val repayment = before.copy(groups = before.groups.map { if (it.id == "local") it.copy(members = it.members + "registered", kind = "Group") else it }, settlements = before.settlements + Settlement(groupId = "local", from = "historical", to = "registered", amount = 10))
        assertTrue(runCatching { BookActions.mergePeople(repayment, "historical", "registered") }.isFailure)
        assertTrue(runCatching { BookActions.mergePeople(before, "self", "registered") }.isFailure)
    }
    @Test fun unclaimedSharedSlotDoesNotEraseAnEstablishedGlobalFriendAccount() {
        val before = Book(people = listOf(Person("self", "You"), Person("friend", "Rahul", accountId = friend)), groups = listOf(Group("local", "Trip", listOf("self", "friend"))))
        val prepared = SharedBooks.prepareShare(before, "local", owner)
        val link = prepared.sharedGroups.single()
        val snapshot = SharedSnapshot(RemoteGroup(link.remoteId, "Trip", ownerId = owner), listOf(RemoteParticipant(link.myParticipantId, "You", owner), RemoteParticipant(link.people.getValue("friend"), "Rahul")), myParticipantId = link.myParticipantId)
        val applied = SharedBooks.applySnapshot(prepared, snapshot, owner, prepared.pendingChanges.single().id)
        assertEquals(friend, applied.people.single { it.id == "friend" }.accountId)
        assertTrue(runCatching { SharedBooks.applySnapshot(applied, snapshot.copy(participants = snapshot.participants.map { if (it.id != link.myParticipantId) it.copy(userId = newId()) else it }), owner) }.isFailure)
    }
}
