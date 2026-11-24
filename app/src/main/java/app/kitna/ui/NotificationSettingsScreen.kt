@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.kitna.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.kitna.data.ActivityNotifications

@Composable internal fun NotificationSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val notifications = remember(context) { ActivityNotifications(context) }
    var enabled by remember { mutableStateOf(notifications.enabled()) }
    var allowed by remember { mutableStateOf(notifications.permissionGranted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> notifications.setEnabled(granted); enabled = granted; allowed = notifications.permissionGranted() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) { val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { allowed = notifications.permissionGranted(); notifications.refreshSchedule() } }; lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) } }
    BackHandler(onBack = onBack)
    Scaffold(topBar = { TopAppBar(title = { Text("Notifications") }, navigationIcon = { ActionIcon("back", "Back", onBack) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            MangaChapter("STAY IN THE LOOP", "YOUR CREW'S NEXT UPDATE.")
            MangaPanel {
                Row { Text("Shared group activity", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium); Switch(checked = enabled && allowed, onCheckedChange = { value ->
                    if (value && Build.VERSION.SDK_INT >= 33 && !allowed) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else { notifications.setEnabled(value); enabled = value; allowed = notifications.permissionGranted() }
                }) }
                Text("Get alerts when friends add or change shared-group activity. Sign in with Google to receive updates.")
                Text("While Kitna is open, updates arrive during sync. In the background Android checks periodically, roughly every 15 minutes or later depending on battery and network. Alerts are not instant push notifications.", style = MaterialTheme.typography.bodySmall)
                Text("Expense amounts and receipt details are kept out of notification previews.", style = MaterialTheme.typography.bodySmall)
                if (!allowed) Text("Notifications are currently blocked by Android. You can enable them in your phone's settings.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedButton({ context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open Android notification settings") }
            }
        }
    }
}
