package com.bank.transfer.persistence.transfer

import com.bank.transfer.domain.Transfer
import com.bank.transfer.domain.TransferNotFoundException
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.domain.TransferType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Application-facing persistence port with workflow-specific operations. */
@Component
class R2dbcTransferRepository(
    private val repository: TransferEntityRepository,
) : TransferRepository {
    // Reuse mutexes to serialize overlapping submissions for the same request.
    private val externalRequestLocks = ConcurrentHashMap<String, Mutex>()

    override suspend fun create(transfer: Transfer): Transfer = repository.insert(transfer)

    override suspend fun claimInternal(candidate: Transfer): InternalTransferClaim {
        require(candidate.type == TransferType.INTERNAL)
        val created = repository.insertInternalIfAbsent(candidate)
        if (created) return InternalTransferClaim(created = true, transfer = candidate)

        val existing = repository.findByIdempotencyKey(
            customerId = candidate.customerId,
            type = TransferType.INTERNAL,
            idempotencyKey = candidate.idempotencyKey,
        ) ?: error("Internal idempotency conflict did not expose the existing transfer")
        return InternalTransferClaim(created = false, transfer = existing)
    }

    override suspend fun save(transfer: Transfer): Transfer = repository.update(transfer)

    override suspend fun getRequired(id: UUID): Transfer =
        repository.findById(id) ?: throw TransferNotFoundException(id)

    override suspend fun findById(id: UUID): Transfer? = repository.findById(id)

    override suspend fun findOwnedById(id: UUID, customerId: UUID): Transfer? =
        repository.findOwnedById(id, customerId)

    override suspend fun findByIdempotencyKey(
        customerId: UUID,
        type: TransferType,
        idempotencyKey: String,
    ): Transfer? = repository.findByIdempotencyKey(customerId, type, idempotencyKey)

    override suspend fun listOwned(
        customerId: UUID,
        status: TransferStatus?,
        type: TransferType?,
        limit: Int,
        offset: Long,
    ): List<Transfer> {
        require(limit in 1..200) { "Limit must be between 1 and 200" }
        require(offset >= 0) { "Offset cannot be negative" }
        return repository.list(
            TransferQuery(
                customerId = customerId,
                status = status,
                type = type,
                limit = limit,
                offset = offset,
            ),
        )
    }

    override suspend fun claimScheduledReady(
        now: Instant,
        leaseUntil: Instant,
        claimedBy: String,
        limit: Int,
    ): List<Transfer> {
        require(limit in 1..1_000) { "Scheduled batch size must be between 1 and 1000" }
        require(leaseUntil > now) { "Scheduling lease must end after its claim time" }
        require(claimedBy.isNotBlank() && claimedBy.length <= 128) {
            "Scheduling claim owner must contain between 1 and 128 characters"
        }
        return repository.claimScheduledReady(
            now = now,
            leaseUntil = leaseUntil,
            claimedBy = claimedBy,
            limit = limit,
        )
    }

    override suspend fun releaseSchedulingClaim(transferId: UUID, claimedBy: String): Boolean {
        require(claimedBy.isNotBlank()) { "Scheduling claim owner must not be blank" }
        return repository.releaseSchedulingClaim(transferId, claimedBy)
    }

    override suspend fun deferSchedulingClaim(
        transferId: UUID,
        claimedBy: String,
        retryAt: Instant,
    ): Boolean {
        require(claimedBy.isNotBlank()) { "Scheduling claim owner must not be blank" }
        return repository.deferSchedulingClaim(
            id = transferId,
            claimedBy = claimedBy,
            retryAt = retryAt,
        )
    }

    override suspend fun rescheduleOwnedScheduled(
        transferId: UUID,
        customerId: UUID,
        executeAt: Instant,
        updatedAt: Instant,
    ): Transfer? = repository.rescheduleOwnedScheduled(
        id = transferId,
        customerId = customerId,
        executeAt = executeAt,
        updatedAt = updatedAt,
    )

    override suspend fun cancelOwnedScheduled(
        transferId: UUID,
        customerId: UUID,
        updatedAt: Instant,
    ): Transfer? = repository.cancelOwnedScheduled(
        id = transferId,
        customerId = customerId,
        updatedAt = updatedAt,
    )

    override suspend fun findForReconciliation(
        statuses: Set<TransferStatus>,
        updatedBefore: Instant,
        limit: Int,
    ): List<Transfer> {
        require(limit > 0) { "Reconciliation batch size must be positive" }
        return repository.findForReconciliation(statuses, updatedBefore, limit)
    }

    override suspend fun updateCbsReference(id: UUID, reference: String, now: Instant): Transfer {
        val transfer = getRequired(id)
        return save(transfer.withCbsReference(reference, now))
    }

    override suspend fun <T> withExternalRequestGuard(
        customerId: UUID,
        idempotencyKey: String,
        action: suspend () -> T,
    ): T {
        val key = "$customerId:$idempotencyKey"
        val mutex = externalRequestLocks.computeIfAbsent(key) { Mutex() }
        return mutex.withLock { action() }
    }

}
