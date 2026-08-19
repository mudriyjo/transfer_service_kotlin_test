package com.bank.transfer.integration

import com.bank.transfer.service.CbsErrorMapper
import com.bank.transfer.service.CbsRequestMapper
import com.bank.transfer.service.ScheduleTransferCommand
import com.bank.transfer.service.ScheduledTransferService
import com.bank.transfer.domain.Money
import com.bank.transfer.domain.InvalidTransferException
import com.bank.transfer.domain.TransferPolicy
import com.bank.transfer.domain.TransferNotFoundException
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.support.PostgresTestDatabase
import com.bank.transfer.support.RecordingCbsClient
import com.bank.transfer.support.TEST_INSTANT
import com.bank.transfer.support.TestIds
import com.bank.transfer.support.fixedClock
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("integration")
class ScheduledTransferFlowIT {
    @Test
    fun `scheduled transfer keeps one provider reference through execution`() = runBlocking {
        val database = PostgresTestDatabase.create()
        database.reset()
        database.seedAccount(TestIds.SOURCE_ACCOUNT, balance = BigDecimal("1000.0000"))
        val cbs = RecordingCbsClient()
        val service = scheduledService(database, cbs)

        val scheduled = service.schedule(
            ScheduleTransferCommand(
                customerId = TestIds.CUSTOMER,
                sourceAccountId = TestIds.SOURCE_ACCOUNT,
                beneficiaryAccount = "DE89370400440532013000",
                money = Money.of("42.00", "EUR"),
                idempotencyKey = "schedule-happy-101",
                executeAt = TEST_INSTANT.plusSeconds(300),
            ),
        )
        val completed = service.execute(scheduled.transfer.id)

        assertFalse(scheduled.replayed)
        assertEquals(TransferStatus.SCHEDULED, scheduled.transfer.status)
        assertEquals(TransferStatus.COMPLETED, completed.status)
        assertTrue(completed.cbsReference!!.startsWith("scheduled:"))
        assertEquals(completed.cbsReference, cbs.transferRequests.single().clientReference)
        assertEquals(1, database.outboxRepository.findByAggregateId(completed.id).size)
    }

    @Test
    fun `owner can reschedule and cancel only while transfer is pending`() = runBlocking {
        val database = PostgresTestDatabase.create()
        database.reset()
        database.seedAccount(TestIds.SOURCE_ACCOUNT)
        val service = scheduledService(database)
        val originalTime = TEST_INSTANT.plusSeconds(300)
        val newTime = TEST_INSTANT.plusSeconds(900)
        val scheduled = service.schedule(
            ScheduleTransferCommand(
                customerId = TestIds.CUSTOMER,
                sourceAccountId = TestIds.SOURCE_ACCOUNT,
                beneficiaryAccount = "DE89370400440532013000",
                money = Money.of("18.50", "EUR"),
                idempotencyKey = "schedule-lifecycle-101",
                executeAt = originalTime,
            ),
        ).transfer

        val changed = service.reschedule(
            transferId = scheduled.id,
            customerId = TestIds.CUSTOMER,
            executeAt = newTime,
        )
        assertFalse(changed.replayed)
        assertEquals(newTime, changed.transfer.scheduledAt)
        assertTrue(
            database.persistence.claimScheduledReady(
                now = originalTime,
                leaseUntil = originalTime.plusSeconds(60),
                claimedBy = "lifecycle-worker",
                limit = 1,
            ).isEmpty(),
        )

        val repeatedChange = service.reschedule(
            transferId = scheduled.id,
            customerId = TestIds.CUSTOMER,
            executeAt = newTime,
        )
        assertTrue(repeatedChange.replayed)

        val ownershipFailure = runCatching {
            service.cancel(scheduled.id, TestIds.OTHER_CUSTOMER)
        }.exceptionOrNull()
        assertTrue(ownershipFailure is TransferNotFoundException)

        val cancelled = service.cancel(scheduled.id, TestIds.CUSTOMER)
        assertFalse(cancelled.replayed)
        assertEquals(TransferStatus.CANCELLED, cancelled.transfer.status)

        val repeatedCancel = service.cancel(scheduled.id, TestIds.CUSTOMER)
        assertTrue(repeatedCancel.replayed)
        assertEquals(TransferStatus.CANCELLED, repeatedCancel.transfer.status)

        val editAfterCancel = runCatching {
            service.reschedule(
                transferId = scheduled.id,
                customerId = TestIds.CUSTOMER,
                executeAt = newTime.plusSeconds(60),
            )
        }.exceptionOrNull()
        assertTrue(editAfterCancel is InvalidTransferException)
        assertTrue(
            database.persistence.claimScheduledReady(
                now = newTime,
                leaseUntil = newTime.plusSeconds(60),
                claimedBy = "lifecycle-worker",
                limit = 1,
            ).isEmpty(),
        )
    }

