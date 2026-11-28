@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.kitna.ui

import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kitna.KitnaViewModel
import app.kitna.core.*
import app.kitna.data.InvitationLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Local search is immediate. Only a selected contact URI is read; no address-book permission or upload. */
@Composable internal fun AddFriendsScreen(
    book: Book, vm: KitnaViewModel, group: Group? = null,
    onClose: () -> Unit, onAccount: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cloud by vm.cloud.state.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable(group?.id) { mutableStateOf(arrayListOf<String>()) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var personId by rememberSaveable { mutableStateOf(newId()) }
    var manual by rememberSaveable { mutableStateOf(false) }
    var optional by rememberSaveable { mutableStateOf(false) }
    var inviteAfterSave by rememberSaveable { mutableStateOf(false) }
    var localBusy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var shareAfterSignIn by rememberSaveable { mutableStateOf(false) }
    var groupShareAfterSignIn by rememberSaveable { mutableStateOf(false) }
    var sharePersonId by rememberSaveable { mutableStateOf<String?>(null) }
    var emailAfterSignIn by rememberSaveable { mutableStateOf(false) }
    val busy = saving || localBusy || cloud.busy
    val currentGroup = group?.let { original -> book.groups.find { it.id == original.id } ?: original }
    val groupLink = group?.let { selectedGroup -> book.sharedGroups.find { it.groupId == selectedGroup.id } }
    val canAddMembers = groupLink == null || (!groupLink.revoked && groupLink.snapshot?.group?.ownerId == cloud.session?.id)
    val members = currentGroup?.members.orEmpty().toSet()
    val matches = remember(book.people, query) { book.people.filter { it.id != "self" && (query.isBlank() || it.name.contains(query.trim(), true) || it.email.contains(query.trim(), true) || it.phone.contains(query.trim(), true)) }.sortedBy { it.name.lowercase() } }

    fun manualEntry(invite: Boolean = false) {
        personId = newId(); name = query.takeUnless { '@' in it || it.any(Char::isDigit) }.orEmpty()
        email = query.takeIf { '@' in it }.orEmpty(); phone = query.takeIf { it.isNotBlank() && '@' !in it && it.any(Char::isDigit) }.orEmpty()
        optional = email.isNotBlank() || phone.isNotBlank(); inviteAfterSave = invite; error = null; manual = true
    }
    fun signIn() {
        if (localBusy || cloud.busy) return
        localBusy = true; error = null
        scope.launch {
            try { vm.cloud.beginSignIn().onSuccess { url -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure { error = it.message } }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; error = "Could not open Google sign-in. Check that a browser is installed." }
            finally { localBusy = false }
        }
    }
    val contacts = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { uri ->
            runCatching {
                context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        name = cursor.getString(0).orEmpty(); phone = cursor.getString(1).orEmpty(); email = ""
                        personId = newId(); optional = phone.isNotBlank(); inviteAfterSave = false; manual = true
                    }
                }
            }.onFailure { error = "Could not open this contact. You can add their name manually." }
        }
    }
    fun shareFriend(id: String?) {
        if (cloud.session == null) { sharePersonId = id; shareAfterSignIn = true; signIn(); return }
        if (busy) return
        localBusy = true; error = null
        scope.launch {
            try { vm.cloud.createFriendInvite(id).onSuccess { url -> shareInvitation(context, "Join me on Kitna to track and split expenses together.\n$url") }.onFailure { error = it.message } }
            catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message ?: "Could not open the invitation share sheet." }
            finally { localBusy = false }
        }
    }
    fun shareGroup() {
        val selectedGroup = currentGroup ?: return
        if (cloud.session == null) { groupShareAfterSignIn = true; signIn(); return }
        if (busy) return
        localBusy = true; error = null
        scope.launch {
            try {
                require(selectedGroup.kind != "Direct") { "Connect this friend from their expense history using their verified email." }
                if (groupLink == null) vm.cloud.shareGroup(selectedGroup.id).getOrThrow()
                val code = vm.cloud.groupCode(selectedGroup.id).getOrThrow().first
                shareInvitation(context, "Join our group ${selectedGroup.name} on Kitna to track and split expenses together.\n${InvitationLinks.url("group", code)}\nGroup code: $code")
            } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message ?: "Could not prepare the group invitation." }
            finally { localBusy = false }
        }
    }
    LaunchedEffect(cloud.session?.id, cloud.busy, localBusy, shareAfterSignIn, groupShareAfterSignIn, emailAfterSignIn) {
        if (cloud.session != null && !cloud.busy && !localBusy) {
            if (shareAfterSignIn) { shareAfterSignIn = false; shareFriend(sharePersonId) }
            if (groupShareAfterSignIn) { groupShareAfterSignIn = false; shareGroup() }
            if (emailAfterSignIn) { emailAfterSignIn = false; manualEntry(true) }
        }
    }
    val editing = book.people.find { it.id == editingId }
    if (editing != null) {
        PersonEditor(book, editing, vm) { editingId = null }
        return
    }
    BackHandler(onBack = onClose)
    Scaffold(topBar = { TopAppBar(title = { Text(if (group == null) "Add friends" else "Add members") }, navigationIcon = { ActionIcon("back", "Back", onClose) }, actions = {
        if (group != null) TextButton(onClick = {
            if (!busy && selected.isNotEmpty()) { localBusy = true; error = null; scope.launch { try { vm.addGroupMembers(group.id, selected.toSet()).onSuccess { onClose() }.onFailure { error = it.message } } finally { localBusy = false } } }
        }, enabled = !busy && canAddMembers && selected.isNotEmpty()) { Text(if (localBusy) "Adding…" else "Add (${selected.size})") }
    }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { OutlinedTextField(query, { query = it }, label = { Text("Enter name, email or phone number") }, singleLine = true, leadingIcon = { Glyph("search") }, modifier = Modifier.fillMaxWidth().padding(16.dp)) }
            if (!canAddMembers) item { Text("Only the group owner can add members.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { ListItem(modifier = Modifier.clickable(enabled = !busy) { if (group != null) shareGroup() else shareFriend(null) }.heightIn(min = 56.dp), leadingContent = { Glyph("share") }, headlineContent = { Text("Share invite link") }) }
            item { ListItem(modifier = Modifier.clickable(enabled = !busy && canAddMembers) { runCatching { contacts.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }.onFailure { error = "No contacts app is available. Add your friend manually." } }.heightIn(min = 56.dp), leadingContent = { Glyph("friends") }, headlineContent = { Text("Add from contacts") }) }
            item { ListItem(modifier = Modifier.clickable(enabled = !busy && canAddMembers) { manualEntry() }.heightIn(min = 56.dp), leadingContent = { Glyph("add") }, headlineContent = { Text(if (group == null) "Add manually" else "Add a new person") }) }
            if (group != null || '@' in query) item { ListItem(modifier = Modifier.clickable(enabled = !busy && canAddMembers) { if (cloud.session == null) { emailAfterSignIn = true; signIn() } else manualEntry(true) }.heightIn(min = 56.dp), leadingContent = { Glyph("mail") }, headlineContent = { Text("Invite by email") }) }
            error?.let { text -> item { Text(text, Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Assertive }, color = MaterialTheme.colorScheme.error) } }
            notice?.let { text -> item { Text(text, Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.primary) } }
            if (group == null && cloud.friendClaims.isNotEmpty()) {
                item { SectionLabel("Confirm your friend") }
                items(cloud.friendClaims, key = { "claim:${it.invitationId}" }) { claim ->
                    val localName = book.people.find { it.id == claim.localPersonId }?.name ?: "your saved friend"
                    ListItem(leadingContent = { Avatar(claim.accountName) }, headlineContent = { Text("Connect ${claim.accountName} to $localName?") }, supportingContent = {
                        Column { Text(claim.email); Text("Confirm this is the same person to keep their existing expenses together.", style = MaterialTheme.typography.bodySmall) }
                    }, trailingContent = {
                        TextButton(onClick = {
                            if (!busy) { localBusy = true; error = null; scope.launch {
                                try { vm.cloud.confirmFriendClaim(claim.invitationId).onSuccess { notice = "$localName connected" }.onFailure { error = it.message } }
                                finally { localBusy = false }
                            } }
                        }, enabled = !busy) { Text("Connect") }
                    })
                }
            }
            if (group == null && cloud.friendInvitations.isNotEmpty()) {
                item { SectionLabel("Invitations") }
                items(cloud.friendInvitations, key = { "invite:${it.id}" }) { invite ->
                    ListItem(leadingContent = { Avatar(invite.name) }, headlineContent = { Text(if (invite.incoming) "${invite.name} invited you" else "Invite to ${invite.email.ifBlank { invite.name }}") }, supportingContent = { Text(when (invite.status) { "expired" -> "Expired"; "revoked" -> "Revoked"; "accepted" -> if (cloud.friendClaims.any { it.invitationId == invite.id }) "Accepted · confirm their identity" else "Connected"; else -> if (invite.incoming) "Accept to connect on Kitna" else "Waiting for acceptance" }) }, trailingContent = {
                        if (invite.status == "pending") TextButton(onClick = {
                            if (!busy) { localBusy = true; error = null; scope.launch {
                                try {
                                    val result = if (invite.incoming) vm.cloud.acceptFriendInvitation(invitationId = invite.id) else vm.cloud.revokeFriendInvitation(invite.id)
                                    result.onSuccess { notice = if (invite.incoming) "Friend connected" else "Invitation revoked" }.onFailure { error = it.message }
                                } finally { localBusy = false }
                            } }
                        }, enabled = !busy) { Text(if (invite.incoming) "Accept" else "Revoke") }
                    })
                }
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)); SectionLabel(if (group == null) "Your friends" else "Choose friends") }
            if (matches.isEmpty()) item { Text(if (query.isBlank()) "Add someone by name to start splitting expenses." else "No saved friends match. Add them by name or invite their email.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(matches, key = { it.id }) { person ->
                val added = person.id in members
                ListItem(modifier = Modifier.clickable(enabled = !busy && !added && canAddMembers) { if (group == null) editingId = person.id else selected = ArrayList(if (person.id in selected) selected - person.id else selected + person.id) }.heightIn(min = 64.dp), leadingContent = { Avatar(person.name) }, headlineContent = { Text(person.name) }, supportingContent = {
                    Text(when { added -> "Already in group"; person.accountId.isNotBlank() -> "Connected on Kitna"; person.email.isNotBlank() -> person.email; person.phone.isNotBlank() -> person.phone; else -> "Track expenses together" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }, trailingContent = {
                    if (group != null) Checkbox(checked = added || person.id in selected, onCheckedChange = if (added || busy || !canAddMembers) null else ({ checked -> selected = ArrayList(if (checked) selected + person.id else selected - person.id) }), enabled = !added && !busy && canAddMembers)
                    else TextButton({ shareFriend(person.id) }, enabled = !busy) { Text("Invite") }
                })
            }
        }
    }
    if (manual) ModalBottomSheet(onDismissRequest = { if (!busy) manual = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp, vertical = 12.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (inviteAfterSave) "Invite by email" else "Add a friend", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(name, { name = it; error = null }, label = { Text("Friend's name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (optional || inviteAfterSave) {
                OutlinedTextField(email, { email = it; error = null }, label = { Text(if (inviteAfterSave) "Google email" else "Email (optional)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                if (!inviteAfterSave) OutlinedTextField(phone, { phone = it; error = null }, label = { Text("Phone (optional)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            } else TextButton({ optional = true }) { Text("Add email or phone") }
            Text(if (inviteAfterSave) "They accept after signing in with this verified Google email. No email message is sent automatically." else "No account is needed. You can invite them later.", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
        if (!busy) { localBusy = true; error = null; scope.launch {
            try {
                val existing = book.people.find { it.id != "self" && ((email.isNotBlank() && it.email.equals(email.trim(), true)) || (phone.isNotBlank() && it.phone.filter(Char::isDigit) == phone.filter(Char::isDigit)) || it.name.equals(name.trim(), true)) }
                require(!inviteAfterSave || existing == null || existing.email.equals(email.trim(), true)) { "This name is already saved. Open that friend to add their email, then invite them." }
                val person = existing ?: Person(id = personId, name = name.trim(), email = email, phone = phone)
                if (existing == null) vm.savePerson(person).getOrThrow()
                if (inviteAfterSave) {
                    if (group != null) {
                        if (person.id !in members) vm.addGroupMembers(group.id, setOf(person.id)).getOrThrow()
                        vm.cloud.inviteEmail(group.id, person.id, person.name, email).getOrThrow()
                    } else vm.cloud.sendFriendEmail(person.id, email).getOrThrow()
                    notice = "Invitation ready. They can accept in Kitna."
                } else { if (group != null && person.id !in members) selected = ArrayList((selected + person.id).distinct()); notice = if (existing == null) "${person.name} added" else "${person.name} is already a friend" }
                manual = false
            } catch (failure: Exception) { if (failure is CancellationException) throw failure; error = failure.message ?: "Could not add this friend." }
            finally { localBusy = false }
        } }
    }, enabled = !busy && name.isNotBlank() && (!inviteAfterSave || email.isNotBlank()), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (busy) "Adding…" else if (inviteAfterSave) "Invite" else "Add friend") }
            TextButton({ manual = false }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel") }
        }
    }
}

internal fun shareInvitation(context: android.content.Context, message: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, message) }, "Invite with Kitna"))
}
