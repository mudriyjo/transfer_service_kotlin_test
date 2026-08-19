package com.bank.transfer.service

import com.bank.transfer.domain.IdempotencyConflictException
import com.bank.transfer.domain.Money
import com.bank.transfer.domain.Transfer
import com.bank.transfer.domain.TransferFingerprintInput
import com.bank.transfer.domain.TransferPolicy
import com.bank.transfer.domain.TransferType
import com.bank.transfer.persistence.ledger.LedgerRepository
import com.bank.transfer.persistence.transaction.ReactiveTransactionRunner
import com.bank.transfer.persistence.transfer.TransferRepository
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

data class InternalTransferCommand(
    /** Identity resolved from Authentication, never copied from request JSON. */
    val customerId: UUID,
    val sourceAccountId: UUID,
    val destinationAccountId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val idempotencyKey: String,
)

data class InternalTransferResult(
    val transfer: Transfer,
    val replayed: Boolean,
)

fun interface TransferIdGenerator {
    fun nextId(): UUID
}

@Component
class RandomTransferIdGenerator : TransferIdGenerator {
    override fun nextId(): UUID = UUID.randomUUID()
}

/**
 * Reference implementation for a safe database-local money movement.
 *
 * The transfer claim, both balance mutations, immutable ledger entries, final
 * status and outbox insert all execute in the same reactive transaction. A
 * partial unique index supplies cross-pod idempotency; the request fingerprint
 * prevents a key from silently representing two different instructions.
 */
@Service
class InternalTransferService(
    private val persistence: TransferRepository,
    private val ledger: LedgerRepository,
    private val eventWriter: TransferOutboxService,
    private val policy: TransferPolicy,
    private val transactionalOperator: ReactiveTransactionRunner,
    private val clock: Clock,
    private val idGenerator: TransferIdGenerator,
) {
    suspend fun execute(command: InternalTransferCommand): InternalTransferResult {
        policy.validateIdempotencyKey(command.idempotencyKey)
        val money = Money.of(command.amount, command.currency)
        policy.validateInternal(
            customerId = command.customerId,
            sourceAccountId = command.sourceAccountId,
            destinationAccountId = command.destinationAccountId,
            money = money,
            idempotencyKey = command.idempotencyKey,
        )
        val fingerprint = policy.fingerprint(
            TransferFingerprintInput(
                type = TransferType.INTERNAL,
                customerId = command.customerId,
                sourceAccountId = command.sourceAccountId,
                destinationAccountId = command.destinationAccountId,
                money = money,
            ),
        )
        val createdAt = clock.instant()
        val candidate = Transfer.create(
            id = idGenerator.nextId(),
            customerId = command.customerId,
            type = TransferType.INTERNAL,
            sourceAccountId = command.sourceAccountId,
            destinationAccountId = command.destinationAccountId,
            money = money,
            idempotencyKey = command.idempotencyKey.trim(),
            requestFingerprint = fingerprint,
            now = createdAt,
        )

        return transactionalOperator.inTransaction {
            val claim = persistence.claimInternal(candidate)
            if (!claim.transfer.hasSameRequest(fingerprint)) {
                throw IdempotencyConflictException(claim.transfer.id)
            }
            if (!claim.created) {
                return@inTransaction InternalTransferResult(
                    transfer = claim.transfer,
                    replayed = true,
                )
            }

            // Ownership is checked while the account rows are locked again by
            // post(), closing the gap between authorization and balance change.
            val processing = claim.transfer.markProcessing(clock.instant())
            ledger.post(processing)
            val completed = persistence.save(processing.markCompleted(clock.instant()))
            eventWriter.appendCompleted(completed)

            InternalTransferResult(
                transfer = completed,
                replayed = false,
            )
        }
    }
}
