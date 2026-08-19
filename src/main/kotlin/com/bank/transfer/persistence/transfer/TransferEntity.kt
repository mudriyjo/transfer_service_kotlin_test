package com.bank.transfer.persistence.transfer

import com.bank.transfer.domain.Money
import com.bank.transfer.domain.Transfer
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.domain.TransferType
import io.r2dbc.spi.Row
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Flat database representation kept separate from the aggregate. */
data class TransferEntity(
    val id: UUID,
    val customerId: UUID,
    val transferType: String,
    val sourceAccountId: UUID,
    val destinationAccountId: UUID?,
    val beneficiaryAccount: String?,
    val amount: BigDecimal,
    val currency: String,
    val idempotencyKey: String,
    val requestFingerprint: String,
    val status: String,
    val cbsReference: String?,
    val scheduledAt: Instant?,
    val failureCode: String?,
    val failureMessage: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
) {
    fun toDomain(): Transfer = Transfer(
        id = id,
        customerId = customerId,
        type = enumValueOf(transferType),
        sourceAccountId = sourceAccountId,
        destinationAccountId = destinationAccountId,
        beneficiaryAccount = beneficiaryAccount,
        money = Money.of(amount, currency),
        idempotencyKey = idempotencyKey,
        requestFingerprint = requestFingerprint,
        status = enumValueOf(status),
        cbsReference = cbsReference,
        scheduledAt = scheduledAt,
        failureCode = failureCode,
        failureMessage = failureMessage,
        createdAt = createdAt,
        updatedAt = updatedAt,
        version = version,
    )

    companion object {
        fun fromDomain(transfer: Transfer): TransferEntity = TransferEntity(
            id = transfer.id,
            customerId = transfer.customerId,
            transferType = transfer.type.name,
            sourceAccountId = transfer.sourceAccountId,
            destinationAccountId = transfer.destinationAccountId,
            beneficiaryAccount = transfer.beneficiaryAccount,
            amount = transfer.money.amount,
            currency = transfer.money.currency,
            idempotencyKey = transfer.idempotencyKey,
            requestFingerprint = transfer.requestFingerprint,
            status = transfer.status.name,
            cbsReference = transfer.cbsReference,
            scheduledAt = transfer.scheduledAt,
            failureCode = transfer.failureCode,
            failureMessage = transfer.failureMessage,
            createdAt = transfer.createdAt,
            updatedAt = transfer.updatedAt,
            version = transfer.version,
        )

        fun fromRow(row: Row): TransferEntity = TransferEntity(
            id = row.required("id", UUID::class.java),
            customerId = row.required("customer_id", UUID::class.java),
            transferType = row.required("transfer_type", String::class.java),
            sourceAccountId = row.required("source_account_id", UUID::class.java),
            destinationAccountId = row.get("destination_account_id", UUID::class.java),
            beneficiaryAccount = row.get("beneficiary_account", String::class.java),
            amount = row.required("amount", BigDecimal::class.java),
            currency = row.required("currency", String::class.java),
            idempotencyKey = row.required("idempotency_key", String::class.java),
            requestFingerprint = row.required("request_fingerprint", String::class.java),
            status = row.required("status", String::class.java),
            cbsReference = row.get("cbs_reference", String::class.java),
            scheduledAt = row.get("scheduled_at", Instant::class.java),
            failureCode = row.get("failure_code", String::class.java),
            failureMessage = row.get("failure_message", String::class.java),
            createdAt = row.required("created_at", Instant::class.java),
            updatedAt = row.required("updated_at", Instant::class.java),
            version = row.required("version", Long::class.javaObjectType),
        )

        private fun <T : Any> Row.required(name: String, type: Class<T>): T =
            get(name, type) ?: error("Column $name must not be null")
    }
}

data class TransferQuery(
    val customerId: UUID,
    val status: TransferStatus? = null,
    val type: TransferType? = null,
    val limit: Int = 50,
    val offset: Long = 0,
)
