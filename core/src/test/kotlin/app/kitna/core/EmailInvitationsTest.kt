package app.kitna.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EmailInvitationsTest {
    @Test fun oldPeopleKeepTheirNamesAndNewEmailsNormalizeWithoutChangingBalances() {
        val old = SharedBooks.json.decodeFromString<Person>("""{"id":"alex","name":"Alex"}""")
        assertEquals("", old.email)
        val book = Book(people = listOf(Person("self", "Owner"), old), groups = listOf(Group("trip", "Trip", listOf("self", "alex"))))
        val withBill = BookActions.saveExpense(book, Expense(groupId = "trip", description = "Taxi", amount = 10000, payers = mapOf("self" to 10000), shares = mapOf("self" to 5000, "alex" to 5000)))
        val changed = BookActions.savePerson(withBill, old.copy(email = " ALEX@Example.com "))
        assertEquals("alex@example.com", changed.people.last().email)
        assertEquals(Accounting.balances(withBill, "trip"), Accounting.balances(changed, "trip"))
        assertTrue(runCatching { BookActions.savePerson(changed, Person(name = "Other", email = "alex@example.com")) }.isFailure)
        assertTrue(runCatching { BookActions.savePerson(changed, old.copy(email = "bad email")) }.isFailure)
    }
    @Test fun sharingIncludesOnlySelectedFriendsEmailsAndKeepsThemOnSnapshotRefresh() {
        val owner = "11111111-1111-4111-8111-111111111111"
        val book = Book(people = listOf(Person("self", "Owner", "owner@example.com"), Person("alex", "Alex", "alex@example.com"), Person("private", "Private", "private@example.com")), groups = listOf(Group("trip", "Trip", listOf("self", "alex"))))
        val prepared = SharedBooks.prepareShare(book, "trip", owner)
        val payload = SharedBooks.json.parseToJsonElement(prepared.pendingChanges.single().payload).jsonObject
        val people = payload.getValue("p_participants").jsonArray
        assertEquals(listOf("alex@example.com"), people.mapNotNull { it.jsonObject["email"]?.jsonPrimitive?.content })
        assertFalse(payload.toString().contains("private@example.com"))
        val snapshot = SharedSnapshot(group = SharedBooks.json.decodeFromJsonElement<RemoteGroup>(payload.getValue("p_group")).copy(ownerId = owner), participants = people.map { val p = it.jsonObject; RemoteParticipant(p.getValue("id").jsonPrimitive.content, p.getValue("name").jsonPrimitive.content, if (p["isSelf"]?.jsonPrimitive?.boolean == true) owner else null) }, myParticipantId = prepared.sharedGroups.single().myParticipantId)
        val refreshed = SharedBooks.applySnapshot(prepared, snapshot, owner, prepared.pendingChanges.single().id)
        assertEquals("alex@example.com", refreshed.people.find { it.id == "alex" }?.email)
        BookValidation.validate(refreshed)
    }
}
