@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.kitna.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import app.kitna.data.InvitationLinks
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kitna.KitnaViewModel
import app.kitna.core.*
import kotlinx.coroutines.launch

@Composable internal fun GroupInviteCard(book: Book, group: Group, vm: KitnaViewModel, onManage: () -> Unit) {
    val cloud by vm.cloud.state.collectAsStateWithLifecycle()
    val link = book.sharedGroups.find { it.groupId == group.id }
    val directFriend = book.people.firstOrNull { it.id != "self" && it.id in group.members }.takeIf { group.kind == "Direct" }
    val directParticipant = directFriend?.let { person -> link?.let { shared -> shared.snapshot?.participants?.find { it.id == shared.people[person.id] } } }
    val owner = link != null && (link.snapshot?.group?.ownerId ?: link.accountId) == cloud.session?.id && cloud.session != null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by rememberSaveable(group.id) { mutableStateOf<String?>(null) }
    var notice by rememberSaveable(group.id) { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var options by rememberSaveable { mutableStateOf(false) }
    var emailOpen by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    fun invitation(action: String) {
        if (group.kind == "Direct") return
        if (loading || cloud.busy) return
        loading = true; error = null
        scope.launch {
            try {
                if (book.sharedGroups.none { it.groupId == group.id }) vm.cloud.shareGroup(group.id).getOrThrow()
                vm.cloud.groupCode(group.id).onSuccess { (code, _) ->
                val url = InvitationLinks.url("group", code)
                if (action == "share") { shareInvitation(context, "Join our group ${group.name} on Kitna to track and split expenses together.\n$url\nGroup code: $code"); options = false }
                else { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(if (action == "code") "Kitna group code" else "Kitna group invitation", if (action == "code") code else url)); notice = if (action == "code") "Group code copied" else "Invitation link copied"; options = false }
            }.onFailure { error = it.message } }
            catch (failure: Exception) { error = failure.message ?: "Your group is saved. Try sharing again when connected." }
            finally { loading = false }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            group.archived -> Text("Restore this group before inviting friends.", style = MaterialTheme.typography.bodyMedium)
            link?.revoked == true -> Text("Your access has ended. Ask the owner for a new invitation.", style = MaterialTheme.typography.bodyMedium)
            directFriend != null -> {
                if (directParticipant?.let { it.userId != null && it.active } == true) Text("Connected with ${directFriend.name}", color = MaterialTheme.colorScheme.primary)
                else if (link == null || owner) OutlinedButton(onClick = {
                    name = directFriend.name; email = directFriend.email
                    if (cloud.session == null) onManage() else emailOpen = true
                }, enabled = !loading && !cloud.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Connect ${directFriend.name}") }
                else Text("Your friend can send an invitation to connect this expense history.", style = MaterialTheme.typography.bodyMedium)
            }
            link == null -> {
                Text("Invite friends to split expenses together.", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton({ if (cloud.session == null) onManage() else invitation("share") }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !loading && !cloud.busy) { Text(if (loading) "Preparing invitation…" else if (cloud.session == null) "Sign in & share invite link" else "Share group & invite friends") }
            }
            owner -> Button({ options = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !loading && !cloud.busy) { Text(if (loading) "Preparing invitation…" else "Invite friends") }
            else -> Text("The group owner can share invitations.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
    if (options) ModalBottomSheet(onDismissRequest = { if (!loading) options = false }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Invite to ${group.name}", style = MaterialTheme.typography.titleLarge)
            Text("Links and codes expire after seven days. An invitation adds a new person; use their verified email to connect existing shares.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button({ invitation("share") }, enabled = !loading && !cloud.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Share invite link") }
            TextButton({ invitation("link") }, enabled = !loading && !cloud.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Copy link") }
            TextButton({ invitation("code") }, enabled = !loading && !cloud.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Copy group code") }
            TextButton({ options = false; emailOpen = true }, enabled = !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Invite by email") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (emailOpen) AlertDialog(onDismissRequest = { if (!loading) emailOpen = false }, title = { Text("Invite by email") }, text = {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Friend's name") }, singleLine = true, readOnly = directFriend != null)
            OutlinedTextField(email, { email = it }, label = { Text("Google email") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Email))
            Text("They accept in Kitna with this verified email. No email is sent automatically.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({
        if (!loading) { loading = true; error = null; scope.launch {
            try {
                val existing = directFriend ?: book.people.find { it.id in group.members && it.email.equals(email.trim(), true) && email.isNotBlank() }
                if (directFriend != null && book.sharedGroups.none { it.groupId == group.id }) vm.cloud.shareGroup(group.id).getOrThrow()
                vm.cloud.inviteEmail(group.id, existing?.id, existing?.name ?: name, email).getOrThrow()
                if (directFriend != null) {
                    val token = vm.cloud.invite(group.id, directFriend.id).getOrThrow()
                    shareInvitation(context, "Join me on Kitna to track and split expenses together.\n${InvitationLinks.url("group", token)}")
                }
                emailOpen = false; notice = "Invitation ready for ${existing?.name ?: name}"
            } catch (failure: Exception) { error = failure.message ?: "Could not prepare this invitation." }
            finally { loading = false }
        } }
    }, enabled = !loading && !cloud.busy && name.isNotBlank() && email.isNotBlank()) { Text(if (loading) "Inviting…" else "Invite") } }, dismissButton = { TextButton({ emailOpen = false }, enabled = !loading) { Text("Cancel") } })
}
