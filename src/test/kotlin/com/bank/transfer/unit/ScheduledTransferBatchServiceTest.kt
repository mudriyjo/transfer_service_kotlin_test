package com.bank.transfer.unit

import com.bank.transfer.config.TransferJobsProperties
import com.bank.transfer.domain.Money
import com.bank.transfer.domain.Transfer
import com.bank.transfer.domain.TransferType
import com.bank.transfer.persistence.transfer.TransferRepository
import com.bank.transfer.service.ScheduledTransferBatchService
import com.bank.transfer.service.ScheduledTransferService
import com.bank.transfer.support.TEST_INSTANT
import com.bank.transfer.support.TestIds
import com.bank.transfer.support.fixedClock
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

@Tag("unit")
class ScheduledTransferBatchServiceTest {
    private val transfer = scheduledTransfer()

    @Test
    fun `empty claim finishes without executing a transfer`() = runBlocking {
        val repository = mock(TransferRepository::class.java)
        val transferService = mock(ScheduledTransferService::class.java)
        `when`(repository.claimScheduledReady(anyValue(), anyValue(), anyValue(), equalTo(1)))
            .thenReturn(emptyList())

        batchService(repository, transferService).executeDue()

        verify(repository).claimScheduledReady(anyValue(), anyValue(), anyValue(), equalTo(1))
        verifyNoInteractions(transferService)
        Unit
    }

    @Test
    fun `successful execution releases its claim`() = runBlocking {
        val repository = mock(TransferRepository::class.java)
        val transferService = mock(ScheduledTransferService::class.java)
        `when`(repository.claimScheduledReady(anyValue(), anyValue(), anyValue(), equalTo(1)))
            .thenReturn(listOf(transfer))
        `when`(transferService.execute(transfer.id)).thenReturn(transfer)
        `when`(repository.releaseSchedulingClaim(equalTo(transfer.id), anyValue())).thenReturn(true)

        batchService(repository, transferService).executeDue()

        verify(transferService).execute(transfer.id)
        verify(repository).releaseSchedulingClaim(equalTo(transfer.id), anyValue())
        verify(repository, never()).deferSchedulingClaim(equalTo(transfer.id), anyValue(), anyValue())
        Unit
    }

    @Test
    fun `failed execution defers its claim`() = runBlocking {
        val repository = mock(TransferRepository::class.java)
        val transferService = mock(ScheduledTransferService::class.java)
        `when`(repository.claimScheduledReady(anyValue(), anyValue(), anyValue(), equalTo(1)))
            .thenReturn(listOf(transfer))
        `when`(transferService.execute(transfer.id)).thenThrow(IllegalStateException("execution failed"))
        `when`(repository.deferSchedulingClaim(equalTo(transfer.id), anyValue(), anyValue())).thenReturn(true)

        batchService(repository, transferService).executeDue()

        verify(repository).deferSchedulingClaim(
            equalTo(transfer.id),
            anyValue(),
            equalTo(TEST_INSTANT.plusSeconds(5)),
        )
        verify(repository, never()).releaseSchedulingClaim(equalTo(transfer.id), anyValue())
        Unit
    }

    @Test
    fun `failed defer falls back to releasing the claim`() = runBlocking {
        val repository = mock(TransferRepository::class.java)
        val transferService = mock(ScheduledTransferService::class.java)
        `when`(repository.claimScheduledReady(anyValue(), anyValue(), anyValue(), equalTo(1)))
            .thenReturn(listOf(transfer))
        `when`(transferService.execute(transfer.id)).thenThrow(IllegalStateException("execution failed"))
        `when`(repository.deferSchedulingClaim(equalTo(transfer.id), anyValue(), anyValue())).thenReturn(false)
        `when`(repository.releaseSchedulingClaim(equalTo(transfer.id), anyValue())).thenReturn(true)

        batchService(repository, transferService).executeDue()

        verify(repository).deferSchedulingClaim(
            equalTo(transfer.id),
            anyValue(),
            equalTo(TEST_INSTANT.plusSeconds(5)),
        )
        verify(repository).releaseSchedulingClaim(equalTo(transfer.id), anyValue())
        Unit
    }

    @Test
    fun `cancellation is rethrown without changing the claim`() {
        val repository = mock(TransferRepository::class.java)
        val transferService = mock(ScheduledTransferService::class.java)
        runBlocking {
            `when`(repository.claimScheduledReady(anyValue(), anyValue(), anyValue(), equalTo(1)))
                .thenReturn(listOf(transfer))
            `when`(transferService.execute(transfer.id)).thenThrow(CancellationException("cancelled"))
        }

        assertThrows(CancellationException::class.java) {
            runBlocking { batchService(repository, transferService).executeDue() }
        }

        runBlocking {
            verify(repository, never()).deferSchedulingClaim(equalTo(transfer.id), anyValue(), anyValue())
            verify(repository, never()).releaseSchedulingClaim(equalTo(transfer.id), anyValue())
        }
    }

    private fun batchService(
        repository: TransferRepository,
        transferService: ScheduledTransferService,
    ): ScheduledTransferBatchService = ScheduledTransferBatchService(
        persistence = repository,
        scheduledTransferService = transferService,
        clock = fixedClock(),
        properties = TransferJobsProperties().apply {
            batchSize = 1
            schedulingLease = Duration.ofSeconds(30)
            schedulingFailureDelay = Duration.ofSeconds(5)
        },
    )

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T> anyValue(): T {
            ArgumentMatchers.any<T>()
            return null as T
        }

        fun <T> equalTo(value: T): T {
            ArgumentMatchers.eq(value)
            return value
        }

        fun scheduledTransfer(): Transfer = Transfer.create(
            id = TestIds.TRANSFER_ONE,
            customerId = TestIds.CUSTOMER,
            type = TransferType.SCHEDULED,
            sourceAccountId = TestIds.SOURCE_ACCOUNT,
            beneficiaryAccount = "DE89370400440532013000",
            money = Money.of("10.00", "EUR"),
            idempotencyKey = "scheduled-batch-101",
            requestFingerprint = "scheduled-batch-fingerprint-101",
            now = TEST_INSTANT.minusSeconds(60),
            scheduledAt = TEST_INSTANT.minusSeconds(1),
            cbsReference = "scheduled:${TestIds.TRANSFER_ONE}",
        )
    }
}
