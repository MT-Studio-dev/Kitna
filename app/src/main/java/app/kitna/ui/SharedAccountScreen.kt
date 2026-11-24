@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.kitna.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kitna.KitnaViewModel
import app.kitna.core.Book
import app.kitna.core.SharedBooks
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Accept a copied invitation message, a Kitna deep link, or the token itself. */
internal fun sharedInviteToken(input: String): String {
    val value = input.trim()
    val link = Regex("app\\.kitna://join\\?[^\\s]+", RegexOption.IGNORE_CASE).find(value)?.value
    val token = if (link != null) Uri.parse(link).getQueryParameter("token").orEmpty() else value
    require(token.matches(Regex("[A-Za-z0-9_-]{20,512}"))) { "Paste a valid Kitna invitation link or code." }
    return token
}

@Composable fun SharedAccountScreen(book: Book, vm: KitnaViewModel, initialGroupId: String? = null, onBack: () -> Unit) {
    val state by vm.cloud.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var localBusy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var joinInput by rememberSaveable { mutableStateOf("") }
    var shareGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var discardGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var invitation by rememberSaveable { mutableStateOf<String?>(null) }
    var invitationGroup by rememberSaveable { mutableStateOf("") }
    var invitationPerson by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var removingGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var removingPersonId by rememberSaveable { mutableStateOf<String?>(null) }
    var leavingGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var revokingGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    val session = state.session
    val busy = state.busy || localBusy
    val groups = book.groups.filter { it.kind != "Direct" }.sortedWith(compareByDescending<app.kitna.core.Group> { it.id == initialGroupId }.thenBy { it.name.lowercase() })
    BackHandler { if (!busy) onBack() }
    LaunchedEffect(state.pendingInvite) { state.pendingInvite?.let { joinInput = it } }

    fun run(action: suspend () -> Result<Unit>, message: String? = null) {
        if (localBusy || state.busy) return
        localBusy = true; error = null; notice = null
        scope.launch {
            try {
                action().onSuccess { notice = message }.onFailure { error = it.message ?: "Could not complete this action. Please try again." }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not complete this action. Please try again." }
            finally { localBusy = false }
        }
    }
    fun invite(groupId: String, personId: String?) {
        if (localBusy || state.busy) return
        localBusy = true; error = null; notice = null
        scope.launch {
            try {
                vm.cloud.invite(groupId, personId).onSuccess {
                    invitation = it; invitationGroup = book.groupName(groupId)
                    invitationPerson = personId?.let(book::personName)
                }.onFailure { error = it.message ?: "Could not create this invitation." }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not create this invitation." }
            finally { localBusy = false }
        }
    }
    fun launchGoogle() {
        if (localBusy || state.busy) return
        localBusy = true; error = null; notice = null
        scope.launch {
            try {
                vm.cloud.beginSignIn().onSuccess { url ->
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        .onFailure { error = "Google sign-in could not open. Enable a browser and try again." }
                }.onFailure { error = it.message ?: "Could not start Google sign-in." }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not start Google sign-in." }
            finally { localBusy = false }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Shared groups") }, navigationIcon = { ActionIcon("back", "Back") { if (!busy) onBack() } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { MangaChapter("YOUR CONNECTED CREW", "SAME TRIP. EVERY PHONE.") }
            item {
                MangaPanel {
                    if (session == null) {
                        Text("Bring your friends into the same group", style = MaterialTheme.typography.titleLarge)
                        Text("Sign in with Google to share a group, invite friends and see each other's saved expenses, balances and receipts. Your existing local records stay available.")
                        Button(onClick = ::launchGoogle, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Continue with Google") }
                    } else {
                        Text(session.name.ifBlank { "Google account" }, style = MaterialTheme.typography.titleLarge)
                        Text(session.email, style = MaterialTheme.typography.bodyMedium)
                        when (state.ready) {
                            true -> Text("Shared-group service connected", style = MaterialTheme.typography.bodySmall, color = Owed)
                            false -> {
                                Text("Shared groups need project setup", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                Text("Ask the project owner to apply Kitna's shared-group database setup, then check the connection again.", style = MaterialTheme.typography.bodySmall)
                            }
                            null -> Text("Check the connection before sharing a group.", style = MaterialTheme.typography.bodySmall)
                        }
                        state.lastSync?.let { Text("Last synced: ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}", style = MaterialTheme.typography.bodySmall) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { run({ vm.cloud.sync() }) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.ready == true) "Sync now" else "Check connection") }
                            OutlinedButton(onClick = ::launchGoogle, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Reconnect Google") }
                            OutlinedButton(onClick = { confirmSignOut = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Sign out") }
                        }
                    }
                }
            }
            if (busy) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.size(24.dp)); Text("Working on your shared groups…", Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } }
            error?.let { text -> item { Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, color = MaterialTheme.colorScheme.error) } }
            state.message?.takeIf { it != error }?.let { text -> item { Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall) } }
            notice?.let { text -> item { Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.primary) } }
            if (session != null) {
                item {
                    MangaPanel {
                        Text("Join a friend's group", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                        Text("Paste the invitation they shared with you. You will join using the Google account shown above.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = joinInput, onValueChange = { joinInput = it; error = null }, label = { Text("Invitation link or code") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, maxLines = 4)
                        Button(onClick = {
                            run({
                                val token = try { sharedInviteToken(joinInput) } catch (failure: IllegalArgumentException) { return@run Result.failure(failure) }
                                vm.cloud.join(token).onSuccess { joinInput = "" }
                            }, "Group joined")
                        }, enabled = !busy && state.ready == true && joinInput.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Join group") }
                    }
                }
                item { Text("Your group connections", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge) }
                if (groups.isEmpty()) item { Text("Create a group from Groups, or paste an invitation above.") }
                items(groups, key = { it.id }) { group ->
                    val anyLink = book.sharedGroups.firstOrNull { it.groupId == group.id }
                    val link = anyLink?.takeIf { it.accountId == session.id }
                    val snapshot = link?.snapshot
                    val owner = snapshot?.group?.ownerId == session.id
                    val pending = book.pendingChanges.filter { it.groupId == group.id && it.accountId == session.id }
                    MangaPanel(color = if (group.id == initialGroupId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            GroupAvatar(group, 44)
                            Column(Modifier.weight(1f)) {
                                Text(group.name, style = MaterialTheme.typography.titleMedium)
                                Text(if (group.archived) "Archived group" else "${group.members.size} people", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        when {
                            anyLink != null && link == null -> Text("This shared group belongs to another signed-in account on this device. Sign in with that account to manage it.", style = MaterialTheme.typography.bodySmall)
                            link?.revoked == true -> Text("Your access to this shared group has ended. New changes cannot be sent. Ask the group owner for help.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            link == null -> {
                                Text("Local group · share this group when your crew is ready.", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { shareGroupId = group.id }, enabled = !busy && state.ready == true && !group.archived, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Share this group") }
                            }
                            else -> {
                                Text(if (snapshot == null) "Waiting for the first shared records" else if (pending.isEmpty()) "No changes waiting to send" else "${pending.size} changes waiting to send", style = MaterialTheme.typography.bodySmall, color = if (pending.isEmpty() && snapshot != null) Owed else MaterialTheme.colorScheme.primary)
                                pending.mapNotNull { it.error }.distinct().forEach { message -> Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                                pending.filter { it.conflict }.groupBy { "${it.kind}:${it.entityId}" }.values.forEach { drafts ->
                                    val draft = drafts.last()
                                    val remote = snapshot?.records?.find { it.kind == draft.kind && it.id == draft.entityId }?.payload
                                    val local = runCatching { SharedBooks.json.parseToJsonElement(draft.payload).jsonObject }.getOrNull()
                                    fun summary(payload: JsonObject?): String {
                                        if(payload==null)return "This record is not saved on the server."
                                        return buildString {
                                            listOf("description","name","text","date","notes").forEach { key -> payload[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { appendLine(it) } }
                                            payload["amount"]?.jsonPrimitive?.longOrNull?.let { appendLine("Amount: ${money(it)}") }
                                            listOf("payers" to "Paid by", "shares" to "Shares").forEach { (key,label) ->
                                                payload[key]?.jsonObject?.let { values -> appendLine("$label: " + values.entries.joinToString { (id,amount) -> "${book.personName(link.people.entries.firstOrNull { it.value==id }?.key?:id)} ${money(amount.jsonPrimitive.long)}" }) }
                                            }
                                        }.trim().ifBlank { payload.toString() }
                                    }
                                    HorizontalDivider()
                                    Text("Saved on the server",fontWeight=FontWeight.Bold)
                                    Text(summary(remote),style=MaterialTheme.typography.bodyMedium)
                                    Text("Your saved draft",fontWeight=FontWeight.Bold)
                                    val draftText=summary(local)
                                    Text(draftText,style=MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick={
                                        val clipboard=context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Kitna expense draft",draftText));notice="Draft copied. You can keep it before choosing the server version."
                                    },modifier=Modifier.heightIn(min=48.dp)){Text("Copy my draft")}
                                }
                                if (pending.isNotEmpty()) TextButton(onClick = { discardGroupId = group.id }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Discard unsent changes") }
                                group.members.forEach { personId ->
                                    val participant = snapshot?.participants?.firstOrNull { it.id == link.people[personId] }
                                    val active = participant?.active == true
                                    val connected = active && participant?.userId != null
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Column(Modifier.weight(1f)) {
                                            Text(book.personName(personId), fontWeight = FontWeight.SemiBold)
                                            Text(if (participant?.active == false) "Removed" else if (connected) "Account connected" else "Not connected yet", style = MaterialTheme.typography.bodySmall)
                                        }
                                        if (connected) Glyph("check", color = Owed)
                                    }
                                    if (owner && personId != "self" && participant != null && !connected) {
                                        OutlinedButton(onClick = { invite(group.id, personId) }, enabled = !busy && state.ready == true, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (active) "Invite ${book.personName(personId)}" else "Reinvite ${book.personName(personId)}") }
                                    }
                                    if (owner && personId != "self" && connected) {
                                        TextButton(onClick = { removingGroupId = group.id; removingPersonId = personId }, enabled = !busy && state.ready == true, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove ${book.personName(personId)}", color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                                if (owner) {
                                    OutlinedButton(onClick = { invite(group.id, null) }, enabled = !busy && state.ready == true && !group.archived, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Invite a new member") }
                                    TextButton(onClick = { revokingGroupId = group.id }, enabled = !busy && state.ready == true, modifier = Modifier.heightIn(min = 48.dp)) { Text("Revoke pending invitations") }
                                } else {
                                    Text("The group owner manages invitations.", style = MaterialTheme.typography.bodySmall)
                                    val ownParticipant = snapshot?.participants?.firstOrNull { it.id == link.myParticipantId }
                                    if (ownParticipant?.active == true && ownParticipant.userId == session.id) {
                                        if (pending.isNotEmpty()) Text("Sync or discard your pending changes before leaving.", style = MaterialTheme.typography.bodySmall)
                                        OutlinedButton(onClick = { leavingGroupId = group.id }, enabled = !busy && state.ready == true && pending.isEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Leave group") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Text("Shared records are visible to accepted group members. Offline changes stay pending until the server accepts them. Keep complete backups when moving phones.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }

    shareGroupId?.let { selectedId ->
        val group = book.groups.firstOrNull { it.id == selectedId }
        AlertDialog(onDismissRequest = { shareGroupId = null }, title = { Text("Share ${group?.name ?: "this group"}?") }, text = { Text("This group's people, expenses, balances, budget, repayments, comments, history and receipt photos will be stored in your Supabase project and visible to accepted members. Other local groups stay separate.") },
            confirmButton = { TextButton(onClick = { shareGroupId = null; run({ vm.cloud.shareGroup(selectedId) }, "Group shared. Create an invitation for each friend.") }, enabled = !busy) { Text("Share group") } }, dismissButton = { TextButton(onClick = { shareGroupId = null }) { Text("Cancel") } })
    }
    discardGroupId?.let { selectedId ->
        AlertDialog(onDismissRequest = { discardGroupId = null }, title = { Text("Discard unsent changes?") }, text = { Text("Your unsent changes in ${book.groupName(selectedId)} will be removed and replaced with the last saved shared records. Other members' saved changes stay in the group. Keep any notes you need before continuing.") },
            confirmButton = { TextButton(onClick = { discardGroupId = null; run({ vm.cloud.discardPending(selectedId) }, "Unsent changes discarded") }, enabled = !busy) { Text("Discard changes") } }, dismissButton = { TextButton(onClick = { discardGroupId = null }) { Text("Keep changes") } })
    }
    if (confirmSignOut) AlertDialog(onDismissRequest = { confirmSignOut = false }, title = { Text("Sign out of Google?") }, text = { Text("Shared groups will require sign-in again. Any unsent changes must be synchronized or discarded first. Your local expense book remains on this device.") },
        confirmButton = { TextButton(onClick = { confirmSignOut = false; run({ vm.cloud.signOut() }, "Signed out") }, enabled = !busy) { Text("Sign out") } }, dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } })
    removingGroupId?.let { selectedId ->
        val personId = removingPersonId
        if (personId != null) AlertDialog(onDismissRequest = { removingGroupId = null; removingPersonId = null }, title = { Text("Remove ${book.personName(personId)}?") }, text = { Text("Their past expenses, repayments and shares stay in ${book.groupName(selectedId)}. Their account will lose access to the shared group. You can create a new invitation later.") },
            confirmButton = { TextButton(onClick = { removingGroupId = null; removingPersonId = null; run({ vm.cloud.removeMember(selectedId, personId) }, "Member removed; their history is kept") }, enabled = !busy) { Text("Remove member", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { removingGroupId = null; removingPersonId = null }) { Text("Cancel") } })
    }
    leavingGroupId?.let { selectedId ->
        AlertDialog(onDismissRequest = { leavingGroupId = null }, title = { Text("Leave ${book.groupName(selectedId)}?") }, text = { Text("Other members keep the shared expense and repayment history. This group's cached records will be removed from your device, and you will lose shared-group access. Synchronize or discard unsent changes first.") },
            confirmButton = { TextButton(onClick = { leavingGroupId = null; run({ vm.cloud.leaveGroup(selectedId) }, "You left the group") }, enabled = !busy) { Text("Leave group", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { leavingGroupId = null }) { Text("Stay in group") } })
    }
    revokingGroupId?.let { selectedId ->
        AlertDialog(onDismissRequest = { revokingGroupId = null }, title = { Text("Revoke pending invitations?") }, text = { Text("Unused invitations for ${book.groupName(selectedId)} will stop working. Friends who have already joined keep their access. You can create new invitations afterwards.") },
            confirmButton = { TextButton(onClick = { revokingGroupId = null; run({ vm.cloud.revokeInvites(selectedId) }, "Pending invitations revoked") }, enabled = !busy) { Text("Revoke invitations") } }, dismissButton = { TextButton(onClick = { revokingGroupId = null }) { Text("Cancel") } })
    }
    invitation?.let { token ->
        val link = Uri.Builder().scheme("app.kitna").authority("join").appendQueryParameter("token", token).build().toString()
        AlertDialog(onDismissRequest = { invitation = null }, title = { Text("Invite to $invitationGroup") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(invitationPerson?.let { "This invitation connects an account to $it's existing shares. Send it only to $it." } ?: "Share this invitation with the friend you want to add to the group.")
                OutlinedTextField(value = link, onValueChange = {}, readOnly = true, label = { Text("Invitation link") }, maxLines = 4)
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Kitna invitation", link)); notice = "Invitation copied"
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Copy invitation") }
                Button(onClick = {
                    val text = "Join $invitationGroup on Kitna.\n$link\nOpen Kitna → Shared groups → Join group, then paste this invitation."
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Share Kitna invitation")) }
                        .onFailure { error = "No sharing app could open. Copy the invitation instead." }
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Share invitation") }
            }
        }, confirmButton = { TextButton(onClick = { invitation = null }) { Text("Done") } })
    }
}
