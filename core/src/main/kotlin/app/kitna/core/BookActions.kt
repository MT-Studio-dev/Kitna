package app.kitna.core

/** Pure book mutations. LocalStore validates and atomically persists each result. */
object BookActions {
    private fun name(value: String): String = value.trim().also { require(it.isNotEmpty() && it.length <= 100) { "Enter a name between 1 and 100 characters." } }
    private fun event(book: Book, group: String = "", message: String) = book.copy(activity = book.activity + Activity(groupId = group, message = message))
    private fun activeGroup(book: Book, id: String) = book.groups.firstOrNull { it.id == id }?.also { require(!it.archived) { "Restore this archived group first." } } ?: throw IllegalArgumentException("Group no longer exists.")

    fun addPerson(book: Book, value: String): Book {
        return savePerson(book, Person(name = value))
    }
    /** Called only after a server-verified friendship identifies the same account.
     * Rekeys local references; remote command payloads and historical amounts stay intact.
     */
    fun mergePeople(book: Book, canonicalId: String, duplicateId: String): Book {
        if (canonicalId == duplicateId) return book
        require(canonicalId != "self" && duplicateId != "self") { "Your profile cannot be merged with a friend." }
        val canonical = requireNotNull(book.people.find { it.id == canonicalId }) { "Friend no longer exists." }
        val duplicate = requireNotNull(book.people.find { it.id == duplicateId }) { "Friend no longer exists." }
        require(canonical.accountId.isEmpty() || duplicate.accountId.isEmpty() || canonical.accountId == duplicate.accountId) { "These people have different verified accounts." }
        require(book.sharedGroups.none { canonicalId in it.people && duplicateId in it.people }) { "This group has two separate historical participant slots. Ask its owner to verify the original participant before connecting the accounts." }
        fun id(value: String) = if (value == duplicateId) canonicalId else value
        require(book.settlements.none { id(it.from) == id(it.to) }) { "These people have repayments to each other. Keep their historical participant identities separate." }
        fun amounts(values: Map<String, Long>): Map<String, Long> = buildMap {
            values.forEach { (key, value) -> val target = id(key); put(target, Math.addExact(get(target) ?: 0L, value)) }
        }
        fun expense(value: Expense) = value.copy(payers = amounts(value.payers), shares = amounts(value.shares), splitWeights = amounts(value.splitWeights))
        fun <T> keys(values: Map<String, T>) = values.entries.associate { id(it.key) to it.value }
        val person = canonical.copy(accountId = canonical.accountId.ifBlank { duplicate.accountId }, email = canonical.email.ifBlank { duplicate.email }, phone = canonical.phone.ifBlank { duplicate.phone })
        return book.copy(
            people = book.people.filterNot { it.id == duplicateId }.map { if (it.id == canonicalId) person else it },
            groups = book.groups.map { it.copy(members = it.members.map(::id).distinct()) },
            expenses = book.expenses.map(::expense),
            settlements = book.settlements.map { it.copy(from = id(it.from), to = id(it.to)) },
            comments = book.comments.map { it.copy(authorId = id(it.authorId)) },
            revisions = book.revisions.map { it.copy(expense = expense(it.expense)) },
            sharedGroups = book.sharedGroups.map { it.copy(people = keys(it.people), userIds = keys(it.userIds)) }
        ).also(BookValidation::validate)
    }
    fun savePerson(book: Book, person: Person): Book {
        val label = name(person.name)
        val email = EmailAddress.normalize(person.email)
        require(person.phone.length <= 100) { "Phone number is too long." }
        require(person.accountId.isEmpty() || runCatching { java.util.UUID.fromString(person.accountId) }.isSuccess) { "Invalid account identity." }
        val old = book.people.firstOrNull { it.id == person.id }
        require(email.isEmpty() || book.people.none { it.id != person.id && it.email.equals(email, true) }) { "This email already belongs to another friend." }
        require(person.accountId.isEmpty() || book.people.none { it.id != person.id && it.accountId == person.accountId }) { "This account is already a friend." }
        if (old != null) {
            val renamed = if (old.name == label) book else renamePerson(book, person.id, label)
            require(old.accountId.isEmpty() || person.accountId == old.accountId) { "An established account identity cannot be replaced." }
            return renamed.copy(people = renamed.people.map { if (it.id == person.id) it.copy(email = email, phone = person.phone.trim(), accountId = person.accountId) else it })
        }
        require(book.people.none { it.name.equals(label, true) }) { "A person with this name already exists." }
        return event(book.copy(people = book.people + person.copy(name = label, email = email)), message = "Added $label")
    }
    fun renamePerson(book: Book, id: String, value: String): Book {
        val old = book.people.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Person no longer exists.")
        val label = name(value)
        require(book.people.none { it.id != id && it.name.equals(label, true) }) { "A person with this name already exists." }
        return event(book.copy(people = book.people.map { if (it.id == id) it.copy(name = label) else it },
            groups = book.groups.map { if (it.kind == "Direct" && id != "self" && id in it.members) it.copy(name = label) else it }), message = "Renamed ${old.name} to $label")
    }
    fun deletePerson(book: Book, id: String): Book {
        require(id != "self") { "Your own profile cannot be deleted. Use Erase local data to start over." }
        val person = book.people.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Person no longer exists.")
        val emptyDirect = book.groups.filter { g -> g.kind == "Direct" && id in g.members && book.expenses.none { it.groupId == g.id } && book.settlements.none { it.groupId == g.id } }.map { it.id }.toSet()
        require(book.groups.none { id in it.members && it.id !in emptyDirect }) { "Remove this person from groups before deleting them. People used in expense history must be kept." }
        require(book.comments.none { it.authorId == id }) { "This person's comments are part of your history." }
        return event(book.copy(people = book.people.filterNot { it.id == id }, groups = book.groups.filterNot { it.id in emptyDirect }, activity = book.activity.filterNot { it.groupId in emptyDirect }), message = "Removed ${person.name}")
    }
    fun saveGroup(book: Book, group: Group): Book {
        val old = book.groups.firstOrNull { it.id == group.id }
        val clean = group.copy(name = name(group.name), members = group.members.distinct())
        require(clean.members.isNotEmpty() && "self" in clean.members) { "Include yourself in this group." }
        require(clean.members.all { id -> book.people.any { it.id == id } }) { "A selected person no longer exists." }
        require(clean.kind in setOf("Group", "Trip", "Home", "Couple", "Friends", "Other", "Direct", "Non-group")) { "Unknown group type." }
        require(clean.kind != "Direct" || clean.members.size == 2) { "A direct friendship must contain exactly two people." }
        GroupAppearance.validate(clean.icon, clean.color)
        BookValidation.budget(clean.budget)
        if (old != null) {
            require(!old.archived || !clean.archived || clean == old) { "Restore this archived group before editing it." }
            require(old.isNonGroup == clean.isNonGroup && (!old.isNonGroup || old.kind == clean.kind)) { "A friendship cannot be converted to a group." }
            val removed = old.members - clean.members.toSet()
            require(book.expenses.filter { it.groupId == group.id }.none { e -> (e.payers.keys + e.shares.keys).any { it in removed } } &&
                book.revisions.filter { it.expense.groupId == group.id }.none { r -> (r.expense.payers.keys + r.expense.shares.keys).any { it in removed } } &&
                book.settlements.filter { it.groupId == group.id }.none { it.from in removed || it.to in removed } &&
                book.comments.none { c -> c.authorId in removed && book.expenses.any { it.id == c.expenseId && it.groupId == group.id } }) { "Members used in expense, comment, or repayment history cannot be removed." }
            if (clean.archived && !old.archived) require(Accounting.transfers(book, group.id).isEmpty()) { "Settle this group's balances before archiving." }
        }
        if (old == clean) return book
        return event(book.copy(groups = book.groups.filterNot { it.id == clean.id } + clean), clean.id, if (old == null) "Created ${clean.name}" else "Updated ${clean.name}")
    }
    fun directGroup(book: Book, personId: String): Pair<Book, String> {
        require(personId != "self" && book.people.any { it.id == personId }) { "Choose a friend." }
        val found = book.groups.firstOrNull { it.kind == "Direct" && it.members.toSet() == setOf("self", personId) }
        if (found != null) return (if (found.archived) saveGroup(book, found.copy(archived = false)) else book) to found.id
        val group = Group(name = book.people.first { it.id == personId }.name, members = listOf("self", personId), kind = "Direct")
        return saveGroup(book, group) to group.id
    }
    fun nonGroupLedger(book: Book, participantIds: Set<String>): Pair<Book, String> {
        val members = (participantIds + "self").sorted()
        require(members.all { id -> book.people.any { it.id == id } }) { "A selected friend no longer exists." }
        if (members.size == 2) return directGroup(book, members.first { it != "self" })
        val found = book.groups.firstOrNull { it.kind == "Non-group" && it.members.toSet() == members.toSet() }
        if (found != null) return (if (found.archived) saveGroup(book, found.copy(archived = false)) else book) to found.id
        val ledger = Group(name = if (members.size == 1) "Personal expenses" else "Non-group expenses", members = members, kind = "Non-group", simplifyDebts = false)
        return saveGroup(book, ledger) to ledger.id
    }
    /** Create the ledger and bill together, so a rejected save cannot leave a partial record. */
    fun saveNonGroupExpense(book: Book, expense: Expense, participantIds: Set<String>): Book {
        val saved = book.expenses.firstOrNull { it.id == expense.id }
        if (saved != null) {
            val ledger = requireNotNull(book.groups.find { it.id == saved.groupId && it.isNonGroup }) { "This expense already belongs to a group." }
            val retry = expense.copy(groupId = ledger.id, description = expense.description.trim(), notes = expense.notes.trim())
            require((expense.groupId.isEmpty() || expense.groupId == ledger.id) && ledger.members.toSet() == participantIds + "self" && retry == saved) { "This expense is already saved. Open it from history to edit the latest version." }
            return book
        }
        val (updated, ledgerId) = nonGroupLedger(book, participantIds)
        return saveExpense(updated, expense.copy(groupId = ledgerId))
    }
    fun deleteGroup(book: Book, id: String): Book {
        val group = book.groups.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Group no longer exists.")
        require(book.expenses.none { it.groupId == id } && book.settlements.none { it.groupId == id }) { "Groups with history cannot be deleted. Archive a settled group instead." }
        return event(book.copy(groups = book.groups.filterNot { it.id == id }, activity = book.activity.filterNot { it.groupId == id }), message = "Deleted empty group ${group.name}")
    }
    fun archive(book: Book, group: Group): Book {
        val current = book.groups.firstOrNull { it.id == group.id } ?: throw IllegalArgumentException("Group no longer exists.")
        return saveGroup(book, current.copy(archived = !current.archived))
    }
    fun saveExpense(book: Book, expense: Expense): Book {
        val clean = expense.copy(description = expense.description.trim(), notes = expense.notes.trim())
        Accounting.validate(clean, activeGroup(book, clean.groupId))
        require(!clean.deleted) { "Use Restore to recover a deleted expense." }
        val old = book.expenses.firstOrNull { it.id == clean.id }
        require(old == null || old.groupId == clean.groupId) { "An expense cannot be moved between groups." }
        require(old?.deleted != true) { "Restore this expense before editing it." }
        if (old == clean) return book
        return event(book.copy(expenses = book.expenses.filterNot { it.id == clean.id } + clean,
            revisions = if (old == null) book.revisions else book.revisions + ExpenseRevision(expense = old, reason = "Before edit")), clean.groupId, "${if (old == null) "Added" else "Updated"} ${clean.description}")
    }
    fun deleteExpense(book: Book, id: String): Book {
        val old = book.expenses.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Expense no longer exists.")
        activeGroup(book, old.groupId)
        require(!old.deleted) { "Expense is already deleted." }
        return event(book.copy(expenses = book.expenses.map { if (it.id == id) it.copy(deleted = true) else it }, revisions = book.revisions + ExpenseRevision(expense = old, reason = "Before deletion")), old.groupId, "Deleted ${old.description}")
    }
    fun restoreExpense(book: Book, id: String): Book {
        val old = book.expenses.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Expense no longer exists.")
        activeGroup(book, old.groupId)
        require(old.deleted) { "Expense is not deleted." }
        return event(book.copy(expenses = book.expenses.map { if (it.id == id) it.copy(deleted = false) else it }, revisions = book.revisions + ExpenseRevision(expense = old, reason = "Before restore")), old.groupId, "Restored ${old.description}")
    }
    fun saveSettlement(book: Book, settlement: Settlement): Book {
        val group = activeGroup(book, settlement.groupId)
        BookValidation.settlement(settlement, group)
        require(!settlement.deleted) { "A deleted repayment cannot be saved." }
        val old = book.settlements.firstOrNull { it.id == settlement.id }
        require(old == null || old.groupId == settlement.groupId) { "A repayment cannot be moved between groups." }
        if (old == settlement.copy(reference = settlement.reference.trim())) return book
        return event(book.copy(settlements = book.settlements.filterNot { it.id == settlement.id } + settlement.copy(reference = settlement.reference.trim())), group.id, if (old == null) "Recorded repayment · ${settlement.method}" else "Updated repayment · ${settlement.method}")
    }
    fun saveDirectSettlement(book: Book, settlement: Settlement, friendId: String): Book {
        val saved = book.settlements.firstOrNull { it.id == settlement.id }
        if (saved != null) {
            val ledger = requireNotNull(book.groups.find { it.id == saved.groupId && it.kind == "Direct" && friendId in it.members }) { "This repayment already belongs to another ledger." }
            require(settlement.copy(groupId = ledger.id, reference = settlement.reference.trim()) == saved) { "This repayment is already saved. Open it from history to edit the latest version." }
            return book
        }
        val (updated, ledgerId) = directGroup(book, friendId)
        return saveSettlement(updated, settlement.copy(groupId = ledgerId))
    }
    fun deleteSettlement(book: Book, id: String): Book {
        val old = book.settlements.firstOrNull { it.id == id } ?: throw IllegalArgumentException("Repayment no longer exists.")
        activeGroup(book, old.groupId)
        require(!old.deleted) { "Repayment is already deleted." }
        return event(book.copy(settlements = book.settlements.map { if (it.id == id) it.copy(deleted = true) else it }), old.groupId, "Deleted repayment · ${old.method}")
    }
    fun comment(book: Book, expenseId: String, text: String, authorId: String): Book {
        return saveComment(book, Comment(expenseId = expenseId, text = text, authorId = authorId))
    }
    fun saveComment(book: Book, comment: Comment): Book {
        val expense = book.expenses.firstOrNull { it.id == comment.expenseId } ?: throw IllegalArgumentException("Expense no longer exists.")
        activeGroup(book, expense.groupId)
        require(!expense.deleted) { "Restore this expense before adding comments." }
        require(comment.text.trim().length in 1..2000) { "Enter a comment between 1 and 2,000 characters." }
        require(comment.authorId in book.groups.first { it.id == expense.groupId }.members) { "Choose a group member as the comment author." }
        val clean = comment.copy(text = comment.text.trim())
        val old = book.comments.firstOrNull { it.id == comment.id }
        require(old == null || old.expenseId == clean.expenseId) { "A comment cannot be moved between expenses." }
        if (old == clean) return book
        return event(book.copy(comments = book.comments.filterNot { it.id == clean.id } + clean), expense.groupId, "${if (old == null) "Commented on" else "Updated comment on"} ${expense.description}")
    }
}
