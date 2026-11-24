package app.kitna.core

import java.math.BigDecimal

/** All figures are integer paise and include the whole group, not visible filters. */
data class BudgetStatus(val budget: Long?, val spent: Long, val remaining: Long?) {
    val overBudget: Long get() = remaining?.takeIf { it < 0 }?.let { -it } ?: 0L
}

data class TripPersonTotal(
    val personId: String, val name: String, val paid: Long, val share: Long,
    val repaymentsSent: Long, val repaymentsReceived: Long, val net: Long
)

object TripReports {
    private fun group(book: Book, groupId: String) = book.groups.firstOrNull { it.id == groupId }
        ?: throw IllegalArgumentException("Group no longer exists.")
    private fun sum(values: Iterable<Long>): Long = values.fold(0L, Math::addExact)
    private fun expenses(book: Book, groupId: String) = book.expenses.filter { it.groupId == groupId && !it.deleted }
    private fun payments(book: Book, groupId: String) = book.settlements.filter { it.groupId == groupId && !it.deleted }
    private fun money(value: Long) = "₹" + BigDecimal.valueOf(value, 2).toPlainString()

    fun budget(book: Book, groupId: String): BudgetStatus {
        val selected = group(book, groupId)
        val spent = sum(expenses(book, groupId).map { it.amount })
        return BudgetStatus(selected.budget, spent, selected.budget?.let { Math.subtractExact(it, spent) })
    }

    fun people(book: Book, groupId: String): List<TripPersonTotal> {
        val selected = group(book, groupId)
        val bills = expenses(book, groupId)
        val settlements = payments(book, groupId)
        val balances = Accounting.balances(book, groupId)
        return selected.members.map { id ->
            TripPersonTotal(
                personId = id, name = book.people.firstOrNull { it.id == id }?.name ?: "Unknown person",
                paid = sum(bills.map { it.payers[id] ?: 0L }),
                share = sum(bills.map { it.shares[id] ?: 0L }),
                repaymentsSent = sum(settlements.filter { it.from == id }.map { it.amount }),
                repaymentsReceived = sum(settlements.filter { it.to == id }.map { it.amount }),
                net = balances.getValue(id)
            )
        }
    }

    fun categories(book: Book, groupId: String): Map<String, Long> {
        group(book, groupId)
        return expenses(book, groupId).groupBy { it.category }.mapValues { (_, bills) -> sum(bills.map { it.amount }) }
            .toList().sortedWith(compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first }).toMap()
    }

    /** A plain-text statement suitable for Android's share sheet or copying. */
    fun summary(book: Book, groupId: String): String {
        val selected = group(book, groupId)
        val bills = expenses(book, groupId)
        val repayments = payments(book, groupId)
        val spending = budget(book, groupId)
        val memberNames = book.people.associate { it.id to it.name }
        val transfers = Accounting.transfers(book, groupId)
        return buildString {
            appendLine("${selected.name} — Trip summary")
            appendLine("Kitna · INR")
            if (selected.archived) appendLine("Archived group")
            val dates = (bills.map { it.date } + repayments.map { it.date }).sorted()
            if (dates.isNotEmpty()) appendLine("Recorded dates: ${dates.first()} to ${dates.last()}")
            appendLine()
            appendLine("Total spending: ${money(spending.spent)}")
            appendLine("Expenses: ${bills.size}")
            appendLine("Recorded repayments: ${money(sum(repayments.map { it.amount }))} (${repayments.size})")
            if (spending.budget != null) {
                appendLine("Trip budget: ${money(spending.budget)}")
                if (spending.overBudget > 0) appendLine("Over budget: ${money(spending.overBudget)}")
                else appendLine("Budget remaining: ${money(spending.remaining!!)}")
            } else appendLine("Trip budget: not set")
            appendLine()
            appendLine("SPENDING BY CATEGORY")
            if (bills.isEmpty()) appendLine("No expenses recorded.")
            categories(book, groupId).forEach { (category, total) -> appendLine("$category: ${money(total)}") }
            appendLine()
            appendLine("EACH PERSON")
            people(book, groupId).forEach { person ->
                appendLine(person.name)
                appendLine("  Paid for expenses: ${money(person.paid)}")
                appendLine("  Expense share: ${money(person.share)}")
                appendLine("  Repayments sent: ${money(person.repaymentsSent)} · received: ${money(person.repaymentsReceived)}")
                appendLine("  Net: ${when { person.net > 0 -> "receives ${money(person.net)}"; person.net < 0 -> "owes ${money(-person.net)}"; else -> money(0) }}")
            }
            appendLine()
            appendLine("FINAL REPAYMENTS")
            appendLine(if (selected.simplifyDebts) "Debts simplified within this group." else "Original pairwise obligations retained.")
            if (transfers.isEmpty()) appendLine("All settled up.")
            transfers.forEach { transfer -> appendLine("${memberNames[transfer.from] ?: "Unknown person"} pays ${memberNames[transfer.to] ?: "Unknown person"} ${money(transfer.amount)}") }
            appendLine()
            append("Based on this device's saved records. Payments are manually recorded; bank transfers are not verified.")
        }
    }
}
