package com.bank.transfer.service

import com.bank.transfer.domain.Transfer
import com.bank.transfer.integration.cbs.CbsOperationStatus
import com.bank.transfer.integration.cbs.CbsReferenceConflictException
import com.bank.transfer.integration.cbs.CbsRejectedException
import com.bank.transfer.integration.cbs.CbsTimeoutException
import com.bank.transfer.integration.cbs.CbsTransferResponse
import java.time.Clock
import org.springframework.stereotype.Component

/** Centralizes CBS status and error conversion for transfers. */
@Component
class CbsErrorMapper(
    private val clock: Clock,
) {
    fun applyResponse(transfer: Transfer, response: CbsTransferResponse): Transfer =
        when (response.status) {
            CbsOperationStatus.ACCEPTED,
            CbsOperationStatus.PROCESSING,
            -> transfer.markProcessing(clock.instant())

            CbsOperationStatus.COMPLETED -> transfer.markCompleted(clock.instant())
            CbsOperationStatus.REJECTED -> transfer.markFailed(
                code = "CBS_REJECTED",
                message = response.message ?: "Transfer was rejected by CBS",
                at = clock.instant(),
            )
            CbsOperationStatus.NOT_FOUND -> transfer.markFailed(
                code = "CBS_NOT_FOUND",
                message = response.message ?: "CBS transfer was not found",
                at = clock.instant(),
            )
        }

    fun applyFailure(transfer: Transfer, error: Throwable): Transfer =
        when (error) {
            is CbsTimeoutException -> transfer.markFailed(
                code = "CBS_TIMEOUT",
                message = error.message ?: "CBS request timed out",
                at = clock.instant(),
            )
            is CbsReferenceConflictException -> transfer.markFailed(
                code = "CBS_REFERENCE_CONFLICT",
                message = error.message ?: "CBS reference conflict",
                at = clock.instant(),
            )
            is CbsRejectedException -> transfer.markFailed(
                code = error.code,
                message = error.message,
                at = clock.instant(),
            )
            else -> transfer.markFailed(
                code = "CBS_UNEXPECTED_ERROR",
                message = error.message ?: error.javaClass.simpleName,
                at = clock.instant(),
            )
        }
}
