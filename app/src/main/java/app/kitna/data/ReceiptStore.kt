package app.kitna.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import app.kitna.core.ReceiptPhoto
import app.kitna.core.newId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Photos are copied into app-private storage; the original picker URI is never needed again. */
class ReceiptStore(context: Context) {
    private val app = context.applicationContext
    private val directory = File(app.filesDir, "receipts")

    fun fileFor(photo: ReceiptPhoto): File = fileForId(photo.id)

    internal fun fileForId(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid receipt identifier." }
        val file = File(directory, "$id.jpg")
        check(file.canonicalFile.parentFile == directory.canonicalFile) { "Invalid receipt path." }
        return file
    }

    suspend fun importPhoto(uri: Uri): ReceiptPhoto = withContext(Dispatchers.IO) { withFileLock {
        val source = File.createTempFile("kitna-photo-", ".source", app.cacheDir)
        try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(source).use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_SOURCE_BYTES) { "Choose a receipt photo smaller than 20 MB." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalArgumentException("Could not open that receipt photo.")
            val orientation = runCatching { ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "That file is not a supported image." }
            val options = BitmapFactory.Options().apply { inSampleSize = 1 }
            while (bounds.outWidth / options.inSampleSize > MAX_EDGE || bounds.outHeight / options.inSampleSize > MAX_EDGE) {
                options.inSampleSize = maxOf(1, options.inSampleSize * 2)
            }
            // BitmapFactory's default sample size is 1 on Android; enforce it explicitly.
            options.inSampleSize = maxOf(1, options.inSampleSize)
            val decoded = BitmapFactory.decodeFile(source.path, options) ?: throw IllegalArgumentException("Could not decode that photo. Try a JPEG or PNG image.")
            val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height))
            var bitmap = if (scale < 1f) Bitmap.createScaledBitmap(decoded, maxOf(1, (decoded.width * scale).toInt()), maxOf(1, (decoded.height * scale).toInt()), true) else decoded
            if (bitmap !== decoded) decoded.recycle()
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
                }
            }
            if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated !== bitmap) { bitmap.recycle(); bitmap = rotated }
            }
            // JPEG has no alpha channel. Receipts supplied as transparent PNGs
            // need paper-white behind their ink rather than a black background.
            if (bitmap.hasAlpha()) {
                val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
                bitmap.recycle()
                bitmap = opaque
            }
            val id = newId()
            check(directory.isDirectory || directory.mkdirs()) { "Could not create private receipt storage." }
            val destination = fileForId(id)
            val pending = File(directory, "$id.pending")
            try {
                FileOutputStream(pending).use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, 82, output)) { "Could not save this photo." }
                    output.flush(); output.fd.sync()
                }
                require(pending.length() in 1..MAX_PHOTO_BYTES) { "This photo is too detailed. Choose a smaller image." }
                check(pending.renameTo(destination)) { "Could not finish saving this receipt." }
                ReceiptPhoto(id, displayName(uri), "image/jpeg", destination.length(), sha256(destination))
            } finally {
                bitmap.recycle()
                if (pending.exists()) pending.delete()
            }
        } finally { source.delete() }
    } }

    private fun displayName(uri: Uri): String = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.filter { !it.isISOControl() }?.take(200)?.takeIf { it.isNotBlank() } ?: "Receipt photo"

    internal fun verify(photo: ReceiptPhoto, file: File = fileFor(photo)) {
        require(photo.mimeType == "image/jpeg" && photo.sizeBytes in 1..MAX_PHOTO_BYTES && photo.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid receipt metadata." }
        require(file.isFile && file.length() == photo.sizeBytes) { "A receipt photo is missing or incomplete. Use a complete ZIP backup to restore it." }
        require(sha256(file) == photo.sha256) { "A receipt photo failed its integrity check." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..MAX_EDGE && bounds.outHeight in 1..MAX_EDGE) { "A receipt photo has invalid image dimensions." }
        val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = 4 })
        requireNotNull(decoded) { "A receipt photo cannot be decoded." }
        decoded.recycle()
    }

    internal fun install(photo: ReceiptPhoto, source: File): Boolean {
        verify(photo, source)
        check(directory.isDirectory || directory.mkdirs()) { "Could not create receipt storage." }
        val destination = fileFor(photo)
        if (destination.exists()) {
            verify(photo, destination)
            return false
        }
        val pending = File(directory, "${photo.id}.pending")
        try {
            source.inputStream().use { input -> FileOutputStream(pending).use { output -> input.copyTo(output); output.flush(); output.fd.sync() } }
            check(pending.renameTo(destination)) { "Could not install a receipt photo." }
            return true
        } finally { if (pending.exists()) pending.delete() }
    }

    /** History can refer to removed photos, so only an explicit book erase deletes the private files. */
    internal fun clearPrivatePhotos() {
        directory.listFiles().orEmpty().forEach { file ->
            check(file.isFile && file.canonicalFile.parentFile == directory.canonicalFile && file.delete()) { "Could not erase a receipt photo. Please retry erasing local data." }
        }
        check(!directory.exists() || directory.delete()) { "Could not erase private receipt storage." }
    }

    companion object {
        private val fileMutex = Mutex()
        internal suspend fun <T> withFileLock(block: suspend () -> T): T = fileMutex.withLock { block() }
        const val MAX_EDGE = 1600
        const val MAX_PHOTO_BYTES = 2L * 1024 * 1024
        const val MAX_SOURCE_BYTES = 20L * 1024 * 1024
        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
