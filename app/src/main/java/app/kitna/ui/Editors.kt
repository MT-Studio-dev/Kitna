package app.kitna.ui

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

private val EditTeal = Color(0xFF159D82)
private val EditCategories = listOf("General", "Food", "Groceries", "Travel", "Rent", "Entertainment", "Utilities", "Shopping", "Transport", "Health", "Other")
private val TextMapSaver = Saver<Map<String, String>, ArrayList<String>>(
    save = { ArrayList(it.flatMap { entry -> listOf(entry.key, entry.value) }) },
    restore = { it.chunked(2).associate { entry -> entry[0] to entry[1] } }
)
private fun Book.editorName(id: String) = people.firstOrNull { it.id == id }?.name ?: "Unknown person"
private fun amountText(value: Long) = BigDecimal.valueOf(value, 2).toPlainString()
private fun editorMoney(value: Long): String = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-IN")).format(BigDecimal.valueOf(value, 2))
private fun formFingerprint(vararg fields: String) = fields.joinToString("") { "${it.length}:$it" }
private fun mapFingerprint(values: Map<String, String>) = values.toSortedMap().entries.joinToString("") { formFingerprint(it.key, it.value) }

@Composable private fun EditorScreen(
    title: String, busy: Boolean, error: String?, dirty: Boolean = false,
    onClose: () -> Unit, onSave: (() -> Unit)? = null, saveLabel: String = "Save",
    content: @Composable ColumnScope.() -> Unit
) {
    var discard by rememberSaveable { mutableStateOf(false) }
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
            error?.let { Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) { Text(it, Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer) } }
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard changes?") }, text = { Text("Your unsaved changes will be lost.") }, confirmButton = { TextButton(onClick = onClose) { Text("Discard") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}

@Composable private fun EditField(value: String, onChange: (String) -> Unit, label: String, money: Boolean = false, singleLine: Boolean = true, modifier: Modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = modifier, singleLine = singleLine, shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(keyboardType = if (money) KeyboardType.Decimal else KeyboardType.Text))
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

