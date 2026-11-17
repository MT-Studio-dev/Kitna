package app.kitna.core

import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

/** Local search: all typed words must match, including across different fields. */
object BookSearch {
    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("(?<=\\d),(?=\\d)"), "")
        .replace("₹", " ")
        .trim()

    fun matches(query: String, fields: Iterable<String>): Boolean {
        val words = normalize(query).split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return true
        val normalized = fields.map(::normalize)
        return words.all { word -> normalized.any { word in it } }
    }

    private fun amount(value: Long): String = BigDecimal.valueOf(value, 2).toPlainString()
    private fun names(book: Book, ids: Set<String>): List<String> = book.people
        .filter { it.id in ids }.map { it.name }

    fun expense(book: Book, expense: Expense, query: String): Boolean = matches(query,
        listOf(expense.description, expense.notes, expense.category, expense.date, amount(expense.amount),
            book.groups.firstOrNull { it.id == expense.groupId }?.name.orEmpty()) +
            names(book, expense.payers.keys + expense.shares.keys))

    fun settlement(book: Book, payment: Settlement, query: String): Boolean = matches(query,
        listOf("payment repayment settlement", payment.method, payment.reference, payment.date,
            amount(payment.amount), book.groups.firstOrNull { it.id == payment.groupId }?.name.orEmpty()) +
            names(book, setOf(payment.from, payment.to)))

    fun group(book: Book, group: Group, query: String): Boolean =
        matches(query, listOf(group.name, group.kind) + names(book, group.members.toSet())) ||
            book.expenses.any { !it.deleted && it.groupId == group.id && expense(book, it, query) } ||
            book.settlements.any { !it.deleted && it.groupId == group.id && settlement(book, it, query) }

    fun person(book: Book, person: Person, query: String): Boolean =
        matches(query, listOf(person.name)) ||
            book.expenses.any { !it.deleted && person.id in (it.payers.keys + it.shares.keys) && expense(book, it, query) } ||
            book.settlements.any { !it.deleted && (person.id == it.from || person.id == it.to) && settlement(book, it, query) }

    fun activity(book: Book, event: Activity, query: String): Boolean = matches(query,
        listOf(event.message, book.groups.firstOrNull { it.id == event.groupId }?.name.orEmpty()))
}
