package app.kitna.ui

import org.junit.Assert.*
import org.junit.Test

class ExpenseAllocationTest {
    @Test fun equalAndPercentageRemaindersAreStableAndReconcile() {
        val people = linkedSetOf("sam", "self", "alex")
        assertEquals(mapOf("alex" to 34L, "sam" to 34L, "self" to 33L), expenseAllocations(101, people, "Equal", emptyMap()).first)
        assertEquals(expenseAllocations(101, people, "Equal", emptyMap()), expenseAllocations(101, people.toList().asReversed().toSet(), "Equal", emptyMap()))
        val percentage = expenseAllocations(101, people, "Percentages", mapOf("alex" to "50", "sam" to "30", "self" to "20")).first
        assertEquals(mapOf("alex" to 51L, "sam" to 30L, "self" to 20L), percentage)
    }

    @Test fun exactSharesAndAdjustmentsPreservePaiseAndExcludedPeople() {
        val people = setOf("alex", "self")
        assertEquals(mapOf("alex" to 1L, "self" to 100L), expenseAllocations(101, people, "Exact amounts", mapOf("alex" to "0.01", "self" to "1.00", "excluded" to "99")).first)
        assertEquals(mapOf("alex" to 34L, "self" to 67L), expenseAllocations(101, people, "Shares", mapOf("alex" to "1", "self" to "2")).first)
        assertEquals(mapOf("alex" to 61L, "self" to 40L), expenseAllocations(101, people, "Adjustments", mapOf("alex" to "0.10", "self" to "-0.10")).first)
    }

    @Test fun invalidPartialAndOverflowingInputsCannotSave() {
        val people = setOf("alex", "self")
        listOf(
            { expenseAllocations(101, emptySet(), "Equal", emptyMap()) },
            { expenseAllocations(101, people, "Exact amounts", mapOf("alex" to "0.50")) },
            { expenseAllocations(101, people, "Percentages", mapOf("alex" to "99")) },
            { expenseAllocations(101, people, "Shares", mapOf("alex" to "1.5")) },
            { expenseAllocations(101, people, "Exact amounts", mapOf("alex" to "9999999999999999999999999999")) }
        ).forEach { calculation -> assertTrue(runCatching(calculation).isFailure) }
    }
}