@Composable private fun EditSection(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

@Composable private fun PersonToggle(name: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(EditTeal.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) { Text(name.take(1).uppercase(), color = EditTeal, fontWeight = FontWeight.Bold) }
        Text(name, Modifier.weight(1f).padding(start = 12.dp))
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun splitInputs(total: Long, members: Set<String>, mode: String, inputs: Map<String, String>): Pair<Map<String, Long>, Map<String, Long>> {
    require(total > 0) { "Enter an amount greater than zero." }
    require(members.isNotEmpty()) { "Choose at least one person to split with." }
    val ordered = members.sorted()
    return when (mode) {
        "Equal" -> Accounting.split(total, ordered.associateWith { 1L }) to emptyMap()
        "Exact amounts" -> {
            val values = ordered.associateWith { Accounting.money(inputs[it].orEmpty().ifBlank { "0" }) }
            require(values.values.sum() == total) { "Exact amounts must add up to ${editorMoney(total)}." }
            values to values
        }
        "Percentages" -> {
            val values = ordered.associateWith { Accounting.money(inputs[it].orEmpty().ifBlank { "0" }) }
            require(values.values.sum() == 10000L) { "Percentages must add up to 100%." }
            Accounting.split(total, values) to values
        }
        "Adjustments" -> {
            val values = ordered.associateWith { Accounting.signedMoney(inputs[it].orEmpty().ifBlank { "0" }) }
            Accounting.adjustedSplit(total, values) to values
        }
        else -> {
            val values = ordered.associateWith { inputs[it].orEmpty().ifBlank { "1" }.toLongOrNull()?.also { count -> require(count in 0..1_000_000) { "Use whole-number shares from 0 to 1,000,000." } } ?: error("Shares must be whole numbers.") }
            Accounting.split(total, values) to values
        }
    }
}

@Composable fun ExpenseEditor(book: Book, existing: Expense?, initialGroupId: String?, vm: KitnaViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val initial = existing?.groupId ?: initialGroupId ?: book.groups.firstOrNull { !it.archived }?.id ?: book.people.firstOrNull { it.id != "self" }?.let { "friend:${it.id}" }.orEmpty()
    var contextId by rememberSaveable { mutableStateOf(initial) }
    val friend = contextId.takeIf { it.startsWith("friend:") }?.removePrefix("friend:")
    val group = if (friend != null) Group(id = contextId, name = book.editorName(friend), members = listOf("self", friend), kind = "Direct") else book.groups.firstOrNull { it.id == contextId }
    val members = group?.members.orEmpty()
    val validExisting = existing?.takeIf { it.groupId == contextId }
    val expenseId = rememberSaveable { existing?.id ?: newId() }
    var description by rememberSaveable { mutableStateOf(existing?.description.orEmpty()) }
    var amount by rememberSaveable { mutableStateOf(existing?.amount?.let(::amountText).orEmpty()) }
    var date by rememberSaveable { mutableStateOf(existing?.date ?: LocalDate.now().toString()) }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: "General") }
    var autoCategory by rememberSaveable { mutableStateOf(existing == null) }
    var notes by rememberSaveable { mutableStateOf(existing?.notes.orEmpty()) }
    var mode by rememberSaveable { mutableStateOf(existing?.splitMode?.takeIf { it in listOf("Equal", "Exact amounts", "Percentages", "Shares", "Adjustments") } ?: "Equal") }
    var payer by rememberSaveable(contextId) { mutableStateOf(validExisting?.payers?.keys?.firstOrNull() ?: "self") }
    var multiple by rememberSaveable(contextId) { mutableStateOf((validExisting?.payers?.size ?: 0) > 1) }
    var paid by rememberSaveable(contextId, stateSaver = TextMapSaver) { mutableStateOf<Map<String, String>>(validExisting?.payers?.mapValues { amountText(it.value) } ?: emptyMap()) }
    var participants by rememberSaveable(contextId) { mutableStateOf(ArrayList(validExisting?.shares?.keys ?: members)) }
    var values by rememberSaveable(contextId, stateSaver = TextMapSaver) {
        mutableStateOf<Map<String, String>>(if (validExisting == null) emptyMap() else if (mode == "Exact amounts") validExisting.shares.mapValues { amountText(it.value) } else validExisting.splitWeights.mapValues { if (mode in listOf("Percentages", "Adjustments")) amountText(it.value) else it.value.toString() })
    }
    var localBusy by remember { mutableStateOf(false) }
    val sharedBusy by vm.saving.collectAsStateWithLifecycle()
    val busy = localBusy || sharedBusy
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val contexts = book.groups.filter { !it.archived || it.id == existing?.groupId }.map { it.id to if (it.kind == "Direct") "With ${it.members.filter { id -> id != "self" }.joinToString { id -> book.editorName(id) }}" else it.name } +
        book.people.filter { it.id != "self" && (it.id == friend || book.groups.none { g -> g.kind == "Direct" && it.id in g.members && !g.archived }) }.map { "friend:${it.id}" to "With ${it.name}" }
    val preview = runCatching { splitInputs(Accounting.money(amount), participants.toSet(), mode, values).first }
    val currentDraft = formFingerprint(contextId, description, amount, date, category, autoCategory.toString(), notes, mode, payer, multiple.toString(), mapFingerprint(paid), formFingerprint(*participants.sorted().toTypedArray()), mapFingerprint(values))
    val initialDraft = rememberSaveable { currentDraft }
    EditorScreen(if (existing == null) "Add expense" else "Edit expense", busy, error, dirty = currentDraft != initialDraft, onClose = onClose, onSave = {
        scope.launch {
            localBusy = true; error = null
            runCatching {
                val currentGroup = requireNotNull(group) { "Add a friend or create a group first." }
                val total = Accounting.money(amount)
                val allocations = splitInputs(total, participants.toSet(), mode, values)
                val payers = if (multiple) members.associateWith { Accounting.money(paid[it].orEmpty().ifBlank { "0" }) }.filterValues { it > 0 } else mapOf(payer to total)
                val draft = Expense(id = expenseId, groupId = currentGroup.id, description = description.trim(), amount = total, payers = payers, shares = allocations.first, category = category, date = date, notes = notes.trim(), splitMode = mode, splitWeights = allocations.second)
                Accounting.validate(draft, currentGroup)
                val actualGroupId = if (friend == null) currentGroup.id else vm.ensureDirectGroup(friend).getOrThrow()
                vm.saveExpense(draft.copy(groupId = actualGroupId)).getOrThrow()
            }.onSuccess { onClose() }.onFailure { error = it.message ?: "Could not save this expense." }
            localBusy = false
        }
    }) {
        if (contexts.isEmpty()) Text("Add a friend first, or create a group from the Groups tab.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        EditChoice("With you and", contexts, contextId, { contextId = it }, enabled = existing == null)
        EditField(description, {
            description = it
            if (autoCategory) category = ExpenseCategories.suggest(it) ?: "General"
        }, "Description")
        EditChoice("Category", (EditCategories + category).distinct().map { it to it }, category, {
            category = it
            autoCategory = false
        }, optionIcon = { CategoryIcon(it, size = 28) })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Auto-categorize", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (autoCategory && ExpenseCategories.suggest(description) != null) "Suggested from your description"
                    else if (autoCategory) "Uses words in your description"
                    else "Your chosen category will stay unchanged",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = autoCategory, onCheckedChange = {
                autoCategory = it
                if (it) category = ExpenseCategories.suggest(description) ?: "General"
            }, modifier = Modifier.semantics { contentDescription = "Auto-categorize expenses" })
        }
        OutlinedTextField(value = amount, onValueChange = { amount = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Total amount") }, prefix = { Text("₹ ") }, singleLine = true, shape = RoundedCornerShape(12.dp), textStyle = LocalTextStyle.current.copy(fontSize = 30.sp, fontWeight = FontWeight.Medium), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Multiple people paid", Modifier.weight(1f)); Switch(checked = multiple, onCheckedChange = { multiple = it }, modifier = Modifier.semantics { contentDescription = "Multiple people paid" })
        }
        if (!multiple) EditChoice("Paid by", members.map { it to book.editorName(it) }, payer, { payer = it })
        else EditSection("Who paid?", "Contributions must equal the total amount.") {
            val summary = runCatching {
                val total = Accounting.money(amount)
                require(total > 0) { "Enter a bill total greater than zero." }
                PayerAllocation.summary(total, members.map { Accounting.money(paid[it].orEmpty().ifBlank { "0" }) })
            }
            summary.getOrNull()?.let { allocation ->
                Surface(color = if (allocation.remaining < 0) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (allocation.remaining < 0) "Overpaid: ${editorMoney(allocation.overpaid)}"
                            else "Remaining: ${editorMoney(allocation.remaining)}",
                            fontWeight = FontWeight.SemiBold,
                            color = if (allocation.remaining < 0) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text("Entered ${editorMoney(allocation.entered)} of ${editorMoney(Accounting.money(amount))}", fontSize = 12.sp)
                    }
                }
            }
            if (summary.isFailure) Text(
                if (amount.isBlank()) "Enter the bill total to see the remaining amount."
                else summary.exceptionOrNull()?.message ?: "Check the amounts entered for each payer.",
                fontSize = 12.sp, color = if (amount.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )
            members.forEach { id ->
                EditField(paid[id].orEmpty(), { paid = paid + (id to it) }, "${book.editorName(id)} paid · ₹", money = true)
                val available = runCatching {
                    val total = Accounting.money(amount)
                    require(total > 0)
                    PayerAllocation.summary(total, members.filter { it != id }.map { Accounting.money(paid[it].orEmpty().ifBlank { "0" }) }).remaining.also { require(it >= 0) }
                }.getOrNull()
                TextButton(onClick = { available?.let { paid = paid + (id to amountText(it)) } }, enabled = available != null && !busy,
                    modifier = Modifier.align(Alignment.End).semantics { contentDescription = "Use remaining for ${book.editorName(id)}" }) {
                    Text("Use remaining")
                }
            }
        }
        EditSection("Split between", "Choose the people included in this expense.") {
            EditChoice("Split method", listOf("Equal", "Exact amounts", "Percentages", "Shares", "Adjustments").map { it to it }, mode, { mode = it; values = emptyMap() })
            if (mode == "Adjustments") Text("Enter an extra or reduced amount for each person. The remaining amount is split equally.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            members.forEach { id ->
                PersonToggle(book.editorName(id), id in participants, onChange = { selected -> participants = ArrayList(if (selected) participants + id else participants - id) })
                if (id in participants && mode != "Equal") EditField(values[id].orEmpty(), { values = values + (id to it) }, when (mode) { "Percentages" -> "Percentage · %"; "Shares" -> "Shares · default 1"; "Adjustments" -> "Adjustment · ₹ (use - to reduce)"; else -> "Exact amount · ₹" }, money = mode != "Adjustments")
            }
            HorizontalDivider()
            preview.getOrNull()?.forEach { (id, share) -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(book.editorName(id), fontSize = 13.sp); Text(editorMoney(share), color = EditTeal, fontWeight = FontWeight.SemiBold) } }
            if (amount.isNotBlank() && preview.isFailure) Text(preview.exceptionOrNull()?.message ?: "Check the split amounts.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        EditDate(date) { date = it }
        EditField(notes, { notes = it }, "Notes (optional)", singleLine = false)
    }
}

