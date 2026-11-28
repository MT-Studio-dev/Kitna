package app.kitna.ui

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.error as semanticError
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kitna.KitnaViewModel
import app.kitna.core.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val ExpenseTextMapSaver = Saver<Map<String, String>, ArrayList<String>>(
    save = { ArrayList(it.flatMap { entry -> listOf(entry.key, entry.value) }) },
    restore = { it.chunked(2).associate { entry -> entry[0] to entry[1] } }
)
private val ExpensePhotosSaver = Saver<List<ReceiptPhoto>, String>(save = { Json.encodeToString(it) }, restore = { Json.decodeFromString(it) })
private val ExpensePeopleSaver = Saver<List<Person>, String>(save = { Json.encodeToString(it) }, restore = { Json.decodeFromString(it) })
private val expenseModes = listOf("Equal", "Exact amounts", "Percentages", "Shares", "Adjustments")
private val expenseCategories = listOf("General", "Food", "Transport", "Shopping", "Groceries", "Entertainment", "Accommodation", "Utilities", "Rent", "Travel", "Health", "Other")
private fun entryAmount(value: Long) = BigDecimal.valueOf(value, 2).toPlainString()

/** All calculations stay in paise; Accounting resolves remainders by stable participant ID. */
internal fun expenseAllocations(total: Long, people: Set<String>, mode: String, inputs: Map<String, String>): Pair<Map<String, Long>, Map<String, Long>> {
    require(total > 0) { "Enter an amount greater than zero." }
    require(people.isNotEmpty()) { "Choose at least one person to split with." }
    val ordered = people.sorted()
    return when (mode) {
        "Equal" -> Accounting.split(total, ordered.associateWith { 1L }) to emptyMap()
        "Exact amounts" -> {
            val values = ordered.associateWith { Accounting.money(inputs[it].orEmpty().ifBlank { "0" }) }
            require(values.values.fold(0L, Math::addExact) == total) { "Exact amounts must add up to ${money(total)}." }
            values to values
        }
        "Percentages" -> {
            val values = ordered.associateWith { Accounting.money(inputs[it].orEmpty().ifBlank { "0" }) }
            require(values.values.fold(0L, Math::addExact) == 10000L) { "Percentages must add up to 100%." }
            Accounting.split(total, values) to values
        }
        "Adjustments" -> {
            val values = ordered.associateWith { Accounting.signedMoney(inputs[it].orEmpty().ifBlank { "0" }) }
            Accounting.adjustedSplit(total, values) to values
        }
        "Shares" -> {
            val values = ordered.associateWith { inputs[it].orEmpty().ifBlank { "1" }.toLongOrNull()?.also { count -> require(count in 0..1_000_000) { "Use whole-number shares from 0 to 1,000,000." } } ?: error("Shares must be whole numbers.") }
            Accounting.split(total, values) to values
        }
        else -> error("Choose a split method.")
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun ExpenseEditor(book: Book, existing: Expense?, initialGroupId: String?, vm: KitnaViewModel, duplicate: Boolean = false, onSaved: (() -> Unit)? = null, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val originalJson by rememberSaveable { mutableStateOf<String?>(existing?.let { Json.encodeToString(it) }) }
    val original = remember(originalJson) { originalJson?.let { Json.decodeFromString<Expense>(it) } }
    val initialContext = existing?.groupId ?: initialGroupId.orEmpty()
    var contextId by rememberSaveable { mutableStateOf(initialContext.takeUnless { it.startsWith("friend:") }.orEmpty()) }
    val initialMembers = book.groups.find { it.id == initialContext }?.members ?: listOfNotNull("self", initialContext.takeIf { it.startsWith("friend:") }?.removePrefix("friend:"))
    var companions by rememberSaveable { mutableStateOf(ArrayList(initialMembers.filter { it != "self" })) }
    var createdPeople by rememberSaveable(stateSaver = ExpensePeopleSaver) { mutableStateOf(emptyList()) }
    val allPeople = (book.people + createdPeople).distinctBy { it.id }
    fun personName(id: String) = allPeople.find { it.id == id }?.name ?: "Unknown person"
    val group = book.groups.find { it.id == contextId }
    val namedGroup = group?.takeUnless { it.kind in setOf("Direct", "Non-group", "Personal") }
    val link = book.sharedGroups.find { it.groupId == contextId }
    val inactive = link?.snapshot?.participants.orEmpty().filterNot { it.active }.mapNotNull { p -> link?.people?.entries?.find { it.value == p.id }?.key }.toSet()
    val members = (group?.members ?: listOf("self") + companions).filter { (existing != null && !duplicate) || it !in inactive }.distinct()
    val expenseId = rememberSaveable { if (duplicate) newId() else existing?.id ?: newId() }
    var description by rememberSaveable { mutableStateOf(existing?.description.orEmpty()) }
    var amount by rememberSaveable { mutableStateOf(existing?.amount?.let(::entryAmount).orEmpty()) }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: "General") }
    var autoCategory by rememberSaveable { mutableStateOf(existing == null) }
    var date by rememberSaveable { mutableStateOf(if (duplicate) LocalDate.now().toString() else existing?.date ?: LocalDate.now().toString()) }
    var notes by rememberSaveable { mutableStateOf(existing?.notes.orEmpty()) }
    var photos by rememberSaveable(stateSaver = ExpensePhotosSaver) { mutableStateOf(if (duplicate) emptyList() else existing?.receipts.orEmpty()) }
    var mode by rememberSaveable { mutableStateOf(existing?.splitMode?.takeIf { it in expenseModes } ?: "Equal") }
    var payer by rememberSaveable { mutableStateOf(existing?.payers?.keys?.firstOrNull() ?: "self") }
    var multiple by rememberSaveable { mutableStateOf((existing?.payers?.size ?: 0) > 1) }
    var paid by rememberSaveable(stateSaver = ExpenseTextMapSaver) { mutableStateOf(existing?.payers?.mapValues { entryAmount(it.value) } ?: emptyMap()) }
    var participants by rememberSaveable { mutableStateOf(ArrayList(existing?.shares?.keys ?: members)) }
    var values by rememberSaveable(stateSaver = ExpenseTextMapSaver) { mutableStateOf(if (existing == null) emptyMap() else if (mode == "Exact amounts") existing.shares.mapValues { entryAmount(it.value) } else existing.splitWeights.mapValues { if (mode in listOf("Percentages", "Adjustments")) entryAmount(it.value) else it.value.toString() }) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var newName by rememberSaveable { mutableStateOf("") }
    var newEmail by rememberSaveable { mutableStateOf("") }
    var newPhone by rememberSaveable { mutableStateOf("") }
    var friendDetails by rememberSaveable { mutableStateOf(false) }
    var localBusy by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var receiptBusy by remember { mutableStateOf(false) }
    val saving by vm.saving.collectAsStateWithLifecycle()
    val busy = localBusy || saving || receiptBusy || completed
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    var problemField by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val descriptionFocus = remember { FocusRequester() }
    val amountFocus = remember { FocusRequester() }
    var validationAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(validationAttempt) {
        if (validationAttempt > 0) when (problemField) { "description" -> descriptionFocus.requestFocus(); "amount" -> amountFocus.requestFocus() }
    }
    val currentDraft = Json.encodeToString(listOf(contextId, companions.joinToString(), description, amount, category, autoCategory.toString(), date, notes, mode, payer, multiple.toString(), Json.encodeToString(paid), participants.sorted().joinToString(), Json.encodeToString(values), Json.encodeToString(photos)))
    val initialDraft = rememberSaveable { currentDraft }
    val close = { if (!busy) { if (duplicate || currentDraft != initialDraft) discard = true else onClose() } }
    BackHandler(enabled = sheet == null) { close() }
    fun clearProblem(field: String) { if (problemField == field) { problem = null; problemField = null } }
    fun chooseContext(id: String) {
        contextId = id
        val selected = book.groups.find { it.id == id }?.members ?: (listOf("self") + companions)
        val active = selected.filter { person -> book.sharedGroups.find { it.groupId == id }?.let { shared -> shared.snapshot?.participants?.find { it.id == shared.people[person] }?.active } != false }
        participants = ArrayList(active); payer = "self"; multiple = false; paid = emptyMap(); values = emptyMap(); mode = "Equal"
        clearProblem("participants"); sheet = null; query = ""
    }
    fun toggleFriend(id: String) {
        contextId = ""
        val selected = id !in companions
        companions = ArrayList(if (selected) companions + id else companions - id)
        participants = ArrayList(if (selected) (participants + id).distinct() else participants - id)
        if (!selected && payer == id) payer = "self"
        clearProblem("participants")
    }
    fun newFriend() {
        newName = query.takeUnless { '@' in it || it.any(Char::isDigit) }.orEmpty()
        newEmail = query.takeIf { '@' in it }.orEmpty()
        newPhone = query.takeIf { it.isNotBlank() && '@' !in it && it.any(Char::isDigit) }.orEmpty()
        friendDetails = newEmail.isNotEmpty() || newPhone.isNotEmpty()
        sheet = "newFriend"
    }
    val allocations = runCatching { expenseAllocations(Accounting.money(amount), participants.filter { it in members }.toSet(), mode, values) }
    val save = {
        if (!localBusy && !saving && !receiptBusy && !completed) {
            // Set synchronously before launching: a second tap cannot enqueue another save.
            localBusy = true; problem = null; problemField = null
            scope.launch {
                var checking: String? = "description"
                runCatching {
                    require(description.isNotBlank() && description.trim().length <= 500) { "Enter a description up to 500 characters." }
                    checking = "amount"
                    val total = Accounting.money(amount)
                    require(total > 0) { "Enter a positive amount." }
                    checking = "participants"
                    require(members.isNotEmpty()) { "Choose who is sharing this expense." }
                    checking = "split"
                    val split = expenseAllocations(total, participants.filter { it in members }.toSet(), mode, values)
                    checking = "payers"
                    val payers = if (multiple) members.associateWith { Accounting.money(paid[it].orEmpty().ifBlank { "0" }) }.filterValues { it > 0 } else mapOf(payer to total)
                    require(payers.keys.all { it in members } && payers.values.fold(0L, Math::addExact) == total) { "Payer contributions must equal the expense total." }
                    checking = "notes"
                    require(notes.trim().length <= 4000) { "Notes must be at most 4,000 characters." }
                    val draft = Expense(expenseId, contextId, description.trim(), total, payers, split.first, category, date, notes = notes.trim(), splitMode = mode, splitWeights = split.second, receipts = photos)
                    Accounting.validate(draft, group ?: Group(id = "", name = "Friends", members = members))
                    checking = null
                    if (group == null) vm.saveNonGroupExpense(draft, members.toSet()).getOrThrow()
                    else vm.saveExpense(draft, original = if (duplicate) null else original).getOrThrow()
                }.onSuccess { completed = true; keyboard?.hide(); onSaved?.invoke(); onClose() }.onFailure {
                    problem = it.message ?: "Could not save this expense. Your draft is kept."; problemField = checking; validationAttempt++
                }
                localBusy = false
            }
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { Surface { Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close, enabled = !busy, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Back" }) { Glyph("back") }
            Text(if (duplicate) "Duplicate expense" else if (existing == null) "Add expense" else "Edit expense", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { save() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Save expense" }) {
                if (localBusy || saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Glyph("check", color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp)); Text("Save")
            }
        } } },
        bottomBar = { Surface(tonalElevation = 2.dp) { Row(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { sheet = "group" }, enabled = !busy && (existing == null || duplicate), modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Glyph("groups", color = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(6.dp)); Text(namedGroup?.name ?: "Choose group", maxLines = 2, style = MaterialTheme.typography.labelLarge)
            }
            IconButton(onClick = {
                val selected = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
                DatePickerDialog(context, { _, year, month, day -> date = LocalDate.of(year, month + 1, day).toString() }, selected.year, selected.monthValue - 1, selected.dayOfMonth).show()
            }, enabled = !busy, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Expense date, $date" }) { ExpenseToolbarIcon("calendar") }
            IconButton(onClick = { sheet = "receipt" }, enabled = !busy, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Receipt photos, ${photos.size} attached" }) { ExpenseToolbarIcon("camera") }
            IconButton(onClick = { sheet = "notes" }, enabled = !busy, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Notes" }) { ExpenseToolbarIcon("notes") }
        } } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("With you and:", style = MaterialTheme.typography.bodyLarge)
            if (namedGroup != null) InputChip(selected = true, onClick = { sheet = "split" }, label = { Text(if (participants.toSet() == members.toSet()) "All of ${namedGroup.name}" else "${participants.size} in ${namedGroup.name}") }, leadingIcon = { Glyph("groups") })
            else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    members.filter { it != "self" }.forEach { id -> InputChip(selected = true, onClick = { if (existing == null || duplicate) toggleFriend(id) else sheet = "split" }, label = { Text(personName(id)) }, trailingIcon = if (existing == null || duplicate) ({ Glyph("close", Modifier.size(16.dp)) }) else null) }
                }
                if (existing == null || duplicate) {
                    TextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Participants") }, placeholder = { Text("Enter names, emails, or phone numbers") }, singleLine = true, colors = entryFieldColors(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { descriptionFocus.requestFocus() }))
                    if (query.isNotBlank()) {
                        val matches = allPeople.filter { it.id != "self" && (it.name.contains(query, true) || it.email.contains(query, true) || it.phone.contains(query, true)) && it.id !in companions }.take(5)
                        matches.forEach { p -> ListItem(modifier = Modifier.clickable { toggleFriend(p.id); query = "" }, headlineContent = { Text(p.name) }, leadingContent = { Avatar(p.name, size = 36) }) }
                        book.groups.filter { !it.archived && it.kind !in setOf("Direct", "Non-group", "Personal") && it.name.contains(query, true) }.take(3).forEach { g -> ListItem(modifier = Modifier.clickable { chooseContext(g.id) }, headlineContent = { Text(g.name) }, leadingContent = { Glyph("groups") }) }
                        TextButton(onClick = { newFriend() }) { Glyph("add"); Spacer(Modifier.width(8.dp)); Text("Add a new friend") }
                    } else if (companions.isEmpty()) TextButton(onClick = { sheet = "people" }) { Text("Choose friends") }
                }
            }
            EntryProblem(problem.takeIf { problemField == "participants" })
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(onClick = { sheet = "category" }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Category: $category" }) { CategoryIcon(category, size = 32) }
                TextField(description, { description = it; clearProblem("description"); if (autoCategory) category = ExpenseCategories.suggest(it) ?: "General" }, Modifier.weight(1f).focusRequester(descriptionFocus), label = { Text("Description") }, placeholder = { Text("Enter a description") }, singleLine = true, isError = problemField == "description", supportingText = if (problemField == "description") ({ EntryProblem(problem) }) else null, colors = entryFieldColors(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { amountFocus.requestFocus() }))
            }
            Text(category, Modifier.padding(start = 60.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("₹", Modifier.width(48.dp), fontSize = 34.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextField(amount, { amount = it; clearProblem("amount") }, Modifier.weight(1f).focusRequester(amountFocus).semantics { if (problemField == "amount") problem?.let { semanticError(it) } }, label = { Text("Total amount") }, placeholder = { Text("0.00") }, textStyle = MaterialTheme.typography.headlineLarge.copy(fontSize = 42.sp, fontWeight = FontWeight.Medium), singleLine = true, isError = problemField == "amount", supportingText = if (problemField == "amount") ({ EntryProblem(problem) }) else null, colors = entryFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }))
            }
            FlowRow(Modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Paid by", Modifier.padding(vertical = 14.dp))
                TextButton(onClick = { sheet = "payers" }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Choose payer" }) { Text(if (multiple) "multiple people" else if (payer == "self") "you" else personName(payer), fontWeight = FontWeight.SemiBold) }
                Text("and split", Modifier.padding(vertical = 14.dp))
                TextButton(onClick = { sheet = "split" }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Choose split method" }) { Text(when(mode) { "Equal" -> "equally"; "Exact amounts" -> "by amount"; "Percentages" -> "by percentage"; "Shares" -> "by shares"; else -> "with adjustments" }, fontWeight = FontWeight.SemiBold) }
            }
            EntryProblem(problem.takeIf { problemField in listOf("split", "payers") })
            if (date != LocalDate.now().toString()) Text(LocalDate.parse(date).format(DateTimeFormatter.ofPattern("d MMM yyyy")), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (photos.isNotEmpty()) Text("${photos.size} receipt ${if (photos.size == 1) "photo" else "photos"} attached", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (notes.isNotBlank()) Text(notes, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            EntryProblem(problem.takeIf { problemField == null || problemField == "notes" })
            Spacer(Modifier.height(24.dp))
        }
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { if (!receiptBusy && !localBusy) sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when(sheet) { "payers" -> "Who paid?"; "split" -> "Split expense"; "category" -> "Category"; "group" -> "Choose group"; "notes" -> "Notes"; "receipt" -> "Receipt photos"; "newFriend" -> "Add a friend"; else -> "Choose friends" }, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { if (!receiptBusy && !localBusy) sheet = null }) { Text("Done") }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when(sheet) {
                "payers" -> {
                    members.forEach { id -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { payer = id; multiple = false; clearProblem("payers"); sheet = null }, verticalAlignment = Alignment.CenterVertically) { RadioButton(!multiple && payer == id, onClick = { payer = id; multiple = false; clearProblem("payers"); sheet = null }); Text(if (id == "self") "You" else personName(id)) } }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { multiple = !multiple; clearProblem("payers") }, verticalAlignment = Alignment.CenterVertically) { Checkbox(multiple, onCheckedChange = { multiple = it; clearProblem("payers") }, modifier = Modifier.semantics { contentDescription = "Multiple people paid" }); Text("Multiple people paid") }
                    if (multiple) {
                        val summary = runCatching { PayerAllocation.summary(Accounting.money(amount), members.map { Accounting.money(paid[it].orEmpty().ifBlank { "0" }) }) }
                        summary.getOrNull()?.let { Text(if (it.remaining < 0) "Overpaid: ${money(it.overpaid)}" else "Remaining: ${money(it.remaining)}", color = if (it.remaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
                        members.forEach { id ->
                            OutlinedTextField(paid[id].orEmpty(), { paid = paid + (id to it); clearProblem("payers") }, label = { Text("${personName(id)} paid · ₹") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                            val remaining = runCatching { PayerAllocation.summary(Accounting.money(amount), members.filter { it != id }.map { Accounting.money(paid[it].orEmpty().ifBlank { "0" }) }).remaining.also { require(it >= 0) } }.getOrNull()
                            TextButton(onClick = { remaining?.let { paid = paid + (id to entryAmount(it)); clearProblem("payers") } }, enabled = remaining != null, modifier = Modifier.align(Alignment.End).semantics { contentDescription = "Use remaining for ${personName(id)}" }) { Text("Use remaining") }
                        }
                        EntryProblem(summary.exceptionOrNull()?.message.takeIf { amount.isNotBlank() })
                    }
                }
                "split" -> {
                    var expanded by remember { mutableStateOf(false) }
                    Box { OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(mode); Spacer(Modifier.weight(1f)); Glyph("expand") }; DropdownMenu(expanded, { expanded = false }) { expenseModes.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { if (mode != choice) { mode = choice; values = emptyMap() }; clearProblem("split"); expanded = false }) } } }
                    if (mode == "Adjustments") Text("Add an extra or reduced amount. The remainder is shared equally.", style = MaterialTheme.typography.bodyMedium)
                    members.forEach { id ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { participants = ArrayList(if (id in participants) participants - id else participants + id); clearProblem("split") }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(id in participants, onCheckedChange = { selected -> participants = ArrayList(if (selected) (participants + id).distinct() else participants - id); clearProblem("split") }, modifier = Modifier.semantics { contentDescription = "Include ${personName(id)}" })
                            Text(personName(id), Modifier.weight(1f)); allocations.getOrNull()?.first?.get(id)?.let { Text(money(it), fontWeight = FontWeight.SemiBold) }
                        }
                        if (id in participants && mode != "Equal") OutlinedTextField(values[id].orEmpty(), { values = values + (id to it); clearProblem("split") }, label = { Text(when(mode) { "Percentages" -> "Percentage · %"; "Shares" -> "Shares · default 1"; "Adjustments" -> "Adjustment · ₹ (use - to reduce)"; else -> "Exact amount · ₹" }) }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = if (mode == "Shares") KeyboardType.Number else KeyboardType.Decimal))
                    }
                    if (mode == "Exact amounts" || mode == "Percentages") runCatching { val entered = participants.filter { it in members }.map { Accounting.money(values[it].orEmpty().ifBlank { "0" }) }.fold(0L, Math::addExact); val remaining = (if (mode == "Percentages") 10000L else Accounting.money(amount)) - entered; if (mode == "Percentages") "Unallocated: ${entryAmount(remaining)}%" else "Unallocated: ${money(remaining)}" }.getOrNull()?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                    EntryProblem(allocations.exceptionOrNull()?.message.takeIf { amount.isNotBlank() })
                }
                "category" -> {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Suggest from description", Modifier.weight(1f)); Switch(autoCategory, { autoCategory = it; if (it) category = ExpenseCategories.suggest(description) ?: "General" }, Modifier.semantics { contentDescription = "Auto-categorize expenses" }) }
                    (expenseCategories + category).distinct().forEach { value -> ListItem(headlineContent = { Text(value) }, leadingContent = { CategoryIcon(value, size = 28) }, trailingContent = { if (category == value) Glyph("check") }, modifier = Modifier.clickable { category = value; autoCategory = false; sheet = null }) }
                }
                "group" -> {
                    ListItem(headlineContent = { Text("No group") }, supportingContent = { Text("Split directly with friends") }, modifier = Modifier.clickable { chooseContext("") })
                    book.groups.filter { !it.archived && it.kind !in setOf("Direct", "Non-group", "Personal") }.forEach { g -> ListItem(headlineContent = { Text(g.name) }, leadingContent = { Glyph("groups") }, modifier = Modifier.clickable { chooseContext(g.id) }) }
                }
                "people" -> {
                    TextField(query, { query = it }, label = { Text("Enter name, email or phone number") }, modifier = Modifier.fillMaxWidth())
                    allPeople.filter { it.id != "self" && (it.name.contains(query, true) || it.email.contains(query, true) || it.phone.contains(query, true)) }.forEach { p -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { toggleFriend(p.id) }, verticalAlignment = Alignment.CenterVertically) { Checkbox(p.id in companions, onCheckedChange = { toggleFriend(p.id) }); Text(p.name) } }
                    TextButton(onClick = { newFriend() }) { Text("Add a new friend") }
                }
                "newFriend" -> {
                    OutlinedTextField(newName, { newName = it }, label = { Text("Friend's name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    if (friendDetails) {
                        OutlinedTextField(newEmail, { newEmail = it }, label = { Text("Email (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                        OutlinedTextField(newPhone, { newPhone = it }, label = { Text("Phone (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
                    } else TextButton(onClick = { friendDetails = true }) { Text("Add email or phone") }
                    EntryProblem(problem.takeIf { problemField == "newFriend" })
                    Button(onClick = { if (!localBusy) { localBusy = true; scope.launch { val person = Person(name = newName.trim(), email = newEmail.trim(), phone = newPhone.trim()); vm.savePerson(person).onSuccess { createdPeople = createdPeople + person; toggleFriend(person.id); query = ""; sheet = null; problem = null; problemField = null }.onFailure { problem = it.message; problemField = "newFriend" }; localBusy = false } } }, enabled = !localBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Add friend") }
                }
                "notes" -> OutlinedTextField(notes, { notes = it; clearProblem("notes") }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp), supportingText = { Text("${notes.length}/4000") }, isError = notes.length > 4000)
                "receipt" -> ReceiptAttachmentPanel(photos, { photos = it }, enabled = !busy, onBusyChange = { receiptBusy = it })
            }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard changes?") }, text = { Text("Your unsaved expense will be lost.") }, confirmButton = { TextButton(onClick = onClose) { Text("Discard") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
}

@Composable private fun entryFieldColors() = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.background, unfocusedContainerColor = MaterialTheme.colorScheme.background, errorContainerColor = MaterialTheme.colorScheme.background)
@Composable private fun EntryProblem(message: String?) { if (message != null) Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Assertive; semanticError(message) }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

/** Vector toolbar icons follow the app's existing ink stroke family. */
@Composable private fun ExpenseToolbarIcon(name: String) {
    val color = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
        val scale = size.width / 24f
        fun line(x: Float, y: Float, a: Float, b: Float) = drawLine(color, androidx.compose.ui.geometry.Offset(x * scale, y * scale), androidx.compose.ui.geometry.Offset(a * scale, b * scale), 1.8f * scale, androidx.compose.ui.graphics.StrokeCap.Round)
        fun box(x: Float, y: Float, w: Float, h: Float) = drawRoundRect(color, androidx.compose.ui.geometry.Offset(x * scale, y * scale), androidx.compose.ui.geometry.Size(w * scale, h * scale), androidx.compose.ui.geometry.CornerRadius(2 * scale), style = androidx.compose.ui.graphics.drawscope.Stroke(1.8f * scale))
        when(name) {
            "calendar" -> { box(3f, 5f, 18f, 16f); line(3f, 10f, 21f, 10f); line(8f, 3f, 8f, 7f); line(16f, 3f, 16f, 7f); line(8f, 15f, 10f, 15f); line(14f, 15f, 16f, 15f) }
            "camera" -> { box(2f, 6f, 20f, 15f); line(7f, 6f, 9f, 3f); line(9f, 3f, 15f, 3f); line(15f, 3f, 17f, 6f); drawCircle(color, 4f * scale, androidx.compose.ui.geometry.Offset(12f * scale, 13f * scale), style = androidx.compose.ui.graphics.drawscope.Stroke(1.8f * scale)) }
            else -> { box(4f, 3f, 16f, 18f); line(8f, 8f, 16f, 8f); line(8f, 12f, 16f, 12f); line(8f, 16f, 13f, 16f) }
        }
    }
}
