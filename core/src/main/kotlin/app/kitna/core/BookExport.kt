package app.kitna.core

import java.math.BigDecimal

object BookExport {
    private fun cell(value: String): String {
        val safe = if (value.trimStart().firstOrNull() in listOf('=', '+', '-', '@') || value.firstOrNull() in listOf('\t', '\r', '\n')) "'$value" else value
        return "\"${safe.replace("\"", "\"\"")}\""
    }
    fun csv(book: Book): String {
        val names = book.people.associate { it.id to it.name }
        val groups = book.groups.associate { it.id to it.name }
        fun amount(value: Long) = BigDecimal.valueOf(value, 2).toPlainString()
        fun allocation(map: Map<String, Long>) = map.entries.joinToString("; ") { "${names[it.key].orEmpty()}: ${amount(it.value)}" }
        val rows = mutableListOf(listOf("type", "id", "date", "group", "description", "amount_inr", "paid_by_or_from", "shares_or_to", "category_or_method", "notes_or_reference", "comments", "status"))
        book.expenses.forEach { e -> rows += listOf("expense", e.id, e.date, groups[e.groupId].orEmpty(), e.description, amount(e.amount), allocation(e.payers), allocation(e.shares), e.category, e.notes,
            book.comments.filter { it.expenseId == e.id }.joinToString("; ") { "${names[it.authorId].orEmpty()}: ${it.text}" }, if (e.deleted) "deleted" else "active") }
        book.settlements.forEach { s -> rows += listOf("repayment", s.id, s.date, groups[s.groupId].orEmpty(), "Repayment", amount(s.amount), names[s.from].orEmpty(), names[s.to].orEmpty(), s.method, s.reference, "", if (s.deleted) "deleted" else "active") }
        return "\uFEFF" + rows.joinToString("\r\n", postfix = "\r\n") { row -> row.joinToString(",", transform = ::cell) }
    }
}
