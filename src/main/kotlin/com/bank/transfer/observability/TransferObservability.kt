package com.bank.transfer.observability

import com.bank.transfer.domain.Transfer
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.domain.TransferType
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/** Metrics used by all three transfer flows. */
@Component
class TransferMetrics(
    private val registry: MeterRegistry,
) {
    private val accepted: Counter = Counter.builder("transfer.accepted")
        .description("Transfer commands accepted by the application")
        .register(registry)

    private val failed: Counter = Counter.builder("transfer.failed")
        .description("Transfer attempts that ended in a local failure")
        .register(registry)

    private val recoveryBatches: Counter = Counter.builder("transfer.reconciliation.batch")
        .description("Reconciliation batches started")
        .register(registry)

    fun commandAccepted(type: TransferType, customerId: UUID) {
        accepted.increment()
        Counter.builder("transfer.command")
            .tag("type", type.name.lowercase())
            .tag("customer_id", customerId.toString())
            .register(registry)
            .increment()
    }

    fun transition(transfer: Transfer, from: TransferStatus, to: TransferStatus) {
        Counter.builder("transfer.transition")
            .tag("type", transfer.type.name.lowercase())
            .tag("from", from.name.lowercase())
            .tag("to", to.name.lowercase())
            .tag("transfer_id", transfer.id.toString())
            .register(registry)
            .increment()
    }

    fun commandFailed(type: TransferType, code: String) {
        failed.increment()
        Counter.builder("transfer.failure")
            .tag("type", type.name.lowercase())
            .tag("code", code.lowercase())
            .register(registry)
            .increment()
    }

    fun reconciliationBatch(size: Int) {
        recoveryBatches.increment()
        registry.summary("transfer.reconciliation.batch.size").record(size.toDouble())
    }

    fun startCbsTimer(type: TransferType): Timer.Sample = Timer.start(registry)

    fun stopCbsTimer(sample: Timer.Sample, type: TransferType, outcome: String) {
        sample.stop(
            Timer.builder("transfer.cbs.duration")
                .tag("type", type.name.lowercase())
                .tag("outcome", outcome.lowercase())
                .publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(5))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(registry),
        )
    }
}

/** Logging context shared by controllers, jobs, and application services. */
@Component
class TransferLogContext {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun <T> withTransfer(transfer: Transfer, block: suspend () -> T): T =
        withFields(
            mapOf(
                "transferId" to transfer.id.toString(),
                "customerId" to transfer.customerId.toString(),
                "sourceAccountId" to transfer.sourceAccountId.toString(),
                "destinationAccountId" to transfer.destinationAccountId.toString(),
                "transferType" to transfer.type.name,
            ),
            block,
        )

    suspend fun <T> withCommand(
        customerId: UUID,
        sourceAccountId: UUID,
        destinationAccountId: UUID,
        block: suspend () -> T,
    ): T = withFields(
        mapOf(
            "customerId" to customerId.toString(),
            "sourceAccountId" to sourceAccountId.toString(),
            "destinationAccountId" to destinationAccountId.toString(),
        ),
        block,
    )

    private suspend fun <T> withFields(fields: Map<String, String>, block: suspend () -> T): T {
        val previous = fields.keys.associateWith(MDC::get)
        fields.forEach(MDC::put)
        return try {
            block()
        } catch (error: Exception) {
            logger.debug("Transfer operation left log context with an exception", error)
            throw error
        } finally {
            fields.keys.forEach { key ->
                previous[key]?.let { MDC.put(key, it) } ?: MDC.remove(key)
            }
        }
    }
}