@Composable fun PersonEditor(book: Book, existing: Person?, vm: KitnaViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val personId = rememberSaveable { existing?.id ?: newId() }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var localBusy by remember { mutableStateOf(false) }
    val sharedBusy by vm.saving.collectAsStateWithLifecycle()
    val busy = localBusy || sharedBusy
    var remove by rememberSaveable { mutableStateOf(false) }
    EditorScreen(if (existing == null) "Add a friend" else if (existing.id == "self") "Your profile" else "Edit friend", busy, error, dirty = name != existing?.name.orEmpty(), onClose = onClose, onSave = {
        scope.launch { localBusy = true; error = null; vm.savePerson(Person(id = personId, name = name)).onSuccess { onClose() }.onFailure { error = it.message }; localBusy = false }
    }) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Box(Modifier.size(80.dp).background(EditTeal.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) { Text(name.take(1).uppercase().ifBlank { "+" }, fontSize = 32.sp, color = EditTeal, fontWeight = FontWeight.SemiBold) } }
        EditField(name, { name = it }, if (existing?.id == "self") "Your name" else "Friend's name")
        Text("Names help you keep track of who paid and who owes. Everything stays on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        if (existing != null && existing.id != "self") TextButton(onClick = { remove = true }, enabled = !busy) { Text("Delete friend", color = MaterialTheme.colorScheme.error) }
    }
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("Delete ${existing?.name}?") }, text = { Text("Friends referenced by groups or financial history cannot be deleted. Rename them instead to preserve your records.") }, confirmButton = { TextButton(onClick = {
        remove = false; scope.launch { localBusy = true; vm.deletePerson(existing!!.id).onSuccess { onClose() }.onFailure { error = it.message }; localBusy = false }
    }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { remove = false }) { Text("Cancel") } })
}

