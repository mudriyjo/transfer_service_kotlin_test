package com.bank.transfer.service

import com.bank.transfer.domain.Transfer
import com.bank.transfer.persistence.outbox.OutboxEvent
import com.bank.transfer.persistence.outbox.OutboxRepository
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

data class TransferCompletedEvent(
    val eventId: UUID,
    val transferId: UUID,
    val customerId: UUID,
    val occurredAt: Instant,
)

/** Serializes transfer events before storing them in the persistence outbox. */
@Service
class TransferOutboxService(
    private val repository: OutboxRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    suspend fun appendCompleted(transfer: Transfer) {
        recordCompleted(
            transferId = transfer.id,
            customerId = transfer.customerId,
            occurredAt = transfer.updatedAt,
        )
    }

    suspend fun recordCompleted(
        transferId: UUID,
        customerId: UUID,
        occurredAt: Instant = clock.instant(),
    ): OutboxEvent {
        val eventId = UUID.randomUUID()
        val payload = objectMapper.writeValueAsString(
            TransferCompletedEvent(
                eventId = eventId,
                transferId = transferId,
                customerId = customerId,
                occurredAt = occurredAt,
            ),
        )
        return repository.insert(
            OutboxEvent(
                id = eventId,
                aggregateId = transferId,
                eventType = TRANSFER_COMPLETED,
                payload = payload,
                createdAt = occurredAt,
                publishedAt = null,
                attempts = 0,
                lastError = null,
            ),
        )
    }

    companion object {
        const val TRANSFER_COMPLETED = "transfer.completed.v1"
    }
}
