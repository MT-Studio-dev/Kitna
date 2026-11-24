package app.kitna.ui

import android.content.Context
import android.net.Uri
import app.kitna.data.BookBackup
import app.kitna.data.LocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun restoreSelectedBackup(context: Context, store: LocalStore, uri: Uri) = withContext(Dispatchers.IO) {
    val backups = BookBackup(context.applicationContext, store)
    checkNotNull(context.contentResolver.openInputStream(uri)) { "Could not open this backup." }.buffered().use { input ->
        input.mark(4)
        val signature = ByteArray(4)
        var count = 0
        while (count < signature.size) {
            val n = input.read(signature, count, signature.size - count)
            if (n < 0) break
            count += n
        }
        input.reset()
        if (count >= 2 && signature[0] == 'P'.code.toByte() && signature[1] == 'K'.code.toByte()) {
            backups.restoreZip(input)
        } else {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size() + n <= 20 * 1024 * 1024) { "JSON backup is larger than 20 MB. Use a complete ZIP backup for books with photos." }
                output.write(buffer, 0, n)
            }
            backups.restoreJson(output.toString("UTF-8"))
        }
    }
}
