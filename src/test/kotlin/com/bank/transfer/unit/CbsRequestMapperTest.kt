package com.bank.transfer.unit

import com.bank.transfer.domain.Money
import com.bank.transfer.domain.Transfer
import com.bank.transfer.domain.TransferType
import com.bank.transfer.service.CbsRequestMapper
import com.bank.transfer.support.TEST_INSTANT
import com.bank.transfer.support.TestIds
import com.bank.transfer.support.fixedClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class CbsRequestMapperTest {
    private val mapper = CbsRequestMapper(fixedClock())

    @Test
    fun `maps a scheduled transfer without changing its provider reference`() {
        val transfer = Transfer.create(
            id = TestIds.TRANSFER_ONE,
            customerId = TestIds.CUSTOMER,
            type = TransferType.SCHEDULED,
            sourceAccountId = TestIds.SOURCE_ACCOUNT,
            beneficiaryAccount = "DE89370400440532013000",
            money = Money.of("48.25", "EUR"),
            idempotencyKey = "schedule-key-101",
            requestFingerprint = "scheduled-fingerprint-101",
            now = TEST_INSTANT,
            scheduledAt = TEST_INSTANT.plusSeconds(60),
            cbsReference = "scheduled:${TestIds.TRANSFER_ONE}",
        )

        val request = mapper.toRequest(transfer)

        assertEquals(transfer.id, request.transferId)
        assertEquals(transfer.cbsReference, request.clientReference)
        assertEquals(transfer.money.amount, request.amount)
        assertEquals(transfer.beneficiaryAccount, request.destinationAccount)
        assertEquals(TEST_INSTANT, request.requestedAt)
    }
}
