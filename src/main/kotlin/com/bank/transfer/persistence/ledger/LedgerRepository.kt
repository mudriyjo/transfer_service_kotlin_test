package com.bank.transfer.persistence.ledger

import com.bank.transfer.domain.LedgerDirection
import com.bank.transfer.domain.Money
import com.bank.transfer.domain.Transfer
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class LedgerAccount(
    val id: UUID,
    val customerId: UUID,
    val currency: String,
    val balance: BigDecimal,
    val active: Boolean,
) {
    fun availableMoney(): Money = Money.of(balance, currency)
}

data class LedgerEntry(
    val id: UUID,
    val transferId: UUID,
    val accountId: UUID,
    val direction: LedgerDirection,
    val money: Money,
    val createdAt: Instant,
)

data class LedgerPosting(val debit: LedgerEntry, val credit: LedgerEntry)

data class LedgerMovementSummary(
    val debitAmount: BigDecimal,
    val creditAmount: BigDecimal,
    val entryCount: Long,
) {
    init {
        require(debitAmount.signum() >= 0) { "Debit amount cannot be negative" }
        require(creditAmount.signum() >= 0) { "Credit amount cannot be negative" }
        require(entryCount >= 0) { "Entry count cannot be negative" }
    }

    val netAmount: BigDecimal get() = creditAmount.subtract(debitAmount)
}

interface LedgerRepository {
    suspend fun assertSourceOwnership(sourceAccountId: UUID, customerId: UUID)
    suspend fun post(transfer: Transfer): LedgerPosting
    suspend fun entriesForTransfer(transferId: UUID): List<LedgerEntry>
    suspend fun findOwnedAccount(customerId: UUID, accountId: UUID): LedgerAccount?
    suspend fun listOwnedAccounts(customerId: UUID, active: Boolean?, limit: Int, offset: Long): List<LedgerAccount>
    suspend fun listOwnedEntries(
        customerId: UUID,
        accountId: UUID,
        fromInclusive: Instant,
        toExclusive: Instant,
        direction: LedgerDirection?,
        limit: Int,
        offset: Long,
    ): List<LedgerEntry>

    suspend fun summarizeOwnedEntries(
        customerId: UUID,
        accountId: UUID,
        fromInclusive: Instant,
        toExclusive: Instant,
        direction: LedgerDirection?,
    ): LedgerMovementSummary
}
