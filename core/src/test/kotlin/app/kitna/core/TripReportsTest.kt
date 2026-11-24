package app.kitna.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TripReportsTest {
    private val group = Group("trip", "Goa weekend", listOf("self", "alex", "sam"), budget = 200000)
    private val people = listOf(Person("self", "You"), Person("alex", "Alex"), Person("sam", "Sam"))
    private val dinner = Expense("dinner", "trip", "Dinner", 90000, mapOf("self" to 90000),
        mapOf("self" to 30000, "alex" to 30000, "sam" to 30000), "Food", "2026-11-17")
    private val taxi = Expense("taxi", "trip", "Taxi", 35000, mapOf("alex" to 35000),
        mapOf("self" to 15000, "alex" to 10000, "sam" to 10000), "Travel", "2026-11-18")
    private val payment = Settlement("paid", "trip", "sam", "self", 20000, "UPI", "2026-11-19")
    private fun sample() = Book(people = people, groups = listOf(group), expenses = listOf(dinner, taxi), settlements = listOf(payment))
    private fun photo(id: String = "receipt-1") = ReceiptPhoto(id, "Dinner bill", sizeBytes = 2048, sha256 = "ab".repeat(32))
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected invalid input to be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun budgetCountsExpenseTotalsWithoutCountingRepaymentsOrOtherGroups() {
        val other = group.copy(id = "other", name = "Unrelated")
        val book = sample().copy(groups = listOf(group, other), expenses = listOf(dinner, taxi,
            dinner.copy(id = "elsewhere", groupId = "other", amount = 300000, payers = mapOf("self" to 300000), shares = mapOf("self" to 300000))))
        BookValidation.validate(book)
        assertEquals(BudgetStatus(200000, 125000, 75000), TripReports.budget(book, "trip"))
        assertEquals(mapOf("Food" to 90000L, "Travel" to 35000L), TripReports.categories(book, "trip"))
        assertEquals(0L, TripReports.budget(book, "trip").overBudget)
    }

    @Test fun editedAndDeletedExpensesImmediatelyChangeBudgetWhileHistoryDoesNot() {
        var book = sample()
        val adjusted = taxi.copy(amount = 60000, payers = mapOf("alex" to 60000), shares = mapOf("self" to 30000, "alex" to 20000, "sam" to 10000))
        book = BookActions.saveExpense(book, adjusted)
        assertEquals(150000L, TripReports.budget(book, "trip").spent)
        assertEquals(1, book.revisions.size)
        book = BookActions.deleteExpense(book, "dinner")
        assertEquals(60000L, TripReports.budget(book, "trip").spent)
        book = BookActions.restoreExpense(book, "dinner")
        assertEquals(150000L, TripReports.budget(book, "trip").spent)
        assertEquals(3, book.revisions.size)
    }

    @Test fun overBudgetAndUnsetBudgetAreExplicit() {
        val over = sample().copy(groups = listOf(group.copy(budget = 100000)))
        assertEquals(-25000L, TripReports.budget(over, "trip").remaining)
        assertEquals(25000L, TripReports.budget(over, "trip").overBudget)
        val unset = sample().copy(groups = listOf(group.copy(budget = null)))
        assertNull(TripReports.budget(unset, "trip").budget)
        assertNull(TripReports.budget(unset, "trip").remaining)
        assertEquals(0L, TripReports.budget(unset, "trip").overBudget)
        assertTrue(TripReports.summary(unset, "trip").contains("Trip budget: not set"))
    }

    @Test fun everyPersonsTotalsReconcileWithRepaymentsAndNetPositions() {
        val totals = TripReports.people(sample(), "trip").associateBy { it.personId }
        assertEquals(TripPersonTotal("self", "You", 90000, 45000, 0, 20000, 25000), totals["self"])
        assertEquals(TripPersonTotal("alex", "Alex", 35000, 40000, 0, 0, -5000), totals["alex"])
        assertEquals(TripPersonTotal("sam", "Sam", 0, 40000, 20000, 0, -20000), totals["sam"])
        assertEquals(125000L, totals.values.sumOf { it.paid })
        assertEquals(125000L, totals.values.sumOf { it.share })
        assertEquals(0L, totals.values.sumOf { it.net })
    }

    @Test fun textSummaryContainsAuthoritativeGroupTotalsDatesBudgetAndRepayments() {
        val text = TripReports.summary(sample(), "trip")
        listOf("Goa weekend — Trip summary", "2026-11-17 to 2026-11-19", "Total spending: ₹1250.00",
            "Recorded repayments: ₹200.00 (1)", "Trip budget: ₹2000.00", "Budget remaining: ₹750.00",
            "Food: ₹900.00", "Travel: ₹350.00", "Alex pays You ₹50.00", "Sam pays You ₹200.00",
            "Payments are manually recorded").forEach { assertTrue("Missing summary fact: $it", text.contains(it)) }
        assertFalse(text.contains("Total spending: ₹1450.00"))
    }

    @Test fun unsimplifiedCyclesAreNotReportedAsSettledDespiteZeroNet() {
        val cycleGroup = group.copy(simplifyDebts = false)
        val bills = listOf("self" to "alex", "alex" to "sam", "sam" to "self").mapIndexed { index, (payer, participant) ->
            Expense("cycle-$index", "trip", "Cycle", 10000, mapOf(payer to 10000), mapOf(participant to 10000), date = "2026-11-17")
        }
        val cycle = Book(people = people, groups = listOf(cycleGroup), expenses = bills)
        assertTrue(TripReports.people(cycle, "trip").all { it.net == 0L })
        val text = TripReports.summary(cycle, "trip")
        assertTrue(text.contains("Original pairwise obligations retained."))
        assertFalse(text.contains("All settled up."))
        assertEquals(3, Accounting.transfers(cycle, "trip").size)
    }

    @Test fun emptyAndArchivedGroupSummariesRemainReadableAndExcludeDeletedRecords() {
        val empty = Book(people = people, groups = listOf(group.copy(archived = true)),
            expenses = listOf(dinner.copy(deleted = true)), settlements = listOf(payment.copy(deleted = true)))
        val text = TripReports.summary(empty, "trip")
        assertTrue(text.contains("Archived group"))
        assertTrue(text.contains("Total spending: ₹0.00"))
        assertTrue(text.contains("No expenses recorded."))
        assertTrue(text.contains("All settled up."))
        assertFalse(text.contains("Recorded dates:"))
    }

    @Test fun invalidBudgetsAreRejectedBeforeTheyCanReplaceSavedSettings() {
        for (amount in listOf(0L, -1L, Accounting.MAX_AMOUNT + 1)) {
            rejected { BookActions.saveGroup(sample(), group.copy(budget = amount)) }
            rejected { BookValidation.validate(sample().copy(groups = listOf(group.copy(budget = amount)))) }
        }
        BookValidation.validate(sample().copy(groups = listOf(group.copy(budget = null))))
        BookValidation.validate(sample().copy(groups = listOf(group.copy(budget = Accounting.MAX_AMOUNT))))
    }

    @Test fun legacyBudgetsAndPhotosHaveCompatibleDefaultsAndNewHistoryRoundTrips() {
        val legacy = """{"people":[{"id":"self","name":"You"},{"id":"alex","name":"Alex"}],"groups":[{"id":"g","name":"Trip","members":["self","alex"]}],"expenses":[{"id":"e","groupId":"g","description":"Dinner","amount":100,"payers":{"self":100},"shares":{"self":50,"alex":50},"date":"2026-11-17"}]}"""
        val old = Json.decodeFromString<Book>(legacy)
        assertNull(old.groups.single().budget)
        assertTrue(old.expenses.single().receipts.isEmpty())
        BookValidation.validate(old)
        var book = sample().copy(expenses = listOf(dinner.copy(receipts = listOf(photo())), taxi))
        book = BookActions.saveExpense(book, book.expenses.first().copy(description = "Edited dinner"))
        book = BookActions.deleteExpense(book, "dinner")
        book = BookActions.restoreExpense(book, "dinner")
        BookValidation.validate(book)
        val roundTrip = Json.decodeFromString<Book>(Json.encodeToString(book))
        assertEquals(book, roundTrip)
        assertEquals(photo(), roundTrip.expenses.first { it.id == "dinner" }.receipts.single())
        assertTrue(roundTrip.revisions.all { it.expense.receipts == listOf(photo()) })
        assertEquals(200000L, roundTrip.groups.single().budget)
    }

    @Test fun receiptValidationRejectsUnsafeMissingAndConflictingMetadata() {
        val bad = listOf(photo().copy(id = "../secret"), photo().copy(sizeBytes = 0),
            photo().copy(sizeBytes = BookValidation.MAX_RECEIPT_BYTES + 1), photo().copy(sha256 = "incorrect"),
            photo().copy(mimeType = "text/plain"), photo().copy(name = ""))
        bad.forEach { invalid -> rejected { BookValidation.validate(sample().copy(expenses = listOf(dinner.copy(receipts = listOf(invalid))))) } }
        rejected { BookValidation.receipts(listOf(photo(), photo())) }
        rejected { BookValidation.receipts((0..10).map { photo("photo-$it") }) }
        val conflict = sample().copy(expenses = listOf(dinner.copy(receipts = listOf(photo())), taxi),
            revisions = listOf(ExpenseRevision(expense = dinner.copy(receipts = listOf(photo().copy(sha256 = "cd".repeat(32)))), reason = "Before edit")))
        rejected { BookValidation.validate(conflict) }
    }
}
