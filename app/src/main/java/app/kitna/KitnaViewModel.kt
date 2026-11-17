package app.kitna

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.kitna.core.*
import app.kitna.data.LocalStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class KitnaViewModel(application: Application) : AndroidViewModel(application) {
    val store = LocalStore(application)
    val book = store.book
    val error = MutableStateFlow<String?>(null)
    private val mutableSaving = MutableStateFlow(false)
    val saving = mutableSaving.asStateFlow()
    private val commandMutex = Mutex()
    init { retryLoad() }
    fun retryLoad() { viewModelScope.launch {
        try { store.load(); error.value = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error.value = "Could not load your records. Your stored data has been kept. ${failure.message.orEmpty()}" }
    } }
    private suspend fun mutate(transform: (Book) -> Book): Result<Unit> = commandMutex.withLock {
        mutableSaving.value = true
        try { store.update(transform); Result.success(Unit) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
        finally { mutableSaving.value = false }
    }
    suspend fun addPerson(name: String) = mutate { BookActions.addPerson(it, name) }
    suspend fun savePerson(person: Person) = mutate { BookActions.savePerson(it, person) }
    suspend fun renamePerson(id: String, name: String) = mutate { BookActions.renamePerson(it, id, name) }
    suspend fun deletePerson(id: String) = mutate { BookActions.deletePerson(it, id) }
    suspend fun renameSelf(name: String) = renamePerson("self", name)
    suspend fun saveGroup(group: Group) = mutate { BookActions.saveGroup(it, group) }
    suspend fun addGroup(name: String, members: Set<String>) = saveGroup(Group(name = name, members = members.sorted()))
    suspend fun archive(group: Group) = mutate { BookActions.archive(it, group) }
    suspend fun deleteGroup(id: String) = mutate { BookActions.deleteGroup(it, id) }
    suspend fun saveExpense(expense: Expense) = mutate { BookActions.saveExpense(it, expense) }
    suspend fun deleteExpense(expense: Expense) = mutate { BookActions.deleteExpense(it, expense.id) }
    suspend fun restoreExpense(id: String) = mutate { BookActions.restoreExpense(it, id) }
    suspend fun saveSettlement(settlement: Settlement) = mutate { BookActions.saveSettlement(it, settlement) }
    suspend fun deleteSettlement(id: String) = mutate { BookActions.deleteSettlement(it, id) }
    suspend fun comment(expense: Expense, text: String, authorId: String = "self") = mutate { BookActions.comment(it, expense.id, text, authorId) }
    suspend fun saveComment(comment: Comment) = mutate { BookActions.saveComment(it, comment) }
    suspend fun ensureDirectGroup(personId: String): Result<String> {
        var resultId = ""
        return mutate { current -> BookActions.directGroup(current, personId).let { resultId = it.second; it.first } }.map { resultId }
    }
    suspend fun resetBook(): Result<Unit> = commandMutex.withLock {
        mutableSaving.value = true
        try { store.reset(); error.value = null; Result.success(Unit) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { Result.failure(failure) }
        finally { mutableSaving.value = false }
    }
    override fun onCleared() { store.close(); super.onCleared() }
}
