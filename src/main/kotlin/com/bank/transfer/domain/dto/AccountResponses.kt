package com.bank.transfer.domain.dto

import com.bank.transfer.domain.LedgerDirection
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class AccountResponse(
    val accountId: UUID,
    val balance: BigDecimal,
    val currency: String,
    val active: Boolean,
)

data class PageResponse(
    val limit: Int,
    val offset: Long,
    val hasNext: Boolean,
)

data class AccountListResponse(
    val accounts: List<AccountResponse>,
    val page: PageResponse,
)

data class LedgerEntryResponse(
    val entryId: UUID,
    val transferId: UUID,
    val direction: LedgerDirection,
    val amount: BigDecimal,
    val currency: String,
    val createdAt: Instant,
)

data class LedgerSummaryResponse(
    val debitAmount: BigDecimal,
    val creditAmount: BigDecimal,
    val netAmount: BigDecimal,
    val currency: String,
    val entryCount: Long,
)

data class LedgerStatementResponse(
    val account: AccountResponse,
    val entries: List<LedgerEntryResponse>,
    val summary: LedgerSummaryResponse,
    val fromInclusive: Instant,
    val toExclusive: Instant,
    val page: PageResponse,
)
