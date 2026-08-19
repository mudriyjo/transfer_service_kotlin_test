package com.bank.transfer.integration

import com.bank.transfer.integration.cbs.CbsOperationStatus
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.service.TransferReconciliationService
import com.bank.transfer.support.PostgresTestDatabase
import com.bank.transfer.support.RecordingCbsClient
import com.bank.transfer.support.TEST_INSTANT
import com.bank.transfer.support.TestIds
import com.bank.transfer.support.externalTransfer
import com.bank.transfer.support.fixedClock
import java.math.BigDecimal
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class ReconciliationIT {
    @Test
    fun `processing transfer is completed from the provider status`() = runBlocking {
        val database = PostgresTestDatabase.create()
        database.reset()
        database.seedAccount(TestIds.SOURCE_ACCOUNT, balance = BigDecimal("1000.0000"))
        val reference = "reconciliation-reference-101"
        val stale = externalTransfer(
            status = TransferStatus.PROCESSING,
            reference = reference,
            now = TEST_INSTANT.minusSeconds(120),
        )
        database.persistence.create(stale)
        val cbs = RecordingCbsClient().apply {
            registerStatus(reference, CbsOperationStatus.COMPLETED)
        }
        val service = TransferReconciliationService(
            persistence = database.persistence,
            cbsClient = cbs,
            outboxService = database.outboxService,
            transactions = database.transactionRunner,
            clock = fixedClock(),
            minimumAge = Duration.ofSeconds(30),
        )

        val report = service.reconcile()

        assertEquals(1, report.selected)
        assertEquals(1, report.completed)
        assertEquals(TransferStatus.COMPLETED, database.persistence.getRequired(stale.id).status)
        assertEquals(listOf(reference), cbs.statusRequests)
        assertEquals(1, database.outboxRepository.findByAggregateId(stale.id).size)
    }
}
