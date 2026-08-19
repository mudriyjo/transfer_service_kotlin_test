package com.bank.transfer.controller

import com.bank.transfer.domain.LedgerDirection
import com.bank.transfer.domain.dto.AccountListResponse
import com.bank.transfer.domain.dto.AccountResponse
import com.bank.transfer.domain.dto.LedgerEntryResponse
import com.bank.transfer.domain.dto.LedgerStatementResponse
import com.bank.transfer.domain.dto.LedgerSummaryResponse
import com.bank.transfer.domain.dto.PageResponse
import com.bank.transfer.service.AccountQueryPage
import com.bank.transfer.service.AccountQueryService
import com.bank.transfer.service.AccountReadModel
import com.bank.transfer.service.LedgerEntryReadModel
import com.bank.transfer.service.LedgerStatementPage
import com.bank.transfer.service.LedgerStatementSummary
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.time.Instant
import java.util.UUID
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
@RequestMapping("/api/v1/accounts")
class AccountController(
    private val accountQueries: AccountQueryService,
) {
    @GetMapping
    suspend fun listAccounts(
        @RequestParam(required = false) active: Boolean?,
        @RequestParam(defaultValue = "50")
        @Min(1)
        @Max(100)
        limit: Int,
        @RequestParam(defaultValue = "0")
        @Min(0)
        @Max(10_000)
        offset: Long,
    ): AccountListResponse = accountQueries
        .listAccounts(active = active, limit = limit, offset = offset)
        .toResponse()

    @GetMapping("/{accountId}")
    suspend fun getAccount(
        @PathVariable accountId: UUID,
    ): AccountResponse = accountQueries.getAccount(accountId).toResponse()

    @GetMapping("/{accountId}/statement")
    suspend fun statement(
        @PathVariable accountId: UUID,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        from: Instant?,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
        to: Instant?,
        @RequestParam(required = false) direction: LedgerDirection?,
        @RequestParam(defaultValue = "50")
        @Min(1)
        @Max(200)
        limit: Int,
        @RequestParam(defaultValue = "0")
        @Min(0)
        @Max(10_000)
        offset: Long,
    ): LedgerStatementResponse = accountQueries
        .statement(
            accountId = accountId,
            fromInclusive = from,
            toExclusive = to,
            direction = direction,
            limit = limit,
            offset = offset,
        )
        .toResponse()
}

private fun AccountQueryPage.toResponse(): AccountListResponse = AccountListResponse(
    accounts = accounts.map { account -> account.toResponse() },
    page = PageResponse(
        limit = limit,
        offset = offset,
        hasNext = hasNext,
    ),
)

private fun AccountReadModel.toResponse(): AccountResponse = AccountResponse(
    accountId = id,
    balance = balance.amount,
    currency = balance.currency,
    active = active,
)

private fun LedgerStatementPage.toResponse(): LedgerStatementResponse = LedgerStatementResponse(
    account = account.toResponse(),
    entries = entries.map { entry -> entry.toResponse() },
    summary = summary.toResponse(),
    fromInclusive = window.fromInclusive,
    toExclusive = window.toExclusive,
    page = PageResponse(
        limit = limit,
        offset = offset,
        hasNext = hasNext,
    ),
)

private fun LedgerEntryReadModel.toResponse(): LedgerEntryResponse = LedgerEntryResponse(
    entryId = id,
    transferId = transferId,
    direction = direction,
    amount = money.amount,
    currency = money.currency,
    createdAt = createdAt,
)

private fun LedgerStatementSummary.toResponse(): LedgerSummaryResponse = LedgerSummaryResponse(
    debitAmount = debit.amount,
    creditAmount = credit.amount,
    netAmount = net.amount,
    currency = net.currency,
    entryCount = entryCount,
)
