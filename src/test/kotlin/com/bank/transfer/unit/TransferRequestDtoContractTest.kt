package com.bank.transfer.unit

import com.bank.transfer.domain.dto.ExternalTransferRequest
import com.bank.transfer.domain.dto.InternalTransferRequest
import com.bank.transfer.domain.dto.ScheduledTransferRequest
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.lang.reflect.Field
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class TransferRequestDtoContractTest {
    @Test
    fun `all transfer amounts and currencies retain their validation contract`() {
        REQUEST_TYPES.forEach { requestType ->
            val minimum = requestType.field("amount").getAnnotation(DecimalMin::class.java)
            val digits = requestType.field("amount").getAnnotation(Digits::class.java)
            val currency = requestType.field("currency").getAnnotation(Pattern::class.java)

            assertNotNull(minimum)
            assertEquals("0.0001", minimum.value)
            assertTrue(minimum.inclusive)
            assertNotNull(digits)
            assertEquals(15, digits.integer)
            assertEquals(4, digits.fraction)
            assertNotNull(currency)
            assertEquals("[A-Za-z]{3}", currency.regexp)
        }
    }

    @Test
    fun `beneficiary and schedule identifiers retain their validation contract`() {
        listOf(ExternalTransferRequest::class.java, ScheduledTransferRequest::class.java).forEach { requestType ->
            val beneficiary = requestType.field("beneficiaryAccount")
            val size = beneficiary.getAnnotation(Size::class.java)

            assertNotNull(beneficiary.getAnnotation(NotBlank::class.java))
            assertEquals(0, size.min)
            assertEquals(128, size.max)
        }

        val scheduleId = ScheduledTransferRequest::class.java.field("scheduleId")
        val size = scheduleId.getAnnotation(Size::class.java)
        assertNotNull(scheduleId.getAnnotation(NotBlank::class.java))
        assertEquals(8, size.min)
        assertEquals(128, size.max)
    }

    @Test
    fun `missing legacy customer remains compatible with external request JSON`() {
        val request = jacksonObjectMapper().readValue<ExternalTransferRequest>(
            """
            {
              "sourceAccountId": "10000000-0000-0000-0000-000000000001",
              "beneficiaryAccount": "DE89370400440532013000",
              "amount": 25.00,
              "currency": "EUR"
            }
            """.trimIndent(),
        )

        assertNull(request.customerId)
        assertEquals("DE89370400440532013000", request.beneficiaryAccount)
    }

    private fun Class<*>.field(name: String): Field = getDeclaredField(name)

    private companion object {
        val REQUEST_TYPES = listOf(
            InternalTransferRequest::class.java,
            ExternalTransferRequest::class.java,
            ScheduledTransferRequest::class.java,
        )
    }
}
