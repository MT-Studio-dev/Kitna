package app.kitna.data

import android.content.Context
import app.kitna.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable backup: validated book.json and every photo referenced by current or historical expenses. */
class BookBackup(context: Context, private val store: LocalStore) {
    private val app = context.applicationContext
    private val receipts = ReceiptStore(app)

    suspend fun exportZip(book: Book, output: OutputStream) = withContext(Dispatchers.IO) { ReceiptStore.withFileLock {
        BookValidation.validate(book)
        val photos = referencedPhotos(book)
        val payload = store.json.encodeToString(SharedBooks.portable(book)).toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_JSON_BYTES) { "This book exceeds the 20 MB metadata backup limit." }
        require(photos.size + 1 <= MAX_ENTRIES && payload.size.toLong() + photos.sumOf { it.sizeBytes } <= MAX_TOTAL_BYTES) { "This backup exceeds the 256 MB receipt backup limit." }
        photos.forEach { receipts.verify(it) }
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("book.json")); zip.write(payload); zip.closeEntry()
            photos.forEach { photo ->
                zip.putNextEntry(ZipEntry("receipts/${photo.id}.jpg"))
                receipts.fileFor(photo).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    } }

    /** Legacy JSON remains supported, but cannot invent photos missing from this phone. */
    suspend fun restoreJson(content: String) = withContext(Dispatchers.IO) { ReceiptStore.withFileLock {
        require(content.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "The JSON backup exceeds 20 MB." }
        val book = try { SharedBooks.portable(store.json.decodeFromString<Book>(content)) }
            catch (failure: IllegalArgumentException) { throw IllegalArgumentException("This is not a valid Kitna JSON backup.", failure) }
        BookValidation.validate(book)
        referencedPhotos(book).forEach { receipts.verify(it) }
        store.replace(book)
    } }

    suspend fun restoreZip(input: InputStream) = withContext(Dispatchers.IO) {
        val staging = File(app.cacheDir, "kitna-restore-${newId()}")
        check(staging.mkdir()) { "Could not prepare a receipt backup restore." }
        val installed = mutableListOf<File>()
        try {
            val names = mutableSetOf<String>()
            val stagedPhotos = mutableMapOf<String, File>()
            var bookBytes: ByteArray? = null
            var total = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(names.add(entry.name)) { "Backup contains a duplicate file." }
                    require(names.size <= MAX_ENTRIES && !entry.isDirectory) { "Backup contains too many or unsupported entries." }
                    val limit: Long
                    val target: File?
                    when {
                        entry.name == "book.json" -> { limit = MAX_JSON_BYTES.toLong(); target = null }
                        entry.name.matches(Regex("receipts/[A-Za-z0-9_-]{1,100}\\.jpg")) -> {
                            limit = ReceiptStore.MAX_PHOTO_BYTES
                            val id = entry.name.removePrefix("receipts/").removeSuffix(".jpg")
                            target = File(staging, "$id.jpg")
                            check(target.canonicalFile.parentFile == staging.canonicalFile)
                            stagedPhotos[id] = target
                        }
                        else -> throw IllegalArgumentException("Backup contains an unexpected or unsafe file path.")
                    }
                    val memory = if (target == null) ByteArrayOutputStream() else null
                    (target?.outputStream() ?: memory!!).use { output ->
                        val buffer = ByteArray(8192)
                        var size = 0L
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            size += count; total += count
                            require(size <= limit && total <= MAX_TOTAL_BYTES) { "Backup is larger than the supported size." }
                            output.write(buffer, 0, count)
                        }
                    }
                    if (target == null) bookBytes = memory!!.toByteArray()
                    zip.closeEntry()
                }
            }
            val payload = requireNotNull(bookBytes) { "The backup does not contain book.json." }
            val book = try { SharedBooks.portable(store.json.decodeFromString<Book>(payload.toString(Charsets.UTF_8))) }
                catch (failure: IllegalArgumentException) { throw IllegalArgumentException("The backup's book.json is invalid.", failure) }
            BookValidation.validate(book)
            val photos = referencedPhotos(book)
            require(stagedPhotos.keys == photos.map { it.id }.toSet()) { "The backup is missing a receipt photo or contains an unreferenced image." }
            photos.forEach { photo -> receipts.verify(photo, stagedPhotos.getValue(photo.id)) }
            // Validate all collisions before installing anything: no existing local
            // image is ever overwritten with different bytes under the same ID.
            withContext(NonCancellable) {
                ReceiptStore.withFileLock {
                    try {
                        photos.forEach { photo -> if (receipts.fileFor(photo).exists()) receipts.verify(photo) }
                        photos.forEach { photo -> if (receipts.install(photo, stagedPhotos.getValue(photo.id))) installed += receipts.fileFor(photo) }
                        store.replace(book)
                    } catch (failure: Throwable) {
                        // Roll back while holding the same file lock as install.
                        // Another restore/reset cannot reference these files yet.
                        installed.forEach { it.delete() }
                        throw failure
                    }
                }
            }
        } finally {
            // Entries were staged as flat JPEG filenames, and never as archive paths.
            check(staging.canonicalFile.parentFile == app.cacheDir.canonicalFile)
            staging.listFiles().orEmpty().forEach { if (it.isFile) it.delete() }
            staging.delete()
        }
    }

    companion object {
        const val MAX_JSON_BYTES = 20 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
        const val MAX_ENTRIES = 2001

        fun referencedPhotos(book: Book): List<ReceiptPhoto> {
            val byId = linkedMapOf<String, ReceiptPhoto>()
            (book.expenses.flatMap { it.receipts } + book.revisions.flatMap { it.expense.receipts }).forEach { photo ->
                val existing = byId.putIfAbsent(photo.id, photo)
                require(existing == null || existing == photo) { "A receipt identifier refers to different photo metadata." }
            }
            require(byId.keys.map { it.lowercase(java.util.Locale.ROOT) }.toSet().size == byId.size) { "Receipt identifiers must also be unique regardless of letter case." }
            return byId.values.toList()
        }
    }
}
