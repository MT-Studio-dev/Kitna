package app.kitna.core

import java.math.BigInteger
import java.math.RoundingMode
import java.time.LocalDate

object Accounting {
    const val MAX_AMOUNT = 100_000_000_000L
    fun money(input: String): Long = parseMoney(input, false)
    fun signedMoney(input: String): Long = parseMoney(input, true)
    private fun parseMoney(input: String, signed: Boolean): Long {
        val cleaned = input.trim()
        require(cleaned.matches(Regex(if (signed) "[+-]?(?:[0-9]+(?:\\.[0-9]{0,2})?|\\.[0-9]{1,2})" else "(?:[0-9]+(?:\\.[0-9]{0,2})?|\\.[0-9]{1,2})"))) { "Enter a valid amount with no more than two decimal places." }
        val value = try { cleaned.toBigDecimal().setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact() }
            catch (_: ArithmeticException) { throw IllegalArgumentException("That amount is too large.") }
        require(value in (if (signed) -MAX_AMOUNT else 0)..MAX_AMOUNT) { "Amount is outside the supported range (up to ₹1,000,000,000)." }
        return value
    }
    private fun exactSum(values: Iterable<Long>): Long = try { values.fold(0L, Math::addExact) }
        catch (_: ArithmeticException) { throw IllegalArgumentException("The combined amounts are too large.") }
    fun split(total: Long, weights: Map<String, Long>): Map<String, Long> {
        require(total in 1..MAX_AMOUNT) { "Enter a positive amount." }
        require(weights.isNotEmpty() && weights.values.all { it >= 0 } && weights.values.any { it > 0 }) { "Select participants and positive shares." }
        val sum = weights.values.fold(BigInteger.ZERO) { a, b -> a + b.toBigInteger() }
        val quotients = weights.mapValues { total.toBigInteger().multiply(it.value.toBigInteger()).divideAndRemainder(sum) }
        val result = quotients.mapValues { it.value[0].longValueExact() }.toMutableMap()
        var remainder = total - exactSum(result.values)
        quotients.entries.sortedWith(compareByDescending<Map.Entry<String, Array<BigInteger>>> { it.value[1] }.thenBy { it.key }).forEach {
            if (remainder > 0) { result[it.key] = result.getValue(it.key) + 1; remainder-- }
        }
        check(remainder == 0L && exactSum(result.values) == total)
        return result
    }
    /** Apply fixed adjustments, then divide the remainder equally. */
    fun adjustedSplit(total: Long, adjustments: Map<String, Long>): Map<String, Long> {
        require(total in 1..MAX_AMOUNT && adjustments.isNotEmpty()) { "Select participants and enter a positive amount." }
        require(adjustments.values.all { it in -MAX_AMOUNT..MAX_AMOUNT }) { "An adjustment is too large." }
        val remainder = Math.subtractExact(total, exactSum(adjustments.values))
        require(remainder >= 0L) { "Adjustments cannot exceed the expense total." }
        val ids = adjustments.keys.sorted()
        val base = ids.mapIndexed { index, id -> id to (remainder / ids.size + if (index.toLong() < remainder % ids.size) 1L else 0L) }.toMap()
        return base.mapValues { (id, value) -> Math.addExact(value, adjustments.getValue(id)).also { require(it >= 0) { "An adjustment makes a participant's share negative." } } }
    }
    fun validDate(date: String) {
        require(date.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) && runCatching { LocalDate.parse(date) }.isSuccess) { "Choose a valid date." }
    }
    fun validate(expense: Expense, group: Group) {
        require(expense.groupId == group.id) { "Expense group is invalid." }
        require(!group.archived) { "Restore this archived group before changing expenses." }
        require(expense.description.isNotBlank() && expense.description.length <= 500) { "Enter a description up to 500 characters." }
        require(expense.amount in 1..MAX_AMOUNT) { "Enter a positive amount up to ₹1,000,000,000." }
        require(expense.payers.isNotEmpty() && expense.shares.isNotEmpty()) { "Choose who paid and who shares this expense." }
        require((expense.payers.keys + expense.shares.keys).all { it in group.members }) { "Every payer and participant must belong to the group." }
        require((expense.payers.values + expense.shares.values).all { it in 0..expense.amount }) { "Contributions and shares must be within the total." }
        require(exactSum(expense.payers.values) == expense.amount) { "Payer contributions must equal the expense total." }
        require(exactSum(expense.shares.values) == expense.amount) { "Split amounts must equal the expense total." }
        require(expense.notes.length <= 4000) { "Notes must be at most 4,000 characters." }
        require(expense.category.isNotBlank() && expense.category.length <= 100) { "Choose an expense category." }
        require(expense.splitMode in setOf("Equal", "Exact amounts", "Percentages", "Shares", "Adjustments")) { "Unknown split method." }
        require(expense.splitWeights.keys.all { it in expense.shares.keys }) { "Split settings include an unknown participant." }
        require(expense.splitWeights.values.all { it in -MAX_AMOUNT..MAX_AMOUNT }) { "Invalid split settings." }
        BookValidation.receipts(expense.receipts)
        validDate(expense.date)
    }
    fun balances(book: Book, groupId: String): Map<String, Long> {
        val group = book.groups.firstOrNull { it.id == groupId } ?: throw IllegalArgumentException("Group no longer exists.")
        val result = group.members.associateWith { 0L }.toMutableMap()
        fun add(id: String, amount: Long) { result[id] = Math.addExact(result.getValue(id), amount) }
        book.expenses.filter { it.groupId == groupId && !it.deleted }.forEach { e ->
            e.payers.forEach { (id, amount) -> add(id, amount) }; e.shares.forEach { (id, amount) -> add(id, -amount) }
        }
        book.settlements.filter { it.groupId == groupId && !it.deleted }.forEach { add(it.from, it.amount); add(it.to, -it.amount) }
        check(exactSum(result.values) == 0L)
        return result
    }
    fun simplify(balances: Map<String, Long>): List<Transfer> {
        require(exactSum(balances.values) == 0L) { "Balances do not sum to zero." }
        require(balances.values.none { it == Long.MIN_VALUE })
        val creditors = balances.filterValues { it > 0 }.toList().sortedBy { it.first }.toMutableList()
        val debtors = balances.filterValues { it < 0 }.toList().sortedBy { it.first }.map { it.first to -it.second }.toMutableList()
        val transfers = mutableListOf<Transfer>()
        var c = 0; var d = 0
        while (c < creditors.size && d < debtors.size) {
            val amount = minOf(creditors[c].second, debtors[d].second)
            transfers += Transfer(debtors[d].first, creditors[c].first, amount)
            creditors[c] = creditors[c].first to creditors[c].second - amount
            debtors[d] = debtors[d].first to debtors[d].second - amount
            if (creditors[c].second == 0L) c++
            if (debtors[d].second == 0L) d++
        }
        return transfers
    }
    /** With simplification off, preserve pairwise obligations, including cycles. */
    fun transfers(book: Book, groupId: String): List<Transfer> {
        val group = book.groups.first { it.id == groupId }
        if (group.simplifyDebts) return simplify(balances(book, groupId))
        val pairs = mutableMapOf<Pair<String, String>, Long>()
        fun add(from: String, to: String, amount: Long) {
            if (from == to || amount == 0L) return
            val key = if (from < to) from to to else to to from
            pairs[key] = Math.addExact(pairs[key] ?: 0L, if (from < to) amount else -amount)
        }
        book.expenses.filter { it.groupId == groupId && !it.deleted }.forEach { expense ->
            simplify(group.members.associateWith { (expense.payers[it] ?: 0L) - (expense.shares[it] ?: 0L) }).forEach { add(it.from, it.to, it.amount) }
        }
        book.settlements.filter { it.groupId == groupId && !it.deleted }.forEach { add(it.to, it.from, it.amount) }
        return pairs.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).mapNotNull { (pair, amount) ->
            when { amount > 0 -> Transfer(pair.first, pair.second, amount); amount < 0 -> Transfer(pair.second, pair.first, -amount); else -> null }
        }
    }
    /** Positive means that friend owes personId, negative means personId owes friend. */
    fun friendBalances(book: Book, personId: String = "self"): Map<String, Long> {
        val balances = book.people.filter { it.id != personId }.associate { it.id to 0L }.toMutableMap()
        book.groups.forEach { group -> transfers(book, group.id).forEach { t ->
            if (t.to == personId) balances[t.from] = Math.addExact(balances[t.from] ?: 0L, t.amount)
            if (t.from == personId) balances[t.to] = Math.subtractExact(balances[t.to] ?: 0L, t.amount)
        } }
        return balances
    }
}
