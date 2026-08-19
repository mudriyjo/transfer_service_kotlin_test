package com.bank.transfer.service

import com.bank.transfer.domain.IdempotencyConflictException
import com.bank.transfer.domain.InvalidTransferException
import com.bank.transfer.domain.Money
import com.bank.transfer.domain.Transfer
import com.bank.transfer.domain.TransferFingerprintInput
import com.bank.transfer.domain.TransferNotFoundException
import com.bank.transfer.domain.TransferPolicy
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.domain.TransferType
import com.bank.transfer.integration.cbs.CbsOperationStatus
import com.bank.transfer.integration.cbs.CbsTimeoutException
import com.bank.transfer.integration.cbs.CbsTransferClient
import com.bank.transfer.persistence.transaction.ReactiveTransactionRunner
import com.bank.transfer.persistence.transfer.TransferRepository
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

data class ScheduleTransferCommand(
    val customerId: UUID,
    val sourceAccountId: UUID,
    val beneficiaryAccount: String,
    val money: Money,
    val idempotencyKey: String,
    val executeAt: Instant,
)

data class ScheduleTransferResult(
    val transfer: Transfer,
    val replayed: Boolean,
)

data class ScheduledTransferChangeResult(
    val transfer: Transfer,
    val replayed: Boolean,
)

/** Creates scheduled transfers and executes them through the shared CBS client. */
@Service
class ScheduledTransferService(
    private val persistence: TransferRepository,
    private val transferPolicy: TransferPolicy,
    private val requestMapper: CbsRequestMapper,
    private val cbsClient: CbsTransferClient,
    private val errorMapper: CbsErrorMapper,
    private val outboxService: TransferOutboxService,
    private val transactions: ReactiveTransactionRunner,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun schedule(command: ScheduleTransferCommand): ScheduleTransferResult {
        val now = clock.instant()
        transferPolicy.validateExternal(
            customerId = command.customerId,
            sourceAccountId = command.sourceAccountId,
            beneficiaryAccount = command.beneficiaryAccount,
            money = command.money,
            idempotencyKey = command.idempotencyKey,
        )
        transferPolicy.validateSchedule(command.executeAt, now)

        val fingerprint = transferPolicy.fingerprint(
            TransferFingerprintInput(
                type = TransferType.SCHEDULED,
                customerId = command.customerId,
                sourceAccountId = command.sourceAccountId,
                beneficiaryAccount = command.beneficiaryAccount,
                money = command.money,
                scheduledAt = command.executeAt,
            ),
        )
        val existing = persistence.findByIdempotencyKey(
            customerId = command.customerId,
            type = TransferType.SCHEDULED,
            idempotencyKey = command.idempotencyKey,
        )
        if (existing != null) {
            if (!existing.hasSameRequest(fingerprint)) {
                throw IdempotencyConflictException(existing.id)
            }
            return ScheduleTransferResult(existing, replayed = true)
        }

        val transferId = UUID.randomUUID()
        val candidate = Transfer.create(
            id = transferId,
            customerId = command.customerId,
            type = TransferType.SCHEDULED,
            sourceAccountId = command.sourceAccountId,
            beneficiaryAccount = command.beneficiaryAccount,
            money = command.money,
            idempotencyKey = command.idempotencyKey,
            requestFingerprint = fingerprint,
            now = now,
            scheduledAt = command.executeAt,
            cbsReference = "scheduled:$transferId",
        )
        return ScheduleTransferResult(persistence.create(candidate), replayed = false)
    }

    suspend fun reschedule(
        transferId: UUID,
        customerId: UUID,
        executeAt: Instant,
    ): ScheduledTransferChangeResult {
        val now = clock.instant()
        val current = getOwned(transferId, customerId)
        transferPolicy.validateCanReschedule(current, executeAt, now)

        if (current.scheduledAt == executeAt) {
            return ScheduledTransferChangeResult(current, replayed = true)
        }

        val proposed = current.reschedule(executeAt = executeAt, at = now)
        val updated = persistence.rescheduleOwnedScheduled(
            transferId = proposed.id,
            customerId = customerId,
            executeAt = requireNotNull(proposed.scheduledAt),
            updatedAt = proposed.updatedAt,
        )
        if (updated != null) {
            return ScheduledTransferChangeResult(updated, replayed = false)
        }

        val latest = getOwned(transferId, customerId)
        if (latest.type == TransferType.SCHEDULED &&
            latest.status == TransferStatus.SCHEDULED &&
            latest.scheduledAt == executeAt
        ) {
            return ScheduledTransferChangeResult(latest, replayed = true)
        }
        throw scheduleUnavailable(transferId, "rescheduled")
    }

    suspend fun cancel(
        transferId: UUID,
        customerId: UUID,
    ): ScheduledTransferChangeResult {
        val current = getOwned(transferId, customerId)
        transferPolicy.validateScheduledType(current)
        if (current.status == TransferStatus.CANCELLED) {
            return ScheduledTransferChangeResult(current, replayed = true)
        }

        transferPolicy.validateCanCancel(current)
        val proposed = current.cancel(clock.instant())
        val updated = persistence.cancelOwnedScheduled(
            transferId = proposed.id,
            customerId = customerId,
            updatedAt = proposed.updatedAt,
        )
        if (updated != null) {
            return ScheduledTransferChangeResult(updated, replayed = false)
        }

        val latest = getOwned(transferId, customerId)
        if (latest.type == TransferType.SCHEDULED && latest.status == TransferStatus.CANCELLED) {
            return ScheduledTransferChangeResult(latest, replayed = true)
        }
        throw scheduleUnavailable(transferId, "cancelled")
    }

    suspend fun execute(transferId: UUID): Transfer {
        val current = persistence.getRequired(transferId)
        require(current.type == TransferType.SCHEDULED) {
            "Transfer $transferId is not a scheduled transfer"
        }
        if (current.status == TransferStatus.COMPLETED || current.status == TransferStatus.CANCELLED) {
            return current
        }

        val processing = when (current.status) {
            TransferStatus.SCHEDULED,
            TransferStatus.FAILED,
            -> persistence.save(current.markProcessing(clock.instant()))
            TransferStatus.PROCESSING -> current
            TransferStatus.CREATED -> error("Scheduled transfer $transferId cannot be CREATED")
            TransferStatus.COMPLETED,
            TransferStatus.CANCELLED,
            -> current
        }

        return try {
            val response = cbsClient.transfer(requestMapper.toRequest(processing))
            val updated = errorMapper.applyResponse(processing, response)
            if (response.status == CbsOperationStatus.COMPLETED) {
                saveCompletedWithEvent(updated)
            } else {
                persistence.save(updated)
            }
        } catch (timeout: CbsTimeoutException) {
            logger.warn(
                "Scheduled transfer {} timed out at CBS; leaving it PROCESSING for status lookup",
                transferId,
            )
            processing
        } catch (error: Exception) {
            persistence.save(errorMapper.applyFailure(processing, error))
        }
    }

    private suspend fun saveCompletedWithEvent(completed: Transfer): Transfer =
        transactions.inTransaction {
                val saved = persistence.save(completed)
                outboxService.appendCompleted(saved)
                saved
        }

    private suspend fun getOwned(transferId: UUID, customerId: UUID): Transfer =
        persistence.findOwnedById(transferId, customerId)
            ?: throw TransferNotFoundException(transferId)

    private fun scheduleUnavailable(transferId: UUID, operation: String): InvalidTransferException =
        InvalidTransferException(
            code = "SCHEDULE_NOT_EDITABLE",
            message = "Scheduled transfer $transferId cannot be $operation in its current state",
        )
}
