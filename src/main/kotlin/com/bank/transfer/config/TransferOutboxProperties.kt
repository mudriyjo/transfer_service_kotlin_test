package com.bank.transfer.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

@Component
@ConfigurationProperties(prefix = "transfer.outbox")
@Validated
class TransferOutboxProperties {
    @field:NotBlank
    var topic: String = "transfer-events"

    @field:Min(1)
    @field:Max(1_000)
    var batchSize: Int = 100

    @field:Min(1)
    @field:Max(100)
    var maxAttempts: Int = 5

    internal companion object {
        fun of(topic: String, batchSize: Int, maxAttempts: Int): TransferOutboxProperties =
            TransferOutboxProperties().apply {
                this.topic = topic
                this.batchSize = batchSize
                this.maxAttempts = maxAttempts
            }
    }
}
