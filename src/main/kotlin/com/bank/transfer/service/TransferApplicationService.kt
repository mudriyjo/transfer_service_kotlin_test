package com.bank.transfer.service

import com.bank.transfer.domain.Money
import com.bank.transfer.domain.Transfer
import com.bank.transfer.domain.TransferFingerprintInput
import com.bank.transfer.domain.TransferPolicy
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.domain.TransferType
import com.bank.transfer.integration.cbs.CbsTimeoutException
import com.bank.transfer.integration.cbs.CbsTransferClient
import com.bank.transfer.observability.TransferLogContext
import com.bank.transfer.observability.TransferMetrics
import com.bank.transfer.persistence.transaction.ReactiveTransactionRunner
import com.bank.transfer.persistence.transfer.TransferRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

data class ExternalTransferCommand(
    val customerId: UUID,
    val sourceAccountId: UUID,
    val beneficiaryAccount: String,
    val money: Money,
    val idempotencyKey: String,
)

data class ExternalTransferResult(
    val transfer: Transfer,
    val replayed: Boolean,
)

/** Coordinates persistence and the synchronous core-banking call. */
@Service
class TransferApplicationService(
    private val persistence: TransferRepository,
    private val policy: TransferPolicy,
    private val requestMapper: CbsRequestMapper,
    private val cbsClient: CbsTransferClient,
    private val errorMapper: CbsErrorMapper,
    private val outbox: TransferOutboxService,
    private val transactions: ReactiveTransactionRunner,
    private val transferIdGenerator: TransferIdGenerator,
    private val clock: Clock,
    private val metrics: TransferMetrics,
    private val logContext: TransferLogContext,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun execute(command: ExternalTransferCommand): ExternalTransferResult =
        persistence.withExternalRequestGuard(command.customerId, command.idempotencyKey) {
            submit(command)
        }

    private suspend fun submit(
        command: ExternalTransferCommand,
    ): ExternalTransferResult {
        val persisted = transactions.inTransaction {
            val existing = persistence.findByIdempotencyKey(
                customerId = command.customerId,
                type = TransferType.EXTERNAL,
                idempotencyKey = command.idempotencyKey,
            )
            if (existing != null && existing.status != TransferStatus.FAILED) {
                return@inTransaction ExternalTransferResult(existing, replayed = true)
            }

            policy.validateExternal(
                customerId = command.customerId,
                sourceAccountId = command.sourceAccountId,
                beneficiaryAccount = command.beneficiaryAccount,
                money = command.money,
                idempotencyKey = command.idempotencyKey,
            )
            val now = clock.instant()
            val fingerprint = policy.fingerprint(
                TransferFingerprintInput(
                    type = TransferType.EXTERNAL,
                    customerId = command.customerId,
                    sourceAccountId = command.sourceAccountId,
                    beneficiaryAccount = command.beneficiaryAccount,
                    money = command.money,
                ),
            )
            val transferId = transferIdGenerator.nextId()
            val candidate = Transfer.create(
                id = transferId,
                customerId = command.customerId,
                type = TransferType.EXTERNAL,
                sourceAccountId = command.sourceAccountId,
                beneficiaryAccount = command.beneficiaryAccount,
                money = command.money,
                idempotencyKey = command.idempotencyKey,
                requestFingerprint = fingerprint,
                now = now,
                cbsReference = "external:$transferId",
            )
            val created = persistence.create(candidate)
            val processing = persistence.save(created.markProcessing(clock.instant()))
            metrics.commandAccepted(TransferType.EXTERNAL, command.customerId)
            metrics.transition(processing, TransferStatus.CREATED, TransferStatus.PROCESSING)
            ExternalTransferResult(processing, replayed = false)
        }
        if (persisted.replayed) {
            return persisted
        }
        return completeWithCbs(persisted.transfer)
    }

    private suspend fun completeWithCbs(processing: Transfer): ExternalTransferResult =
        logContext.withTransfer(processing) {
            logger.info(
                "Sending external transfer from account {} to beneficiary {} for customer {}",
                processing.sourceAccountId,
                processing.beneficiaryAccount,
                processing.customerId,
            )
            val timer = metrics.startCbsTimer(TransferType.EXTERNAL)
            try {
                val response = cbsClient.transfer(requestMapper.toRequest(processing))
                metrics.stopCbsTimer(timer, TransferType.EXTERNAL, response.status.name)
                persistOutcome(processing, errorMapper.applyResponse(processing, response))
            } catch (timeout: CbsTimeoutException) {
                metrics.stopCbsTimer(timer, TransferType.EXTERNAL, timeout.javaClass.simpleName)
                logger.warn(
                    "External transfer {} timed out at CBS; leaving it PROCESSING for status lookup",
                    processing.id,
                )
                ExternalTransferResult(processing, replayed = false)
            } catch (error: Exception) {
                metrics.stopCbsTimer(timer, TransferType.EXTERNAL, error.javaClass.simpleName)
                persistOutcome(processing, errorMapper.applyFailure(processing, error))
            }
        }

    private suspend fun persistOutcome(
        processing: Transfer,
        updated: Transfer,
    ): ExternalTransferResult {
        val saved = if (updated.status == TransferStatus.COMPLETED) {
            saveCompletedWithEvent(updated)
        } else {
            persistence.save(updated)
        }
        metrics.transition(processing, TransferStatus.PROCESSING, saved.status)
        if (saved.status == TransferStatus.FAILED) {
            metrics.commandFailed(TransferType.EXTERNAL, saved.failureCode ?: "unknown")
        }
        return ExternalTransferResult(saved, replayed = false)
    }

    private suspend fun saveCompletedWithEvent(completed: Transfer): Transfer =
        transactions.inTransaction {
            val saved = persistence.save(completed)
            outbox.appendCompleted(saved)
            saved
        }
}
