package app.kitna.core

object BookValidation {
    private fun unique(ids: List<String>) { require(ids.size == ids.toSet().size && ids.none { it.isBlank() || it.length > 200 }) { "Backup has duplicate or invalid identifiers." } }
    const val MAX_RECEIPT_BYTES = 2L * 1024 * 1024
    const val MAX_RECEIPTS_PER_EXPENSE = 10
    fun budget(amount: Long?) {
        require(amount == null || amount in 1..Accounting.MAX_AMOUNT) { "Enter a positive budget up to ₹1,000,000,000, or leave it empty." }
    }
    fun receipts(photos: List<ReceiptPhoto>) {
        require(photos.size <= MAX_RECEIPTS_PER_EXPENSE) { "Attach up to $MAX_RECEIPTS_PER_EXPENSE receipt photos to one expense." }
        require(photos.map { it.id }.distinct().size == photos.size) { "A receipt photo is attached more than once." }
        photos.forEach { photo ->
            require(photo.id.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Receipt identifier is invalid." }
            require(photo.name.isNotBlank() && photo.name.length <= 200) { "Receipt name must contain 1–200 characters." }
            require(photo.mimeType == "image/jpeg") { "Unsupported receipt image format." }
            require(photo.sizeBytes in 1..MAX_RECEIPT_BYTES) { "Receipt photos must be at most 2 MB." }
            require(photo.sha256.matches(Regex("[a-f0-9]{64}"))) { "Receipt checksum is invalid." }
        }
    }
    fun settlement(value: Settlement, group: Group) {
        require(value.from != value.to && value.from in group.members && value.to in group.members) { "Choose two different group members." }
        require(value.amount in 1..Accounting.MAX_AMOUNT) { "Enter a positive repayment amount." }
        require(value.method.isNotBlank() && value.method.length <= 100) { "Choose a repayment method." }
        require(value.reference.length <= 1000) { "Payment reference must be at most 1,000 characters." }
        Accounting.validDate(value.date)
    }
    fun validate(book: Book) {
        require(book.schemaVersion == 1) { "Unsupported backup version." }
        require(book.people.any { it.id == "self" }) { "Backup is missing your profile." }
        unique(book.people.map { it.id }); unique(book.groups.map { it.id }); unique(book.expenses.map { it.id })
        unique(book.settlements.map { it.id }); unique(book.comments.map { it.id }); unique(book.activity.map { it.id }); unique(book.revisions.map { it.id })
        require(book.people.all { it.name.isNotBlank() && it.name.length <= 100 }) { "Every person needs a name up to 100 characters." }
        book.people.forEach { EmailAddress.normalize(it.email) }
        val people = book.people.map { it.id }.toSet()
        val groups = book.groups.associateBy { it.id }
        fun isUuid(id: String) = runCatching { java.util.UUID.fromString(id).toString() == id.lowercase() }.getOrDefault(false)
        unique(book.sharedGroups.map { it.groupId }); unique(book.sharedGroups.map { it.remoteId }); unique(book.pendingChanges.map { it.id })
        book.sharedGroups.forEach { link ->
            val group = requireNotNull(groups[link.groupId]) { "Shared metadata references a missing group." }
            require(isUuid(link.remoteId) && isUuid(link.accountId) && isUuid(link.myParticipantId)) { "Invalid shared account or group identity." }
            require(link.people.keys == group.members.toSet() && link.people["self"] == link.myParticipantId && link.people.values.all(::isUuid) && link.people.values.toSet().size == link.people.size) { "Shared participant mapping is invalid." }
            require(link.versions.values.all { it >= 0 } && link.entities.values.all(::isUuid)) { "Shared record versions are invalid." }
            require(link.userIds.keys.all { it in link.people } && link.userIds.values.all(::isUuid)) { "Shared account mapping is invalid." }
            require(link.snapshot == null || (link.snapshot.group.id == link.remoteId && link.snapshot.myParticipantId == link.myParticipantId)) { "Shared snapshot identity is invalid." }
        }
        require(book.pendingChanges.size <= 10_000) { "Too many pending shared changes. Sync or resolve them before adding more." }
        book.pendingChanges.forEach { change ->
            val link = requireNotNull(book.sharedGroups.find { it.groupId == change.groupId }) { "A pending change references a missing shared group." }
            require(isUuid(change.id) && isUuid(change.entityId) && change.accountId == link.accountId && change.expectedVersion >= 0 && change.kind in setOf("share", "expense", "settlement", "comment", "group") && change.payload.length in 2..20_971_520) { "Invalid pending shared change." }
        }
        book.groups.forEach { g ->
            require(g.name.isNotBlank() && g.name.length <= 100 && g.members.size >= 2 && "self" in g.members && g.members.all { it in people }) { "A group has invalid members or a missing name." }
            unique(g.members)
            require(g.kind in setOf("Group", "Trip", "Home", "Couple", "Direct") && (g.kind != "Direct" || g.members.size == 2)) { "A group has an invalid type." }
            GroupAppearance.validate(g.icon, g.color)
            budget(g.budget)
        }
        fun validateExpense(expense: Expense) { Accounting.validate(expense, requireNotNull(groups[expense.groupId]) { "An expense references a missing group." }.copy(archived = false)) }
        book.expenses.forEach(::validateExpense)
        book.revisions.forEach { revision ->
            require(book.expenses.any { it.id == revision.expense.id && it.groupId == revision.expense.groupId }) { "History references a missing expense." }
            validateExpense(revision.expense)
            require(revision.reason.length in 1..500 && revision.time >= 0) { "Invalid expense history." }
        }
        // A saved photo can appear in both the current expense and its history.
        // One file identifier must always describe the same immutable bytes.
        val photoMetadata = mutableMapOf<String, ReceiptPhoto>()
        (book.expenses + book.revisions.map { it.expense }).forEach { expense ->
            expense.receipts.forEach { photo ->
                val existing = photoMetadata.putIfAbsent(photo.id.lowercase(java.util.Locale.ROOT), photo)
                require(existing == null || existing == photo) { "Receipt history has conflicting photo metadata." }
            }
        }
        book.settlements.forEach { s -> settlement(s, requireNotNull(groups[s.groupId]) { "A repayment references a missing group." }) }
        require(book.activity.all { it.groupId.isEmpty() || it.groupId in groups }) { "Activity references a missing group." }
        require(book.activity.all { it.message.isNotBlank() && it.message.length <= 2000 && it.time >= 0 }) { "Invalid activity entry." }
        book.comments.forEach { c ->
            val expense = requireNotNull(book.expenses.firstOrNull { it.id == c.expenseId }) { "A comment references a missing expense." }
            require(c.text.trim().length in 1..2000 && c.authorId in groups.getValue(expense.groupId).members && c.time >= 0) { "Invalid comment or author." }
        }
        // Check the complete ledger before it becomes visible to the UI.
        book.groups.forEach { Accounting.balances(book, it.id); Accounting.transfers(book, it.id) }
        Accounting.friendBalances(book)
    }
}
