package app.kitna.core

data class PayerSummary(val entered: Long, val remaining: Long) {
    val overpaid: Long get() = maxOf(0L, -remaining)
    val fullyAllocated: Boolean get() = remaining == 0L
}

object PayerAllocation {
    /** Amounts are integer paise. Negative remaining means over-allocation. */
    fun summary(total: Long, contributions: Iterable<Long>): PayerSummary {
        require(total in 0..Accounting.MAX_AMOUNT) { "Enter a valid bill total." }
        val entered = try {
            contributions.fold(0L) { sum, value ->
                require(value in 0..Accounting.MAX_AMOUNT) { "Enter valid payer contributions." }
                Math.addExact(sum, value)
            }
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("The combined contributions are too large.")
        }
        return PayerSummary(entered, Math.subtractExact(total, entered))
    }
}
