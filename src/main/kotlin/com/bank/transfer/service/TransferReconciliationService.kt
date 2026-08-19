package com.bank.transfer.service

import com.bank.transfer.domain.Transfer
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.integration.cbs.CbsOperationStatus
import com.bank.transfer.integration.cbs.CbsStatusResponse
import com.bank.transfer.integration.cbs.CbsTimeoutException
import com.bank.transfer.integration.cbs.CbsTransferClient
import com.bank.transfer.persistence.transaction.ReactiveTransactionRunner
import com.bank.transfer.persistence.transfer.TransferRepository
import java.time.Clock
import java.time.Duration
import org.slf4j.LoggerFactory

data class ReconciliationReport(
    val selected: Int,
    val completed: Int,
    val failed: Int,
    val unchanged: Int,
    val errors: Int,
)

/** Recovers transfers whose remote status may have changed after the initial call. */
class TransferReconciliationService(
    private val persistence: TransferRepository,
    private val cbsClient: CbsTransferClient,
    private val outboxService: TransferOutboxService,
    private val transactions: ReactiveTransactionRunner,
    private val clock: Clock,
    private val minimumAge: Duration,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun reconcile(): ReconciliationReport {
        val candidates = persistence.findForReconciliation(
            statuses = setOf(TransferStatus.PROCESSING),
            updatedBefore = clock.instant().minus(minimumAge),
            limit = Int.MAX_VALUE,
        )

        var completed = 0
        var failed = 0
        var unchanged = 0
        var errors = 0
        for (candidate in candidates) {
            when (reconcileOne(candidate)) {
                ReconciliationOutcome.COMPLETED -> completed++
                ReconciliationOutcome.FAILED -> failed++
                ReconciliationOutcome.UNCHANGED -> unchanged++
                ReconciliationOutcome.ERROR -> errors++
            }
        }
        return ReconciliationReport(candidates.size, completed, failed, unchanged, errors)
    }

    private suspend fun reconcileOne(transfer: Transfer): ReconciliationOutcome {
        val reference = transfer.cbsReference
        if (reference.isNullOrBlank()) {
            logger.warn("Cannot reconcile transfer {} because it has no CBS reference", transfer.id)
            return ReconciliationOutcome.ERROR
        }

        return try {
            applyRemoteStatus(transfer, cbsClient.status(reference))
        } catch (timeout: CbsTimeoutException) {
            logger.info("CBS status lookup timed out for transfer {}", transfer.id)
            ReconciliationOutcome.UNCHANGED
        } catch (error: Exception) {
            logger.warn(
                "CBS reconciliation failed for transfer {}: {}",
                transfer.id,
                error.message,
            )
            ReconciliationOutcome.ERROR
        }
    }

    private suspend fun applyRemoteStatus(
        transfer: Transfer,
        remote: CbsStatusResponse,
    ): ReconciliationOutcome = when (remote.status) {
        CbsOperationStatus.COMPLETED -> {
            val completed = transfer.markCompleted(clock.instant())
            saveCompletedWithEvent(completed)
            ReconciliationOutcome.COMPLETED
        }
        CbsOperationStatus.REJECTED -> {
            persistence.save(
                transfer.markFailed(
                    code = "CBS_REJECTED",
                    message = remote.message ?: "Transfer was rejected by CBS",
                    at = clock.instant(),
                ),
            )
            ReconciliationOutcome.FAILED
        }
        CbsOperationStatus.ACCEPTED,
        CbsOperationStatus.PROCESSING,
        CbsOperationStatus.NOT_FOUND,
        -> ReconciliationOutcome.UNCHANGED
    }

    private suspend fun saveCompletedWithEvent(completed: Transfer): Transfer =
        transactions.inTransaction {
                val saved = persistence.save(completed)
                outboxService.appendCompleted(saved)
                saved
        }

    private enum class ReconciliationOutcome {
        COMPLETED,
        FAILED,
        UNCHANGED,
        ERROR,
    }
}
