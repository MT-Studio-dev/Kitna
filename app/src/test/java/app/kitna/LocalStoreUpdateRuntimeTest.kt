package app.kitna

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.kitna.core.*
import app.kitna.data.BookRow
import app.kitna.data.KitnaDatabase
import app.kitna.data.LocalStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

/** Simulates a binary update over the same app-private Room database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalStoreUpdateRuntimeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    private val snapshots: File get() = File(context.noBackupFilesDir, "upgrade-backups")

    @Before fun cleanStorage() {
        context.deleteDatabase("kitna.db")
        context.getSharedPreferences("storage_versions", Context.MODE_PRIVATE).edit().clear().commit()
        snapshots.deleteRecursively()
    }

    private fun installedVersion(version: Long) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        info.setLongVersionCode(version)
        shadowOf(context.packageManager).installPackage(info)
    }

    private fun sample(): Book {
        val group = Group("trip", "A preserved trip", listOf("self", "alex"), kind = "Trip", simplifyDebts = false)
        val expense = Expense(
            "lunch", group.id, "Lunch from the previous version", 15001,
            mapOf("self" to 10000, "alex" to 5001), mapOf("self" to 7500, "alex" to 7501),
            category = "Food", date = "2026-11-16", notes = "Original notes 🌴",
            splitMode = "Percentages", splitWeights = mapOf("self" to 5000, "alex" to 5000)
        )
        val deleted = expense.copy(id = "deleted", description = "Deleted historical record", deleted = true)
        return Book(
            people = listOf(Person("self", "Me"), Person("alex", "Alex")), groups = listOf(group),
            expenses = listOf(expense, deleted),
            settlements = listOf(Settlement("payment", group.id, "alex", "self", 1000, "UPI", "2026-11-17", reference = "Old payment reference")),
            comments = listOf(Comment("comment", expense.id, "Original comment", time = 10, authorId = "alex")),
            activity = listOf(Activity("event", group.id, "Old activity", time = 9)),
            revisions = listOf(ExpenseRevision("revision", expense.copy(amount = 9000, payers = mapOf("self" to 9000), shares = mapOf("self" to 4500, "alex" to 4500), date = "2026-10-01", splitMode = "Equal", splitWeights = emptyMap()), "Before edit", time = 8))
        ).also(BookValidation::validate)
    }

    private fun versionTwoPayload(book: Book): String {
        val root = json.encodeToJsonElement(book).jsonObject
        return JsonObject(root.toMutableMap().also { data ->
            data["groups"] = JsonArray(root.getValue("groups").jsonArray.map { group ->
                JsonObject(group.jsonObject.filterKeys { it != "icon" && it != "color" })
            })
        }).toString()
    }

    private suspend fun seedPreviousVersion(payload: String) {
        installedVersion(2)
        val previous = Room.databaseBuilder(context, KitnaDatabase::class.java, "kitna.db").build()
        try { previous.book().write(BookRow(payload = payload)) } finally { previous.close() }
        context.getSharedPreferences("storage_versions", Context.MODE_PRIVATE).edit().putLong("last_opened_version", 2L).commit()
    }

    @Test fun updateKeepsEveryRecordAndAReadOnlyCopyBeforeFirstRewrite() = runBlocking {
        val expected = sample()
        val originalPayload = versionTwoPayload(expected)
        seedPreviousVersion(originalPayload)
        installedVersion(3)
        val updated = LocalStore(context)
        val styled = expected.copy(groups = expected.groups.map { it.copy(icon = "plane", color = "purple") })
        try {
            updated.load()
            assertEquals(expected, updated.book.value)
            assertEquals(originalPayload, File(snapshots, "before-version-3.json").readText())
            updated.update { styled }
        } finally { updated.close() }

        val reopened = LocalStore(context)
        try {
            reopened.load()
            assertEquals(styled, reopened.book.value)
            assertEquals(originalPayload, File(snapshots, "before-version-3.json").readText())
        } finally { reopened.close() }

        installedVersion(4)
        val nextUpdate = LocalStore(context)
        try {
            nextUpdate.load()
            assertEquals(styled, nextUpdate.book.value)
            assertEquals(styled, json.decodeFromString<Book>(File(snapshots, "before-version-4.json").readText()))
            assertEquals(originalPayload, File(snapshots, "before-version-3.json").readText())
        } finally { nextUpdate.close() }
    }

    @Test fun firstTrackedUpdateOfAnOlderInstallAlsoPreservesData() = runBlocking {
        val expected = sample()
        val originalPayload = versionTwoPayload(expected)
        seedPreviousVersion(originalPayload)
        context.getSharedPreferences("storage_versions", Context.MODE_PRIVATE).edit().clear().commit()
        installedVersion(3)
        val updated = LocalStore(context)
        try {
            updated.load()
            assertEquals(expected, updated.book.value)
            assertEquals(originalPayload, File(snapshots, "before-version-3.json").readText())
        } finally { updated.close() }
    }

    @Test fun malformedOldDataFailsClosedInsteadOfStartingANewEmptyBook() = runBlocking {
        val originalPayload = "{ broken old payload"
        seedPreviousVersion(originalPayload)
        installedVersion(3)
        val updated = LocalStore(context)
        try {
            assertTrue(runCatching { updated.load() }.isFailure)
            assertNull(updated.book.value)
            assertFalse(File(snapshots, "before-version-3.json").exists())
            assertEquals(2L, context.getSharedPreferences("storage_versions", Context.MODE_PRIVATE).getLong("last_opened_version", 0))
        } finally { updated.close() }
        val raw = Room.databaseBuilder(context, KitnaDatabase::class.java, "kitna.db").build()
        try { assertEquals(originalPayload, raw.book().read()!!.payload) } finally { raw.close() }
    }

    @Test fun anUnsupportedDatabaseVersionDoesNotTriggerDestructiveFallback() = runBlocking {
        val payload = versionTwoPayload(sample())
        seedPreviousVersion(payload)
        val database = Room.databaseBuilder(context, KitnaDatabase::class.java, "kitna.db").build()
        database.openHelper.writableDatabase.execSQL("PRAGMA user_version=99")
        database.close()
        installedVersion(3)
        val updated = LocalStore(context)
        try { assertTrue(runCatching { updated.load() }.isFailure); assertNull(updated.book.value) }
        finally { updated.close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath("kitna.db").path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(99, raw.version)
            raw.rawQuery("SELECT payload FROM book WHERE id=1", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(payload, cursor.getString(0))
            }
        }
    }

    @Test fun explicitEraseRemovesUpgradeCopiesAsWellAsTheCurrentBook() = runBlocking {
        seedPreviousVersion(versionTwoPayload(sample()))
        installedVersion(3)
        val store = LocalStore(context)
        try {
            store.load()
            assertTrue(File(snapshots, "before-version-3.json").exists())
            store.reset()
            assertEquals(Book(), store.book.value)
            assertTrue(snapshots.listFiles().isNullOrEmpty())
        } finally { store.close() }
        val reopened = LocalStore(context)
        try { reopened.load(); assertEquals(Book(), reopened.book.value) } finally { reopened.close() }
    }
}
