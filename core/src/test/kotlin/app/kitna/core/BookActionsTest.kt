package app.kitna.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BookActionsTest {
    private val group = Group("g", "Flat", listOf("self", "b", "c"))
    private fun initial(simplify: Boolean = true) = Book(people = listOf(Person("self", "Me"), Person("b", "Bob"), Person("c", "Cara")), groups = listOf(group.copy(simplifyDebts = simplify)))
    private fun expense(id: String = "e", amount: Long = 900L) = Expense(id, "g", "Dinner", amount, mapOf("self" to amount), Accounting.split(amount, group.members.associateWith { 1L }))
    private fun checked(book: Book): Book = book.also(BookValidation::validate)
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } }

    @Test fun editDeleteRestoreUpdatesBalancesAndPreservesSnapshots() {
        var book = checked(BookActions.saveExpense(initial(), expense()))
        assertEquals(600L, Accounting.balances(book, "g").getValue("self"))
        book = checked(BookActions.saveExpense(book, expense(amount = 1200)))
        assertEquals(800L, Accounting.balances(book, "g").getValue("self"))
        assertEquals(900L, book.revisions.single().expense.amount)
        book = checked(BookActions.deleteExpense(book, "e"))
        assertTrue(Accounting.balances(book, "g").values.all { it == 0L })
        book = checked(BookActions.restoreExpense(book, "e"))
        assertEquals(800L, Accounting.balances(book, "g").getValue("self"))
        assertEquals(3, book.revisions.size)
    }
    @Test fun realRepaymentCanBeRecordedWhenNetIsZeroAndThenCorrected() {
        // A cycle has zero group net positions but still has pairwise obligations.
        var book = initial(false)
        listOf(Triple("one", "self", "b"), Triple("two", "b", "c"), Triple("three", "c", "self")).forEach { (id, payer, debtor) ->
            book = checked(BookActions.saveExpense(book, Expense(id, "g", "Bill", 100, mapOf(payer to 100), mapOf(debtor to 100))))
        }
        assertTrue(Accounting.balances(book, "g").values.all { it == 0L })
        assertEquals(3, Accounting.transfers(book, "g").size)
        val repayment = Settlement("payment", "g", "b", "self", 40, reference = "UPI ref")
        book = checked(BookActions.saveSettlement(book, repayment))
        assertEquals(60L, Accounting.transfers(book, "g").first { it.from == "b" && it.to == "self" }.amount)
        book = checked(BookActions.saveSettlement(book, repayment.copy(amount = 100)))
        assertTrue(Accounting.transfers(book, "g").none { it.from == "b" && it.to == "self" })
        book = checked(BookActions.deleteSettlement(book, "payment"))
        assertTrue(Accounting.balances(book, "g").values.all { it == 0L })
        assertEquals(3, Accounting.transfers(book, "g").size)
    }
    @Test fun simplificationTogglePreservesEveryPersonNetAndFriendTotals() {
        val raw = checked(BookActions.saveExpense(initial(false), expense()))
        val simplified = checked(BookActions.saveGroup(raw, group.copy(simplifyDebts = true)))
        assertEquals(Accounting.balances(raw, "g"), Accounting.balances(simplified, "g"))
        assertEquals(600L, Accounting.friendBalances(raw).values.sum())
        assertEquals(600L, Accounting.friendBalances(simplified).values.sum())
    }
    @Test fun duplicateNamesAndHistoryBreakingMemberRemovalAreRejected() {
        rejected { BookActions.addPerson(initial(), " bob ") }
        rejected { BookActions.renamePerson(initial(), "c", "Bob") }
        val book = checked(BookActions.saveExpense(initial(), expense()))
        rejected { BookActions.saveGroup(book, group.copy(members = listOf("self", "b"))) }
        rejected { BookActions.deletePerson(book, "self") }
        rejected { BookActions.deleteGroup(book, "g") }
    }
    @Test fun archivedGroupsCannotBeMutatedAndCyclesCannotBeSilentlyArchived() {
        val expenseBook = checked(BookActions.saveExpense(initial(), expense()))
        rejected { BookActions.archive(expenseBook, group) }
        var empty = checked(BookActions.archive(initial(), group))
        rejected { BookActions.saveExpense(empty, expense()) }
        rejected { BookActions.saveSettlement(empty, Settlement(groupId = "g", from = "b", to = "self", amount = 1)) }
        empty = checked(BookActions.archive(empty, group))
        checked(BookActions.saveExpense(empty, expense()))
    }
    @Test fun commentsRequireActualMemberAndPersistThroughExpenseDeletion() {
        var book = checked(BookActions.saveExpense(initial(), expense()))
        rejected { BookActions.comment(book, "e", "Note", "stranger") }
        book = checked(BookActions.comment(book, "e", " Paid at cafe ", "b"))
        assertEquals("Paid at cafe", book.comments.single().text)
        book = checked(BookActions.deleteExpense(book, "e"))
        assertEquals("b", book.comments.single().authorId)
        rejected { BookActions.comment(book, "e", "More", "self") }
    }
    @Test fun directFriendLedgerIsReusedAndRenamed() {
        val (first, id) = BookActions.directGroup(initial(), "b")
        val (again, repeatedId) = BookActions.directGroup(first, "b")
        assertEquals(id, repeatedId)
        assertEquals(2, again.groups.size)
        val renamed = checked(BookActions.renamePerson(again, "b", "Robert"))
        assertEquals("Robert", renamed.groups.first { it.id == id }.name)
    }
    @Test fun cancellingAnEmptyDirectLedgerDoesNotTrapFriendDeletion() {
        val bare = Book(people = listOf(Person("self", "Me"), Person("b", "Bob")))
        val (withEmptyDirect, _) = BookActions.directGroup(bare, "b")
        val deleted = checked(BookActions.deletePerson(withEmptyDirect, "b"))
        assertTrue(deleted.groups.isEmpty())
        assertEquals(listOf("self"), deleted.people.map { it.id })
    }
    @Test fun retryingStableIdsDoesNotDuplicateRecordsOrAuditEntries() {
        val person = Person("new-person", "Dana")
        var book = checked(BookActions.savePerson(initial(), person))
        assertEquals(book, BookActions.savePerson(book, person))
        book = checked(BookActions.saveExpense(book, expense()))
        assertEquals(book, BookActions.saveExpense(book, expense()))
        val payment = Settlement("payment", "g", "b", "self", 10)
        book = checked(BookActions.saveSettlement(book, payment))
        assertEquals(book, BookActions.saveSettlement(book, payment))
        val comment = Comment("note", "e", "hello", time = 1)
        book = checked(BookActions.saveComment(book, comment))
        assertEquals(book, BookActions.saveComment(book, comment))
    }
    @Test fun legacyBackupGetsSafeDefaultsAndNewBackupRoundTripsAllHistory() {
        val old = """{"people":[{"id":"self","name":"Me"},{"id":"b","name":"Bob"}],"groups":[{"id":"g","name":"G","members":["self","b"]}],"expenses":[{"id":"e","groupId":"g","description":"Old","amount":100,"payers":{"self":100},"shares":{"self":50,"b":50}}]}"""
        val loaded = checked(Json.decodeFromString<Book>(old))
        assertTrue(loaded.groups.single().simplifyDebts)
        assertEquals("Exact amounts", loaded.expenses.single().splitMode)
        val changed = checked(BookActions.saveExpense(loaded, loaded.expenses.single().copy(notes = "A note", amount = 200, payers = mapOf("self" to 200), shares = mapOf("self" to 100, "b" to 100))))
        assertEquals(changed, checked(Json.decodeFromString<Book>(Json.encodeToString(changed))))
    }
    @Test fun invalidBackupReferencesAmountsAndDuplicateIdsAreRejected() {
        rejected { BookValidation.validate(initial().copy(people = initial().people + Person("b", "Duplicate"))) }
        rejected { BookValidation.validate(initial().copy(expenses = listOf(expense().copy(payers = mapOf("self" to 899))))) }
        rejected { BookValidation.validate(initial().copy(groups = listOf(group.copy(members = listOf("self", "missing"))))) }
        rejected { BookValidation.validate(initial().copy(settlements = listOf(Settlement(groupId = "g", from = "self", to = "self", amount = 1)))) }
    }
    @Test fun amountParsingRejectsInvalidFormatsWithoutRoundingAndAllowsSignedAdjustments() {
        assertEquals(12345L, Accounting.money("123.45"))
        assertEquals(50L, Accounting.money(".5"))
        assertEquals(-125L, Accounting.signedMoney("-1.25"))
        listOf("", "NaN", "1e3", "-1", "1.001", "9".repeat(100)).forEach { text -> rejected { Accounting.money(text) } }
        val adjusted = Accounting.adjustedSplit(1000, mapOf("self" to 200L, "b" to 0L))
        assertEquals(mapOf("self" to 600L, "b" to 400L), adjusted)
        assertEquals(1000L, Accounting.adjustedSplit(1000, mapOf("self" to -100L, "b" to 0L)).values.sum())
        assertEquals(Accounting.MAX_AMOUNT, Accounting.adjustedSplit(Accounting.MAX_AMOUNT, mapOf("self" to -100L, "b" to 0L)).values.sum())
        rejected { Accounting.adjustedSplit(100, mapOf("self" to -200L, "b" to 0L)) }
    }
    @Test fun csvExportsAmountsReferencesCommentsAndEscapesFormulaInjection() {
        var book = checked(BookActions.saveExpense(initial(), expense().copy(description = "=HYPERLINK(\"x\")", notes = "line1\nline2")))
        book = checked(BookActions.comment(book, "e", "A note", "b"))
        book = checked(BookActions.saveSettlement(book, Settlement(groupId = "g", from = "b", to = "self", amount = 1, reference = "ref123")))
        val csv = BookExport.csv(book)
        assertTrue(csv.contains("'=HYPERLINK")); assertTrue(csv.contains("Bob: A note")); assertTrue(csv.contains("ref123")); assertTrue(csv.contains("9.00"))
    }
}
