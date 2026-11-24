@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.kitna.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kitna.KitnaViewModel
import app.kitna.core.*
import kotlinx.coroutines.launch

@Composable internal fun GroupSettingsScreen(book: Book, group: Group, vm: KitnaViewModel, onEdit: () -> Unit, onMembers: () -> Unit, onBack: () -> Unit) {
    val cloud by vm.cloud.state.collectAsStateWithLifecycle()
    val link = book.sharedGroups.find { it.groupId == group.id }
    val balances = Accounting.balances(book, group.id)
    val scope = rememberCoroutineScope()
    var leave by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    Scaffold(topBar = { TopAppBar(title = { Text("Group settings") }, navigationIcon = { ActionIcon("back", "Back", onBack) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { GroupAvatar(group, 56); Column(Modifier.weight(1f)) { Text(group.name, style = MaterialTheme.typography.titleLarge); Text(group.kind, color = MaterialTheme.colorScheme.onSurfaceVariant) }; ActionIcon("edit", "Edit group", onEdit) } }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            item { GroupInviteCard(book, group, vm, onMembers) }
            item { SectionLabel("Group members"); TextButton(onMembers, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Glyph("groups", color = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp)); Text("Add people & manage invitations") } }
            items(group.members, key = { it }) { id ->
                val person = book.people.first { it.id == id }
                val net = balances[id] ?: 0
                val participant = link?.snapshot?.participants?.find { it.id == link.people[id] }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Avatar(person.name, size = 44)
                    Column(Modifier.weight(1f)) { Text(person.name + if (id == "self") " (you)" else "", style = MaterialTheme.typography.bodyLarge); Text(if (id == "self") cloud.session?.email ?: "This device" else person.email.ifBlank { if (participant?.active == false) "Left group" else if (participant?.userId != null) "Account connected" else "Not connected yet" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (net != 0L) Column(horizontalAlignment = Alignment.End) { Text(if (net > 0) "gets back" else "owes", style = MaterialTheme.typography.bodySmall, color = if (net > 0) Owed else Owe); Text(money(kotlin.math.abs(net)), fontWeight = FontWeight.Medium, color = if (net > 0) Owed else Owe) }
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant); TextButton(onEdit, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Edit group, budget & appearance") } }
            if (link != null && !link.revoked) item { TextButton({ leave = true }, enabled = !cloud.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Leave group", color = MaterialTheme.colorScheme.error) }; error?.let { Text(it, Modifier.padding(20.dp), color = MaterialTheme.colorScheme.error) } }
        }
    }
    if (leave) AlertDialog(onDismissRequest = { leave = false }, title = { Text("Leave ${group.name}?") }, text = { Text("Your past expenses and balances stay with the group. Sync pending changes first. If you're the owner, another joined friend takes ownership; at least one friend must have joined before you can leave.") }, confirmButton = { TextButton({ leave = false; scope.launch { vm.cloud.leaveGroup(group.id).onSuccess { onBack() }.onFailure { error = it.message } } }) { Text("Leave group", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ leave = false }) { Text("Cancel") } })
}
