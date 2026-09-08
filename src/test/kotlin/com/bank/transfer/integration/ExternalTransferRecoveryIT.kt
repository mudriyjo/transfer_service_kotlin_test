package com.bank.transfer.integration

import com.bank.transfer.domain.Money
import com.bank.transfer.domain.TransferPolicy
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.integration.cbs.CbsTimeoutException
import com.bank.transfer.observability.TransferLogContext
import com.bank.transfer.observability.TransferMetrics
import com.bank.transfer.service.CbsErrorMapper
import com.bank.transfer.service.CbsRequestMapper
import com.bank.transfer.service.ExternalTransferCommand
import com.bank.transfer.service.TransferApplicationService
import com.bank.transfer.service.TransferReconciliationService
import com.bank.transfer.support.DeterministicIdGenerator
import com.bank.transfer.support.PostgresTestDatabase
import com.bank.transfer.support.RecordingCbsClient
import com.bank.transfer.support.TEST_INSTANT
import com.bank.transfer.support.TestIds
import com.bank.transfer.support.fixedClock
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.math.BigDecimal
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class ExternalTransferRecoveryIT {
    @Test
    fun `timeout after CBS commit stays processing and retry does not resubmit`() = runBlocking {
        val database = PostgresTestDatabase.create()
        database.reset()
        database.seedAccount(TestIds.SOURCE_ACCOUNT, balance = BigDecimal("1000.0000"))
        val expectedReference = "external:${TestIds.TRANSFER_ONE}"
        val cbs = RecordingCbsClient().apply {
            transferException = CbsTimeoutException(expectedReference, mayHaveCommitted = true)
        }
        val service = externalService(database, cbs)

        val timedOut = service.execute(command("external-recovery-101"))

        assertFalse(timedOut.replayed)
        assertEquals(TransferStatus.PROCESSING, timedOut.transfer.status)
        assertEquals(expectedReference, timedOut.transfer.cbsReference)
        assertEquals(expectedReference, database.persistence.getRequired(timedOut.transfer.id).cbsReference)
        assertEquals(1, cbs.transferRequests.size)
        assertEquals(expectedReference, cbs.transferRequests.single().clientReference)
        assertTrue(database.outboxRepository.findByAggregateId(timedOut.transfer.id).isEmpty())

        val replayed = service.execute(command("external-recovery-101"))

        assertTrue(replayed.replayed)
        assertEquals(timedOut.transfer.id, replayed.transfer.id)
        assertEquals(TransferStatus.PROCESSING, replayed.transfer.status)
        assertEquals(1, cbs.transferRequests.size)

        val report = TransferReconciliationService(
            persistence = database.persistence,
            cbsClient = cbs,
            outboxService = database.outboxService,
            transactions = database.transactionRunner,
            clock = fixedClock(TEST_INSTANT.plusSeconds(60)),
            minimumAge = Duration.ofSeconds(30),
        ).reconcile()

        assertEquals(1, report.selected)
        assertEquals(1, report.completed)
        assertEquals(TransferStatus.COMPLETED, database.persistence.getRequired(timedOut.transfer.id).status)
        assertEquals(listOf(expectedReference), cbs.statusRequests)
        assertEquals(1, database.outboxRepository.findByAggregateId(timedOut.transfer.id).size)
    }

    private fun command(idempotencyKey: String) = ExternalTransferCommand(
        customerId = TestIds.CUSTOMER,
        sourceAccountId = TestIds.SOURCE_ACCOUNT,
        beneficiaryAccount = "DE89370400440532013000",
        money = Money.of("35.50", "EUR"),
        idempotencyKey = idempotencyKey,
    )

    private fun externalService(
        database: PostgresTestDatabase,
        cbs: RecordingCbsClient,
    ): TransferApplicationService {
        val clock = fixedClock()
        return TransferApplicationService(
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
    }
}
