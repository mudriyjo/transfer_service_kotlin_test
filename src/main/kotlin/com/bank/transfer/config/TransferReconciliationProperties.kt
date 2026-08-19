package com.bank.transfer.config

import jakarta.validation.constraints.AssertTrue
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

@Component
@ConfigurationProperties(prefix = "transfer.reconciliation")
@Validated
class TransferReconciliationProperties {
    var minimumAge: Duration = Duration.ofSeconds(5)

    @get:AssertTrue(message = "Reconciliation minimum age must be positive")
    val validMinimumAge: Boolean
        get() = !minimumAge.isZero && !minimumAge.isNegative
}
