package app.kitna.data

import android.content.Context
import android.os.Build
import androidx.room.*
import app.kitna.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

@Entity(tableName = "book")
data class BookRow(@PrimaryKey val id: Int = 1, val payload: String)
data class BookPartInfo(val id: Int, val characters: Int)
@Dao interface BookDao {
    @Query("SELECT * FROM book WHERE id = 1") suspend fun read(): BookRow?
    @Query("SELECT id, length(payload) AS characters FROM book ORDER BY id") suspend fun parts(): List<BookPartInfo>
    @Query("SELECT substr(payload, :start, :count) FROM book WHERE id = :id") suspend fun readPart(id: Int, start: Int, count: Int): String
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun write(row: BookRow)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun writeRows(rows: List<BookRow>)
    @Query("DELETE FROM book") suspend fun deleteRows()
    @Transaction suspend fun replaceRows(rows: List<BookRow>) { deleteRows(); writeRows(rows) }
}
@Database(entities = [BookRow::class], version = 1, exportSchema = true)
abstract class KitnaDatabase : RoomDatabase() { abstract fun book(): BookDao }

/** A private ledger, committed atomically in bounded SQLite rows. */
class LocalStore(context: Context) {
    private val db = Room.databaseBuilder(context.applicationContext, KitnaDatabase::class.java, "kitna.db").build()
    private val appContext = context.applicationContext
    private val upgradePreferences = appContext.getSharedPreferences("storage_versions", Context.MODE_PRIVATE)
    private val upgradeDirectory = File(appContext.noBackupFilesDir, "upgrade-backups")
    private val mutex = Mutex()
    val json = Json { prettyPrint = true; encodeDefaults = true }
    private val mutable = MutableStateFlow<Book?>(null)
    val book = mutable.asStateFlow()
    suspend fun load() = withContext(Dispatchers.IO) { mutex.withLock {
        val parts = db.book().parts()
        val payload = buildString {
            for (part in parts) {
                var start = 1
                while (start <= part.characters) {
                    val text = db.book().readPart(part.id, start, 32768)
                    require(text.isNotEmpty()) { "Stored records could not be reconstructed." }
                    append(text)
                    start += text.codePointCount(0, text.length)
                }
            }
        }
        val loaded = if (parts.isEmpty()) Book() else json.decodeFromString<Book>(payload)
        BookValidation.validate(loaded)
        preserveBeforeUpgrade(payload.takeIf { parts.isNotEmpty() })
        mutable.value = loaded
    } }
    suspend fun update(transform: (Book) -> Book) = withContext(Dispatchers.IO) { mutex.withLock {
        val current = checkNotNull(mutable.value) { "Records are not loaded. Retry loading or restore a backup." }
        persist(transform(current))
    } }
    suspend fun replace(book: Book) = withContext(Dispatchers.IO) { mutex.withLock { persist(book) } }
    private suspend fun persist(book: Book) {
        BookValidation.validate(book)
        val payload = json.encodeToString(book)
        val chunks = mutableListOf<BookRow>()
        var offset = 0
        while (offset < payload.length) {
            var end = minOf(offset + 32768, payload.length)
            if (end < payload.length && Character.isHighSurrogate(payload[end - 1]) && Character.isLowSurrogate(payload[end])) end--
            chunks += BookRow(chunks.size + 1, payload.substring(offset, end))
            offset = end
        }
        // A screen may be rotated or dismissed during this suspend point. Once
        // committing starts, finish both the SQLite transaction and publication
        // so the next mutation can never overwrite a committed-but-unpublished book.
        withContext(NonCancellable) {
            db.book().replaceRows(chunks)
            mutable.value = book
        }
    }
    suspend fun restore(content: String) = withContext(Dispatchers.IO) {
        val imported = try { SharedBooks.portable(json.decodeFromString<Book>(content)) }
            catch (failure: IllegalArgumentException) { throw IllegalArgumentException("This is not a valid Kitna JSON backup.", failure) }
        // A raw JSON book may reference local photos. Never accept metadata for
        // images missing from this device; portable ZIP restore installs them first.
        ReceiptStore.withFileLock {
            BookBackup.referencedPhotos(imported).forEach { ReceiptStore(appContext).verify(it) }
            replace(imported)
        }
    }
    /**
     * Normal APK updates keep the same application ID, signing key and database.
     * Before a newer build can rewrite a valid old book, keep its exact JSON in
     * noBackupFilesDir. The filename is per target app version and never reused.
     * A pending file is renamed only after a complete fsynced write, so process
     * death cannot turn an incomplete copy into a committed safety snapshot.
     */
    private suspend fun preserveBeforeUpgrade(payload: String?) = upgradeMutex.withLock {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val version = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else {
            @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
        }
        val previous = upgradePreferences.getLong("last_opened_version", 0L)
        if (version <= previous) return@withLock
        if (payload != null) {
            check(upgradeDirectory.isDirectory || upgradeDirectory.mkdirs()) { "Could not create an update safety copy. Your existing records are unchanged." }
            val snapshot = File(upgradeDirectory, "before-version-$version.json")
            if (!snapshot.exists()) {
                val pending = File(upgradeDirectory, ".before-version-$version.pending")
                try {
                    FileOutputStream(pending).use { output ->
                        output.write(payload.toByteArray(Charsets.UTF_8))
                        output.flush()
                        output.fd.sync()
                    }
                    check(pending.renameTo(snapshot)) { "Could not finish an update safety copy. Your existing records are unchanged." }
                } finally {
                    if (pending.exists()) pending.delete()
                }
            }
        }
        check(upgradePreferences.edit().putLong("last_opened_version", version).commit()) { "Could not record this update. Your existing records are unchanged." }
    }

    suspend fun reset() = withContext(Dispatchers.IO) { ReceiptStore.withFileLock {
        mutex.withLock {
            withContext(NonCancellable) {
                persist(Book())
                ReceiptStore(appContext).clearPrivatePhotos()
                val sharing = File(appContext.noBackupFilesDir, "sharing-backups")
                sharing.listFiles().orEmpty().forEach { file -> check(file.isFile && file.canonicalFile.parentFile == sharing.canonicalFile && file.delete()) { "Could not erase a sharing safety copy. Please retry." } }
                check(!sharing.exists() || sharing.delete()) { "Could not erase sharing safety copies. Please retry." }
                upgradeMutex.withLock {
                    // Erase really erases the private safety copies too.
                    upgradeDirectory.listFiles().orEmpty().forEach { file ->
                        check(!file.isFile || file.delete()) { "Could not erase an update safety copy. Please retry erasing local data." }
                    }
                    check(!upgradeDirectory.exists() || upgradeDirectory.delete()) { "Could not erase update safety copies. Please retry erasing local data." }
                }
            }
        }
    } }
    fun close() = db.close()

    private companion object {
        val upgradeMutex = Mutex()
    }
}
