@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.kitna.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable internal fun SettingsScreen(theme: String, onTheme: (String) -> Unit, onCsv: () -> Unit, onBackup: () -> Unit, onRestore: () -> Unit, onTrash: () -> Unit, onClear: () -> Unit, onBack: () -> Unit, onNotifications: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { ActionIcon("back", "Back", onBack) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            MangaChapter("MAKE IT YOURS", "YOUR BOOK. YOUR WAY.")
            MangaPanel {
                Text("Appearance", style = MaterialTheme.typography.titleLarge)
                Box { OutlinedButton({ expanded = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Theme: $theme") }; DropdownMenu(expanded, { expanded = false }) { listOf("System", "Light", "Dark").forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { expanded = false; onTheme(value) }) } } }
                onNotifications?.let { OutlinedButton(it, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Notifications") } }
            }
            MangaPanel {
                Text("Data & backups", style = MaterialTheme.typography.titleLarge)
                Text("Keep a complete ZIP backup before changing phones. Backups include your records and receipt photos.", style = MaterialTheme.typography.bodySmall)
                Button(onBackup, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save a backup") }
                OutlinedButton(onRestore, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Restore a backup") }
                OutlinedButton(onCsv, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Export CSV") }
                TextButton(onTrash, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Recently deleted") }
            }
            MangaPanel {
                Text("Device storage", style = MaterialTheme.typography.titleLarge)
                Text("App updates keep your records. Uninstalling or clearing app data removes this device's local records and photos.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClear, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Erase all local data", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
