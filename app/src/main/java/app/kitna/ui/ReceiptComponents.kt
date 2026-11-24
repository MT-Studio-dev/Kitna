package app.kitna.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kitna.core.ReceiptPhoto
import app.kitna.data.ReceiptStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun ReceiptAttachmentPanel(
    photos: List<ReceiptPhoto>,
    onPhotosChange: (List<ReceiptPhoto>) -> Unit,
    enabled: Boolean = true,
    onBusyChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val storage = remember(context) { ReceiptStore(context) }
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var removingId by rememberSaveable { mutableStateOf<String?>(null) }
    val reportBusy by rememberUpdatedState(onBusyChange)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            importing = true; reportBusy(true); error = null
            try {
                require(photos.size < 10) { "An expense can have up to ten receipt photos." }
                val photo = storage.importPhoto(uri)
                onPhotosChange(photos + photo)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not attach this photo." }
            finally { importing = false; reportBusy(false) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${photos.size} of 10 photos · stored on this device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (photos.isNotEmpty()) ReceiptTiles(photos, onRemove = { removingId = it }, enabled = enabled && !importing)
        OutlinedButton(onClick = { launcher.launch(arrayOf("image/*")) }, enabled = enabled && !importing && photos.size < 10, modifier = Modifier.fillMaxWidth()) {
            Text(if (importing) "Attaching photo…" else "Attach receipt photo")
        }
        Text("A private JPEG copy is kept with your records. Complete ZIP backups include receipt photos and their history.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    if (removingId != null) AlertDialog(onDismissRequest = { removingId = null }, title = { Text("Remove this receipt?") }, text = { Text("It will be removed from this expense. Previous expense versions keep their receipt photos.") }, confirmButton = {
        TextButton(onClick = { onPhotosChange(photos.filterNot { it.id == removingId }); removingId = null }) { Text("Remove") }
    }, dismissButton = { TextButton(onClick = { removingId = null }) { Text("Cancel") } })
}

@Composable fun ReceiptGallery(photos: List<ReceiptPhoto>) {
    if (photos.isEmpty()) Text("No receipt photos attached.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    else ReceiptTiles(photos)
}

@Composable private fun ReceiptTiles(photos: List<ReceiptPhoto>, onRemove: ((String) -> Unit)? = null, enabled: Boolean = true) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(photos, key = { _, photo -> photo.id }) { index, photo ->
            Column(Modifier.width(126.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(126.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = enabled) { selectedId = photo.id }
                    .semantics { contentDescription = "View receipt ${index + 1}" }, contentAlignment = Alignment.Center) {
                    ReceiptImage(photo, thumbnail = true)
                }
                Text(photo.name, maxLines = 2, style = MaterialTheme.typography.labelSmall)
                if (onRemove != null) TextButton(onClick = { onRemove(photo.id) }, enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "Remove receipt ${index + 1}" }) { Text("Remove") }
            }
        }
    }
    photos.firstOrNull { it.id == selectedId }?.let { photo -> ReceiptViewer(photo) { selectedId = null } }
}

@Composable private fun ReceiptImage(photo: ReceiptPhoto, thumbnail: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val loaded by produceState<Result<Bitmap>?>(initialValue = null, photo.id, photo.sha256, thumbnail) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val storage = ReceiptStore(context)
                storage.verify(photo)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(storage.fileFor(photo).path, bounds)
                val target = if (thumbnail) 256 else ReceiptStore.MAX_EDGE
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (bounds.outWidth / inSampleSize > target || bounds.outHeight / inSampleSize > target) inSampleSize *= 2
                }
                requireNotNull(BitmapFactory.decodeFile(storage.fileFor(photo).path, options)) { "Photo unavailable" }
            }
        }
    }
    when {
        loaded == null -> CircularProgressIndicator(Modifier.size(24.dp))
        loaded?.isFailure == true -> Text("Photo unavailable", Modifier.padding(10.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        else -> Image(loaded!!.getOrThrow().asImageBitmap(), contentDescription = if (thumbnail) null else "Receipt photo",
            modifier = modifier.fillMaxSize(), contentScale = if (thumbnail) ContentScale.Crop else ContentScale.Fit)
    }
}

@Composable private fun ReceiptViewer(photo: ReceiptPhoto, onClose: () -> Unit) {
    var zoom by remember(photo.id) { mutableFloatStateOf(1f) }
    var offset by remember(photo.id) { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(photo.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2)
                    TextButton(onClick = onClose) { Text("Close receipt") }
                }
                Box(Modifier.fillMaxWidth().weight(1f).clipToBounds().pointerInput(photo.id) {
                    detectTransformGestures { _, pan, factor, _ ->
                        zoom = (zoom * factor).coerceIn(1f, 5f)
                        val xLimit = size.width * (zoom - 1f) / 2f
                        val yLimit = size.height * (zoom - 1f) / 2f
                        offset = Offset((offset.x + pan.x).coerceIn(-xLimit, xLimit), (offset.y + pan.y).coerceIn(-yLimit, yLimit))
                    }
                }, contentAlignment = Alignment.Center) {
                    ReceiptImage(photo, thumbnail = false, modifier = Modifier.graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = offset.x, translationY = offset.y))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Pinch to zoom and drag to read", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { zoom = 1f; offset = Offset.Zero }) { Text("Reset zoom") }
                }
            }
        }
    }
}
