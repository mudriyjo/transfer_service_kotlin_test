package com.bank.transfer.persistence.ledger
import com.bank.transfer.domain.AccountAccessDeniedException
import com.bank.transfer.domain.AccountCurrencyMismatchException
import com.bank.transfer.domain.AccountNotFoundException
import com.bank.transfer.domain.InactiveAccountException
import com.bank.transfer.domain.InsufficientFundsException
import com.bank.transfer.domain.LedgerDirection
import com.bank.transfer.domain.Money
import com.bank.transfer.domain.Transfer
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.domain.TransferType
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * Implements the stable, database-local internal transfer side effect.
 * The caller supplies the surrounding reactive transaction so account locks,
 * balance updates, ledger entries, transfer status and outbox event commit as
 * one unit.
 */
@Repository
class R2dbcLedgerRepository(
    private val databaseClient: DatabaseClient,
) : LedgerRepository {
    override suspend fun assertSourceOwnership(sourceAccountId: UUID, customerId: UUID) {
        val account = loadAccount(sourceAccountId) ?: throw AccountAccessDeniedException(sourceAccountId)
        if (account.customerId != customerId) throw AccountAccessDeniedException(sourceAccountId)
        if (!account.active) throw InactiveAccountException(sourceAccountId)
    }

    override suspend fun post(transfer: Transfer): LedgerPosting {
        require(transfer.type == TransferType.INTERNAL) {
            "Internal ledger cannot post ${transfer.type} transfers"
        }
        require(transfer.status == TransferStatus.PROCESSING) {
            "Internal transfer must be PROCESSING before ledger posting"
        }

        val accounts = loadAccountsForUpdate(
            transfer.sourceAccountId,
            requireNotNull(transfer.destinationAccountId),
        )
        val source = accounts[transfer.sourceAccountId]
            ?: throw AccountNotFoundException(transfer.sourceAccountId)
        val destinationId = requireNotNull(transfer.destinationAccountId)
        val destination = accounts[destinationId]
            ?: throw AccountNotFoundException(destinationId)

        validateAccounts(transfer, source, destination)
        debitSource(source, transfer.money, transfer.updatedAt)
        creditDestination(destination, transfer.money, transfer.updatedAt)

        val debit = LedgerEntry(
            id = deterministicEntryId(transfer.id, LedgerDirection.DEBIT),
            transferId = transfer.id,
            accountId = source.id,
            direction = LedgerDirection.DEBIT,
            money = transfer.money,
            createdAt = transfer.updatedAt,
        )
        val credit = LedgerEntry(
            id = deterministicEntryId(transfer.id, LedgerDirection.CREDIT),
            transferId = transfer.id,
            accountId = destination.id,
            direction = LedgerDirection.CREDIT,
            money = transfer.money,
            createdAt = transfer.updatedAt,
        )
        insertEntry(debit)
        insertEntry(credit)
        return LedgerPosting(debit, credit)
    }

    override suspend fun entriesForTransfer(transferId: UUID): List<LedgerEntry> = databaseClient
        .sql(
            """
            SELECT id, transfer_id, account_id, direction, amount, currency, created_at
              FROM ledger_entries
             WHERE transfer_id = :transferId
             ORDER BY direction ASC
            """.trimIndent(),
        )
        .bind("transferId", transferId)
        .map { row, _ ->
            LedgerEntry(
                id = row.get("id", UUID::class.java)!!,
                transferId = row.get("transfer_id", UUID::class.java)!!,
                accountId = row.get("account_id", UUID::class.java)!!,
                direction = enumValueOf(row.get("direction", String::class.java)!!),
                money = Money.of(
                    row.get("amount", BigDecimal::class.java)!!,
                    row.get("currency", String::class.java)!!,
                ),
                createdAt = row.get("created_at", Instant::class.java)!!,
            )
        }
        .all()
        .collectList()
        .awaitSingle()

    override suspend fun findOwnedAccount(customerId: UUID, accountId: UUID): LedgerAccount? = databaseClient
        .sql(
            """
            SELECT id, customer_id, currency, balance, active
              FROM bank_accounts
             WHERE id = :accountId
               AND customer_id = :customerId
            """.trimIndent(),
        )
        .bind("accountId", accountId)
        .bind("customerId", customerId)
        .map { row, _ -> row.toLedgerAccount() }
        .one()
        .awaitSingleOrNull()

    override suspend fun listOwnedAccounts(
        customerId: UUID,
        active: Boolean?,
        limit: Int,
        offset: Long,
    ): List<LedgerAccount> {
        require(limit in 1..201) { "Account query limit must be between 1 and 201" }
        require(offset >= 0) { "Account query offset cannot be negative" }

        val activePredicate = if (active == null) "" else "AND active = :active"
        var query = databaseClient.sql(
            """
            SELECT id, customer_id, currency, balance, active
              FROM bank_accounts
             WHERE customer_id = :customerId
               $activePredicate
             ORDER BY id
             LIMIT :limit OFFSET :offset
            """.trimIndent(),
        )
            .bind("customerId", customerId)
            .bind("limit", limit)
            .bind("offset", offset)
        active?.let { query = query.bind("active", it) }

        return query
            .map { row, _ -> row.toLedgerAccount() }
            .all()
            .collectList()
            .awaitSingle()
    }

    override suspend fun listOwnedEntries(
        customerId: UUID,
        accountId: UUID,
        fromInclusive: Instant,
        toExclusive: Instant,
        direction: LedgerDirection?,
        limit: Int,
        offset: Long,
    ): List<LedgerEntry> {
        require(fromInclusive < toExclusive) { "Statement start must precede its end" }
        require(limit in 1..201) { "Statement query limit must be between 1 and 201" }
        require(offset >= 0) { "Statement query offset cannot be negative" }

        val directionPredicate = if (direction == null) "" else "AND entry.direction = :direction"
        var query = databaseClient.sql(
            """
            SELECT entry.id, entry.transfer_id, entry.account_id, entry.direction,
                   entry.amount, entry.currency, entry.created_at
              FROM ledger_entries entry
              JOIN bank_accounts account ON account.id = entry.account_id
             WHERE account.id = :accountId
               AND account.customer_id = :customerId
               AND entry.created_at >= :fromInclusive
               AND entry.created_at < :toExclusive
               $directionPredicate
             ORDER BY entry.created_at DESC, entry.id DESC
             LIMIT :limit OFFSET :offset
            """.trimIndent(),
        )
            .bind("accountId", accountId)
            .bind("customerId", customerId)
            .bind("fromInclusive", fromInclusive)
            .bind("toExclusive", toExclusive)
            .bind("limit", limit)
            .bind("offset", offset)
        direction?.let { query = query.bind("direction", it.name) }

        return query
            .map { row, _ -> row.toLedgerEntry() }
            .all()
            .collectList()
            .awaitSingle()
    }

    override suspend fun summarizeOwnedEntries(
        customerId: UUID,
        accountId: UUID,
        fromInclusive: Instant,
        toExclusive: Instant,
        direction: LedgerDirection?,
    ): LedgerMovementSummary {
        require(fromInclusive < toExclusive) { "Statement start must precede its end" }

        val directionJoin = if (direction == null) "" else "AND entry.direction = :direction"
        var query = databaseClient.sql(
            """
            SELECT COALESCE(
                       SUM(CASE WHEN entry.direction = 'DEBIT' THEN entry.amount ELSE 0 END),
                       0
                   ) AS debit_amount,
                   COALESCE(
                       SUM(CASE WHEN entry.direction = 'CREDIT' THEN entry.amount ELSE 0 END),
                       0
                   ) AS credit_amount,
                   COUNT(entry.id) AS entry_count
              FROM bank_accounts account
              LEFT JOIN ledger_entries entry
                ON entry.account_id = account.id
               AND entry.created_at >= :fromInclusive
               AND entry.created_at < :toExclusive
               $directionJoin
             WHERE account.id = :accountId
               AND account.customer_id = :customerId
            """.trimIndent(),
        )
            .bind("accountId", accountId)
            .bind("customerId", customerId)
            .bind("fromInclusive", fromInclusive)
            .bind("toExclusive", toExclusive)
        direction?.let { query = query.bind("direction", it.name) }

        return query
            .map { row, _ ->
                LedgerMovementSummary(
                    debitAmount = requireNotNull(row.get("debit_amount", BigDecimal::class.java)),
                    creditAmount = requireNotNull(row.get("credit_amount", BigDecimal::class.java)),
                    entryCount = requireNotNull(row.get("entry_count", Long::class.javaObjectType)),
                )
            }
            .one()
            .awaitSingle()
    }

    private suspend fun loadAccount(accountId: UUID): LedgerAccount? = databaseClient
        .sql(
            """
            SELECT id, customer_id, currency, balance, active
              FROM bank_accounts
             WHERE id = :accountId
            """.trimIndent(),
        )
        .bind("accountId", accountId)
        .map { row, _ -> row.toLedgerAccount() }
        .one()
        .awaitSingleOrNull()

    private suspend fun loadAccountsForUpdate(
        sourceAccountId: UUID,
        destinationAccountId: UUID,
    ): Map<UUID, LedgerAccount> = databaseClient
        .sql(
            """
            SELECT id, customer_id, currency, balance, active
              FROM bank_accounts
             WHERE id = :sourceAccountId OR id = :destinationAccountId
             ORDER BY id
             FOR UPDATE
            """.trimIndent(),
        )
        .bind("sourceAccountId", sourceAccountId)
        .bind("destinationAccountId", destinationAccountId)
        .map { row, _ -> row.toLedgerAccount() }
        .all()
        .collectList()
        .awaitSingle()
        .associateBy(LedgerAccount::id)

    private fun validateAccounts(
        transfer: Transfer,
        source: LedgerAccount,
        destination: LedgerAccount,
    ) {
        if (source.customerId != transfer.customerId) {
            throw AccountAccessDeniedException(source.id)
        }
        if (!source.active) throw InactiveAccountException(source.id)
        if (!destination.active) throw InactiveAccountException(destination.id)
        if (source.currency != transfer.money.currency || destination.currency != transfer.money.currency) {
            throw AccountCurrencyMismatchException()
        }
        if (source.balance < transfer.money.amount) throw InsufficientFundsException()
    }

    private suspend fun debitSource(account: LedgerAccount, money: Money, now: Instant) {
        val rows = databaseClient.sql(
            """
            UPDATE bank_accounts
               SET balance = balance - :amount, updated_at = :updatedAt
             WHERE id = :accountId
               AND active = TRUE
               AND currency = :currency
               AND balance >= :amount
            """.trimIndent(),
        )
            .bind("amount", money.amount)
            .bind("updatedAt", now)
            .bind("accountId", account.id)
            .bind("currency", money.currency)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        if (rows != 1L) throw InsufficientFundsException()
    }

    private suspend fun creditDestination(account: LedgerAccount, money: Money, now: Instant) {
        val rows = databaseClient.sql(
            """
            UPDATE bank_accounts
               SET balance = balance + :amount, updated_at = :updatedAt
             WHERE id = :accountId
               AND active = TRUE
               AND currency = :currency
            """.trimIndent(),
        )
            .bind("amount", money.amount)
            .bind("updatedAt", now)
            .bind("accountId", account.id)
            .bind("currency", money.currency)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        if (rows != 1L) throw InactiveAccountException(account.id)
    }

    private suspend fun insertEntry(entry: LedgerEntry) {
        val rows = databaseClient.sql(
            """
            INSERT INTO ledger_entries (
                id, transfer_id, account_id, direction, amount, currency, created_at
            ) VALUES (
                :id, :transferId, :accountId, :direction, :amount, :currency, :createdAt
            )
            """.trimIndent(),
        )
            .bind("id", entry.id)
            .bind("transferId", entry.transferId)
            .bind("accountId", entry.accountId)
            .bind("direction", entry.direction.name)
            .bind("amount", entry.money.amount)
            .bind("currency", entry.money.currency)
            .bind("createdAt", entry.createdAt)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        check(rows == 1L) { "Ledger entry was not inserted" }
    }

    private fun io.r2dbc.spi.Row.toLedgerAccount(): LedgerAccount = LedgerAccount(
        id = get("id", UUID::class.java)!!,
        customerId = get("customer_id", UUID::class.java)!!,
        currency = get("currency", String::class.java)!!,
        balance = get("balance", BigDecimal::class.java)!!,
        active = get("active", Boolean::class.javaObjectType)!!,
    )

    private fun io.r2dbc.spi.Row.toLedgerEntry(): LedgerEntry = LedgerEntry(
        id = get("id", UUID::class.java)!!,
        transferId = get("transfer_id", UUID::class.java)!!,
        accountId = get("account_id", UUID::class.java)!!,
        direction = enumValueOf(get("direction", String::class.java)!!),
        money = Money.of(
            get("amount", BigDecimal::class.java)!!,
            get("currency", String::class.java)!!,
        ),
        createdAt = get("created_at", Instant::class.java)!!,
    )

    private fun deterministicEntryId(transferId: UUID, direction: LedgerDirection): UUID =
        UUID.nameUUIDFromBytes("$transferId:${direction.name}".toByteArray(StandardCharsets.UTF_8))
}
