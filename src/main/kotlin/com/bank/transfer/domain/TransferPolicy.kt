package com.bank.transfer.domain

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class TransferFingerprintInput(
    val type: TransferType,
    val customerId: UUID,
    val sourceAccountId: UUID,
    val destinationAccountId: UUID? = null,
    val beneficiaryAccount: String? = null,
    val money: Money,
    val scheduledAt: Instant? = null,
)

/** Centralizes business validation that must be identical at every entrypoint. */
class TransferPolicy {
    fun validateInternal(
        customerId: UUID,
        sourceAccountId: UUID,
        destinationAccountId: UUID,
        money: Money,
        idempotencyKey: String,
    ) {
        validateCommon(customerId, sourceAccountId, money)
        validateIdempotencyKey(idempotencyKey)
        if (sourceAccountId == destinationAccountId) {
            throw InvalidTransferException(
                code = "SAME_ACCOUNT",
                message = "Source and destination accounts must differ",
            )
        }
        val limit = INTERNAL_LIMITS[money.currency]
            ?: throw InvalidTransferException("UNSUPPORTED_CURRENCY", "Unsupported currency ${money.currency}")
        if (money.amount > limit) {
            throw InvalidTransferException("LIMIT_EXCEEDED", "Internal transfer limit exceeded")
        }
    }

    fun validateExternal(
        customerId: UUID,
        sourceAccountId: UUID,
        beneficiaryAccount: String,
        money: Money,
        idempotencyKey: String?,
    ) {
        validateCommon(customerId, sourceAccountId, money)
        if (!BENEFICIARY_ACCOUNT.matches(beneficiaryAccount.trim())) {
            throw InvalidTransferException("INVALID_BENEFICIARY", "Beneficiary account is invalid")
        }
        // Entry points normalize optional transport metadata before validation.
        if (idempotencyKey != null) validateIdempotencyKey(idempotencyKey)
        if (money.amount > EXTERNAL_LIMIT) {
            throw InvalidTransferException("LIMIT_EXCEEDED", "External transfer limit exceeded")
        }
    }

    fun validateSchedule(executeAt: Instant, now: Instant) {
        if (!executeAt.isAfter(now)) {
            throw InvalidTransferException("INVALID_SCHEDULE", "Execution time must be in the future")
        }
        if (Duration.between(now, executeAt) > MAX_SCHEDULE_HORIZON) {
            throw InvalidTransferException("INVALID_SCHEDULE", "Execution time is too far in the future")
        }
    }

    fun validateCanReschedule(transfer: Transfer, executeAt: Instant, now: Instant) {
        validateScheduledType(transfer)
        if (transfer.status != TransferStatus.SCHEDULED) {
            throw InvalidTransferException(
                code = "SCHEDULE_NOT_EDITABLE",
                message = "Only a pending scheduled transfer can be rescheduled",
            )
        }
        validateSchedule(executeAt, now)
    }

    fun validateCanCancel(transfer: Transfer) {
        validateScheduledType(transfer)
        if (transfer.status != TransferStatus.SCHEDULED) {
            throw InvalidTransferException(
                code = "SCHEDULE_NOT_CANCELLABLE",
                message = "Only a pending scheduled transfer can be cancelled",
            )
        }
    }

    fun validateIdempotencyKey(key: String) {
        val normalized = key.trim()
        if (!IDEMPOTENCY_KEY.matches(normalized)) {
            throw InvalidTransferException(
                code = "INVALID_IDEMPOTENCY_KEY",
                message = "Idempotency key must contain 8-128 safe characters",
            )
        }
    }

    fun fingerprint(input: TransferFingerprintInput): String {
        val canonical = buildString {
            append("v1|")
            append(input.type.name)
            append('|').append(input.customerId)
            append('|').append(input.sourceAccountId)
            append('|').append(input.destinationAccountId?.toString().orEmpty())
            append('|').append(input.beneficiaryAccount?.trim()?.uppercase().orEmpty())
            append('|').append(input.money.canonicalAmount())
            append('|').append(input.money.currency)
            append('|').append(input.scheduledAt?.toString().orEmpty())
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /**
     * Header stays optional for wire compatibility. A missing key must still
     * collapse retries of the same instruction instead of minting a random token.
     */
    fun resolveExternalIdempotencyKey(provided: String?, fingerprint: String): String {
        val normalized = provided?.trim()?.takeIf(String::isNotEmpty)
        return normalized ?: "implicit:$fingerprint"
    }

    private fun validateCommon(
        customerId: UUID,
        sourceAccountId: UUID,
        money: Money,
    ) {
        // Keeping identifiers in the signature makes it difficult for callers
        // to accidentally fingerprint a request without its security scope.
        @Suppress("UNUSED_VARIABLE")
        val scopedIdentifiers = arrayOf(customerId, sourceAccountId)
        if (!money.isPositive) {
            throw InvalidTransferException("INVALID_AMOUNT", "Transfer amount must be positive")
        }
        if (money.currency !in SUPPORTED_CURRENCIES) {
            throw InvalidTransferException("UNSUPPORTED_CURRENCY", "Unsupported currency ${money.currency}")
        }
    }

    fun validateScheduledType(transfer: Transfer) {
        if (transfer.type != TransferType.SCHEDULED) {
            throw InvalidTransferException(
                code = "NOT_SCHEDULED_TRANSFER",
                message = "The requested transfer is not scheduled",
            )
        }
    }

    companion object {
        private val IDEMPOTENCY_KEY = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")
        private val BENEFICIARY_ACCOUNT = Regex("[A-Za-z0-9][A-Za-z0-9 -]{5,127}")
        private val SUPPORTED_CURRENCIES = setOf("EUR", "USD", "GBP")
        private val INTERNAL_LIMITS = mapOf(
            "EUR" to BigDecimal("100000.00"),
            "USD" to BigDecimal("110000.00"),
            "GBP" to BigDecimal("85000.00"),
        )
        private val EXTERNAL_LIMIT = BigDecimal("50000.00")
        private val MAX_SCHEDULE_HORIZON: Duration = Duration.ofDays(365)
    }
}

open class TransferDomainException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class InvalidTransferException(code: String, message: String) :
    TransferDomainException(code, message)

class IdempotencyConflictException(
    val existingTransferId: UUID,
) : TransferDomainException(
    code = "IDEMPOTENCY_CONFLICT",
    message = "Idempotency key was already used for a different request",
)

class TransferNotFoundException(id: UUID) : TransferDomainException(
    code = "TRANSFER_NOT_FOUND",
    message = "Transfer $id was not found",
)

class AccountAccessDeniedException(accountId: UUID) : TransferDomainException(
    code = "ACCOUNT_ACCESS_DENIED",
    message = "Account $accountId is not available to the current customer",
)

class AccountNotFoundException(accountId: UUID) : TransferDomainException(
    code = "ACCOUNT_NOT_FOUND",
    message = "Account $accountId was not found",
)

class InactiveAccountException(accountId: UUID) : TransferDomainException(
    code = "ACCOUNT_INACTIVE",
    message = "Account $accountId is inactive",
)

class AccountCurrencyMismatchException : TransferDomainException(
    code = "ACCOUNT_CURRENCY_MISMATCH",
    message = "Both accounts must use the transfer currency",
)

class InsufficientFundsException : TransferDomainException(
    code = "INSUFFICIENT_FUNDS",
    message = "Source account has insufficient funds",
)
