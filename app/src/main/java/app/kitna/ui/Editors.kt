package app.kitna.ui

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.error as semanticError
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kitna.KitnaViewModel
import app.kitna.core.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private fun Book.editorName(id: String) = people.firstOrNull { it.id == id }?.name ?: "Unknown person"
private fun amountText(value: Long) = BigDecimal.valueOf(value, 2).toPlainString()
private fun editorMoney(value: Long): String = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).format(BigDecimal.valueOf(value, 2))
private fun formFingerprint(vararg fields: String) = fields.joinToString("") { "${it.length}:$it" }

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun EditorScreen(
    title: String, busy: Boolean, error: String?, dirty: Boolean = false,
    onClose: () -> Unit, onSave: (() -> Unit)? = null, saveLabel: String = "Save",
    content: @Composable ColumnScope.() -> Unit
) {
    var discard by rememberSaveable { mutableStateOf(false) }
    val errorBring = remember { BringIntoViewRequester() }
    val errorFocus = remember { FocusRequester() }
    LaunchedEffect(error) {
        if (error != null) { errorBring.bringIntoView(); errorFocus.requestFocus() }
    }
    val close = { if (!busy) { if (dirty) discard = true else onClose() } }
    BackHandler { close() }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        Surface(shadowElevation = 1.dp) {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = close, enabled = !busy) { Text("Cancel") }
                    Text(title, Modifier.weight(1f).padding(horizontal = 4.dp), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    if (onSave != null) TextButton(onClick = onSave, enabled = !busy) { Text(if (busy) "Saving…" else saveLabel, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            error?.let { Surface(Modifier.bringIntoViewRequester(errorBring).focusRequester(errorFocus).focusable().semantics { liveRegion = LiveRegionMode.Assertive }, color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(4.dp)) { Text(it, Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer) } }
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard changes?") }, text = { Text("Your unsaved changes will be lost.") }, confirmButton = { TextButton(onClick = onClose) { Text("Discard") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}

@Composable private fun EditField(value: String, onChange: (String) -> Unit, label: String, money: Boolean = false, singleLine: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth(), fieldError: String? = null) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = modifier.semantics { fieldError?.let { semanticError(it) } }, isError = fieldError != null,
        supportingText = if (fieldError == null) null else ({ Text(fieldError, Modifier.semantics { liveRegion = LiveRegionMode.Assertive }) }),
        singleLine = singleLine, shape = RoundedCornerShape(4.dp), keyboardOptions = KeyboardOptions(keyboardType = if (money) KeyboardType.Decimal else KeyboardType.Text))
}

@Composable private fun FieldProblem(message: String?) {
    if (message != null) Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Assertive; semanticError(message) }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable private fun EditChoice(label: String, options: List<Pair<String, String>>, value: String, onChange: (String) -> Unit, enabled: Boolean = true, optionIcon: (@Composable (String) -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(16.dp)) {
                optionIcon?.let { it(value); Spacer(Modifier.width(12.dp)) }
                Text(options.firstOrNull { it.first == value }?.second ?: "Choose…", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                Text("⌄", fontSize = 20.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 350.dp)) {
                options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, leadingIcon = if (optionIcon == null) null else ({ optionIcon(id) }), onClick = { expanded = false; onChange(id) }) }
            }
        }
    }
}

