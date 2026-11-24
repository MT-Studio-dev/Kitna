package app.kitna.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kitna.KitnaViewModel
import app.kitna.core.*
import kotlinx.coroutines.launch

@Composable internal fun GroupInviteCard(book: Book, group: Group, vm: KitnaViewModel, onManage: () -> Unit) {
    val cloud by vm.cloud.state.collectAsStateWithLifecycle()
    val link = book.sharedGroups.find { it.groupId == group.id }
    val owner = link?.snapshot?.group?.ownerId == cloud.session?.id && cloud.session != null
    val pending = book.pendingChanges.any { it.groupId == group.id }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var code by remember(group.id) { mutableStateOf<String?>(null) }
    var error by remember(group.id) { mutableStateOf<String?>(null) }
    var copied by remember(group.id) { mutableStateOf(false) }
    var loading by remember(group.id) { mutableStateOf(false) }
    fun fetch() { if (!loading) { loading = true; error = null; scope.launch { try { vm.cloud.groupCode(group.id).onSuccess { code = it.first }.onFailure { error = it.message } } finally { loading = false } } } }
    LaunchedEffect(group.id, owner, pending, cloud.invitationsReady, link?.revoked) { if (owner && !pending && cloud.invitationsReady && link?.revoked == false && !group.archived) fetch() }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        MangaPanel {
            Text("Group code", style = MaterialTheme.typography.titleMedium)
            when {
                group.archived -> Text("Restore this group before inviting friends.", style = MaterialTheme.typography.bodySmall)
                link?.revoked == true -> Text("Your access has ended. Ask the group owner to invite your email again.", style = MaterialTheme.typography.bodySmall)
                link == null -> {
                    Text(if (cloud.session == null) "Sign in with Google and share this group to create its code." else "Share this group to create its code and connect friends by their Google emails.", style = MaterialTheme.typography.bodyMedium)
                    Button(onManage, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (cloud.session == null) "Sign in & invite friends" else "Share group & get code") }
                }
                pending -> Text("Your group is saved on this phone. Sync its pending changes to make invitations available.", style = MaterialTheme.typography.bodySmall)
                !cloud.invitationsReady -> { Text("Invitations need the latest group service update. Your records are kept.", style = MaterialTheme.typography.bodySmall); OutlinedButton(onManage) { Text("Check shared-group connection") } }
                owner -> {
                    Text(code ?: if (loading) "Creating your code…" else "Code unavailable", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Text("Friends enter this in Groups → Join group. The code lasts 7 days; anyone who has it can join. Use email invitations to connect an existing person's balances.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { code?.let { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Kitna group code", it)); copied = true } }, enabled = code != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (copied) "Code copied" else "Copy code") }
                    if (error != null) OutlinedButton(onClick = ::fetch, enabled = !loading) { Text("Retry code") }
                }
                else -> Text("You're connected. The group owner can invite more friends.", style = MaterialTheme.typography.bodySmall)
            }
            if (link != null) OutlinedButton(onManage, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Members, invitations & leave group") }
        }
    }
}
