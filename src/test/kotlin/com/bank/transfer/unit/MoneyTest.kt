package com.bank.transfer.unit

import com.bank.transfer.domain.Money
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class MoneyTest {
    @Test
    fun `normalizes currency and preserves a stable canonical amount`() {
        val money = Money.of("12.3400", " eur ")

        assertEquals("EUR", money.currency)
        assertEquals("12.34", money.canonicalAmount())
        assertEquals(1234L, money.toMinorUnits())
    }

    @Test
    fun `arithmetic requires matching currencies`() {
        val total = Money.of("10.00", "EUR") + Money.of("2.50", "EUR")

        assertEquals(BigDecimal("12.50"), total.amount)
        assertThrows(IllegalArgumentException::class.java) {
            total + Money.of("1.00", "USD")
        }
    }
}
