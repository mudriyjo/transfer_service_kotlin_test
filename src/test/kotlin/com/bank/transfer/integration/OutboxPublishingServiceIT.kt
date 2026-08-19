package com.bank.transfer.integration

import com.bank.transfer.config.TransferOutboxProperties
import com.bank.transfer.messaging.KafkaOutboxMessagePublisher
import com.bank.transfer.observability.OutboxMetrics
import com.bank.transfer.persistence.outbox.OutboxDeliveryState
import com.bank.transfer.persistence.outbox.OutboxRepository
import com.bank.transfer.service.OutboxPublishOutcome
import com.bank.transfer.service.OutboxPublishingService
import com.bank.transfer.support.PostgresTestDatabase
import com.bank.transfer.support.TestIds
import com.bank.transfer.support.externalTransfer
import com.bank.transfer.support.fixedClock
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.math.BigDecimal
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult

@Tag("integration")
class OutboxPublishingServiceIT {
    @Test
    fun `acknowledged event is marked as published and reported in batch outcome`() = runBlocking {
        val database = preparedDatabase()
        val transfer = externalTransfer()
        database.persistence.create(transfer)
        val event = database.outboxService.recordCompleted(
            transferId = transfer.id,
            customerId = transfer.customerId,
            occurredAt = transfer.updatedAt,
        )
        val kafka = successfulKafka()
        val metrics = SimpleMeterRegistry()
        val publisher = publisher(database, kafka, metrics, maxAttempts = 3)

        val batch = publisher.publishPending()

        val stored = database.outboxRepository.findByAggregateId(transfer.id).single()
        assertEquals(event.id, stored.id)
        assertEquals(1, stored.attempts)
        assertNotNull(stored.publishedAt)
        assertNull(stored.lastError)
        assertEquals(OutboxDeliveryState.PUBLISHED, stored.deliveryState(maxAttempts = 3))
        assertEquals(1, batch.selected)
        assertEquals(1, batch.count(OutboxPublishOutcome.PUBLISHED))
        assertEquals(0, batch.count(OutboxPublishOutcome.RETRY_SCHEDULED))
        assertEquals(0, batch.count(OutboxPublishOutcome.EXHAUSTED))
        assertEquals(1L, batch.statistics.published)
        assertEquals(0L, batch.statistics.pending)
        assertFalse(
            metrics.meters
                .flatMap { meter -> meter.id.tags }
                .any { tag -> tag.key == "transfer_id" },
            "Outbox metrics must not create a time series per transfer",
        )
    }

    @Test
    fun `failed events stop being selected after the configured maximum attempts`() = runBlocking {
        val database = preparedDatabase()
        val transfer = externalTransfer()
        database.persistence.create(transfer)
        database.outboxService.recordCompleted(
            transferId = transfer.id,
            customerId = transfer.customerId,
            occurredAt = transfer.updatedAt,
        )
        val kafka = failingKafka("broker unavailable\n${"x".repeat(1_000)}")
        val publisher = publisher(
            database = database,
            kafka = kafka,
            metrics = SimpleMeterRegistry(),
            maxAttempts = 2,
        )

        val firstBatch = publisher.publishPending()
        val secondBatch = publisher.publishPending()
        val thirdBatch = publisher.publishPending()

        assertEquals(OutboxPublishOutcome.RETRY_SCHEDULED, firstBatch.results.single().outcome)
        assertEquals(1, firstBatch.results.single().attemptCount)
        assertEquals(OutboxPublishOutcome.EXHAUSTED, secondBatch.results.single().outcome)
        assertEquals(2, secondBatch.results.single().attemptCount)
        assertEquals(0, thirdBatch.selected)
        assertEquals(1L, thirdBatch.statistics.exhausted)
        assertEquals(0L, thirdBatch.statistics.pending)

        val exhausted = database.outboxRepository.findExhausted(maxAttempts = 2, limit = 10).toList()
        val stored = exhausted.single()
        assertEquals(2, stored.attempts)
        assertEquals(OutboxDeliveryState.EXHAUSTED, stored.deliveryState(maxAttempts = 2))
        val storedError = requireNotNull(stored.lastError)
        assertTrue(storedError.length <= OutboxRepository.MAX_ERROR_LENGTH)
        assertFalse(storedError.contains('\n'))
        verify(kafka, times(2)).send(anyString(), anyString(), anyString())
        Unit
    }

    @Test
    fun `compare and set prevents a stale delivery result from advancing attempts`() = runBlocking {
        val database = preparedDatabase()
        val transfer = externalTransfer()
        database.persistence.create(transfer)
        val event = database.outboxService.recordCompleted(
            transferId = transfer.id,
            customerId = transfer.customerId,
            occurredAt = transfer.updatedAt,
        )

        val firstMutation = database.outboxRepository.markFailed(
            id = event.id,
            expectedAttempts = 0,
            error = "first failure",
            maxAttempts = 3,
        )
        val staleMutation = database.outboxRepository.markFailed(
            id = event.id,
            expectedAttempts = 0,
            error = "stale failure",
            maxAttempts = 3,
        )

        assertEquals(1, firstMutation?.attemptCount)
        assertEquals(OutboxDeliveryState.PENDING, firstMutation?.state)
        assertNull(staleMutation)
        val statistics = database.outboxRepository.deliveryStatistics(maxAttempts = 3)
        assertEquals(1L, statistics.pending)
        assertEquals(0L, statistics.exhausted)
        assertEquals(0L, statistics.published)
        assertEquals(1, statistics.highestAttemptCount)
    }

    private suspend fun preparedDatabase(): PostgresTestDatabase {
        val database = PostgresTestDatabase.create()
        database.reset()
        database.seedAccount(TestIds.SOURCE_ACCOUNT, balance = BigDecimal("1000.0000"))
        return database
    }

    private fun publisher(
        database: PostgresTestDatabase,
        kafka: KafkaTemplate<String, String>,
        metrics: SimpleMeterRegistry,
        maxAttempts: Int,
    ): OutboxPublishingService {
        val properties = TransferOutboxProperties.of(
            topic = "transfer-events-test",
            batchSize = 10,
            maxAttempts = maxAttempts,
        )
        return OutboxPublishingService(
            repository = database.outboxRepository,
            messagePublisher = KafkaOutboxMessagePublisher(kafka, properties),
            metrics = OutboxMetrics(metrics),
            clock = fixedClock(),
            properties = properties,
        )
    }

    private fun successfulKafka(): KafkaTemplate<String, String> {
        @Suppress("UNCHECKED_CAST")
        val kafka = mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
        @Suppress("UNCHECKED_CAST")
        val sendResult = mock(SendResult::class.java) as SendResult<String, String>
        `when`(kafka.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(sendResult))
        return kafka
    }

    private fun failingKafka(message: String): KafkaTemplate<String, String> {
        @Suppress("UNCHECKED_CAST")
        val kafka = mock(KafkaTemplate::class.java) as KafkaTemplate<String, String>
        val failure = CompletableFuture<SendResult<String, String>>()
        failure.completeExceptionally(IllegalStateException(message))
        `when`(kafka.send(anyString(), anyString(), anyString())).thenReturn(failure)
        return kafka
    }
}
