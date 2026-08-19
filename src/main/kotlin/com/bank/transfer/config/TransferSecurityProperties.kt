package com.bank.transfer.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated

@Component
@ConfigurationProperties(prefix = "transfer.security")
@ConditionalOnProperty(prefix = "transfer.security", name = ["mode"], havingValue = "mock-header")
@Validated
class TransferSecurityProperties {
    @field:NotBlank
    var customerHeader: String = "X-Customer-Id"
}
