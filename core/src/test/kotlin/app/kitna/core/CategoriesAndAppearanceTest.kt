package app.kitna.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CategoriesAndAppearanceTest {
    @Test fun travelWordsAreWholeWordsAndCaseInsensitive() {
        listOf("car", "CAR parking", "Train to Goa", "Taxi", "taxis home", "Buses", "Flight tickets", "rent a car").forEach {
            assertEquals(it, "Travel", ExpenseCategories.suggest(it))
        }
        listOf("scar", "training session", "carpet cleaning", "business meeting", "card payment").forEach {
            assertNull(it, ExpenseCategories.suggest(it))
        }
    }

    @Test fun specificPhrasesWinAndCommonCategoriesHaveSuggestions() {
        assertEquals("Travel", ExpenseCategories.suggest("rent a car"))
        assertEquals("Travel", ExpenseCategories.suggest("train ticket and lunch"))
        assertEquals("Utilities", ExpenseCategories.suggest("water bill"))
        assertEquals("Food", ExpenseCategories.suggest("Lunch at café"))
        assertEquals("Groceries", ExpenseCategories.suggest("Groceries: carrots and milk"))
        assertEquals("Rent", ExpenseCategories.suggest("monthly rent"))
        assertEquals("Health", ExpenseCategories.suggest("health insurance"))
        assertEquals("Entertainment", ExpenseCategories.suggest("movie tickets"))
        assertEquals("Shopping", ExpenseCategories.suggest("new shoes"))
        assertNull(ExpenseCategories.suggest(""))
        assertNull(ExpenseCategories.suggest("Payment to Alex"))
    }

    @Test fun remainingContributionsAreExactAndOverpaymentIsSeparate() {
        assertEquals(PayerSummary(45000, 55000), PayerAllocation.summary(100000, listOf(45000)))
        assertEquals(PayerSummary(35000, 65000), PayerAllocation.summary(100000, listOf(35000)))
        assertEquals(55000L, PayerAllocation.summary(100000, listOf(20000, 25000)).remaining)
        assertTrue(PayerAllocation.summary(100000, listOf(45000, 55000)).fullyAllocated)
        val over = PayerAllocation.summary(100000, listOf(60000, 50000))
        assertEquals(-10000L, over.remaining)
        assertEquals(10000L, over.overpaid)
        assertFalse(over.fullyAllocated)
        assertEquals(10001L, PayerAllocation.summary(10001, emptyList()).remaining)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativePayerContributionIsRejected() { PayerAllocation.summary(100000, listOf(-1)) }

    @Test fun oldBooksGainAppearanceDefaultsWithoutChangingFinancialRecords() {
        val old = """{"people":[{"id":"self","name":"You"},{"id":"alex","name":"Alex"}],"groups":[{"id":"trip","name":"Trip","members":["self","alex"]}],"expenses":[{"id":"bill","groupId":"trip","description":"Taxi","amount":100001,"payers":{"self":100001},"shares":{"self":50000,"alex":50001},"category":"General"}],"settlements":[{"id":"paid","groupId":"trip","from":"alex","to":"self","amount":10000}]}"""
        val book = Json.decodeFromString<Book>(old)
        BookValidation.validate(book)
        assertEquals("group", book.groups.single().icon)
        assertEquals("teal", book.groups.single().color)
        // Suggestions never recategorize a saved legacy/manual expense.
        assertEquals("General", book.expenses.single().category)
        assertEquals(100001L, book.expenses.single().amount)
        assertEquals(10000L, book.settlements.single().amount)
        assertEquals(40001L, Accounting.balances(book, "trip").getValue("self"))
        assertEquals(book, Json.decodeFromString<Book>(Json.encodeToString(book)))
    }

    @Test fun chosenGroupStyleSurvivesSaveRenameAndBackupRoundTrip() {
        val people = listOf(Person("self", "You"), Person("alex", "Alex"))
        val chosen = Group("trip", "Weekend", people.map { it.id }, icon = "plane", color = "purple")
        var book = BookActions.saveGroup(Book(people = people), chosen)
        book = BookActions.saveGroup(book, chosen.copy(name = "New name"))
        book = Json.decodeFromString(Json.encodeToString(book))
        BookValidation.validate(book)
        assertEquals("plane", book.groups.single().icon)
        assertEquals("purple", book.groups.single().color)
        val direct = Group("direct", "Alex", people.map { it.id }, kind = "Direct", icon = "coffee", color = "orange")
        val renamed = BookActions.renamePerson(BookActions.saveGroup(book, direct), "alex", "Alexander")
        assertEquals("coffee", renamed.groups.first { it.id == "direct" }.icon)
        assertEquals("orange", renamed.groups.first { it.id == "direct" }.color)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownGroupStyleIsRejectedWithoutChangingBook() {
        val people = listOf(Person("self", "You"), Person("alex", "Alex"))
        BookActions.saveGroup(Book(people = people), Group("g", "Group", people.map { it.id }, color = "not-a-color"))
    }
}
