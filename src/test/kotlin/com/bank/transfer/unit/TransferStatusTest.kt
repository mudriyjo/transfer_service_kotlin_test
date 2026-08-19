package com.bank.transfer.unit

import com.bank.transfer.domain.InvalidTransferStateException
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.support.TEST_INSTANT
import com.bank.transfer.support.externalTransfer
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class TransferStatusTest {
    @Test
    fun `created transfer follows the normal completion lifecycle`() {
        val created = externalTransfer(reference = null)
        val processing = created.markProcessing(TEST_INSTANT.plusSeconds(1))
        val completed = processing.markCompleted(TEST_INSTANT.plusSeconds(2))

        assertEquals(TransferStatus.COMPLETED, completed.status)
        assertTrue(completed.status.isTerminal)
        assertTrue(completed.status.isSuccessful)
        assertFalse(completed.status.mayBeRetried)
        assertEquals(Duration.ofSeconds(2), Duration.between(created.createdAt, completed.updatedAt))
    }

    @Test
    fun `terminal transfer rejects another state transition`() {
        val completed = externalTransfer()
            .markProcessing(TEST_INSTANT)
            .markCompleted(TEST_INSTANT)

        assertThrows(InvalidTransferStateException::class.java) {
            completed.markFailed("LATE_FAILURE", null, TEST_INSTANT.plusSeconds(1))
        }
    }
}
