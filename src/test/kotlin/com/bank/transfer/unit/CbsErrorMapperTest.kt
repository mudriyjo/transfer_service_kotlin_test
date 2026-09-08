package com.bank.transfer.unit

import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.integration.cbs.CbsTimeoutException
import com.bank.transfer.service.CbsErrorMapper
import com.bank.transfer.support.externalTransfer
import com.bank.transfer.support.fixedClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class CbsErrorMapperTest {
    private val mapper = CbsErrorMapper(fixedClock())

    @Test
    fun `timeout keeps a processing transfer unchanged`() {
        val processing = externalTransfer(status = TransferStatus.PROCESSING)
        val updated = mapper.applyFailure(
            processing,
            CbsTimeoutException(requireNotNull(processing.cbsReference), mayHaveCommitted = true),
        )

        assertEquals(TransferStatus.PROCESSING, updated.status)
        assertEquals(processing.cbsReference, updated.cbsReference)
        assertNull(updated.failureCode)
        assertEquals(processing, updated)
    }
}
