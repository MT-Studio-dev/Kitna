package app.kitna.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaPresentationTest {
    @Test fun budgetWarningsChangeAtEachThresholdAndDistinguishUsedFromOver() {
        val cases = mapOf(
            0L to BudgetMood.Comfortable, 5999L to BudgetMood.Comfortable,
            6000L to BudgetMood.Watch, 7999L to BudgetMood.Watch,
            8000L to BudgetMood.Near, 9499L to BudgetMood.Near,
            9500L to BudgetMood.Critical, 9999L to BudgetMood.Critical,
            10000L to BudgetMood.Used, 10001L to BudgetMood.Over
        )
        cases.forEach { (spent, expected) -> assertEquals("spent=$spent", expected, budgetMood(spent, 10000)) }
        assertEquals("Budget used", BudgetMood.Used.label)
        assertEquals("Over budget", BudgetMood.Over.label)
    }

    @Test fun magnitudeUsesTheWholeGroupBudgetAndHasReadableSmallMediumLargeStates() {
        assertEquals(ExpenseEmphasis.Small, expenseEmphasis(499, 10000, 4200))
        assertEquals(ExpenseEmphasis.Medium, expenseEmphasis(500, 10000, 4200))
        assertEquals(ExpenseEmphasis.Medium, expenseEmphasis(1999, 10000, 4200))
        assertEquals(ExpenseEmphasis.Large, expenseEmphasis(2000, 10000, 4200))
        // Searching or another bill becoming the largest cannot alter a budget-based classification.
        assertEquals(expenseEmphasis(4200, 10000, 4200), expenseEmphasis(4200, 10000, 9000))
    }

    @Test fun groupsWithoutBudgetsCompareWithTheirLargestActiveBill() {
        assertEquals(ExpenseEmphasis.Large, expenseEmphasis(4200, null, 4200))
        assertEquals(ExpenseEmphasis.Large, expenseEmphasis(2520, null, 4200))
        assertEquals(ExpenseEmphasis.Medium, expenseEmphasis(850, null, 4200))
        assertEquals(ExpenseEmphasis.Small, expenseEmphasis(320, null, 4200))
        assertEquals(ExpenseEmphasis.Small, expenseEmphasis(0, null, 0))
    }

    @Test fun invalidBudgetInputCannotSilentlyCreateAnOnTrackWarning() {
        assertTrue(runCatching { budgetMood(100, 0) }.isFailure)
        assertTrue(runCatching { budgetMood(-1, 100) }.isFailure)
        assertEquals(BudgetMood.Critical, budgetMood(Long.MAX_VALUE - 1, Long.MAX_VALUE))
        assertEquals(BudgetMood.Used, budgetMood(Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
