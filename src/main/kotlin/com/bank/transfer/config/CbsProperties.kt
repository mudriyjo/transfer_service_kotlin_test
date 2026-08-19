package com.bank.transfer.config

import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.time.Duration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

@Component("cbsHttpProperties")
@ConfigurationProperties(prefix = "transfer.cbs")
@ConditionalOnProperty(
    prefix = "transfer.cbs",
    name = ["mode"],
    havingValue = "http",
    matchIfMissing = true,
)
@Validated
class CbsHttpProperties {
    var mode: String = "http"

    @field:NotBlank
    var baseUrl: String = ""

    var connectTimeout: Duration = Duration.ofSeconds(2)

    var readTimeout: Duration = Duration.ofSeconds(5)

    @field:Valid
    var retry: Retry = Retry()

    @get:AssertTrue(message = "CBS connect and read timeouts must be positive")
    val validTimeouts: Boolean
        get() = connectTimeout.hasPositiveLength() && readTimeout.hasPositiveLength()

    class Retry {
        @field:Min(1)
        var maxAttempts: Long = 3

        var initialDelay: Duration = Duration.ofMillis(100)

        var multiplier: Double = 2.0
    }

    internal companion object {
        fun of(
            baseUrl: String,
            connectTimeout: Duration,
            readTimeout: Duration,
            maxAttempts: Long,
        ): CbsHttpProperties = CbsHttpProperties().apply {
            this.baseUrl = baseUrl
            this.connectTimeout = connectTimeout
            this.readTimeout = readTimeout
            retry.maxAttempts = maxAttempts
        }
    }
}

@Component("localCbsProperties")
@ConfigurationProperties(prefix = "transfer.cbs.local")
@ConditionalOnProperty(prefix = "transfer.cbs", name = ["mode"], havingValue = "in-memory")
@Validated
class LocalCbsProperties {
    @field:NotBlank
    var timeoutMode: String = "NONE"

    @field:NotBlank
    var defaultStatus: String = "COMPLETED"

    internal companion object {
        fun of(timeoutMode: String, defaultStatus: String): LocalCbsProperties = LocalCbsProperties().apply {
            this.timeoutMode = timeoutMode
            this.defaultStatus = defaultStatus
        }
    }
}

private fun Duration.hasPositiveLength(): Boolean = !isZero && !isNegative
