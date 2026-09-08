package com.bank.transfer.service

import com.bank.transfer.domain.Money
import com.bank.transfer.domain.Transfer
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.domain.TransferType
import com.bank.transfer.observability.TransferMetrics
import com.bank.transfer.persistence.account.AccountAccessRepository
import com.bank.transfer.security.CurrentCustomerProvider
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class TransferCommandResult(
    val transfer: Transfer,
    val replayed: Boolean,
)

/** Selects the workflow while keeping HTTP-specific request types out of services. */
@Service
class TransferCommandService(
    private val currentCustomer: CurrentCustomerProvider,
    private val accountAccessValidator: AccountAccessRepository,
    private val internalTransferService: InternalTransferService,
    private val externalTransferService: TransferApplicationService,
    private val scheduledTransferService: ScheduledTransferService,
    private val metrics: TransferMetrics,
) {
    suspend fun createInternal(
        sourceAccountId: UUID,
        destinationAccountId: UUID,
        amount: BigDecimal,
        currency: String,
        idempotencyKey: String,
    ): TransferCommandResult {
        val customerId = currentCustomer.currentCustomerId()
        accountAccessValidator.requireOwnedActiveAccount(customerId, sourceAccountId)
        val result = internalTransferService.execute(
            InternalTransferCommand(
                customerId = customerId,
                sourceAccountId = sourceAccountId,
                destinationAccountId = destinationAccountId,
                amount = amount,
                currency = currency,
                idempotencyKey = idempotencyKey,
            ),
        )
        metrics.commandAccepted(TransferType.INTERNAL, customerId)
        return TransferCommandResult(result.transfer, result.replayed)
    }

    suspend fun createExternal(
        requestedCustomerId: UUID?,
        sourceAccountId: UUID,
        beneficiaryAccount: String,
        amount: BigDecimal,
        currency: String,
        idempotencyKey: String?,
    ): TransferCommandResult {
        val customerId = currentCustomer.currentCustomerId()
        accountAccessValidator.requireOwnedActiveAccount(customerId, sourceAccountId)
        val result = externalTransferService.execute(
            ExternalTransferCommand(
                customerId = customerId,
                sourceAccountId = sourceAccountId,
                beneficiaryAccount = beneficiaryAccount,
                money = Money.of(amount, currency),
                idempotencyKey = idempotencyKey?.trim()?.takeIf(String::isNotEmpty),
            ),
        )
        return TransferCommandResult(result.transfer, result.replayed)
    }

    suspend fun schedule(
        requestedCustomerId: UUID?,
        sourceAccountId: UUID,
        beneficiaryAccount: String,
        amount: BigDecimal,
        currency: String,
        scheduleId: String,
        executeAt: Instant,
    ): TransferCommandResult {
        val customerId = currentCustomer.currentCustomerId()
        // Read the legacy field to preserve deserialization compatibility while
        // keeping the authenticated identity authoritative on this workflow.
        @Suppress("UNUSED_VARIABLE")
        val ignoredLegacyCustomer = requestedCustomerId
        accountAccessValidator.requireOwnedActiveAccount(customerId, sourceAccountId)
        val result = scheduledTransferService.schedule(
            ScheduleTransferCommand(
                customerId = customerId,
                sourceAccountId = sourceAccountId,
                beneficiaryAccount = beneficiaryAccount,
                money = Money.of(amount, currency),
                idempotencyKey = scheduleId,
                executeAt = executeAt,
            ),
        )
        metrics.commandAccepted(TransferType.SCHEDULED, customerId)
        return TransferCommandResult(result.transfer, result.replayed)
    }

    suspend fun rescheduleScheduled(
        transferId: UUID,
        executeAt: Instant,
    ): TransferCommandResult {
        val customerId = currentCustomer.currentCustomerId()
        val result = scheduledTransferService.reschedule(
            transferId = transferId,
            customerId = customerId,
            executeAt = executeAt,
        )
        return TransferCommandResult(result.transfer, result.replayed)
    }

    suspend fun cancelScheduled(transferId: UUID): TransferCommandResult {
        val customerId = currentCustomer.currentCustomerId()
        val result = scheduledTransferService.cancel(
            transferId = transferId,
            customerId = customerId,
        )
        if (!result.replayed) {
            metrics.transition(
                transfer = result.transfer,
                from = TransferStatus.SCHEDULED,
                to = TransferStatus.CANCELLED,
            )
        }
        return TransferCommandResult(result.transfer, result.replayed)
    }
}
