package com.bank.transfer.integration

import com.bank.transfer.service.ExternalTransferCommand
import com.bank.transfer.service.TransferApplicationService
import com.bank.transfer.service.CbsErrorMapper
import com.bank.transfer.service.CbsRequestMapper
import com.bank.transfer.domain.Money
import com.bank.transfer.domain.TransferPolicy
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.observability.TransferLogContext
import com.bank.transfer.observability.TransferMetrics
import com.bank.transfer.support.DeterministicIdGenerator
import com.bank.transfer.support.PostgresTestDatabase
import com.bank.transfer.support.RecordingCbsClient
import com.bank.transfer.support.TestIds
import com.bank.transfer.support.fixedClock
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class ExternalHappyPathIT {
    @Test
    fun `completed CBS response is persisted and emits an outbox event`() = runBlocking {
        val database = PostgresTestDatabase.create()
        database.reset()
        database.seedAccount(TestIds.SOURCE_ACCOUNT, balance = BigDecimal("1000.0000"))
        val cbs = RecordingCbsClient()
        val clock = fixedClock()
        val service = TransferApplicationService(
            persistence = database.persistence,
            policy = TransferPolicy(),
            requestMapper = CbsRequestMapper(clock),
            cbsClient = cbs,
            errorMapper = CbsErrorMapper(clock),
            outbox = database.outboxService,
            transactions = database.transactionRunner,
            transferIdGenerator = DeterministicIdGenerator(TestIds.TRANSFER_ONE),
            clock = clock,
            metrics = TransferMetrics(SimpleMeterRegistry()),
            logContext = TransferLogContext(),
        )

        val result = service.execute(
            ExternalTransferCommand(
                customerId = TestIds.CUSTOMER,
                sourceAccountId = TestIds.SOURCE_ACCOUNT,
                beneficiaryAccount = "DE89370400440532013000",
                money = Money.of("35.50", "EUR"),
                idempotencyKey = "external-happy-101",
            ),
        )

        assertFalse(result.replayed)
        assertEquals(TransferStatus.COMPLETED, result.transfer.status)
        assertEquals(TransferStatus.COMPLETED, database.persistence.getRequired(result.transfer.id).status)
        assertEquals("external:${TestIds.TRANSFER_ONE}", result.transfer.cbsReference)
        assertEquals(1, cbs.transferRequests.size)
        assertEquals(result.transfer.cbsReference, cbs.transferRequests.single().clientReference)
        assertEquals(1, database.outboxRepository.findByAggregateId(result.transfer.id).size)
    }
}
