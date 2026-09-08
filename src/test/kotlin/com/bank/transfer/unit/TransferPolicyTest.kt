package com.bank.transfer.unit

import com.bank.transfer.domain.InvalidTransferException
import com.bank.transfer.domain.Money
import com.bank.transfer.domain.TransferFingerprintInput
import com.bank.transfer.domain.TransferPolicy
import com.bank.transfer.domain.TransferType
import com.bank.transfer.support.TEST_INSTANT
import com.bank.transfer.support.TestIds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class TransferPolicyTest {
    private val policy = TransferPolicy()

    @Test
    fun `fingerprint is canonical for equivalent monetary and beneficiary values`() {
        val first = input(Money.of("15.00", "EUR"), "DE89 3704 0044 0532 0130 00")
        val equivalent = input(Money.of("15.0000", "EUR"), " de89 3704 0044 0532 0130 00 ")
        val different = input(Money.of("16.00", "EUR"), "DE89 3704 0044 0532 0130 00")

        assertEquals(policy.fingerprint(first), policy.fingerprint(equivalent))
        assertNotEquals(policy.fingerprint(first), policy.fingerprint(different))
    }

    @Test
    fun `rejects an invalid schedule and unsafe idempotency key`() {
        assertThrows(InvalidTransferException::class.java) {
            policy.validateSchedule(TEST_INSTANT, TEST_INSTANT)
        }
        assertThrows(InvalidTransferException::class.java) {
            policy.validateIdempotencyKey("short")
        }
    }

    @Test
    fun `missing external idempotency key is derived from the request fingerprint`() {
        val fingerprint = policy.fingerprint(input(Money.of("15.00", "EUR"), "DE89370400440532013000"))

        assertEquals("implicit:$fingerprint", policy.resolveExternalIdempotencyKey(null, fingerprint))
        assertEquals("implicit:$fingerprint", policy.resolveExternalIdempotencyKey("  ", fingerprint))
        assertEquals("client-key-101", policy.resolveExternalIdempotencyKey(" client-key-101 ", fingerprint))
        policy.validateIdempotencyKey(policy.resolveExternalIdempotencyKey(null, fingerprint))
    }

    private fun input(money: Money, beneficiary: String) = TransferFingerprintInput(
        type = TransferType.EXTERNAL,
        customerId = TestIds.CUSTOMER,
        sourceAccountId = TestIds.SOURCE_ACCOUNT,
        beneficiaryAccount = beneficiary,
        money = money,
    )
}
