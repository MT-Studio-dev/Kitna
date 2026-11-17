package app.kitna

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import app.kitna.core.*
import app.kitna.data.LocalStore
import app.kitna.data.BookRow
import app.kitna.data.KitnaDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalStoreRuntimeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun clearPreviousBook() { context.deleteDatabase("kitna.db") }

    private fun sample(): Book {
        val group = Group(id = "trip", name = "Weekend", members = listOf("self", "alex", "sam"))
        return Book(
            people = listOf(Person("self", "Me"), Person("alex", "Alex"), Person("sam", "Sam")),
            groups = listOf(group),
            expenses = listOf(Expense(
                id = "dinner", groupId = group.id, description = "Dinner", amount = 12001,
                payers = mapOf("self" to 12001),
                shares = Accounting.split(12001, group.members.associateWith { 1L }),
                date = "2026-11-17"
            ))
        )
    }

    @Test fun savedBookSurvivesClosingAndReopeningDatabase() = runBlocking {
        val first = LocalStore(context)
        val expected = sample()
        first.load()
        first.update { expected }
        assertEquals(expected, first.book.value)
        first.close()

        val reopened = LocalStore(context)
        try {
            reopened.load()
            assertEquals(expected, reopened.book.value)
            assertEquals(8001L, Accounting.balances(reopened.book.value!!, "trip").getValue("self"))
        } finally { reopened.close() }
    }

    @Test fun malformedRestoreAndInvalidMutationKeepDurableRecords() = runBlocking {
        val store = LocalStore(context)
        val expected = sample()
        store.load()
        store.update { expected }
        try {
            assertTrue(runCatching { store.restore("{ this is not valid JSON }") }.isFailure)
            assertEquals(expected, store.book.value)
            val invalid = expected.copy(expenses = expected.expenses.map { it.copy(amount = it.amount + 1) })
            assertTrue(runCatching { store.update { invalid } }.isFailure)
            assertEquals(expected, store.book.value)
        } finally { store.close() }
        val reopened = LocalStore(context)
        try { reopened.load(); assertEquals(expected, reopened.book.value) }
        finally { reopened.close() }
    }

    @Test fun concurrentEditsDoNotLoseSavedPeople() = runBlocking {
        val store = LocalStore(context)
        store.load()
        try {
            coroutineScope {
                (1..25).map { index -> async {
                    store.update { current -> current.copy(people = current.people + Person("person-$index", "Friend $index")) }
                } }.awaitAll()
            }
            assertEquals(26, store.book.value!!.people.size)
            assertEquals(26, store.book.value!!.people.map { it.id }.toSet().size)
        } finally { store.close() }
        val reopened = LocalStore(context)
        try { reopened.load(); assertEquals(26, reopened.book.value!!.people.size) }
        finally { reopened.close() }
    }

    @Test fun existingVersionOneDatabaseLoadsWithoutLosingExpenses() = runBlocking {
        // This is the serialized shape written by the original APK, before the
        // added notes, split metadata, repayment flags and revision fields.
        val legacy = """{
          "schemaVersion":1,
          "people":[{"id":"self","name":"You"},{"id":"p1","name":"Alex"}],
          "groups":[{"id":"g","name":"Old group","members":["self","p1"],"archived":false}],
          "expenses":[{"id":"e","groupId":"g","description":"Original bill","amount":1200,"payers":{"self":1200},"shares":{"self":600,"p1":600},"category":"General","date":"2026-11-16","deleted":false}],
          "settlements":[],"activity":[],"comments":[]
        }"""
        val original = Room.databaseBuilder(context, KitnaDatabase::class.java, "kitna.db").build()
        original.book().write(BookRow(payload = legacy))
        original.close()
        val upgraded = LocalStore(context)
        try {
            upgraded.load()
            val book = upgraded.book.value!!
            assertEquals("Original bill", book.expenses.single().description)
            assertEquals(1200L, book.expenses.single().amount)
            assertEquals(600L, Accounting.balances(book, "g").getValue("self"))
            assertEquals("Group", book.groups.single().kind)
            assertTrue(book.expenses.single().notes.isEmpty())
        } finally { upgraded.close() }
    }

    @Test fun booksLargerThanAndroidCursorWindowReloadWithoutUnicodeLoss() = runBlocking {
        val base = sample()
        val notes = "n".repeat(3000) + "🌴".repeat(400)
        val expected = base.copy(expenses = List(700) { index ->
            base.expenses.single().copy(id = "expense-$index", notes = notes)
        })
        val store = LocalStore(context)
        store.load()
        assertTrue(store.json.encodeToString(Book.serializer(), expected).toByteArray().size > 2 * 1024 * 1024)
        store.update { expected }
        store.close()
        val reopened = LocalStore(context)
        try {
            reopened.load()
            assertEquals(expected, reopened.book.value)
            assertEquals(notes, reopened.book.value!!.expenses.last().notes)
        } finally { reopened.close() }
    }

    @Test fun existingOversizedSingleRowBookCanLoadAndBeUpdated() = runBlocking {
        val base = sample()
        val notes = "Old notes " + "🌴".repeat(1700)
        val expected = base.copy(expenses = List(700) { index ->
            base.expenses.single().copy(id = "legacy-expense-$index", notes = notes)
        })
        val original = Room.databaseBuilder(context, KitnaDatabase::class.java, "kitna.db").build()
        val payload = kotlinx.serialization.json.Json.encodeToString(Book.serializer(), expected)
        assertTrue(payload.toByteArray().size > 2 * 1024 * 1024)
        original.book().write(BookRow(payload = payload))
        original.close()
        val upgraded = LocalStore(context)
        val edited = expected.copy(people = expected.people.map { if (it.id == "self") it.copy(name = "Updated profile") else it })
        try {
            upgraded.load()
            assertEquals(expected, upgraded.book.value)
            upgraded.update { edited }
        } finally { upgraded.close() }
        val reopened = LocalStore(context)
        try { reopened.load(); assertEquals(edited, reopened.book.value) }
        finally { reopened.close() }
    }
}
