package app.kitna.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class SimplifiedWorkflowTest {
    private val people = listOf(Person("self", "You"), Person("a", "Asha"), Person("b", "Rahul"))
    private fun expense(id: String = "bill") = Expense(id, "", "Dinner", 10001, mapOf("self" to 10001), Accounting.split(10001, people.associate { it.id to 1L }), splitMode = "Equal")

    @Test fun nameOnlyGroupCanBeCreatedAndKeepsItsIdWhenMembersJoin() {
        val original = BookActions.saveGroup(Book(people = people), Group("trip", "Vrindavan", listOf("self")))
        val updated = BookActions.saveGroup(original, original.groups.single().copy(members = listOf("self", "a", "a", "b")))
        assertEquals(listOf("self", "a", "b"), updated.groups.single().members)
        assertEquals("trip", updated.groups.single().id)
        BookValidation.validate(updated)
    }
    @Test fun outsideGroupBillReconcilesAndDoesNotCreateAVisibleGroup() {
        val saved = BookActions.saveNonGroupExpense(Book(people = people), expense(), people.map { it.id }.toSet())
        assertTrue(saved.groups.single().isNonGroup)
        assertEquals(10001L, saved.expenses.single().shares.values.sum())
        assertEquals(mapOf("a" to 3334L, "b" to 3334L), Accounting.friendBalances(saved))
        assertEquals(0L, Accounting.balances(saved, saved.groups.single().id).values.sum())
        BookValidation.validate(saved)
    }
    @Test fun unorderedSelectionReusesLedgerAndRepaymentsAffectOnlyTheirLedger() {
        val first = BookActions.saveNonGroupExpense(Book(people = people), expense(), setOf("self", "a", "b"))
        val second = BookActions.saveNonGroupExpense(first, expense("bill2"), linkedSetOf("b", "a", "self"))
        assertEquals(1, second.groups.size)
        val repaid = BookActions.saveSettlement(second, Settlement(groupId = second.groups.single().id, from = "a", to = "self", amount = 3334))
        assertEquals(3334L, Accounting.friendBalances(repaid)["a"])
        assertEquals(6668L, Accounting.friendBalances(repaid)["b"])
    }
    @Test fun twoPersonBillUsesExistingBackwardCompatibleDirectLedger() {
        val direct = BookActions.directGroup(Book(people = people), "a")
        val bill = expense().copy(shares = mapOf("self" to 5001, "a" to 5000))
        val saved = BookActions.saveNonGroupExpense(direct.first, bill, setOf("a"))
        assertEquals(direct.second, saved.expenses.single().groupId)
        assertEquals(1, saved.groups.size)
        assertEquals("Direct", saved.groups.single().kind)
    }
    @Test fun personalExpensesNeverInflateFriendBalances() {
        val bill = expense().copy(shares = mapOf("self" to 10001))
        val saved = BookActions.saveNonGroupExpense(Book(people = people), bill, emptySet())
        assertTrue(Accounting.friendBalances(saved).values.all { it == 0L })
        assertEquals(listOf("self"), saved.groups.single().members)
        BookValidation.validate(saved)
    }
    @Test fun directRepaymentCreatesOneLedgerAtomicallyAndRetriesWithoutDuplicating() {
        val original = Book(people = people)
        val draft = Settlement("payment", "friend:a", "self", "a", 500)
        val saved = BookActions.saveDirectSettlement(original, draft, "a")
        assertEquals(1, saved.groups.size)
        assertEquals(1, saved.settlements.size)
        assertEquals(saved, BookActions.saveDirectSettlement(saved, draft, "a"))
        assertTrue(runCatching { BookActions.saveDirectSettlement(original, draft.copy(amount = 0), "a") }.isFailure)
        assertTrue(runCatching { BookActions.saveDirectSettlement(saved, draft.copy(amount = 600), "a") }.isFailure)
        assertTrue(original.groups.isEmpty())
        assertTrue(original.settlements.isEmpty())
    }
    @Test fun outsideGroupAndGroupBalancesAreCountedExactlyOnce() {
        var saved = BookActions.saveNonGroupExpense(Book(people = people), expense(), setOf("a", "b"))
        saved = BookActions.saveGroup(saved, Group("g", "Home", people.map { it.id }))
        saved = BookActions.saveExpense(saved, expense("group-bill").copy(groupId = "g"))
        assertEquals(6668L, Accounting.friendBalances(saved)["a"])
        assertEquals(6668L, Accounting.friendBalances(saved)["b"])
        assertEquals(2, saved.expenses.size)
    }
    @Test fun repeatedSaveIsIdempotentAndEditingKeepsHistory() {
        val original = BookActions.saveNonGroupExpense(Book(people = people), expense(), setOf("a", "b"))
        val bill = original.expenses.single()
        assertEquals(original, BookActions.saveExpense(original, bill))
        val edited = BookActions.saveExpense(original, bill.copy(notes = "A note"))
        assertEquals(bill, edited.revisions.single().expense)
        assertEquals(1, edited.expenses.size)
    }
    @Test fun retryAfterProcessRecreationReusesCommittedNonGroupBillWithoutOverwritingAnEdit() {
        val draft = expense()
        val saved = BookActions.saveNonGroupExpense(Book(people = people), draft, setOf("a", "b"))
        assertEquals(saved, BookActions.saveNonGroupExpense(saved, draft, setOf("a", "b")))
        assertTrue(runCatching { BookActions.saveNonGroupExpense(saved, draft.copy(notes = "Changed stale draft"), setOf("a", "b")) }.isFailure)
        assertTrue(runCatching { BookActions.saveNonGroupExpense(saved, draft, setOf("a")) }.isFailure)
        assertEquals(1, saved.expenses.size)
        assertTrue(saved.revisions.isEmpty())
    }
    @Test fun invalidNonGroupSaveCannotChangeTheOriginalBook() {
        val original = Book(people = people)
        assertTrue(runCatching { BookActions.saveNonGroupExpense(original, expense().copy(payers = mapOf("self" to 10000)), setOf("a", "b")) }.isFailure)
        assertTrue(original.groups.isEmpty())
        assertTrue(original.expenses.isEmpty())
    }
    @Test fun financialInputRejectsOverflowNegativeMalformedAndFractionalPaise() {
        listOf("-1", "1.001", "NaN", "1e3", "9223372036854775808", "1,000.00", "1000000000.01").forEach { assertTrue(it, runCatching { Accounting.money(it) }.isFailure) }
        assertEquals(101L, Accounting.money("1.01"))
        assertTrue(runCatching { Accounting.split(Accounting.money("0"), mapOf("self" to 1)) }.isFailure)
    }
    @Test fun legacyBookLoadsWithContactDefaultsAndKeepsHistoricalAmounts() {
        val source = """{"people":[{"id":"self","name":"You"},{"id":"a","name":"Asha"}],"groups":[{"id":"g","name":"Old","members":["self","a"]}],"expenses":[{"id":"e","groupId":"g","description":"Tea","amount":301,"payers":{"self":301},"shares":{"self":151,"a":150}}]}"""
        val book = Json.decodeFromString<Book>(source)
        assertEquals("", book.people[1].phone)
        assertEquals("", book.people[1].accountId)
        assertEquals(150L, Accounting.friendBalances(book)["a"])
        BookValidation.validate(book)
    }
}