    @Test
    fun `scheduling claim is exclusive and becomes available after release or expiry`() = runBlocking {
        val database = PostgresTestDatabase.create()
        database.reset()
        database.seedAccount(TestIds.SOURCE_ACCOUNT)
        val service = scheduledService(database)
        val dueAt = TEST_INSTANT.plusSeconds(60)
        val transfer = service.schedule(
            ScheduleTransferCommand(
                customerId = TestIds.CUSTOMER,
                sourceAccountId = TestIds.SOURCE_ACCOUNT,
                beneficiaryAccount = "DE89370400440532013000",
                money = Money.of("9.25", "EUR"),
                idempotencyKey = "schedule-claim-101",
                executeAt = dueAt,
            ),
        ).transfer

        val firstClaim = database.persistence.claimScheduledReady(
            now = dueAt,
            leaseUntil = dueAt.plusSeconds(60),
            claimedBy = "scheduler-a",
            limit = 10,
        )
        assertEquals(listOf(transfer.id), firstClaim.map { it.id })

        val competingClaim = database.persistence.claimScheduledReady(
            now = dueAt.plusSeconds(30),
            leaseUntil = dueAt.plusSeconds(90),
            claimedBy = "scheduler-b",
            limit = 10,
        )
        assertTrue(competingClaim.isEmpty())

        val expiredClaim = database.persistence.claimScheduledReady(
            now = dueAt.plusSeconds(61),
            leaseUntil = dueAt.plusSeconds(121),
            claimedBy = "scheduler-b",
            limit = 10,
        )
        assertEquals(listOf(transfer.id), expiredClaim.map { it.id })
        assertFalse(database.persistence.releaseSchedulingClaim(transfer.id, "scheduler-a"))

        assertTrue(
            database.persistence.deferSchedulingClaim(
                transferId = transfer.id,
                claimedBy = "scheduler-b",
                retryAt = dueAt.plusSeconds(180),
            ),
        )
        assertTrue(
            database.persistence.claimScheduledReady(
                now = dueAt.plusSeconds(179),
                leaseUntil = dueAt.plusSeconds(239),
                claimedBy = "scheduler-c",
                limit = 10,
            ).isEmpty(),
        )

        val afterDelay = database.persistence.claimScheduledReady(
            now = dueAt.plusSeconds(181),
            leaseUntil = dueAt.plusSeconds(241),
            claimedBy = "scheduler-c",
            limit = 10,
        )
        assertEquals(listOf(transfer.id), afterDelay.map { it.id })
        assertTrue(database.persistence.releaseSchedulingClaim(transfer.id, "scheduler-c"))

        val afterRelease = database.persistence.claimScheduledReady(
            now = dueAt.plusSeconds(181),
            leaseUntil = dueAt.plusSeconds(241),
            claimedBy = "scheduler-d",
            limit = 10,
        )
        assertEquals(listOf(transfer.id), afterRelease.map { it.id })
        assertTrue(database.persistence.releaseSchedulingClaim(transfer.id, "scheduler-d"))
    }

    private fun scheduledService(
        database: PostgresTestDatabase,
        cbs: RecordingCbsClient = RecordingCbsClient(),
    ): ScheduledTransferService {
        val clock = fixedClock()
        return ScheduledTransferService(
            persistence = database.persistence,
            transferPolicy = TransferPolicy(),
            requestMapper = CbsRequestMapper(clock),
            cbsClient = cbs,
            errorMapper = CbsErrorMapper(clock),
            outboxService = database.outboxService,
            transactions = database.transactionRunner,
            clock = clock,
        )
    }
}
