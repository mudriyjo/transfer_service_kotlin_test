package com.bank.transfer.service

import com.bank.transfer.domain.AccountAccessDeniedException
import com.bank.transfer.domain.InvalidTransferException
import com.bank.transfer.domain.LedgerDirection
import com.bank.transfer.domain.Money
import com.bank.transfer.persistence.ledger.LedgerAccount
import com.bank.transfer.persistence.ledger.LedgerEntry
import com.bank.transfer.persistence.ledger.LedgerRepository
import com.bank.transfer.security.CurrentCustomerProvider
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

data class AccountReadModel(
    val id: UUID,
    val balance: Money,
    val active: Boolean,
)

data class AccountQueryPage(
    val accounts: List<AccountReadModel>,
    val limit: Int,
    val offset: Long,
    val hasNext: Boolean,
)

data class LedgerEntryReadModel(
    val id: UUID,
    val transferId: UUID,
    val direction: LedgerDirection,
    val money: Money,
    val createdAt: Instant,
)

data class LedgerStatementSummary(
    val debit: Money,
    val credit: Money,
    val net: Money,
    val entryCount: Long,
)

data class LedgerStatementWindow(
    val fromInclusive: Instant,
    val toExclusive: Instant,
)

data class LedgerStatementPage(
    val account: AccountReadModel,
    val entries: List<LedgerEntryReadModel>,
    val summary: LedgerStatementSummary,
    val window: LedgerStatementWindow,
    val limit: Int,
    val offset: Long,
    val hasNext: Boolean,
)

/** Read-only use cases for accounts visible to the authenticated customer. */
@Service
class AccountQueryService(
    private val currentCustomer: CurrentCustomerProvider,
    private val ledger: LedgerRepository,
    private val clock: Clock,
) {
    suspend fun listAccounts(
        active: Boolean? = null,
        limit: Int = DEFAULT_ACCOUNT_LIMIT,
        offset: Long = 0,
    ): AccountQueryPage {
        validatePage(limit, offset, MAX_ACCOUNT_PAGE_SIZE)
        val customerId = currentCustomer.currentCustomerId()
        val rows = ledger.listOwnedAccounts(
            customerId = customerId,
            active = active,
            limit = limit + 1,
            offset = offset,
        )
        val hasNext = rows.size > limit
        return AccountQueryPage(
            accounts = rows.take(limit).map { account -> account.toReadModel() },
            limit = limit,
            offset = offset,
            hasNext = hasNext,
        )
    }

    suspend fun getAccount(accountId: UUID): AccountReadModel {
        val customerId = currentCustomer.currentCustomerId()
        return requireOwnedAccount(customerId, accountId).toReadModel()
    }

    suspend fun statement(
        accountId: UUID,
        fromInclusive: Instant? = null,
        toExclusive: Instant? = null,
        direction: LedgerDirection? = null,
        limit: Int = DEFAULT_STATEMENT_LIMIT,
        offset: Long = 0,
    ): LedgerStatementPage {
        validatePage(limit, offset, MAX_STATEMENT_PAGE_SIZE)
        val window = normalizeWindow(fromInclusive, toExclusive)
        val customerId = currentCustomer.currentCustomerId()
        val account = requireOwnedAccount(customerId, accountId)

        val rows = ledger.listOwnedEntries(
            customerId = customerId,
            accountId = accountId,
            fromInclusive = window.fromInclusive,
            toExclusive = window.toExclusive,
            direction = direction,
            limit = limit + 1,
            offset = offset,
        )
        val totals = ledger.summarizeOwnedEntries(
            customerId = customerId,
            accountId = accountId,
            fromInclusive = window.fromInclusive,
            toExclusive = window.toExclusive,
            direction = direction,
        )
        val currency = account.currency

        return LedgerStatementPage(
            account = account.toReadModel(),
            entries = rows.take(limit).map { entry -> entry.toReadModel() },
            summary = LedgerStatementSummary(
                debit = Money.of(totals.debitAmount, currency),
                credit = Money.of(totals.creditAmount, currency),
                net = Money.of(totals.netAmount, currency),
                entryCount = totals.entryCount,
            ),
            window = window,
            limit = limit,
            offset = offset,
            hasNext = rows.size > limit,
        )
    }

    private suspend fun requireOwnedAccount(customerId: UUID, accountId: UUID): LedgerAccount =
        ledger.findOwnedAccount(customerId, accountId)
            ?: throw AccountAccessDeniedException(accountId)

    private fun validatePage(limit: Int, offset: Long, maxLimit: Int) {
        if (limit !in 1..maxLimit) {
            throw InvalidTransferException(
                code = "INVALID_PAGE_LIMIT",
                message = "Page limit must be between 1 and $maxLimit",
            )
        }
        if (offset !in 0..MAX_OFFSET) {
            throw InvalidTransferException(
                code = "INVALID_PAGE_OFFSET",
                message = "Page offset must be between 0 and $MAX_OFFSET",
            )
        }
    }

    private fun normalizeWindow(
        requestedFrom: Instant?,
        requestedTo: Instant?,
    ): LedgerStatementWindow {
        val now = clock.instant()
        val to = requestedTo ?: now.plus(DEFAULT_END_GRACE)
        val from = requestedFrom ?: to.minus(DEFAULT_STATEMENT_WINDOW)

        if (!from.isBefore(to)) {
            throw InvalidTransferException(
                code = "INVALID_STATEMENT_WINDOW",
                message = "Statement start must be before statement end",
            )
        }
        if (Duration.between(from, to) > MAX_STATEMENT_WINDOW) {
            throw InvalidTransferException(
                code = "STATEMENT_WINDOW_TOO_LARGE",
                message = "Statement window cannot exceed ${MAX_STATEMENT_WINDOW.toDays()} days",
            )
        }
        if (to > now.plus(MAX_FUTURE_SKEW)) {
            throw InvalidTransferException(
                code = "INVALID_STATEMENT_WINDOW",
                message = "Statement end is too far in the future",
            )
        }
        return LedgerStatementWindow(fromInclusive = from, toExclusive = to)
    }

    private fun LedgerAccount.toReadModel(): AccountReadModel = AccountReadModel(
        id = id,
        balance = availableMoney(),
        active = active,
    )

    private fun LedgerEntry.toReadModel(): LedgerEntryReadModel = LedgerEntryReadModel(
        id = id,
        transferId = transferId,
        direction = direction,
        money = money,
        createdAt = createdAt,
    )

    companion object {
        const val DEFAULT_ACCOUNT_LIMIT: Int = 50
        const val MAX_ACCOUNT_PAGE_SIZE: Int = 100
        const val DEFAULT_STATEMENT_LIMIT: Int = 50
        const val MAX_STATEMENT_PAGE_SIZE: Int = 200
        const val MAX_OFFSET: Long = 10_000

        private val DEFAULT_STATEMENT_WINDOW: Duration = Duration.ofDays(90)
        private val MAX_STATEMENT_WINDOW: Duration = Duration.ofDays(366)
        private val DEFAULT_END_GRACE: Duration = Duration.ofSeconds(1)
        private val MAX_FUTURE_SKEW: Duration = Duration.ofMinutes(5)
    }
}
