package com.bank.transfer.observability

import io.micrometer.core.instrument.MeterRegistry
import java.time.Duration
import org.springframework.stereotype.Component

@Component
class OutboxMetrics(
    private val registry: MeterRegistry,
) {
    fun recordDelivery(eventType: String, outcome: String) {
        registry.counter(
            "transfer.outbox.delivery",
            "event_type",
            eventType,
            "outcome",
            outcome,
        ).increment()
    }

    fun recordBatch(selected: Int, duration: Duration, pending: Long, exhausted: Long) {
        registry.summary("transfer.outbox.batch.size").record(selected.toDouble())
        registry.timer("transfer.outbox.batch.duration").record(duration)
        registry.summary("transfer.outbox.pending.snapshot").record(pending.toDouble())
        registry.summary("transfer.outbox.exhausted.snapshot").record(exhausted.toDouble())
    }
}
