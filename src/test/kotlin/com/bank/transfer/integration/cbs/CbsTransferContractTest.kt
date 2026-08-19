package com.bank.transfer.integration.cbs
import com.bank.transfer.support.DeterministicCbsHttpStub
import com.bank.transfer.support.TEST_INSTANT
import com.bank.transfer.support.TestIds
import java.math.BigDecimal
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.reactive.function.client.WebClient

@Tag("contract")
class CbsTransferContractTest {
    @Test
    fun `same reference and payload returns the original operation`() {
        DeterministicCbsHttpStub.start().use { stub ->
            val client = client(stub)
            val request = request("contract-reference-101")

            val first = runBlocking { client.transfer(request) }
            val replay = runBlocking { client.transfer(request) }

            assertEquals(first.operationId, replay.operationId)
            assertEquals(first.status, replay.status)
            assertEquals(request.clientReference, replay.clientReference)
        }
    }

    @Test
    fun `same reference with a different payload is rejected`() {
        DeterministicCbsHttpStub.start().use { stub ->
            val client = client(stub)
            val original = request("contract-reference-102")
            runBlocking { client.transfer(original) }

            val error = assertThrows<RuntimeException> {
                runBlocking {
                    client.transfer(original.copy(amount = BigDecimal("99.00")))
                }
            }
            assertTrue(error.causes().any { it is CbsReferenceConflictException })
        }
    }

    private fun client(stub: DeterministicCbsHttpStub) = HttpCbsTransferClient(
        builder = WebClient.builder(),
        baseUrl = stub.baseUrl,
        requestTimeout = Duration.ofSeconds(2),
        totalAttempts = 1,
    )

    private fun request(reference: String) = CbsTransferRequest(
        transferId = TestIds.TRANSFER_ONE,
        clientReference = reference,
        sourceAccountId = TestIds.SOURCE_ACCOUNT,
        destinationAccount = "DE89370400440532013000",
        amount = BigDecimal("25.00"),
        currency = "EUR",
        requestedAt = TEST_INSTANT,
    )

    private fun Throwable.causes(): Sequence<Throwable> =
        generateSequence(this) { current -> current.cause?.takeUnless { it === current } }
}
