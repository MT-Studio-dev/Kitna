package app.kitna.ui

import app.kitna.core.Book
import app.kitna.core.Expense
import app.kitna.core.Group
import app.kitna.core.Person
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pure JVM regression checks: these do not require an Android device or Robolectric. */
class ExpenseFilterTest {
    private val expense = Expense(
        id = "dinner", groupId = "trip", description = "Dinner bill", amount = 12345,
        payers = mapOf("self" to 12345), shares = mapOf("self" to 5000, "bob" to 7345),
        category = "Food", date = "2026-11-10", notes = "Includes taxi and tip"
    )
    private val book = Book(
        people = listOf(Person("self", "Me"), Person("bob", "Bob Smith"), Person("cara", "Cara")),
        groups = listOf(Group("trip", "Coast trip", listOf("self", "bob", "cara"))),
        expenses = listOf(expense)
    )
    private fun rejectsValidation(filter: ExpenseFilter) {
        try { filter.validate(); fail("Expected invalid filter to be rejected: $filter") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun searchMatchesDescriptionNotesGroupAndParticipantCaseInsensitively() {
        val filter = ExpenseFilter()
        listOf("dINNer", "TAXI", "Coast", "BOB SMITH", "me", "", "   ").forEach { query ->
            assertTrue("Expected search to match '$query'", filter.matches(book, expense, query))
        }
        assertFalse(filter.matches(book, expense, "unrelated"))
        // A group member who did not pay or share this expense is not a matching participant.
        assertFalse(filter.matches(book, expense, "Cara"))
    }

    @Test fun participantAndNotesSearchCannotBypassAnyOtherFilter() {
        val excluded = listOf(
            ExpenseFilter(category = "Travel"), ExpenseFilter(person = "cara"),
            ExpenseFilter(from = "2026-11-11"), ExpenseFilter(to = "2026-11-09"),
            ExpenseFilter(min = "123.46"), ExpenseFilter(max = "123.44")
        )
        for (filter in excluded) {
            filter.validate()
            for (query in listOf("Bob", "taxi", "Coast", "Dinner")) {
                assertFalse("Query '$query' must not bypass $filter", filter.matches(book, expense, query))
            }
        }
    }

    @Test fun categoryPersonDateAndAmountConditionsAllApplyTogether() {
        val filter = ExpenseFilter(category = "Food", person = "bob", from = "2026-11-01", to = "2026-11-30", min = "100", max = "200")
        filter.validate()
        assertTrue(filter.matches(book, expense, "taxi"))
        assertFalse(filter.copy(category = "Other").matches(book, expense, "Bob"))
        assertFalse(filter.copy(person = "cara").matches(book, expense, "Bob"))
        assertFalse(filter.matches(book, expense, "unrelated"))
    }

    @Test fun dateAndAmountBoundariesAreInclusiveAndUsePaise() {
        val exact = ExpenseFilter(from = expense.date, to = expense.date, min = "123.45", max = "123.45")
        exact.validate()
        assertTrue(exact.matches(book, expense, ""))
        assertFalse(exact.copy(min = "123.46", max = "").matches(book, expense, ""))
        assertFalse(exact.copy(min = "", max = "123.44").matches(book, expense, ""))
        assertTrue(ExpenseFilter(person = "self").matches(book, expense, ""))
        assertTrue(ExpenseFilter(person = "bob").matches(book, expense, ""))
        assertFalse(ExpenseFilter(person = "cara").matches(book, expense, ""))
    }

    @Test fun validationRejectsInvalidDatesDateOrderAndAmountBounds() {
        listOf(
            ExpenseFilter(from = "2026-02-30"), ExpenseFilter(to = "2026-1-10"),
            ExpenseFilter(from = "+10000-01-01"), ExpenseFilter(from = "2026-11-20", to = "2026-11-10"),
            ExpenseFilter(min = "200", max = "100"), ExpenseFilter(min = "-1"),
            ExpenseFilter(max = "1.001"), ExpenseFilter(min = "not money")
        ).forEach(::rejectsValidation)
        ExpenseFilter().validate()
        ExpenseFilter(from = "2024-02-29", to = "2024-02-29", min = "0", max = "0").validate()
    }

    @Test fun customCategoriesMatchWithoutBeingInTheBuiltInCategoryList() {
        val custom = expense.copy(category = "Pet care")
        assertTrue(ExpenseFilter(category = "Pet care").matches(book, custom, "Dinner"))
        assertFalse(ExpenseFilter(category = "Food").matches(book, custom, "Dinner"))
        assertTrue(ExpenseFilter(category = "Transport").matches(book, expense.copy(category = "Transport"), "Bob"))
    }

    @Test fun activeFlagReflectsEveryFilterAndClearsWithDefaults() {
        assertFalse(ExpenseFilter().active)
        listOf(
            ExpenseFilter(category = "Food"), ExpenseFilter(person = "bob"),
            ExpenseFilter(from = "2026-11-01"), ExpenseFilter(to = "2026-11-30"),
            ExpenseFilter(min = "0"), ExpenseFilter(max = "100")
        ).forEach { assertTrue(it.active) }
    }
}
