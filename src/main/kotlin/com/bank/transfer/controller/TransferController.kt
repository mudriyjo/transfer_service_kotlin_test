package com.bank.transfer.controller

import com.bank.transfer.domain.Transfer
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.domain.TransferType
import com.bank.transfer.domain.dto.ExternalTransferRequest
import com.bank.transfer.domain.dto.InternalTransferRequest
import com.bank.transfer.domain.dto.RescheduleScheduledTransferRequest
import com.bank.transfer.domain.dto.ScheduledTransferRequest
import com.bank.transfer.domain.dto.TransferListResponse
import com.bank.transfer.domain.dto.TransferResponse
import com.bank.transfer.service.TransferCommandResult
import com.bank.transfer.service.TransferCommandService
import com.bank.transfer.service.TransferQueryPage
import com.bank.transfer.service.TransferQueryService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.util.UUID

@RestController
@RequestMapping("/api/v1/transfers")
class TransferController(
    private val commandService: TransferCommandService,
) {
    @PostMapping("/internal")
    suspend fun internal(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @Valid @RequestBody request: InternalTransferRequest,
    ): ResponseEntity<TransferResponse> = created(
        commandService.createInternal(
            sourceAccountId = request.sourceAccountId,
            destinationAccountId = request.destinationAccountId,
            amount = request.amount,
            currency = request.currency,
            idempotencyKey = idempotencyKey,
        ),
    )

    @PostMapping("/external")
    suspend fun external(
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @Valid @RequestBody request: ExternalTransferRequest,
    ): ResponseEntity<TransferResponse> = accepted(
        commandService.createExternal(
            requestedCustomerId = request.customerId,
            sourceAccountId = request.sourceAccountId,
            beneficiaryAccount = request.beneficiaryAccount,
            amount = request.amount,
            currency = request.currency,
            idempotencyKey = idempotencyKey,
        ),
    )

    @PostMapping("/scheduled")
    suspend fun scheduled(
        @Valid @RequestBody request: ScheduledTransferRequest,
    ): ResponseEntity<TransferResponse> = accepted(
        commandService.schedule(
            requestedCustomerId = request.customerId,
            sourceAccountId = request.sourceAccountId,
            beneficiaryAccount = request.beneficiaryAccount,
            amount = request.amount,
            currency = request.currency,
            scheduleId = request.scheduleId,
            executeAt = request.executeAt,
        ),
    )

    @PatchMapping("/scheduled/{transferId}")
    suspend fun rescheduleScheduled(
        @PathVariable transferId: UUID,
        @Valid @RequestBody request: RescheduleScheduledTransferRequest,
    ): ResponseEntity<TransferResponse> = ok(
        commandService.rescheduleScheduled(
            transferId = transferId,
            executeAt = request.executeAt,
        ),
    )

    @DeleteMapping("/scheduled/{transferId}")
    suspend fun cancelScheduled(
        @PathVariable transferId: UUID,
    ): ResponseEntity<TransferResponse> = ok(
        commandService.cancelScheduled(transferId),
    )

    private fun created(result: TransferCommandResult): ResponseEntity<TransferResponse> =
        ResponseEntity.status(HttpStatus.CREATED)
            .header(HttpHeaders.LOCATION, transferLocation(result.transfer).toString())
            .body(result.transfer.toResponse(result.replayed))

    private fun accepted(result: TransferCommandResult): ResponseEntity<TransferResponse> =
        ResponseEntity.accepted()
            .header(HttpHeaders.LOCATION, transferLocation(result.transfer).toString())
            .body(result.transfer.toResponse(result.replayed))

    private fun ok(result: TransferCommandResult): ResponseEntity<TransferResponse> =
        ResponseEntity.ok(result.transfer.toResponse(result.replayed))

    private fun transferLocation(transfer: Transfer): URI =
        URI.create("/api/v1/transfers/${transfer.id}")
}

@Validated
@RestController
@RequestMapping("/api/v1/transfers")
class TransferQueryController(
    private val queryService: TransferQueryService,
) {
    @GetMapping("/{transferId}")
    suspend fun get(@PathVariable transferId: UUID): TransferResponse =
        queryService.get(transferId).toResponse()

    @GetMapping
    suspend fun list(
        @RequestParam(required = false) status: TransferStatus?,
        @RequestParam(required = false) type: TransferType?,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) limit: Int,
        @RequestParam(defaultValue = "0") @Min(0) offset: Long,
    ): TransferListResponse = queryService.list(
        status = status,
        type = type,
        limit = limit,
        offset = offset,
    ).toResponse()
}

private fun Transfer.toResponse(replayed: Boolean = false): TransferResponse = TransferResponse(
    transferId = id,
    type = type,
    status = status,
    sourceAccountId = sourceAccountId,
    destinationAccountId = destinationAccountId,
    beneficiaryAccount = beneficiaryAccount,
    amount = money.amount,
    currency = money.currency,
    scheduledAt = scheduledAt,
    failureCode = failureCode,
    failureMessage = failureMessage,
    createdAt = createdAt,
    updatedAt = updatedAt,
    replayed = replayed,
)

private fun TransferQueryPage.toResponse(): TransferListResponse = TransferListResponse(
    items = items.map { transfer -> transfer.toResponse() },
    limit = limit,
    offset = offset,
    returned = returned,
    hasMore = hasMore,
    nextOffset = nextOffset,
    previousOffset = previousOffset,
    pageNumber = pageNumber,
    firstPage = firstPage,
    rangeStart = rangeStart,
    rangeEnd = rangeEnd,
)
