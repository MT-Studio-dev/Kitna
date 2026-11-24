package app.kitna

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.kitna.core.*
import app.kitna.data.BookBackup
import app.kitna.data.LocalStore
import app.kitna.data.ReceiptStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReceiptStorageRuntimeTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val receipts: ReceiptStore get() = ReceiptStore(context)

    @Before fun clearPreviousBook() {
        context.deleteDatabase("kitna.db")
        receipts.clearPrivatePhotos()
    }

    private fun source(color: Int = Color.BLUE, width: Int = 80, height: Int = 40, transparent: Boolean = false): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(if (transparent) Color.TRANSPARENT else Color.WHITE)
            drawRect(width / 4f, height / 4f, width * .75f, height * .75f, Paint().apply { this.color = color })
        }
        return File.createTempFile("receipt-test-", ".png", context.cacheDir).also { file ->
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    private suspend fun photo(color: Int = Color.BLUE): ReceiptPhoto {
        val source = source(color)
        return try { receipts.importPhoto(Uri.fromFile(source)) } finally { source.delete() }
    }

    private fun sample(photo: ReceiptPhoto? = null): Book = Book(
        people = listOf(Person("self", "You"), Person("alex", "Alex")),
        groups = listOf(Group("trip", "Goa", listOf("self", "alex"), kind = "Trip", budget = 500000)),
        expenses = listOf(Expense("bill", "trip", "Lunch", 100000,
            mapOf("self" to 100000), mapOf("self" to 50000, "alex" to 50000),
            date = "2026-11-17", receipts = listOfNotNull(photo)))
    )

    private fun archive(store: LocalStore, book: Book, files: Map<String, ByteArray> = emptyMap()): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("book.json")); zip.write(store.json.encodeToString(book).toByteArray()); zip.closeEntry()
                files.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            }
        }.toByteArray()

    @Test fun importKeepsAPrivateSizedJpegAndRendersTransparencyAsWhitePaper() = runBlocking {
        val original = source(Color.BLACK, 3200, 1600, transparent = true)
        val photo = receipts.importPhoto(Uri.fromFile(original))
        assertTrue(original.delete())
        receipts.verify(photo)
        assertEquals("image/jpeg", photo.mimeType)
        assertTrue(photo.sizeBytes in 1..ReceiptStore.MAX_PHOTO_BYTES)
        val decoded = BitmapFactory.decodeFile(receipts.fileFor(photo).path)!!
        try {
            assertEquals(1600, decoded.width)
            assertEquals(800, decoded.height)
            val paper = decoded.getPixel(10, 10)
            assertTrue(Color.red(paper) > 240 && Color.green(paper) > 240 && Color.blue(paper) > 240)
            val ink = decoded.getPixel(800, 400)
            assertTrue(Color.red(ink) < 20 && Color.green(ink) < 20 && Color.blue(ink) < 20)
        } finally { decoded.recycle() }
    }

    @Test fun completeZipRestoresCurrentAndHistoricalPhotosAfterAnExplicitErase() = runBlocking {
        val oldPhoto = photo(Color.BLUE)
        val currentPhoto = photo(Color.RED)
        val initial = sample(oldPhoto)
        val expected = BookActions.saveExpense(initial, initial.expenses.single().copy(description = "Lunch corrected", receipts = listOf(currentPhoto)))
        val store = LocalStore(context)
        try {
            store.load(); store.replace(expected)
            val output = ByteArrayOutputStream()
            BookBackup(context, store).exportZip(expected, output)
            val names = mutableSetOf<String>()
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; names += entry.name; zip.closeEntry() }
            }
            assertEquals(setOf("book.json", "receipts/${oldPhoto.id}.jpg", "receipts/${currentPhoto.id}.jpg"), names)
            store.reset()
            assertFalse(receipts.fileFor(oldPhoto).exists())
            assertFalse(receipts.fileFor(currentPhoto).exists())
            BookBackup(context, store).restoreZip(ByteArrayInputStream(output.toByteArray()))
            assertEquals(expected, store.book.value)
            receipts.verify(oldPhoto); receipts.verify(currentPhoto)
        } finally { store.close() }
        val reopened = LocalStore(context)
        try { reopened.load(); assertEquals(expected, reopened.book.value) } finally { reopened.close() }
    }

    @Test fun corruptReceiptRejectsTheWholeBackupAndKeepsExistingRecordsAndBytes() = runBlocking {
        val keptPhoto = photo(Color.BLUE)
        val incomingPhoto = photo(Color.RED)
        val kept = sample(keptPhoto)
        val originalBytes = receipts.fileFor(keptPhoto).readBytes()
        val corrupt = receipts.fileFor(incomingPhoto).readBytes().also { it[it.lastIndex / 2] = (it[it.lastIndex / 2].toInt() xor 1).toByte() }
        receipts.fileFor(incomingPhoto).delete()
        val store = LocalStore(context)
        try {
            store.load(); store.replace(kept)
            val bytes = archive(store, sample(incomingPhoto), mapOf("receipts/${incomingPhoto.id}.jpg" to corrupt))
            assertTrue(runCatching { BookBackup(context, store).restoreZip(ByteArrayInputStream(bytes)) }.isFailure)
            assertEquals(kept, store.book.value)
            assertArrayEquals(originalBytes, receipts.fileFor(keptPhoto).readBytes())
            assertFalse(receipts.fileFor(incomingPhoto).exists())
        } finally { store.close() }
        val reopened = LocalStore(context)
        try { reopened.load(); assertEquals(kept, reopened.book.value) } finally { reopened.close() }
    }

    @Test fun unsafePathsAndMissingPhotosAreRejectedBeforeReplacingTheBook() = runBlocking {
        val incomingPhoto = photo()
        receipts.fileFor(incomingPhoto).delete()
        val store = LocalStore(context)
        try {
            store.load(); store.replace(sample())
            val kept = store.book.value
            val unsafe = archive(store, sample(), mapOf("../outside.jpg" to byteArrayOf(1, 2)))
            assertTrue(runCatching { BookBackup(context, store).restoreZip(ByteArrayInputStream(unsafe)) }.isFailure)
            val missing = archive(store, sample(incomingPhoto))
            assertTrue(runCatching { BookBackup(context, store).restoreZip(ByteArrayInputStream(missing)) }.isFailure)
            assertEquals(kept, store.book.value)
            assertFalse(File(context.cacheDir, "outside.jpg").exists())
        } finally { store.close() }
    }

    @Test fun receiptIdCollisionNeverOverwritesTheExistingImage() = runBlocking {
        val keptPhoto = photo(Color.BLUE)
        val incomingSource = photo(Color.RED)
        val incomingPhoto = incomingSource.copy(id = keptPhoto.id)
        val incomingBytes = receipts.fileFor(incomingSource).readBytes()
        receipts.fileFor(incomingSource).delete()
        val originalBytes = receipts.fileFor(keptPhoto).readBytes()
        val kept = sample(keptPhoto)
        val store = LocalStore(context)
        try {
            store.load(); store.replace(kept)
            val bytes = archive(store, sample(incomingPhoto), mapOf("receipts/${incomingPhoto.id}.jpg" to incomingBytes))
            assertTrue(runCatching { BookBackup(context, store).restoreZip(ByteArrayInputStream(bytes)) }.isFailure)
            assertEquals(kept, store.book.value)
            assertArrayEquals(originalBytes, receipts.fileFor(keptPhoto).readBytes())
        } finally { store.close() }
    }

    @Test fun failedDatabaseCommitRollsBackOnlyNewlyInstalledPhotos() = runBlocking {
        val keptPhoto = photo(Color.BLUE)
        val incomingPhoto = photo(Color.RED)
        val incomingBytes = receipts.fileFor(incomingPhoto).readBytes()
        receipts.fileFor(incomingPhoto).delete()
        val kept = sample(keptPhoto)
        val store = LocalStore(context)
        store.load(); store.replace(kept)
        val bytes = archive(store, sample(incomingPhoto), mapOf("receipts/${incomingPhoto.id}.jpg" to incomingBytes))
        // Reject an INSERT after replaceRows has deleted the old rows inside
        // its transaction. This exercises a real SQLite rollback; closing a
        // Room instance is insufficient because its helper may reopen lazily.
        SQLiteDatabase.openDatabase(context.getDatabasePath("kitna.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("CREATE TRIGGER reject_restore BEFORE INSERT ON book BEGIN SELECT RAISE(ABORT, 'forced restore failure'); END")
        }
        try {
            assertTrue(runCatching { BookBackup(context, store).restoreZip(ByteArrayInputStream(bytes)) }.isFailure)
            assertEquals(kept, store.book.value)
            receipts.verify(keptPhoto)
            assertFalse(receipts.fileFor(incomingPhoto).exists())
        } finally {
            SQLiteDatabase.openDatabase(context.getDatabasePath("kitna.db").path, null, SQLiteDatabase.OPEN_READWRITE).use {
                it.execSQL("DROP TRIGGER IF EXISTS reject_restore")
            }
            store.close()
        }
        val reopened = LocalStore(context)
        try { reopened.load(); assertEquals(kept, reopened.book.value) } finally { reopened.close() }
    }

    @Test fun oldJsonWithoutPhotoOrBudgetFieldsStillRestores() = runBlocking {
        val legacy = """{"people":[{"id":"self","name":"You"},{"id":"alex","name":"Alex"}],"groups":[{"id":"trip","name":"Old trip","members":["self","alex"]}],"expenses":[{"id":"bill","groupId":"trip","description":"Old lunch","amount":1000,"payers":{"self":1000},"shares":{"self":500,"alex":500}}]}"""
        val store = LocalStore(context)
        try {
            store.load()
            BookBackup(context, store).restoreJson(legacy)
            assertEquals("Old lunch", store.book.value!!.expenses.single().description)
            assertNull(store.book.value!!.groups.single().budget)
            assertTrue(store.book.value!!.expenses.single().receipts.isEmpty())
        } finally { store.close() }
    }

    @Test fun jsonCannotReplaceRecordsWhenItsReferencedPhotoIsMissing() = runBlocking {
        val missingPhoto = photo()
        receipts.fileFor(missingPhoto).delete()
        val store = LocalStore(context)
        try {
            store.load(); store.replace(sample())
            val kept = store.book.value
            val json = store.json.encodeToString(sample(missingPhoto))
            assertTrue(runCatching { BookBackup(context, store).restoreJson(json) }.isFailure)
            assertEquals(kept, store.book.value)
        } finally { store.close() }
    }
}
