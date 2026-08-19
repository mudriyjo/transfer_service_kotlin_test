package com.bank.transfer.domain.dto

import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.domain.TransferType
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class TransferResponse(
    val transferId: UUID,
    val type: TransferType,
    val status: TransferStatus,
    val sourceAccountId: UUID,
    val destinationAccountId: UUID?,
    val beneficiaryAccount: String?,
    val amount: BigDecimal,
    val currency: String,
    val scheduledAt: Instant?,
    val failureCode: String?,
    val failureMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val replayed: Boolean = false,
)

data class TransferListResponse(
    val items: List<TransferResponse>,
    val limit: Int,
    val offset: Long,
    val returned: Int,
    val hasMore: Boolean,
    val nextOffset: Long?,
    val previousOffset: Long?,
    val pageNumber: Long,
    val firstPage: Boolean,
    val rangeStart: Long?,
    val rangeEnd: Long?,
)
