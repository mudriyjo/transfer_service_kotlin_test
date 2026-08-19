package com.bank.transfer.config

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

@Component("transferJobsProperties")
@ConfigurationProperties(prefix = "transfer.jobs")
@Validated
class TransferJobsProperties {
    var schedulingEnabled: Boolean = false
    var reconciliationEnabled: Boolean = false
    var outboxEnabled: Boolean = true

    var schedulingDelay: Duration = Duration.ofSeconds(1)
    var reconciliationDelay: Duration = Duration.ofSeconds(30)
    var outboxDelay: Duration = Duration.ofSeconds(2)

    @field:Min(1)
    @field:Max(MAX_BATCH_SIZE.toLong())
    var batchSize: Int = 100

    var schedulingLease: Duration = Duration.ofMinutes(2)
    var schedulingFailureDelay: Duration = Duration.ofSeconds(5)

    @get:AssertTrue(message = "Job delays, scheduling lease, and scheduling failure delay must be positive")
    val validDurations: Boolean
        get() = sequenceOf(
            schedulingDelay,
            reconciliationDelay,
            outboxDelay,
            schedulingLease,
            schedulingFailureDelay,
        ).all { duration -> !duration.isZero && !duration.isNegative }

    companion object {
        const val MAX_BATCH_SIZE = 1_000
    }
}
