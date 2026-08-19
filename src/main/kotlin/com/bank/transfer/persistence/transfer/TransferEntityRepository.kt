package com.bank.transfer.persistence.transfer

import com.bank.transfer.domain.Transfer
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.domain.TransferType
import java.time.Instant
import java.util.UUID

interface TransferEntityRepository {
    suspend fun insert(transfer: Transfer): Transfer

    suspend fun insertInternalIfAbsent(transfer: Transfer): Boolean

    suspend fun update(transfer: Transfer): Transfer

    suspend fun findById(id: UUID): Transfer?

    suspend fun findOwnedById(id: UUID, customerId: UUID): Transfer?

    suspend fun findByIdempotencyKey(
        customerId: UUID,
        type: TransferType,
        idempotencyKey: String,
    ): Transfer?

    suspend fun list(query: TransferQuery): List<Transfer>

    suspend fun claimScheduledReady(
        now: Instant,
        leaseUntil: Instant,
        claimedBy: String,
        limit: Int,
    ): List<Transfer>

    suspend fun releaseSchedulingClaim(id: UUID, claimedBy: String): Boolean

    suspend fun deferSchedulingClaim(
        id: UUID,
        claimedBy: String,
        retryAt: Instant,
    ): Boolean

    suspend fun rescheduleOwnedScheduled(
        id: UUID,
        customerId: UUID,
        executeAt: Instant,
        updatedAt: Instant,
    ): Transfer?

    suspend fun cancelOwnedScheduled(
        id: UUID,
        customerId: UUID,
        updatedAt: Instant,
    ): Transfer?

    suspend fun findForReconciliation(
        statuses: Set<TransferStatus>,
        updatedBefore: Instant,
        limit: Int,
    ): List<Transfer>
}
