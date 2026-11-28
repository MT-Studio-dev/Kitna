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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private fun repaymentAmount(value: Long) = BigDecimal.valueOf(value, 2).toPlainString()

/** Select a debt involving the viewer first; other people's debts should never become the default. */
private fun repaymentDefaults(book: Book, group: Group?, initialFrom: String? = null, initialTo: String? = null): Triple<String, String, String> {
    val members = group?.members.orEmpty()
    val transfers = group?.takeIf { selected -> book.groups.any { it.id == selected.id } }?.let { Accounting.transfers(book, it.id) }.orEmpty()
    val requested = if (initialFrom == null && initialTo == null) null else transfers.firstOrNull { (initialFrom == null || it.from == initialFrom) && (initialTo == null || it.to == initialTo) }
    val suggested = requested ?: transfers.firstOrNull { it.from == "self" || it.to == "self" }
    val from = initialFrom?.takeIf { it in members } ?: suggested?.from ?: "self"
    val to = initialTo?.takeIf { it in members && it != from } ?: suggested?.to?.takeIf { it != from } ?: members.firstOrNull { it != from }.orEmpty()
    val amount = transfers.firstOrNull { it.from == from && it.to == to }?.amount?.let(::repaymentAmount).orEmpty()
    return Triple(from, to, amount)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun SettlementEditor(book: Book, existing: Settlement?, initialGroupId: String?, initialFrom: String?, initialTo: String?, vm: KitnaViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val originalJson by rememberSaveable { mutableStateOf<String?>(existing?.let { Json.encodeToString(it) }) }
    val original = remember(originalJson) { originalJson?.let { Json.decodeFromString<Settlement>(it) } }
    val id = rememberSaveable { existing?.id ?: newId() }
    val options = book.groups.filter { !it.archived || it.id == original?.groupId }
    val initial = original?.groupId ?: initialGroupId ?: options.firstOrNull { g -> Accounting.transfers(book, g.id).any { it.from == "self" || it.to == "self" } }?.id ?: options.firstOrNull()?.id ?: book.people.firstOrNull { it.id != "self" }?.let { "friend:${it.id}" }.orEmpty()
    var contextId by rememberSaveable { mutableStateOf(initial) }
    val friendId = contextId.takeIf { it.startsWith("friend:") }?.removePrefix("friend:")
    val actualGroup = if (friendId == null) options.find { it.id == contextId } else options.find { it.kind == "Direct" && it.members.toSet() == setOf("self", friendId) }
    val group = actualGroup ?: friendId?.takeIf { person -> book.people.any { it.id == person && it.id != "self" } }?.let { Group(id = contextId, name = book.personName(it), members = listOf("self", it), kind = "Direct") }
    val initialDefaults = repaymentDefaults(book, group, initialFrom, initialTo)
    var from by rememberSaveable { mutableStateOf(original?.from ?: initialDefaults.first) }
    var to by rememberSaveable { mutableStateOf(original?.to ?: initialDefaults.second) }
    var amount by rememberSaveable { mutableStateOf(original?.amount?.let(::repaymentAmount) ?: initialDefaults.third) }
    var method by rememberSaveable { mutableStateOf(original?.method ?: "Cash") }
    var date by rememberSaveable { mutableStateOf(original?.date ?: LocalDate.now().toString()) }
    var reference by rememberSaveable { mutableStateOf(original?.reference.orEmpty()) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var errorField by rememberSaveable { mutableStateOf<String?>(null) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var remove by rememberSaveable { mutableStateOf(false) }
    var localBusy by remember { mutableStateOf(false) }
    var complete by remember { mutableStateOf(false) }
    val saving by vm.saving.collectAsStateWithLifecycle()
    val busy = localBusy || saving || complete
    val amountFocus = remember { FocusRequester() }
    var validationAttempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(validationAttempt) { if (validationAttempt > 0 && errorField == "amount") amountFocus.requestFocus() }
    val fingerprint = Json.encodeToString(listOf(contextId, from, to, amount, method, date, reference))
    val initialFingerprint = rememberSaveable { fingerprint }
    val close = { if (!localBusy && !saving && !complete) { if (fingerprint != initialFingerprint) discard = true else onClose() } }
    BackHandler(enabled = sheet == null && !remove) { close() }
    fun clearError(field: String) { if (errorField == field) { error = null; errorField = null } }
    fun chooseContext(value: String) {
        contextId = value
        val person = value.takeIf { it.startsWith("friend:") }?.removePrefix("friend:")
        val selected = options.find { it.id == value } ?: person?.let { id -> options.find { it.kind == "Direct" && it.members.toSet() == setOf("self", id) } ?: Group(id = value, name = book.personName(id), members = listOf("self", id), kind = "Direct") }
        val defaults = repaymentDefaults(book, selected)
        from = defaults.first; to = defaults.second
        if (amount.isBlank()) amount = defaults.third
        error = null; errorField = null; query = ""; sheet = null
    }
    val save = {
        if (!localBusy && !saving && !complete) {
            localBusy = true; error = null; errorField = null
            scope.launch {
                var field = "context"
                try {
                    val current = requireNotNull(group) { "Choose a friend or group." }
                    field = "people"
                    require(from != to && from in current.members && to in current.members) { "Choose two different people." }
                    field = "amount"
                    val total = Accounting.money(amount)
                    require(total > 0) { "Enter a positive repayment amount." }
                    field = "details"
                    val draft = Settlement(id, current.id, from, to, total, method, date, reference = reference.trim())
                    BookValidation.settlement(draft, current)
                    field = "save"
                    if (actualGroup == null && friendId != null) vm.saveDirectSettlement(draft, friendId).getOrThrow()
                    else vm.saveSettlement(draft, original = original).getOrThrow()
                    complete = true; keyboard?.hide(); onClose()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.message ?: "Could not save this repayment. Your draft is kept."; errorField = field; validationAttempt++ }
                finally { localBusy = false }
            }
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = {
        Surface { Row(Modifier.fillMaxWidth().statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close, enabled = !busy, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Back" }) { Glyph("back") }
            Text(if (original == null) "Settle up" else "Edit repayment", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { save() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Save repayment" }) {
                if (localBusy || saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Glyph("check", color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(4.dp)); Text("Save")
            }
        } }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextButton(onClick = { sheet = "context" }, enabled = original == null && !busy, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Choose repayment context" }) {
                Glyph(if (group?.isNonGroup != false) "friends" else "groups", color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(if (group == null) "Choose friend or group" else if (group.isNonGroup) "With ${group.members.filter { it != "self" }.joinToString { book.personName(it) }}" else group.name, maxLines = 2)
                if (original == null) { Spacer(Modifier.width(4.dp)); Glyph("expand", color = MaterialTheme.colorScheme.primary) }
            }
            RepaymentProblem(error.takeIf { errorField == "context" })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                TextButton(onClick = { sheet = "from" }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Choose who paid" }) { Text(if (from == "self") "You" else book.personName(from), style = MaterialTheme.typography.titleMedium) }
                Text("paid", Modifier.padding(vertical = 14.dp))
                TextButton(onClick = { sheet = "to" }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Choose who received payment" }) { Text(if (to.isBlank()) "Choose person" else if (to == "self") "you" else book.personName(to), style = MaterialTheme.typography.titleMedium) }
            }
            RepaymentProblem(error.takeIf { errorField == "people" })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("₹", Modifier.width(40.dp), style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextField(amount, { amount = it; clearError("amount") }, Modifier.weight(1f).focusRequester(amountFocus).semantics { if (errorField == "amount") error?.let { semanticError(it) } }, label = { Text("Amount · ₹") }, placeholder = { Text("0.00") }, singleLine = true, isError = errorField == "amount", supportingText = if (errorField == "amount") ({ RepaymentProblem(error) }) else null, textStyle = MaterialTheme.typography.headlineLarge.copy(fontSize = 40.sp, fontWeight = FontWeight.Medium), colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.background, unfocusedContainerColor = MaterialTheme.colorScheme.background, errorContainerColor = MaterialTheme.colorScheme.background), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }))
            }
            TextButton(onClick = { sheet = "details" }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "More repayment options" }) { Glyph("edit", color = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text("More options") }
            val dateLabel = if (date == LocalDate.now().toString()) "Today" else runCatching { LocalDate.parse(date).format(DateTimeFormatter.ofPattern("d MMM yyyy")) }.getOrDefault(date)
            if (method != "Cash" || date != LocalDate.now().toString() || reference.isNotBlank()) Text(listOfNotNull(method, dateLabel, reference.takeIf { it.isNotBlank() }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            RepaymentProblem(error.takeIf { errorField in listOf("details", "save") })
            Text("Record money already paid. Kitna does not transfer money.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { if (!localBusy) sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when (sheet) { "from" -> "Who paid?"; "to" -> "Who received it?"; "context" -> "Choose friend or group"; else -> "Payment details" }, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { sheet = null }) { Text("Done") }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (sheet) {
                    "from", "to" -> group?.members.orEmpty().filter { sheet == "from" || it != from }.forEach { person ->
                        val selected = if (sheet == "from") from == person else to == person
                        fun choose() { if (sheet == "from") { from = person; if (to == person) to = group?.members?.firstOrNull { it != person }.orEmpty() } else to = person; clearError("people"); sheet = null }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { choose() }, verticalAlignment = Alignment.CenterVertically) { RadioButton(selected, onClick = { choose() }); Text(if (person == "self") "You" else book.personName(person)) }
                    }
                    "context" -> {
                        TextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search friends and groups") }, singleLine = true)
                        options.filter { !it.isNonGroup && it.name.contains(query, true) }.forEach { selected -> ListItem(headlineContent = { Text(selected.name) }, leadingContent = { Glyph("groups") }, modifier = Modifier.clickable { chooseContext(selected.id) }) }
                        book.people.filter { it.id != "self" && (it.name.contains(query, true) || it.email.contains(query, true) || it.phone.contains(query, true)) }.forEach { person -> ListItem(headlineContent = { Text(person.name) }, leadingContent = { Avatar(person.name, size = 36) }, modifier = Modifier.clickable { chooseContext("friend:${person.id}") }) }
                    }
                    "details" -> {
                        Text("Payment method", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (listOf("Cash", "UPI", "Bank transfer", "Other") + method).distinct().forEach { option -> FilterChip(method == option, onClick = { method = option; clearError("details") }, label = { Text(option) }) } }
                        OutlinedButton(onClick = { val selected = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now()); DatePickerDialog(context, { _, year, month, day -> date = LocalDate.of(year, month + 1, day).toString(); clearError("details") }, selected.year, selected.monthValue - 1, selected.dayOfMonth).show() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Date", Modifier.weight(1f)); Text(date) }
                        OutlinedTextField(reference, { reference = it; clearError("details") }, Modifier.fillMaxWidth(), label = { Text("Reference or note (optional)") }, isError = reference.trim().length > 1000, supportingText = { Text("${reference.trim().length}/1000") })
                        RepaymentProblem(error.takeIf { errorField == "details" })
                        if (original != null) TextButton(onClick = { sheet = null; remove = true }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete repayment", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard changes?") }, text = { Text("Your unsaved repayment will be lost.") }, confirmButton = { TextButton(onClick = onClose) { Text("Discard") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
    if (remove) AlertDialog(onDismissRequest = { if (!localBusy) remove = false }, title = { Text("Delete this repayment?") }, text = { Text("Balances will be recalculated. Its history stays available.") }, confirmButton = { TextButton(onClick = {
        if (!localBusy && !saving && !complete && original != null) { localBusy = true; scope.launch {
            try { vm.deleteSettlement(original.id, original = original).getOrThrow(); complete = true; onClose() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "Could not delete this repayment."; errorField = "save" }
            finally { localBusy = false; remove = false }
        } }
    }, enabled = !busy) { Text("Delete") } }, dismissButton = { TextButton(onClick = { remove = false }, enabled = !busy) { Text("Cancel") } })
}

@Composable private fun RepaymentProblem(message: String?) { if (message != null) Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Assertive; semanticError(message) }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
