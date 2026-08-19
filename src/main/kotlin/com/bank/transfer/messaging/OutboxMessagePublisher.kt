package com.bank.transfer.messaging

import com.bank.transfer.persistence.outbox.OutboxEvent
import com.bank.transfer.config.TransferOutboxProperties
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

fun interface OutboxMessagePublisher {
    suspend fun publish(event: OutboxEvent)
}

/** Sends one already-persisted outbox event to Kafka. */
@Component
@ConditionalOnProperty(
    prefix = "transfer.jobs",
    name = ["outbox-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class KafkaOutboxMessagePublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    properties: TransferOutboxProperties,
) : OutboxMessagePublisher {
    private val topic = properties.topic

    init {
        require(topic.isNotBlank()) { "Outbox topic must not be blank" }
    }

    override suspend fun publish(event: OutboxEvent) {
        Mono.fromFuture(kafkaTemplate.send(topic, event.aggregateId.toString(), event.payload))
            .awaitSingle()
    }
}
