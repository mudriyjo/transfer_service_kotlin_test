package com.bank.transfer.domain.dto

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class InternalTransferRequest(
    val sourceAccountId: UUID,
    val destinationAccountId: UUID,
    @field:DecimalMin(value = "0.0001", inclusive = true)
    @field:Digits(integer = 15, fraction = 4)
    val amount: BigDecimal,
    @field:Pattern(regexp = "[A-Za-z]{3}")
    val currency: String,
)

data class ExternalTransferRequest(
    /** Retained for backward wire compatibility. */
    val customerId: UUID? = null,
    val sourceAccountId: UUID,
    @field:NotBlank
    @field:Size(max = 128)
    val beneficiaryAccount: String,
    @field:DecimalMin(value = "0.0001", inclusive = true)
    @field:Digits(integer = 15, fraction = 4)
    val amount: BigDecimal,
    @field:Pattern(regexp = "[A-Za-z]{3}")
    val currency: String,
)

data class ScheduledTransferRequest(
    val customerId: UUID? = null,
    val sourceAccountId: UUID,
    @field:NotBlank
    @field:Size(max = 128)
    val beneficiaryAccount: String,
    @field:DecimalMin(value = "0.0001", inclusive = true)
    @field:Digits(integer = 15, fraction = 4)
    val amount: BigDecimal,
    @field:Pattern(regexp = "[A-Za-z]{3}")
    val currency: String,
    @field:NotBlank
    @field:Size(min = 8, max = 128)
    val scheduleId: String,
    val executeAt: Instant,
)

data class RescheduleScheduledTransferRequest(
    val executeAt: Instant,
)
