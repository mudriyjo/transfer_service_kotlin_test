package com.bank.transfer.persistence.transfer

import com.bank.transfer.domain.Transfer
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.domain.TransferType
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * SQL is explicit here because the important correctness properties live in
 * constraints and conflict handling, not in generated repository methods.
 */
@Repository
class R2dbcTransferEntityRepository(
    private val databaseClient: DatabaseClient,
) : TransferEntityRepository {

    override suspend fun insert(transfer: Transfer): Transfer {
        val entity = TransferEntity.fromDomain(transfer)
        return bindTransfer(databaseClient.sql(INSERT_TRANSFER), entity)
            .map { row, _ -> TransferEntity.fromRow(row).toDomain() }
            .one()
            .awaitSingle()
    }

    override suspend fun insertInternalIfAbsent(transfer: Transfer): Boolean {
        require(transfer.type == TransferType.INTERNAL) {
            "The conflict-safe insert is reserved for internal transfers"
        }
        val entity = TransferEntity.fromDomain(transfer)
        return bindTransfer(databaseClient.sql(INSERT_INTERNAL_IF_ABSENT), entity)
            .map { row, _ -> row.get("id", UUID::class.java) }
            .one()
            .awaitSingleOrNull() != null
    }

    /** Stores the latest aggregate snapshot and advances its audit version. */
    override suspend fun update(transfer: Transfer): Transfer {
        val entity = TransferEntity.fromDomain(transfer)
        return bindMutableFields(
            databaseClient.sql(UPDATE_TRANSFER)
                .bind("id", entity.id),
            entity,
            includeVersion = false,
        )
            .map { row, _ -> TransferEntity.fromRow(row).toDomain() }
            .one()
            .awaitSingle()
    }

    override suspend fun findById(id: UUID): Transfer? = databaseClient
        .sql("SELECT $COLUMNS FROM transfers WHERE id = :id")
        .bind("id", id)
        .map { row, _ -> TransferEntity.fromRow(row).toDomain() }
        .one()
        .awaitSingleOrNull()

    override suspend fun findOwnedById(id: UUID, customerId: UUID): Transfer? = databaseClient
        .sql("SELECT $COLUMNS FROM transfers WHERE id = :id AND customer_id = :customerId")
        .bind("id", id)
        .bind("customerId", customerId)
        .map { row, _ -> TransferEntity.fromRow(row).toDomain() }
        .one()
        .awaitSingleOrNull()

    override suspend fun findByIdempotencyKey(
        customerId: UUID,
        type: TransferType,
        idempotencyKey: String,
    ): Transfer? = databaseClient
        .sql(
            """
            SELECT $COLUMNS
              FROM transfers
             WHERE customer_id = :customerId
               AND transfer_type = :transferType
               AND idempotency_key = :idempotencyKey
             ORDER BY created_at ASC
             LIMIT 1
            """.trimIndent(),
        )
        .bind("customerId", customerId)
        .bind("transferType", type.name)
        .bind("idempotencyKey", idempotencyKey)
        .map { row, _ -> TransferEntity.fromRow(row).toDomain() }
        .one()
        .awaitSingleOrNull()

    override suspend fun list(query: TransferQuery): List<Transfer> {
        val predicates = mutableListOf("customer_id = :customerId")
        if (query.status != null) predicates += "status = :status"
        if (query.type != null) predicates += "transfer_type = :transferType"

        var spec = databaseClient.sql(
            """
            SELECT $COLUMNS
              FROM transfers
             WHERE ${predicates.joinToString(" AND ")}
             ORDER BY created_at DESC, id DESC
             LIMIT :limit OFFSET :offset
            """.trimIndent(),
        )
            .bind("customerId", query.customerId)
            .bind("limit", query.limit)
            .bind("offset", query.offset)
        query.status?.let { spec = spec.bind("status", it.name) }
        query.type?.let { spec = spec.bind("transferType", it.name) }

        return spec.map { row, _ -> TransferEntity.fromRow(row).toDomain() }
            .all()
            .collectList()
            .awaitSingle()
    }

    override suspend fun claimScheduledReady(
        now: Instant,
        leaseUntil: Instant,
        claimedBy: String,
        limit: Int,
    ): List<Transfer> = databaseClient
        .sql(CLAIM_SCHEDULED_TRANSFERS)
        .bind("now", now)
        .bind("leaseUntil", leaseUntil)
        .bind("claimedBy", claimedBy)
        .bind("limit", limit)
        .map { row, _ -> TransferEntity.fromRow(row).toDomain() }
        .all()
        .collectList()
        .awaitSingle()

    override suspend fun releaseSchedulingClaim(id: UUID, claimedBy: String): Boolean = databaseClient
        .sql(
            """
            UPDATE transfers
               SET scheduling_claimed_by = NULL,
                   scheduling_claimed_until = NULL
             WHERE id = :id
               AND scheduling_claimed_by = :claimedBy
            """.trimIndent(),
        )
        .bind("id", id)
        .bind("claimedBy", claimedBy)
        .fetch()
        .rowsUpdated()
        .awaitSingle() == 1L

    override suspend fun deferSchedulingClaim(
        id: UUID,
        claimedBy: String,
        retryAt: Instant,
    ): Boolean = databaseClient
        .sql(
            """
            UPDATE transfers
               SET scheduling_claimed_until = :retryAt
             WHERE id = :id
               AND scheduling_claimed_by = :claimedBy
               AND transfer_type = 'SCHEDULED'
               AND status = 'SCHEDULED'
            """.trimIndent(),
        )
        .bind("id", id)
        .bind("claimedBy", claimedBy)
        .bind("retryAt", retryAt)
        .fetch()
        .rowsUpdated()
        .awaitSingle() == 1L

    override suspend fun rescheduleOwnedScheduled(
        id: UUID,
        customerId: UUID,
        executeAt: Instant,
        updatedAt: Instant,
    ): Transfer? = databaseClient
        .sql(
            """
            UPDATE transfers
               SET scheduled_at = :executeAt,
                   updated_at = :updatedAt,
                   version = version + 1,
                   scheduling_claimed_by = NULL,
                   scheduling_claimed_until = NULL
             WHERE id = :id
               AND customer_id = :customerId
               AND transfer_type = 'SCHEDULED'
               AND status = 'SCHEDULED'
               AND (
                    scheduling_claimed_until IS NULL
                    OR scheduling_claimed_until <= :updatedAt
               )
            RETURNING $COLUMNS
            """.trimIndent(),
        )
        .bind("id", id)
        .bind("customerId", customerId)
        .bind("executeAt", executeAt)
        .bind("updatedAt", updatedAt)
        .map { row, _ -> TransferEntity.fromRow(row).toDomain() }
        .one()
        .awaitSingleOrNull()

    override suspend fun cancelOwnedScheduled(
        id: UUID,
        customerId: UUID,
        updatedAt: Instant,
    ): Transfer? = databaseClient
        .sql(
            """
            UPDATE transfers
               SET status = 'CANCELLED',
                   updated_at = :updatedAt,
                   version = version + 1,
                   scheduling_claimed_by = NULL,
                   scheduling_claimed_until = NULL
             WHERE id = :id
               AND customer_id = :customerId
               AND transfer_type = 'SCHEDULED'
               AND status = 'SCHEDULED'
               AND (
                    scheduling_claimed_until IS NULL
                    OR scheduling_claimed_until <= :updatedAt
               )
            RETURNING $COLUMNS
            """.trimIndent(),
        )
        .bind("id", id)
        .bind("customerId", customerId)
        .bind("updatedAt", updatedAt)
        .map { row, _ -> TransferEntity.fromRow(row).toDomain() }
        .one()
        .awaitSingleOrNull()

    override suspend fun findForReconciliation(
        statuses: Set<TransferStatus>,
        updatedBefore: Instant,
        limit: Int,
    ): List<Transfer> {
        if (statuses.isEmpty()) return emptyList()
        val statusLiterals = statuses.joinToString(",") { "'${it.name}'" }
        return databaseClient.sql(
            """
            SELECT $COLUMNS
              FROM transfers
             WHERE transfer_type IN ('EXTERNAL', 'SCHEDULED')
               AND status IN ($statusLiterals)
               AND updated_at < :updatedBefore
             ORDER BY updated_at ASC
             LIMIT :limit
            """.trimIndent(),
        )
            .bind("updatedBefore", updatedBefore)
            .bind("limit", limit)
            .map { row, _ -> TransferEntity.fromRow(row).toDomain() }
            .all()
            .collectList()
            .awaitSingle()
    }

    private fun bindTransfer(
        initial: DatabaseClient.GenericExecuteSpec,
        entity: TransferEntity,
    ): DatabaseClient.GenericExecuteSpec = bindMutableFields(
        initial
            .bind("id", entity.id)
            .bind("customerId", entity.customerId)
            .bind("transferType", entity.transferType)
            .bind("sourceAccountId", entity.sourceAccountId)
            .bindNullable("destinationAccountId", entity.destinationAccountId, UUID::class.java)
            .bindNullable("beneficiaryAccount", entity.beneficiaryAccount, String::class.java)
            .bind("amount", entity.amount)
            .bind("currency", entity.currency)
            .bind("idempotencyKey", entity.idempotencyKey)
            .bind("requestFingerprint", entity.requestFingerprint)
            .bind("createdAt", entity.createdAt),
        entity,
        includeVersion = true,
    )

    private fun bindMutableFields(
        initial: DatabaseClient.GenericExecuteSpec,
        entity: TransferEntity,
        includeVersion: Boolean,
    ): DatabaseClient.GenericExecuteSpec {
        var spec = initial
            .bind("status", entity.status)
            .bindNullable("cbsReference", entity.cbsReference, String::class.java)
            .bindNullable("scheduledAt", entity.scheduledAt, Instant::class.java)
            .bindNullable("failureCode", entity.failureCode, String::class.java)
            .bindNullable("failureMessage", entity.failureMessage, String::class.java)
            .bind("updatedAt", entity.updatedAt)
        if (includeVersion) spec = spec.bind("version", entity.version)
        return spec
    }

    private fun DatabaseClient.GenericExecuteSpec.bindNullable(
        name: String,
        value: Any?,
        type: Class<*>,
    ): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)

    companion object {
        private const val COLUMNS = """
            id, customer_id, transfer_type, source_account_id, destination_account_id, beneficiary_account,
            amount, currency, idempotency_key, request_fingerprint, status,
            cbs_reference, scheduled_at, failure_code, failure_message,
            created_at, updated_at, version
        """

        private const val INSERT_TRANSFER = """
            INSERT INTO transfers (
                id, customer_id, transfer_type, source_account_id, destination_account_id, beneficiary_account,
                amount, currency, idempotency_key, request_fingerprint, status,
                cbs_reference, scheduled_at, failure_code, failure_message,
                created_at, updated_at, version
            ) VALUES (
                :id, :customerId, :transferType, :sourceAccountId, :destinationAccountId, :beneficiaryAccount,
                :amount, :currency, :idempotencyKey, :requestFingerprint, :status,
                :cbsReference, :scheduledAt, :failureCode, :failureMessage,
                :createdAt, :updatedAt, :version
            )
            RETURNING *
        """

        private const val INSERT_INTERNAL_IF_ABSENT = """
            INSERT INTO transfers (
                id, customer_id, transfer_type, source_account_id, destination_account_id, beneficiary_account,
                amount, currency, idempotency_key, request_fingerprint, status,
                cbs_reference, scheduled_at, failure_code, failure_message,
                created_at, updated_at, version
            ) VALUES (
                :id, :customerId, :transferType, :sourceAccountId, :destinationAccountId, :beneficiaryAccount,
                :amount, :currency, :idempotencyKey, :requestFingerprint, :status,
                :cbsReference, :scheduledAt, :failureCode, :failureMessage,
                :createdAt, :updatedAt, :version
            )
            ON CONFLICT (customer_id, transfer_type, idempotency_key)
                WHERE transfer_type = 'INTERNAL'
            DO NOTHING
            RETURNING id
        """

        private const val CLAIM_SCHEDULED_TRANSFERS = """
            WITH ready AS (
                SELECT id
                  FROM transfers
                 WHERE transfer_type = 'SCHEDULED'
                   AND status = 'SCHEDULED'
                   AND scheduled_at <= :now
                   AND (
                        scheduling_claimed_until IS NULL
                        OR scheduling_claimed_until <= :now
                   )
                 ORDER BY scheduled_at ASC, id ASC
                 LIMIT :limit
                 FOR UPDATE SKIP LOCKED
            )
            UPDATE transfers AS transfer
               SET scheduling_claimed_by = :claimedBy,
                   scheduling_claimed_until = :leaseUntil
              FROM ready
             WHERE transfer.id = ready.id
            RETURNING
                transfer.id,
                transfer.customer_id,
                transfer.transfer_type,
                transfer.source_account_id,
                transfer.destination_account_id,
                transfer.beneficiary_account,
                transfer.amount,
                transfer.currency,
                transfer.idempotency_key,
                transfer.request_fingerprint,
                transfer.status,
                transfer.cbs_reference,
                transfer.scheduled_at,
                transfer.failure_code,
                transfer.failure_message,
                transfer.created_at,
                transfer.updated_at,
                transfer.version
        """

        private const val UPDATE_TRANSFER = """
            UPDATE transfers
               SET status = :status,
                   cbs_reference = :cbsReference,
                   scheduled_at = :scheduledAt,
                   failure_code = :failureCode,
                   failure_message = :failureMessage,
                   updated_at = :updatedAt,
                   version = version + 1
             WHERE id = :id
            RETURNING *
        """
    }
}
