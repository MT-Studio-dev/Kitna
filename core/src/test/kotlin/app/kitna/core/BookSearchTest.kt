package app.kitna.core

import org.junit.Assert.*
import org.junit.Test

class BookSearchTest {
    private val group = Group("trip", "Goa weekend", listOf("self", "alex"))
    private val expense = Expense("taxi", "trip", "Airport taxi", 100050,
        mapOf("self" to 100050), mapOf("self" to 50025, "alex" to 50025),
        "Travel", "2026-11-17", notes = "Night pickup")
    private val payment = Settlement("paid", "trip", "alex", "self", 45000,
        "UPI", reference = "Trip payment")
    private val book = Book(people = listOf(Person("self", "You"), Person("alex", "Alex Rao")),
        groups = listOf(group), expenses = listOf(expense), settlements = listOf(payment))

    @Test fun expenseSearchTrimsAndCombinesWordsAcrossFields() {
        listOf("  TAXI  ", "airport   alex", "Goa Travel", "pickup 2026-11", "₹1,000.50", "1000.50").forEach {
            assertTrue(it, BookSearch.expense(book, expense, it))
        }
        assertFalse(BookSearch.expense(book, expense, "taxi Sam"))
    }
    @Test fun homeFindsRecordsInsideGroupsAndFriendships() {
        assertTrue(BookSearch.group(book, group, "taxi"))
        assertTrue(BookSearch.person(book, book.people.last(), "travel"))
        assertTrue(BookSearch.settlement(book, payment, "Alex UPI 450"))
        assertTrue(BookSearch.group(book, group, "UPI"))
        assertFalse(BookSearch.expense(book, expense, "UPI"))
    }
    @Test fun deletedRecordsDoNotProduceHiddenGroupOrFriendHits() {
        val deleted = book.copy(expenses = listOf(expense.copy(deleted = true)),
            settlements = listOf(payment.copy(deleted = true)))
        assertFalse(BookSearch.group(deleted, group, "taxi"))
        assertFalse(BookSearch.person(deleted, book.people.last(), "UPI"))
    }
    @Test fun whitespaceAndUnicodeAreNormalized() {
        assertTrue(BookSearch.expense(book, expense, "   "))
        assertTrue(BookSearch.matches("ＣＡＲ", listOf("car fare")))
        assertFalse(BookSearch.matches("car train", listOf("car")))
    }
}
