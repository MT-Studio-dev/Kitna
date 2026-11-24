package app.kitna

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.kitna.core.*
import app.kitna.data.LocalStore
import app.kitna.data.CloudCoordinator
import app.kitna.data.KitnaRuntime
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class KitnaViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = KitnaRuntime.get(application)
    val store = runtime.store
    val cloud = runtime.cloud
    val book = store.book
    val error = MutableStateFlow<String?>(null)
    private val mutableSaving = MutableStateFlow(false)
    val saving = mutableSaving.asStateFlow()
    private val mutableFileTransferBusy = MutableStateFlow(false)
    val fileTransferBusy = mutableFileTransferBusy.asStateFlow()
    private val commandMutex = Mutex()
    init { retryLoad() }
    fun retryLoad() { viewModelScope.launch {
        try { runtime.load(); error.value = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error.value = "Could not load your records. Your stored data has been kept. ${failure.message.orEmpty()}" }
    } }
    private suspend fun mutate(transform: (Book) -> Book): Result<Unit> = commandMutex.withLock {
        mutableSaving.value = true
        try { store.update { current -> SharedBooks.queueChanges(current, transform(current), cloud.state.value.session?.id) }; cloud.requestSync(); Result.success(Unit) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
        finally { mutableSaving.value = false }
    }
    suspend fun addPerson(name: String) = mutate { BookActions.addPerson(it, name) }
    suspend fun savePerson(person: Person) = mutate {
        require(person.id=="self" || it.sharedGroups.none { link -> person.id in link.people } || it.people.find { p -> p.id==person.id }?.name==person.name) { "Shared member names come from their account. Rename local-only friends here." }
        BookActions.savePerson(it, person)
    }
    suspend fun renamePerson(id: String, name: String) = mutate { BookActions.renamePerson(it, id, name) }
    suspend fun deletePerson(id: String) = mutate { BookActions.deletePerson(it, id) }
    suspend fun renameSelf(name: String) = renamePerson("self", name)
    suspend fun saveGroup(group: Group, original: Group? = null) = mutate {
        require(original==null || it.sharedGroups.none { link -> link.groupId==group.id } || it.groups.find { g -> g.id==group.id }==original) { "This group changed on another phone. Close this editor and reopen the latest group before saving." }
        BookActions.saveGroup(it, group)
    }
    suspend fun addGroup(name: String, members: Set<String>) = saveGroup(Group(name = name, members = members.sorted()))
    suspend fun archive(group: Group) = mutate { BookActions.archive(it, group) }
    suspend fun deleteGroup(id: String) = mutate { BookActions.deleteGroup(it, id) }
    suspend fun saveExpense(expense: Expense, original: Expense? = null) = mutate {
        require(original==null || it.sharedGroups.none { link -> link.groupId==expense.groupId } || it.expenses.find { e -> e.id==expense.id }==original) { "This expense changed on another phone. Your draft is kept; reopen the latest expense before saving." }
        BookActions.saveExpense(it, expense)
    }
    suspend fun deleteExpense(expense: Expense) = mutate {
        require(it.sharedGroups.none { link -> link.groupId==expense.groupId } || it.expenses.find { e -> e.id==expense.id }==expense) { "This expense changed on another phone. Review it before deleting." }
        BookActions.deleteExpense(it, expense.id)
    }
    suspend fun restoreExpense(id: String) = mutate { BookActions.restoreExpense(it, id) }
    suspend fun saveSettlement(settlement: Settlement, original: Settlement? = null) = mutate {
        require(original==null || it.sharedGroups.none { link -> link.groupId==settlement.groupId } || it.settlements.find { s -> s.id==settlement.id }==original) { "This repayment changed on another phone. Reopen the latest repayment before saving." }
        BookActions.saveSettlement(it, settlement)
    }
    suspend fun deleteSettlement(id: String) = mutate { BookActions.deleteSettlement(it, id) }
    suspend fun comment(expense: Expense, text: String, authorId: String = "self") = mutate { BookActions.comment(it, expense.id, text, authorId) }
    suspend fun saveComment(comment: Comment) = mutate { BookActions.saveComment(it, comment) }
    suspend fun ensureDirectGroup(personId: String): Result<String> {
        var resultId = ""
        return mutate { current -> BookActions.directGroup(current, personId).let { resultId = it.second; it.first } }.map { resultId }
    }
    suspend fun resetBook(): Result<Unit> = commandMutex.withLock {
        mutableSaving.value = true
        try { cloud.exclusive { require(cloud.state.value.session==null) { "Sign out in Shared groups before erasing this device. Shared groups are kept on the server." }; store.reset() }; error.value = null; Result.success(Unit) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
        finally { mutableSaving.value = false }
    }
    suspend fun fileTransfer(action: suspend () -> Unit): Result<Unit> = commandMutex.withLock {
        mutableFileTransferBusy.value = true
        try { cloud.exclusive { action() }; error.value = null; Result.success(Unit) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
        finally { mutableFileTransferBusy.value = false }
    }
    fun receiveCloudIntent(uri: Uri) { viewModelScope.launch { book.filterNotNull().first(); cloud.receive(uri) } }
    // The process runtime also serves Android's scheduled sync jobs.
}
