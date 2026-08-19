package com.bank.transfer.service

import com.bank.transfer.persistence.outbox.OutboxDeliveryState
import com.bank.transfer.persistence.outbox.OutboxDeliveryStatistics
import com.bank.transfer.persistence.outbox.OutboxEvent
import com.bank.transfer.persistence.outbox.OutboxRepository
import com.bank.transfer.config.TransferOutboxProperties
import com.bank.transfer.messaging.OutboxMessagePublisher
import com.bank.transfer.observability.OutboxMetrics
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

enum class OutboxPublishOutcome(val metricValue: String) {
    PUBLISHED("published"),
    RETRY_SCHEDULED("retry_scheduled"),
    EXHAUSTED("exhausted"),
    STATE_CHANGED("state_changed"),
    NOT_ELIGIBLE("not_eligible"),
    PERSISTENCE_ERROR("persistence_error"),
}

data class OutboxPublishResult(
    val eventId: UUID,
    val eventType: String,
    val outcome: OutboxPublishOutcome,
    val attemptCount: Int,
    val failure: String? = null,
)

data class OutboxBatchResult(
    val startedAt: Instant,
    val completedAt: Instant,
    val results: List<OutboxPublishResult>,
    val statistics: OutboxDeliveryStatistics,
) {
    init {
        require(!completedAt.isBefore(startedAt)) { "Outbox batch completion cannot precede its start" }
    }

    val selected: Int get() = results.size
    val duration: Duration get() = Duration.between(startedAt, completedAt)
    fun count(outcome: OutboxPublishOutcome): Int = results.count { it.outcome == outcome }
}

/** Publishes persisted events and records their bounded delivery lifecycle. */
@Service
@ConditionalOnProperty(
    prefix = "transfer.jobs",
    name = ["outbox-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class OutboxPublishingService(
    private val repository: OutboxRepository,
    private val messagePublisher: OutboxMessagePublisher,
    private val metrics: OutboxMetrics,
    private val clock: Clock,
    properties: TransferOutboxProperties,
) {
    private val batchSize = properties.batchSize
    private val maxAttempts = properties.maxAttempts
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        require(batchSize in 1..OutboxRepository.MAX_QUERY_LIMIT) {
            "Outbox batch size must be between 1 and ${OutboxRepository.MAX_QUERY_LIMIT}"
        }
        require(maxAttempts in 1..OutboxRepository.MAX_ATTEMPTS_LIMIT) {
            "Outbox max attempts must be between 1 and ${OutboxRepository.MAX_ATTEMPTS_LIMIT}"
        }
    }

    suspend fun publishPending(): OutboxBatchResult {
        val startedAt = clock.instant()
        val events = repository.findPending(maxAttempts, batchSize).toList()
        val results = events.map { event -> publish(event) }
        val statistics = repository.deliveryStatistics(maxAttempts)
        val completedAt = clock.instant().coerceAtLeast(startedAt)
        val batch = OutboxBatchResult(
            startedAt = startedAt,
            completedAt = completedAt,
            results = results,
            statistics = statistics,
        )
        metrics.recordBatch(
            selected = batch.selected,
            duration = batch.duration,
            pending = batch.statistics.pending,
            exhausted = batch.statistics.exhausted,
        )
        logger.info(
            "Outbox batch selected={}, published={}, retryScheduled={}, exhausted={}, " +
                "persistenceErrors={}, pendingAfter={}, exhaustedAfter={}",
            batch.selected, batch.count(OutboxPublishOutcome.PUBLISHED),
            batch.count(OutboxPublishOutcome.RETRY_SCHEDULED), batch.count(OutboxPublishOutcome.EXHAUSTED),
            batch.count(OutboxPublishOutcome.PERSISTENCE_ERROR), statistics.pending, statistics.exhausted,
        )
        return batch
    }

    suspend fun publish(event: OutboxEvent): OutboxPublishResult {
        val initialState = event.deliveryState(maxAttempts)
        if (initialState != OutboxDeliveryState.PENDING) {
            return result(
                event = event,
                outcome = OutboxPublishOutcome.NOT_ELIGIBLE,
                attemptCount = event.attempts,
                failure = "Event is ${initialState.name.lowercase()}",
            )
        }

        val sendFailure = try {
            messagePublisher.publish(event)
            null
        } catch (error: Exception) {
            error
        }
        if (sendFailure != null) return recordFailure(event, sendFailure)

        val mutation = try {
            repository.markPublished(event.id, event.attempts, clock.instant())
        } catch (error: Exception) {
            return persistenceError(event, error, "recording Kafka acknowledgement")
        }

        return if (mutation == null) {
            result(
                event = event,
                outcome = OutboxPublishOutcome.STATE_CHANGED,
                attemptCount = event.attempts,
                failure = "Delivery row changed while the event was in flight",
            )
        } else {
            result(
                event = event,
                outcome = OutboxPublishOutcome.PUBLISHED,
                attemptCount = mutation.attemptCount,
            )
        }
    }

    private suspend fun recordFailure(event: OutboxEvent, error: Exception): OutboxPublishResult {
        val failure = failureSummary(error)
        val mutation = try {
            repository.markFailed(event.id, event.attempts, failure, maxAttempts)
        } catch (persistenceFailure: Exception) {
            return persistenceError(event, persistenceFailure, "recording delivery failure")
        }

        if (mutation == null) {
            return result(
                event = event,
                outcome = OutboxPublishOutcome.STATE_CHANGED,
                attemptCount = event.attempts,
                failure = failure,
            )
        }

        val outcome = if (mutation.state == OutboxDeliveryState.EXHAUSTED) {
            OutboxPublishOutcome.EXHAUSTED
        } else {
            OutboxPublishOutcome.RETRY_SCHEDULED
        }
        logger.warn(
            "Outbox event {} delivery failed on attempt {}/{}; outcome={}; reason={}",
            event.id,
            mutation.attemptCount,
            maxAttempts,
            outcome.metricValue,
            failure,
        )
        return result(
            event = event,
            outcome = outcome,
            attemptCount = mutation.attemptCount,
            failure = failure,
        )
    }

    private fun persistenceError(
        event: OutboxEvent,
        error: Exception,
        operation: String,
    ): OutboxPublishResult {
        val failure = failureSummary(error)
        logger.error("Outbox persistence failed while {} for event {}: {}", operation, event.id, failure)
        return result(
            event = event,
            outcome = OutboxPublishOutcome.PERSISTENCE_ERROR,
            attemptCount = event.attempts,
            failure = failure,
        )
    }

    private fun result(
        event: OutboxEvent,
        outcome: OutboxPublishOutcome,
        attemptCount: Int,
        failure: String? = null,
    ): OutboxPublishResult = OutboxPublishResult(
        eventId = event.id,
        eventType = event.eventType,
        outcome = outcome,
        attemptCount = attemptCount,
        failure = failure,
    ).also { result -> metrics.recordDelivery(result.eventType, result.outcome.metricValue) }

    private fun failureSummary(error: Exception): String {
        val type = error.javaClass.simpleName.ifBlank { "Exception" }
        val message = error.message
            ?.trim()
            ?.replace(WHITESPACE, " ")
            ?.takeIf(String::isNotBlank)
            ?: "No failure message"
        return "$type: $message".take(OutboxRepository.MAX_ERROR_LENGTH)
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")
    }
}
