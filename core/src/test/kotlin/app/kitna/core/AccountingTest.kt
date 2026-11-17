package app.kitna.core

import org.junit.Assert.*
import org.junit.Test

class AccountingTest {
    @Test fun roundingPreservesPaise() {
        assertEquals(mapOf("a" to 3334L, "b" to 3333L, "c" to 3333L), Accounting.split(10000, linkedMapOf("c" to 1L, "b" to 1L, "a" to 1L)))
        assertEquals(mapOf("a" to 6000L, "b" to 4000L), Accounting.split(10000, mapOf("a" to 60L, "b" to 40L)))
    }
    @Test fun zeroWeightNeverReceivesRemainder() {
        assertEquals(0L, Accounting.split(1, mapOf("a" to 0L, "b" to 1L)).getValue("a"))
    }
    @Test fun partialSettlementPreservesBalance() {
        val group = Group("g", "Trip", listOf("a", "b", "c"))
        val expense = Expense(groupId = "g", description = "Dinner", amount = 90000, payers = mapOf("a" to 90000), shares = Accounting.split(90000, group.members.associateWith { 1L }))
        Accounting.validate(expense, group)
        val book = Book(groups = listOf(group), expenses = listOf(expense), settlements = listOf(Settlement(groupId = "g", from = "b", to = "a", amount = 10000)))
        assertEquals(mapOf("a" to 50000L, "b" to -20000L, "c" to -30000L), Accounting.balances(book, "g"))
        val cleared = Accounting.balances(book, "g").toMutableMap()
        Accounting.simplify(cleared).forEach { cleared[it.from] = cleared.getValue(it.from) + it.amount; cleared[it.to] = cleared.getValue(it.to) - it.amount }
        assertTrue(cleared.values.all { it == 0L })
    }
    @Test(expected = IllegalArgumentException::class) fun rejectMismatchedContributions() {
        Accounting.validate(Expense(groupId = "g", description = "Bill", amount = 100, payers = mapOf("a" to 99), shares = mapOf("a" to 100)), Group("g", "G", listOf("a")))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectFractionalPaise() { Accounting.money("1.001") }
    @Test fun largeWeightsDoNotOverflow() {
        assertEquals(10001L, Accounting.split(10001, mapOf("a" to Long.MAX_VALUE, "b" to Long.MAX_VALUE)).values.sum())
    }
    @Test fun randomSplitsAndSimplificationsPreserveTotals() {
        val random = java.util.Random(42)
        repeat(500) {
            val total = random.nextInt(1000000).toLong() + 1
            val weights = (1..8).associate { it.toString() to random.nextInt(100).toLong() + 1 }
            val shares = Accounting.split(total, weights)
            assertEquals(total, shares.values.sum())
            val balances = shares.mapValues { -it.value }.toMutableMap()
            balances["payer"] = total
            Accounting.simplify(balances).forEach { balances[it.from] = balances.getValue(it.from) + it.amount; balances[it.to] = balances.getValue(it.to) - it.amount }
            assertTrue(balances.values.all { it == 0L })
        }
    }
}
