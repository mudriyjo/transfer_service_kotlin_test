package com.bank.transfer.persistence.transfer

import com.bank.transfer.domain.Transfer
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.domain.TransferType
import java.time.Instant
import java.util.UUID

data class InternalTransferClaim(
    val created: Boolean,
    val transfer: Transfer,
)

interface TransferRepository {
    suspend fun create(transfer: Transfer): Transfer
    suspend fun claimInternal(candidate: Transfer): InternalTransferClaim
    suspend fun save(transfer: Transfer): Transfer
    suspend fun getRequired(id: UUID): Transfer
    suspend fun findById(id: UUID): Transfer?
    suspend fun findOwnedById(id: UUID, customerId: UUID): Transfer?
    suspend fun findByIdempotencyKey(customerId: UUID, type: TransferType, idempotencyKey: String): Transfer?
    suspend fun listOwned(
        customerId: UUID,
        status: TransferStatus? = null,
        type: TransferType? = null,
        limit: Int,
        offset: Long,
    ): List<Transfer>

    suspend fun claimScheduledReady(
        now: Instant,
        leaseUntil: Instant,
        claimedBy: String,
        limit: Int,
    ): List<Transfer>

    suspend fun releaseSchedulingClaim(transferId: UUID, claimedBy: String): Boolean
    suspend fun deferSchedulingClaim(transferId: UUID, claimedBy: String, retryAt: Instant): Boolean
    suspend fun rescheduleOwnedScheduled(
        transferId: UUID,
        customerId: UUID,
        executeAt: Instant,
        updatedAt: Instant,
    ): Transfer?

    suspend fun cancelOwnedScheduled(transferId: UUID, customerId: UUID, updatedAt: Instant): Transfer?
    suspend fun findForReconciliation(
        statuses: Set<TransferStatus>,
        updatedBefore: Instant,
        limit: Int,
    ): List<Transfer>

    suspend fun updateCbsReference(id: UUID, reference: String, now: Instant): Transfer
    suspend fun <T> withExternalRequestGuard(
        customerId: UUID,
        idempotencyKey: String,
        action: suspend () -> T,
    ): T
}