@Composable private fun EditDate(value: String, onChange: (String) -> Unit) {
    val context = LocalContext.current
    val selected = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
    OutlinedButton(onClick = {
        DatePickerDialog(context, { _, year, month, day -> onChange(LocalDate.of(year, month + 1, day).toString()) }, selected.year, selected.monthValue - 1, selected.dayOfMonth).show()
    }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(16.dp)) {
        Text("Date", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(selected.format(DateTimeFormatter.ofPattern("d MMM yyyy")), color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable private fun EditSection(title: String, subtitle: String? = null, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

@Composable private fun PersonToggle(name: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) { Text(name.take(1).uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
        Text(name, Modifier.weight(1f).padding(start = 12.dp))
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable fun PersonEditor(book: Book, existing: Person?, vm: KitnaViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val personId = rememberSaveable { existing?.id ?: newId() }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var email by rememberSaveable { mutableStateOf(existing?.email.orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var localBusy by remember { mutableStateOf(false) }
    val sharedBusy by vm.saving.collectAsStateWithLifecycle()
    val busy = localBusy || sharedBusy
    var remove by rememberSaveable { mutableStateOf(false) }
    EditorScreen(if (existing == null) "Add a friend" else if (existing.id == "self") "Your profile" else "Edit friend", busy, error, dirty = name != existing?.name.orEmpty() || email != existing?.email.orEmpty(), onClose = onClose, onSave = {
        scope.launch { localBusy = true; error = null; vm.savePerson(existing?.copy(name = name, email = email) ?: Person(id = personId, name = name, email = email)).onSuccess { onClose() }.onFailure { error = it.message }; localBusy = false }
    }) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Box(Modifier.size(80.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) { Text(name.take(1).uppercase().ifBlank { "+" }, fontSize = 32.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) } }
        EditField(name, { name = it }, if (existing?.id == "self") "Your name" else "Friend's name")
        if (existing?.id != "self") OutlinedTextField(email, { email = it }, label = { Text("Friend's Google email (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("A name is all you need to track expenses. Add an email later when you want to invite them.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
        if (existing != null && existing.id != "self") TextButton(onClick = { remove = true }, enabled = !busy) { Text("Delete friend", color = MaterialTheme.colorScheme.error) }
    }
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("Delete ${existing?.name}?") }, text = { Text("Friends referenced by groups or financial history cannot be deleted. Rename them instead to preserve your records.") }, confirmButton = { TextButton(onClick = {
        remove = false; scope.launch { localBusy = true; vm.deletePerson(existing!!.id).onSuccess { onClose() }.onFailure { error = it.message }; localBusy = false }
    }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { remove = false }) { Text("Cancel") } })
}

@Composable fun GroupEditor(book: Book, existing: Group?, vm: KitnaViewModel, onCreated: ((String, Boolean) -> Unit)? = null, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sharedGroup = existing != null && book.sharedGroups.any { it.groupId == existing.id }
    val originalJson by rememberSaveable { mutableStateOf<String?>(existing?.let { Json.encodeToString(it) }) }
    val originalGroup = remember(originalJson) { originalJson?.let { Json.decodeFromString<Group>(it) } }
    val groupId = rememberSaveable { existing?.id ?: newId() }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var members by rememberSaveable { mutableStateOf(ArrayList(existing?.members ?: listOf("self"))) }
    var simplify by rememberSaveable { mutableStateOf(existing?.simplifyDebts ?: true) }
    var kind by rememberSaveable { mutableStateOf(existing?.kind ?: "Group") }
    var icon by rememberSaveable { mutableStateOf(existing?.icon ?: "group") }
    var colour by rememberSaveable { mutableStateOf(existing?.color ?: "teal") }
    var budgetInput by rememberSaveable { mutableStateOf(existing?.budget?.let(::amountText).orEmpty()) }
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var pickerIcon by rememberSaveable { mutableStateOf(icon) }
    var pickerColour by rememberSaveable { mutableStateOf(colour) }
    var newFriendName by rememberSaveable { mutableStateOf("") }
    var newFriendEmail by rememberSaveable { mutableStateOf("") }
    var membersOpen by rememberSaveable { mutableStateOf(existing != null) }
    var optionsOpen by rememberSaveable { mutableStateOf(existing != null) }
    var newFriendId by rememberSaveable { mutableStateOf(newId()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var localBusy by remember { mutableStateOf(false) }
    val sharedBusy by vm.saving.collectAsStateWithLifecycle()
    val busy = localBusy || sharedBusy
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    EditorScreen(if (existing == null) "Create a group" else "Group settings", busy, error, dirty = name != existing?.name.orEmpty() || members.toSet() != (existing?.members?.toSet() ?: setOf("self")) || simplify != (existing?.simplifyDebts ?: true) || kind != (existing?.kind ?: "Group") || icon != (existing?.icon ?: "group") || colour != (existing?.color ?: "teal") || budgetInput != existing?.budget?.let(::amountText).orEmpty() || newFriendName.isNotBlank(), onClose = onClose, onSave = {
        if (newFriendName.isNotBlank() || newFriendEmail.isNotBlank()) error = "Add the new friend to the group, or clear their details before saving."
        else scope.launch {
            localBusy = true; error = null
            runCatching {
                val budget = budgetInput.takeIf { it.isNotBlank() }?.let { Accounting.money(it) }
                require(budget == null || budget > 0) { "Enter a positive budget, or leave it blank." }
                vm.saveGroup(Group(id = groupId, name = name.trim(), members = members.distinct(), archived = existing?.archived ?: false, kind = kind, simplifyDebts = simplify, icon = icon, color = colour, budget = budget), original = originalGroup).getOrThrow()
            }.onSuccess { if (existing == null && onCreated != null) onCreated(groupId, false) else onClose() }.onFailure { error = it.message ?: "Could not save group." }
            localBusy = false
        }
    }) {
        EditField(name, { name = it }, "Group name")
        if (existing?.kind != "Direct") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GroupStylePreview(icon = icon, color = colour)
                TextButton(onClick = { pickerIcon = icon; pickerColour = colour; appearanceOpen = true }, enabled = !busy) { Text("Choose icon and colour") }
            }
            EditChoice("Group type (optional)", listOf("Group" to "Other", "Trip" to "Trip", "Home" to "Home", "Couple" to "Couple", "Friends" to "Friends"), kind, { kind = it })
        }
        TextButton(onClick = { membersOpen = !membersOpen }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Glyph("groups", color = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
            Text(if (membersOpen) "Hide members" else "Add members · ${members.size} selected")
        }
        if (existing == null) Text("You can add friends and share an invitation after creating the group.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (membersOpen) EditSection("Members", if (sharedGroup) "Use invitations in Shared groups to connect another person's account. Existing participants stay with the group's history." else "Keep people with existing expenses in the group to preserve its history.") {
            book.people.filter { if (sharedGroup) it.id in members else existing?.kind != "Direct" || it.id in members }.forEach { person -> PersonToggle(person.name, person.id in members, enabled = !sharedGroup && person.id != "self" && existing?.kind != "Direct", onChange = { checked -> if (!sharedGroup && person.id != "self" && existing?.kind != "Direct") members = ArrayList(if (checked) members + person.id else members - person.id) }) }
            if (existing?.kind != "Direct" && !sharedGroup) {
                HorizontalDivider()
                if (book.people.size < 2) Text("You can add someone now or invite friends later.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                EditField(newFriendName, { newFriendName = it }, "New friend's name")
                OutlinedTextField(newFriendEmail, { newFriendEmail = it }, label = { Text("Friend's Google email (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    scope.launch {
                        localBusy = true; error = null
                        vm.savePerson(Person(id = newFriendId, name = newFriendName, email = newFriendEmail)).onSuccess {
                            members = ArrayList((members + newFriendId).distinct())
                            newFriendName = ""; newFriendEmail = ""; newFriendId = newId()
                        }.onFailure { error = it.message }
                        localBusy = false
                    }
                }, enabled = newFriendName.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text("Add friend to group") }
            }
        }
        TextButton(onClick = { optionsOpen = !optionsOpen }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (optionsOpen) "Hide more options" else "More options") }
        if (optionsOpen) {
        if (existing?.kind != "Direct") EditSection("Trip budget", "An optional total spending limit for this group.") { EditField(budgetInput, { budgetInput = it }, "Trip budget · ₹ (optional)", money = true) }
        EditSection("Simplify debts", "Suggest repayments based on everyone's net balance, so fewer payments are needed.") {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Simplify group debts", Modifier.weight(1f)); Switch(simplify, { simplify = it }) }
            Text("Turn this off to keep repayments between the original payers and participants.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        }
        if (existing != null) {
            OutlinedButton(onClick = { confirm = "archive" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (existing.archived) "Restore group" else "Archive group") }
            if (!sharedGroup) TextButton(onClick = { confirm = "delete" }, enabled = !busy) { Text("Delete empty group", color = MaterialTheme.colorScheme.error) }
        }
    }
    if (appearanceOpen) AlertDialog(
        onDismissRequest = { appearanceOpen = false },
        title = { Text("Group appearance") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { GroupStylePreview(icon = pickerIcon, color = pickerColour, size = 72) }
                Text("Choose an icon", fontWeight = FontWeight.SemiBold)
                GroupAppearance.icons.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { id ->
                            val selected = pickerIcon == id
                            Surface(
                                modifier = Modifier.weight(1f).height(64.dp)
                                    .selectable(selected = selected, role = Role.RadioButton, onClick = { pickerIcon = id })
                                    .semantics { contentDescription = "Group icon $id" },
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    GroupSymbol(id, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(id.replaceFirstChar { it.uppercase() }, fontSize = 12.sp, maxLines = 1)
                                }
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text("Choose a colour", fontWeight = FontWeight.SemiBold)
                GroupAppearance.colors.keys.toList().chunked(5).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { key ->
                            Box(
                                Modifier.weight(1f).height(52.dp)
                                    .selectable(selected = pickerColour == key, role = Role.RadioButton, onClick = { pickerColour = key })
                                    .semantics { contentDescription = "Group colour $key" },
                                contentAlignment = Alignment.Center
                            ) {
                                Box(Modifier.size(38.dp).background(groupColor(key), CircleShape).border(if (pickerColour == key) 3.dp else 1.dp, if (pickerColour == key) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape), contentAlignment = Alignment.Center) {
                                    if (pickerColour == key) Glyph("check", color = Color.White)
                                }
                            }
                        }
                        repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Text("${pickerColour.replaceFirstChar { it.uppercase() }} selected", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { icon = pickerIcon; colour = pickerColour; appearanceOpen = false }) { Text("Done") } },
        dismissButton = { TextButton(onClick = { appearanceOpen = false }) { Text("Cancel") } }
    )
    if (confirm != null) AlertDialog(onDismissRequest = { confirm = null }, title = { Text(if (confirm == "delete") "Delete this group?" else if (existing?.archived == true) "Restore this group?" else "Archive this group?") }, text = { Text(if (confirm == "delete") "Only a group without financial history can be deleted." else "The group's records will remain on your device. Outstanding debts must be settled before archiving.") }, confirmButton = { TextButton(onClick = {
        val action = confirm; confirm = null
        scope.launch { localBusy = true; val result = if (action == "delete") vm.deleteGroup(existing!!.id) else vm.archive(existing!!); result.onSuccess { onClose() }.onFailure { error = it.message }; localBusy = false }
    }) { Text("Confirm") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
}

@Composable fun ExpenseDetails(book: Book, expenseId: String, vm: KitnaViewModel, onEdit: (Expense) -> Unit, onDuplicate: ((Expense) -> Unit)? = null, onClose: () -> Unit) {
    val expense = book.expenses.firstOrNull { it.id == expenseId }
    val sharedExpense = book.sharedGroups.any { it.groupId == expense?.groupId }
    val scope = rememberCoroutineScope()
    var comment by rememberSaveable(expenseId) { mutableStateOf("") }
    var author by rememberSaveable(expenseId) { mutableStateOf("self") }
    var commentDraftId by rememberSaveable(expenseId) { mutableStateOf(newId()) }
    var commentDraftTime by rememberSaveable(expenseId) { mutableLongStateOf(System.currentTimeMillis()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var localBusy by remember { mutableStateOf(false) }
    val sharedBusy by vm.saving.collectAsStateWithLifecycle()
    val busy = localBusy || sharedBusy
    var remove by rememberSaveable { mutableStateOf(false) }
    var revisionToRestore by rememberSaveable { mutableStateOf<String?>(null) }
    var actionOriginal by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmEdit by rememberSaveable { mutableStateOf(false) }
    EditorScreen("Expense details", busy, error, dirty = comment.isNotBlank(), onClose = onClose, onSave = if (expense != null && !expense.deleted && book.groups.none { it.id == expense.groupId && it.archived }) ({ if (comment.isNotBlank()) confirmEdit = true else onEdit(expense) }) else null, saveLabel = "Edit") {
        if (expense == null) Text("This expense is no longer available.")
        else {
            Text(expense.description, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text(editorMoney(expense.amount), fontSize = 35.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text("${book.groups.firstOrNull { it.id == expense.groupId }?.name.orEmpty()} · ${expense.date} · ${expense.category}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            if (onDuplicate != null && !expense.deleted && book.groups.none { it.id == expense.groupId && it.archived }) OutlinedButton(onClick = { if (comment.isBlank()) onDuplicate(expense) else error = "Save or clear your comment before duplicating this expense." }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Glyph("add"); Spacer(Modifier.width(8.dp)); Text("Duplicate expense") }
            if (expense.deleted) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) { Text("Deleted expense · excluded from balances", Modifier.fillMaxWidth().padding(12.dp)) }
            EditSection("Paid by") { expense.payers.forEach { (id, value) -> AmountRow(book.editorName(id), editorMoney(value)) } }
            EditSection("Split ${when (expense.splitMode) { "Equal" -> "equally"; "Percentages" -> "by percentage"; "Shares" -> "by shares"; "Adjustments" -> "with adjustments"; else -> "by exact amounts" }}") { expense.shares.forEach { (id, value) -> AmountRow(book.editorName(id), editorMoney(value)) } }
            if (expense.notes.isNotBlank()) EditSection("Notes") { Text(expense.notes) }
            if (expense.receipts.isNotEmpty()) EditSection("Receipt photos") { ReceiptGallery(expense.receipts) }
            val revisions = book.revisions.filter { it.expense.id == expenseId }.sortedByDescending { it.time }
            if (revisions.isNotEmpty()) EditSection("Edit history", "Previous versions stay available when an expense changes.") {
                revisions.forEach { revision ->
                    var expanded by rememberSaveable(revision.id) { mutableStateOf(false) }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(revision.reason, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(revision.time)), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(if (expanded) "Hide" else "View", color = MaterialTheme.colorScheme.primary, fontSize = 14.sp)
                        }
                        if (expanded) {
                            Text(revision.expense.description, fontWeight = FontWeight.Medium)
                            AmountRow(revision.expense.date, editorMoney(revision.expense.amount))
                            Text("${revision.expense.category} · ${revision.expense.splitMode}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Paid by", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            revision.expense.payers.forEach { (id, amount) -> AmountRow(book.editorName(id), editorMoney(amount)) }
                            Text("Shares", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            revision.expense.shares.forEach { (id, amount) -> AmountRow(book.editorName(id), editorMoney(amount)) }
                            if (revision.expense.notes.isNotBlank()) Text(revision.expense.notes, fontSize = 14.sp)
                            if (!expense.deleted && book.groups.none { it.id == expense.groupId && it.archived }) TextButton(onClick = { actionOriginal = Json.encodeToString(expense); revisionToRestore = revision.id }, enabled = !busy) { Text("Restore this version") }
                        }
                        HorizontalDivider()
                    }
                }
            }
            EditSection("Comments") {
                val comments = book.comments.filter { it.expenseId == expenseId }.sortedBy { it.time }
                if (comments.isEmpty()) Text("No comments yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                comments.forEach { entry -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(book.editorName(entry.authorId), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(entry.text)
                    Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(entry.time)), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                if (!expense.deleted && book.groups.none { it.id == expense.groupId && it.archived }) {
                    if (sharedExpense) Text("Posting as your signed-in account", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else EditChoice("Comment as", book.people.filter { it.id in book.groups.firstOrNull { g -> g.id == expense.groupId }?.members.orEmpty() }.map { it.id to it.name }, author, { author = it })
                    EditField(comment, { comment = it }, "Write a comment", singleLine = false)
                    Button(onClick = { scope.launch { localBusy = true; vm.saveComment(Comment(id = commentDraftId, expenseId = expenseId, text = comment, time = commentDraftTime, authorId = if (sharedExpense) "self" else author)).onSuccess { comment = ""; commentDraftId = newId(); commentDraftTime = System.currentTimeMillis() }.onFailure { error = it.message }; localBusy = false } }, enabled = comment.isNotBlank() && !busy) { Text("Add comment") }
                }
            }
            if (book.groups.none { it.id == expense.groupId && it.archived }) {
                if (expense.deleted) OutlinedButton(onClick = { scope.launch { localBusy = true; vm.restoreExpense(expense.id).onFailure { error = it.message }; localBusy = false } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Restore expense") }
                else TextButton(onClick = { actionOriginal = Json.encodeToString(expense); remove = true }, enabled = !busy) { Text("Delete expense", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (remove && expense != null) AlertDialog(onDismissRequest = { remove = false }, title = { Text("Delete this expense?") }, text = { Text("Balances will update. You can restore it from its history.") }, confirmButton = { TextButton(onClick = { remove = false; val original = actionOriginal?.let { Json.decodeFromString<Expense>(it) } ?: expense; scope.launch { localBusy = true; vm.deleteExpense(original).onFailure { error = it.message }; localBusy = false } }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { remove = false }) { Text("Cancel") } })
    if (revisionToRestore != null) AlertDialog(onDismissRequest = { revisionToRestore = null }, title = { Text("Restore this version?") }, text = { Text("The expense and its balances will use this previous version. Your current version will stay in edit history.") }, confirmButton = { TextButton(onClick = {
        val revision = book.revisions.firstOrNull { it.id == revisionToRestore }; revisionToRestore = null
        if (revision != null) scope.launch { localBusy = true; vm.saveExpense(revision.expense.copy(deleted = false), original = actionOriginal?.let { Json.decodeFromString<Expense>(it) }).onFailure { error = it.message }; localBusy = false }
    }) { Text("Restore version") } }, dismissButton = { TextButton(onClick = { revisionToRestore = null }) { Text("Cancel") } })
    if (confirmEdit && expense != null) AlertDialog(onDismissRequest = { confirmEdit = false }, title = { Text("Discard unsent comment?") }, text = { Text("Your comment has not been saved. Discard it to edit the expense.") }, confirmButton = { TextButton(onClick = { confirmEdit = false; onEdit(expense) }) { Text("Discard and edit") } }, dismissButton = { TextButton(onClick = { confirmEdit = false }) { Text("Keep comment") } })
}

@Composable private fun AmountRow(label: String, amount: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Text(amount, fontWeight = FontWeight.SemiBold) }
}