@Composable fun GroupEditor(book: Book, existing: Group?, vm: KitnaViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val groupId = rememberSaveable { existing?.id ?: newId() }
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var members by rememberSaveable { mutableStateOf(ArrayList(existing?.members ?: listOf("self"))) }
    var simplify by rememberSaveable { mutableStateOf(existing?.simplifyDebts ?: true) }
    var kind by rememberSaveable { mutableStateOf(existing?.kind ?: "Group") }
    var icon by rememberSaveable { mutableStateOf(existing?.icon ?: "group") }
    var colour by rememberSaveable { mutableStateOf(existing?.color ?: "teal") }
    var appearanceOpen by rememberSaveable { mutableStateOf(false) }
    var pickerIcon by rememberSaveable { mutableStateOf(icon) }
    var pickerColour by rememberSaveable { mutableStateOf(colour) }
    var newFriendName by rememberSaveable { mutableStateOf("") }
    var newFriendId by rememberSaveable { mutableStateOf(newId()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var localBusy by remember { mutableStateOf(false) }
    val sharedBusy by vm.saving.collectAsStateWithLifecycle()
    val busy = localBusy || sharedBusy
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    EditorScreen(if (existing == null) "Create a group" else "Group settings", busy, error, dirty = name != existing?.name.orEmpty() || members.toSet() != (existing?.members?.toSet() ?: setOf("self")) || simplify != (existing?.simplifyDebts ?: true) || kind != (existing?.kind ?: "Group") || icon != (existing?.icon ?: "group") || colour != (existing?.color ?: "teal") || newFriendName.isNotBlank(), onClose = onClose, onSave = {
        if (newFriendName.isNotBlank()) error = "Add the new friend to the group, or clear their name before saving."
        else scope.launch { localBusy = true; error = null; vm.saveGroup(Group(id = groupId, name = name.trim(), members = members.distinct(), archived = existing?.archived ?: false, kind = kind, simplifyDebts = simplify, icon = icon, color = colour)).onSuccess { onClose() }.onFailure { error = it.message }; localBusy = false }
    }) {
        EditField(name, { name = it }, "Group name")
        if (existing?.kind != "Direct") EditChoice("Group type", listOf("Group" to "Other", "Trip" to "Trip", "Home" to "Home", "Couple" to "Couple"), kind, { kind = it })
        EditSection("Group appearance") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GroupStylePreview(icon = icon, color = colour)
                Column(Modifier.weight(1f)) {
                    Text(name.ifBlank { "Your group" }, fontWeight = FontWeight.SemiBold)
                    Text("${icon.replaceFirstChar { it.uppercase() }} · ${colour.replaceFirstChar { it.uppercase() }}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedButton(onClick = { pickerIcon = icon; pickerColour = colour; appearanceOpen = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Choose icon and colour") }
        }
        EditSection("Members", "Keep people with existing expenses in the group to preserve its history.") {
            book.people.filter { existing?.kind != "Direct" || it.id in members }.forEach { person -> PersonToggle(person.name, person.id in members, enabled = person.id != "self" && existing?.kind != "Direct", onChange = { checked -> if (person.id != "self" && existing?.kind != "Direct") members = ArrayList(if (checked) members + person.id else members - person.id) }) }
            if (existing?.kind != "Direct") {
                HorizontalDivider()
                if (book.people.size < 2) Text("Add a friend below to start your group.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                EditField(newFriendName, { newFriendName = it }, "New friend's name")
                OutlinedButton(onClick = {
                    scope.launch {
                        localBusy = true; error = null
                        vm.savePerson(Person(id = newFriendId, name = newFriendName)).onSuccess {
                            members = ArrayList((members + newFriendId).distinct())
                            newFriendName = ""; newFriendId = newId()
                        }.onFailure { error = it.message }
                        localBusy = false
                    }
                }, enabled = newFriendName.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) { Text("Add friend to group") }
            }
        }
        EditSection("Simplify debts", "Suggest repayments based on everyone's net balance, so fewer payments are needed.") {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Simplify group debts", Modifier.weight(1f)); Switch(simplify, { simplify = it }) }
            Text("Turn this off to keep repayments between the original payers and participants.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (existing != null) {
            OutlinedButton(onClick = { confirm = "archive" }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (existing.archived) "Restore group" else "Archive group") }
            TextButton(onClick = { confirm = "delete" }, enabled = !busy) { Text("Delete empty group", color = MaterialTheme.colorScheme.error) }
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
                                    Text(id.replaceFirstChar { it.uppercase() }, fontSize = 10.sp, maxLines = 1)
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
                Text("${pickerColour.replaceFirstChar { it.uppercase() }} selected", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable fun SettlementEditor(book: Book, existing: Settlement?, initialGroupId: String?, initialFrom: String?, initialTo: String?, vm: KitnaViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val id = rememberSaveable { existing?.id ?: newId() }
    val options = book.groups.filter { !it.archived || it.id == existing?.groupId }
    val requested = initialGroupId?.let { initial ->
        if (initial.startsWith("friend:")) options.firstOrNull { it.kind == "Direct" && initial.removePrefix("friend:") in it.members }?.id ?: initial else initial
    }
    val initial = existing?.groupId ?: requested ?: options.firstOrNull()?.id ?: book.people.firstOrNull { it.id != "self" }?.let { "friend:${it.id}" }.orEmpty()
    var groupId by rememberSaveable { mutableStateOf(initial) }
    val friend = groupId.takeIf { it.startsWith("friend:") }?.removePrefix("friend:")
    val group = if (friend != null && book.people.any { it.id == friend }) Group(id = groupId, name = book.editorName(friend), members = listOf("self", friend), kind = "Direct") else options.firstOrNull { it.id == groupId }
    val contexts = options.map { it.id to if (it.kind == "Direct") "With ${it.members.filter { id -> id != "self" }.joinToString { id -> book.editorName(id) }}" else it.name } +
        book.people.filter { it.id != "self" && (it.id == friend || options.none { g -> g.kind == "Direct" && it.id in g.members }) }.map { "friend:${it.id}" to "With ${it.name}" }
    val transfers = group?.takeIf { friend == null }?.let { Accounting.transfers(book, it.id) }.orEmpty()
    var from by rememberSaveable(groupId) { mutableStateOf(existing?.from ?: initialFrom?.takeIf { it in group?.members.orEmpty() } ?: transfers.firstOrNull()?.from ?: "self") }
    var to by rememberSaveable(groupId) { mutableStateOf(existing?.to ?: initialTo?.takeIf { it in group?.members.orEmpty() } ?: transfers.firstOrNull()?.to ?: group?.members?.firstOrNull { it != from }.orEmpty()) }
    var amount by rememberSaveable(groupId) { mutableStateOf(existing?.amount?.let(::amountText) ?: transfers.firstOrNull { it.from == from && it.to == to }?.amount?.let(::amountText).orEmpty()) }
    var method by rememberSaveable { mutableStateOf(existing?.method ?: "Cash") }
    var date by rememberSaveable { mutableStateOf(existing?.date ?: LocalDate.now().toString()) }
    var reference by rememberSaveable { mutableStateOf(existing?.reference.orEmpty()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var localBusy by remember { mutableStateOf(false) }
    val sharedBusy by vm.saving.collectAsStateWithLifecycle()
    val busy = localBusy || sharedBusy
    var remove by rememberSaveable { mutableStateOf(false) }
    val currentDraft = formFingerprint(groupId, from, to, amount, method, date, reference)
    val initialDraft = rememberSaveable { currentDraft }
    EditorScreen(if (existing == null) "Settle up" else "Edit repayment", busy, error, dirty = currentDraft != initialDraft, onClose = onClose, onSave = {
        scope.launch {
            localBusy = true; error = null
            runCatching {
                val currentGroup = requireNotNull(group) { "Add a friend or create a group first." }
                val draft = Settlement(id = id, groupId = groupId, from = from, to = to, amount = Accounting.money(amount), method = method, date = date, reference = reference.trim())
                BookValidation.settlement(draft, currentGroup)
                val actualGroupId = if (friend == null) currentGroup.id else vm.ensureDirectGroup(friend).getOrThrow()
                vm.saveSettlement(draft.copy(groupId = actualGroupId)).getOrThrow()
            }.onSuccess { onClose() }.onFailure { error = it.message }; localBusy = false
        }
    }) {
        Text("Record a payment", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Keep track of money already paid in cash, UPI or a bank transfer.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        EditChoice("Group or friend", contexts, groupId, { groupId = it }, enabled = existing == null)
        EditChoice("Paid by", group?.members.orEmpty().map { it to book.editorName(it) }, from, { selected -> from = selected; if (to == selected) to = group?.members?.firstOrNull { it != selected }.orEmpty() })
        EditChoice("Paid to", group?.members.orEmpty().filter { it != from }.map { it to book.editorName(it) }, to, { to = it })
        EditField(amount, { amount = it }, "Amount · ₹", money = true)
        EditChoice("Payment method", listOf("Cash", "UPI", "Bank transfer", "Other").map { it to it }, method, { method = it })
        EditDate(date) { date = it }
        EditField(reference, { reference = it }, "Reference or note (optional)")
        Text("This only updates your records. Kitna does not transfer money or verify the payment.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (existing != null) TextButton(onClick = { remove = true }, enabled = !busy) { Text("Delete repayment", color = MaterialTheme.colorScheme.error) }
    }
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("Delete this repayment?") }, text = { Text("Balances will be recalculated. Its history will remain available.") }, confirmButton = { TextButton(onClick = { remove = false; scope.launch { localBusy = true; vm.deleteSettlement(existing!!.id).onSuccess { onClose() }.onFailure { error = it.message }; localBusy = false } }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { remove = false }) { Text("Cancel") } })
}

@Composable fun ExpenseDetails(book: Book, expenseId: String, vm: KitnaViewModel, onEdit: (Expense) -> Unit, onClose: () -> Unit) {
    val expense = book.expenses.firstOrNull { it.id == expenseId }
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
    var confirmEdit by rememberSaveable { mutableStateOf(false) }
    EditorScreen("Expense details", busy, error, dirty = comment.isNotBlank(), onClose = onClose, onSave = if (expense != null && !expense.deleted && book.groups.none { it.id == expense.groupId && it.archived }) ({ if (comment.isNotBlank()) confirmEdit = true else onEdit(expense) }) else null, saveLabel = "Edit") {
        if (expense == null) Text("This expense is no longer available.")
        else {
            Text(expense.description, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text(editorMoney(expense.amount), fontSize = 35.sp, color = EditTeal, fontWeight = FontWeight.SemiBold)
            Text("${book.groups.firstOrNull { it.id == expense.groupId }?.name.orEmpty()} · ${expense.date} · ${expense.category}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            if (expense.deleted) Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) { Text("Deleted expense · excluded from balances", Modifier.fillMaxWidth().padding(12.dp)) }
            EditSection("Paid by") { expense.payers.forEach { (id, value) -> AmountRow(book.editorName(id), editorMoney(value)) } }
            EditSection("Split ${when (expense.splitMode) { "Equal" -> "equally"; "Percentages" -> "by percentage"; "Shares" -> "by shares"; "Adjustments" -> "with adjustments"; else -> "by exact amounts" }}") { expense.shares.forEach { (id, value) -> AmountRow(book.editorName(id), editorMoney(value)) } }
            if (expense.notes.isNotBlank()) EditSection("Notes") { Text(expense.notes) }
            val revisions = book.revisions.filter { it.expense.id == expenseId }.sortedByDescending { it.time }
            if (revisions.isNotEmpty()) EditSection("Edit history", "Previous versions stay available when an expense changes.") {
                revisions.forEach { revision ->
                    var expanded by rememberSaveable(revision.id) { mutableStateOf(false) }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(revision.reason, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(revision.time)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(if (expanded) "Hide" else "View", color = EditTeal, fontSize = 12.sp)
                        }
                        if (expanded) {
                            Text(revision.expense.description, fontWeight = FontWeight.Medium)
                            AmountRow(revision.expense.date, editorMoney(revision.expense.amount))
                            Text("${revision.expense.category} · ${revision.expense.splitMode}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Paid by", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            revision.expense.payers.forEach { (id, amount) -> AmountRow(book.editorName(id), editorMoney(amount)) }
                            Text("Shares", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            revision.expense.shares.forEach { (id, amount) -> AmountRow(book.editorName(id), editorMoney(amount)) }
                            if (revision.expense.notes.isNotBlank()) Text(revision.expense.notes, fontSize = 12.sp)
                            if (!expense.deleted && book.groups.none { it.id == expense.groupId && it.archived }) TextButton(onClick = { revisionToRestore = revision.id }, enabled = !busy) { Text("Restore this version") }
                        }
                        HorizontalDivider()
                    }
                }
            }
            EditSection("Comments") {
                val comments = book.comments.filter { it.expenseId == expenseId }.sortedBy { it.time }
                if (comments.isEmpty()) Text("No comments yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                comments.forEach { entry -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(book.editorName(entry.authorId), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(entry.text)
                    Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(entry.time)), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                if (!expense.deleted && book.groups.none { it.id == expense.groupId && it.archived }) {
                    EditChoice("Comment as", book.people.filter { it.id in book.groups.firstOrNull { g -> g.id == expense.groupId }?.members.orEmpty() }.map { it.id to it.name }, author, { author = it })
                    EditField(comment, { comment = it }, "Write a comment", singleLine = false)
                    Button(onClick = { scope.launch { localBusy = true; vm.saveComment(Comment(id = commentDraftId, expenseId = expenseId, text = comment, time = commentDraftTime, authorId = author)).onSuccess { comment = ""; commentDraftId = newId(); commentDraftTime = System.currentTimeMillis() }.onFailure { error = it.message }; localBusy = false } }, enabled = comment.isNotBlank() && !busy) { Text("Add comment") }
                }
            }
            if (book.groups.none { it.id == expense.groupId && it.archived }) {
                if (expense.deleted) OutlinedButton(onClick = { scope.launch { localBusy = true; vm.restoreExpense(expense.id).onFailure { error = it.message }; localBusy = false } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Restore expense") }
                else TextButton(onClick = { remove = true }, enabled = !busy) { Text("Delete expense", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
    if (remove && expense != null) AlertDialog(onDismissRequest = { remove = false }, title = { Text("Delete this expense?") }, text = { Text("Balances will update. You can restore it from its history.") }, confirmButton = { TextButton(onClick = { remove = false; scope.launch { localBusy = true; vm.deleteExpense(expense).onFailure { error = it.message }; localBusy = false } }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { remove = false }) { Text("Cancel") } })
    if (revisionToRestore != null) AlertDialog(onDismissRequest = { revisionToRestore = null }, title = { Text("Restore this version?") }, text = { Text("The expense and its balances will use this previous version. Your current version will stay in edit history.") }, confirmButton = { TextButton(onClick = {
        val revision = book.revisions.firstOrNull { it.id == revisionToRestore }; revisionToRestore = null
        if (revision != null) scope.launch { localBusy = true; vm.saveExpense(revision.expense.copy(deleted = false)).onFailure { error = it.message }; localBusy = false }
    }) { Text("Restore version") } }, dismissButton = { TextButton(onClick = { revisionToRestore = null }) { Text("Cancel") } })
    if (confirmEdit && expense != null) AlertDialog(onDismissRequest = { confirmEdit = false }, title = { Text("Discard unsent comment?") }, text = { Text("Your comment has not been saved. Discard it to edit the expense.") }, confirmButton = { TextButton(onClick = { confirmEdit = false; onEdit(expense) }) { Text("Discard and edit") } }, dismissButton = { TextButton(onClick = { confirmEdit = false }) { Text("Keep comment") } })
}

@Composable private fun AmountRow(label: String, amount: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Text(amount, fontWeight = FontWeight.SemiBold) }
}
