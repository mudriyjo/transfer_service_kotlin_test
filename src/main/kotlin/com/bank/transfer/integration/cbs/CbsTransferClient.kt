package com.bank.transfer.integration.cbs

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

interface CbsTransferClient {
    suspend fun transfer(request: CbsTransferRequest): CbsTransferResponse
    suspend fun status(clientReference: String): CbsStatusResponse
}

data class CbsTransferRequest(
    val transferId: UUID,
    val clientReference: String,
    val sourceAccountId: UUID,
    val destinationAccount: String,
    val amount: BigDecimal,
    val currency: String,
    val requestedAt: Instant,
)

data class CbsTransferResponse(
    val operationId: String,
    val clientReference: String,
    val status: CbsOperationStatus,
    val processedAt: Instant?,
    val message: String? = null,
)

data class CbsStatusResponse(
    val operationId: String?,
    val clientReference: String,
    val status: CbsOperationStatus,
    val processedAt: Instant?,
    val message: String? = null,
)

enum class CbsOperationStatus {
    ACCEPTED,
    PROCESSING,
    COMPLETED,
    REJECTED,
    NOT_FOUND,
}

class CbsTimeoutException(
    val clientReference: String,
    val mayHaveCommitted: Boolean = true,
    cause: Throwable? = null,
) : RuntimeException("CBS did not return a result for reference $clientReference", cause)

class CbsReferenceConflictException(clientReference: String) :
    RuntimeException("CBS reference $clientReference was already used with another payload")

class CbsRejectedException(
    val code: String,
    override val message: String,
) : RuntimeException(message)
