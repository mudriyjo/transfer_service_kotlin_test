package com.bank.transfer.persistence.outbox

import java.time.Instant
import java.util.UUID

enum class OutboxDeliveryState {
    PENDING,
    EXHAUSTED,
    PUBLISHED,
}

data class OutboxEvent(
    val id: UUID,
    val aggregateId: UUID,
    val eventType: String,
    val payload: String,
    val createdAt: Instant,
    val publishedAt: Instant?,
    val attempts: Int,
    val lastError: String?,
) {
    fun deliveryState(maxAttempts: Int): OutboxDeliveryState {
        require(maxAttempts > 0) { "Maximum delivery attempts must be positive" }
        return when {
            publishedAt != null -> OutboxDeliveryState.PUBLISHED
            attempts >= maxAttempts -> OutboxDeliveryState.EXHAUSTED
            else -> OutboxDeliveryState.PENDING
        }
    }
}

data class OutboxDeliveryStatistics(
    val pending: Long,
    val exhausted: Long,
    val published: Long,
    val highestAttemptCount: Int,
)

data class OutboxDeliveryMutation(
    val eventId: UUID,
    val previousAttempts: Int,
    val attemptCount: Int,
    val state: OutboxDeliveryState,
)
