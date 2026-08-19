package com.bank.transfer.integration.cbs
import com.bank.transfer.support.DeterministicCbsHttpStub
import com.bank.transfer.support.StubFailureMode
import com.bank.transfer.support.TEST_INSTANT
import com.bank.transfer.support.TestIds
import com.bank.transfer.support.fixedClock
import java.math.BigDecimal
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.reactive.function.client.WebClient

@Tag("contract")
class CbsStatusContractTest {
    @Test
    fun `local CBS supports deterministic processing to completion lifecycle`() = runBlocking {
        val control = LocalCbsControl(configuredMode = "NONE")
        val client = LocalCbsTransferClient(control, fixedClock())
        val request = CbsTransferRequest(
            transferId = TestIds.TRANSFER_ONE,
            clientReference = "local-lifecycle-reference-101",
            sourceAccountId = TestIds.SOURCE_ACCOUNT,
            destinationAccount = "DE89370400440532013000",
            amount = BigDecimal("19.50"),
            currency = "EUR",
            requestedAt = TEST_INSTANT,
        )
        control.respondNext(CbsOperationStatus.PROCESSING)

        val submitted = client.transfer(request)
        val replay = client.transfer(request)
        val completed = client.complete(request.clientReference)
        val status = client.status(request.clientReference)

        assertEquals(CbsOperationStatus.PROCESSING, submitted.status)
        assertEquals(submitted, replay)
        assertEquals(CbsOperationStatus.COMPLETED, completed.status)
        assertEquals(CbsOperationStatus.COMPLETED, status.status)
        assertEquals(request.transferId, client.operation(request.clientReference)?.transferId)
        assertEquals(1, client.operations().size)
        assertEquals(2, control.statistics().commands)
        assertEquals(1, control.statistics().idempotentReplays)
        assertEquals(1, control.statistics().statusLookups)
    }

    @Test
    fun `status lookup resolves an operation after its command result was unavailable`() {
        DeterministicCbsHttpStub.start().use { stub ->
            val client = HttpCbsTransferClient(
                builder = WebClient.builder(),
                baseUrl = stub.baseUrl,
                requestTimeout = Duration.ofSeconds(2),
                totalAttempts = 1,
            )
            val request = CbsTransferRequest(
                transferId = TestIds.TRANSFER_TWO,
                clientReference = "contract-reference-after-commit-101",
                sourceAccountId = TestIds.SOURCE_ACCOUNT,
                destinationAccount = "DE89370400440532013000",
                amount = BigDecimal("31.00"),
                currency = "EUR",
                requestedAt = TEST_INSTANT,
            )
            stub.failNext(StubFailureMode.AFTER_COMMIT)

            val unavailable = assertThrows<RuntimeException> {
                runBlocking { client.transfer(request) }
            }
            assertTrue(unavailable.causes().any { it is CbsRejectedException })
            val status = runBlocking { client.status(request.clientReference) }

            assertEquals(CbsOperationStatus.COMPLETED, status.status)
            assertEquals(request.clientReference, status.clientReference)
            assertNotNull(status.operationId)
        }
    }

    private fun Throwable.causes(): Sequence<Throwable> =
        generateSequence(this) { current -> current.cause?.takeUnless { it === current } }
}
