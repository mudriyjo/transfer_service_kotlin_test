package com.bank.transfer.domain

import java.time.Instant
import java.util.UUID

/** Locally observable lifecycle shared by all transfer workflows. */
enum class TransferStatus {
    CREATED,
    SCHEDULED,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == CANCELLED

    val isSuccessful: Boolean
        get() = this == COMPLETED

    val mayBeRetried: Boolean
        get() = this == FAILED

    fun canTransitionTo(target: TransferStatus): Boolean = when (this) {
        CREATED -> target in setOf(PROCESSING, FAILED, CANCELLED)
        SCHEDULED -> target in setOf(PROCESSING, FAILED, CANCELLED)
        PROCESSING -> target in setOf(COMPLETED, FAILED)
        FAILED -> target in setOf(PROCESSING, CANCELLED)
        COMPLETED, CANCELLED -> false
    }
}

class InvalidTransferStateException(
    val transferId: UUID,
    val current: TransferStatus,
    val requested: TransferStatus,
) : IllegalStateException(
    "Transfer $transferId cannot move from $current to $requested",
)

enum class TransferType {
    INTERNAL,
    EXTERNAL,
    SCHEDULED,
}

/**
 * Immutable aggregate shared by the synchronous, CBS and scheduled flows.
 * Persistence metadata lives on the aggregate because recovery and query paths
 * need to make state decisions without loading a second object graph.
 */
data class Transfer(
    val id: UUID,
    val customerId: UUID,
    val type: TransferType,
    val sourceAccountId: UUID,
    val destinationAccountId: UUID? = null,
    val beneficiaryAccount: String? = null,
    val money: Money,
    val idempotencyKey: String,
    val requestFingerprint: String,
    val status: TransferStatus,
    val cbsReference: String? = null,
    val scheduledAt: Instant? = null,
    val failureCode: String? = null,
    val failureMessage: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long = 0,
) {
    init {
        require(idempotencyKey.isNotBlank()) { "Idempotency key must not be blank" }
        require(requestFingerprint.isNotBlank()) { "Request fingerprint must not be blank" }
        require(createdAt <= updatedAt) { "updatedAt cannot precede createdAt" }
        require(version >= 0) { "Version cannot be negative" }
        require(type == TransferType.SCHEDULED || scheduledAt == null) {
            "Only scheduled transfers may carry scheduledAt"
        }
        require(type != TransferType.SCHEDULED || scheduledAt != null) {
            "A scheduled transfer requires scheduledAt"
        }
        require(status != TransferStatus.SCHEDULED || type == TransferType.SCHEDULED) {
            "Only scheduled transfers may have SCHEDULED status"
        }
        require(
            (type == TransferType.INTERNAL && destinationAccountId != null && beneficiaryAccount == null) ||
                (type != TransferType.INTERNAL && destinationAccountId == null && !beneficiaryAccount.isNullOrBlank()),
        ) {
            "Internal transfers require a destination account; CBS transfers require a beneficiary account"
        }
        require(failureCode == null || status == TransferStatus.FAILED) {
            "Failure details are only valid for FAILED transfers"
        }
    }

    fun markProcessing(at: Instant): Transfer = transitionTo(
        target = TransferStatus.PROCESSING,
        at = at,
    )

    fun markCompleted(at: Instant): Transfer = transitionTo(
        target = TransferStatus.COMPLETED,
        at = at,
    )

    fun markFailed(code: String, message: String?, at: Instant): Transfer {
        require(code.isNotBlank()) { "Failure code must not be blank" }
        return transitionTo(
            target = TransferStatus.FAILED,
            at = at,
            failureCode = code,
            failureMessage = message,
        )
    }

    fun cancel(at: Instant): Transfer = transitionTo(
        target = TransferStatus.CANCELLED,
        at = at,
    )

    fun transitionTo(
        target: TransferStatus,
        at: Instant,
        failureCode: String? = null,
        failureMessage: String? = null,
    ): Transfer {
        if (target == status) return this
        if (!status.canTransitionTo(target)) {
            throw InvalidTransferStateException(id, status, target)
        }
        require(at >= updatedAt) { "State transition timestamp cannot move backwards" }
        require(target == TransferStatus.FAILED || failureCode == null) {
            "Only FAILED transitions can include failure details"
        }

        return copy(
            status = target,
            failureCode = if (target == TransferStatus.FAILED) failureCode else null,
            failureMessage = if (target == TransferStatus.FAILED) failureMessage else null,
            updatedAt = at,
        )
    }

    /**
     * Associates a provider reference with the latest submission attempt.
     */
    fun withCbsReference(reference: String, at: Instant): Transfer {
        require(reference.isNotBlank()) { "CBS reference must not be blank" }
        require(at >= updatedAt) { "CBS reference timestamp cannot move backwards" }
        return copy(cbsReference = reference, updatedAt = at)
    }

    fun reschedule(executeAt: Instant, at: Instant): Transfer {
        require(type == TransferType.SCHEDULED) { "Only scheduled transfers can be rescheduled" }
        require(status == TransferStatus.SCHEDULED) { "Only pending scheduled transfers can be rescheduled" }
        require(at >= updatedAt) { "Reschedule timestamp cannot move backwards" }
        require(executeAt > at) { "New execution time must be in the future" }
        return copy(scheduledAt = executeAt, updatedAt = at)
    }

    fun belongsTo(customer: UUID): Boolean = customerId == customer

    fun hasSameRequest(fingerprint: String): Boolean = requestFingerprint == fingerprint

    companion object {
        fun create(
            id: UUID,
            customerId: UUID,
            type: TransferType,
            sourceAccountId: UUID,
            destinationAccountId: UUID? = null,
            beneficiaryAccount: String? = null,
            money: Money,
            idempotencyKey: String,
            requestFingerprint: String,
            now: Instant,
            scheduledAt: Instant? = null,
            cbsReference: String? = null,
        ): Transfer {
            val initialStatus = if (type == TransferType.SCHEDULED) {
                TransferStatus.SCHEDULED
            } else {
                TransferStatus.CREATED
            }
            return Transfer(
                id = id,
                customerId = customerId,
                type = type,
                sourceAccountId = sourceAccountId,
                destinationAccountId = destinationAccountId,
                beneficiaryAccount = beneficiaryAccount?.trim(),
                money = money,
                idempotencyKey = idempotencyKey.trim(),
                requestFingerprint = requestFingerprint,
                status = initialStatus,
                cbsReference = cbsReference,
                scheduledAt = scheduledAt,
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}
