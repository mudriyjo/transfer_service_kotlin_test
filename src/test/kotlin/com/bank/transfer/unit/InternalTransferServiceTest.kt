package com.bank.transfer.unit

import com.bank.transfer.domain.InvalidTransferException
import com.bank.transfer.domain.TransferPolicy
import com.bank.transfer.persistence.ledger.LedgerRepository
import com.bank.transfer.persistence.transaction.ReactiveTransactionRunner
import com.bank.transfer.persistence.transfer.TransferRepository
import com.bank.transfer.service.InternalTransferCommand
import com.bank.transfer.service.InternalTransferService
import com.bank.transfer.service.TransferOutboxService
import com.bank.transfer.service.TransferIdGenerator
import com.bank.transfer.support.TestIds
import com.bank.transfer.support.fixedClock
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

@Tag("unit")
class InternalTransferServiceTest {
    @Test
    fun `validates the command before starting persistence work`() {
        val service = InternalTransferService(
            persistence = mock(TransferRepository::class.java),
            ledger = mock(LedgerRepository::class.java),
            eventWriter = mock(TransferOutboxService::class.java),
            policy = TransferPolicy(),
            transactionalOperator = mock(ReactiveTransactionRunner::class.java),
            clock = fixedClock(),
            idGenerator = mock(TransferIdGenerator::class.java),
        )

        val error = assertThrows<InvalidTransferException> {
            runBlocking {
                service.execute(
                    InternalTransferCommand(
                        customerId = TestIds.CUSTOMER,
                        sourceAccountId = TestIds.SOURCE_ACCOUNT,
                        destinationAccountId = TestIds.SOURCE_ACCOUNT,
                        amount = BigDecimal("10.00"),
                        currency = "EUR",
                        idempotencyKey = "internal-key-101",
                    ),
                )
            }
        }

        assertEquals("SAME_ACCOUNT", error.code)
    }
}
