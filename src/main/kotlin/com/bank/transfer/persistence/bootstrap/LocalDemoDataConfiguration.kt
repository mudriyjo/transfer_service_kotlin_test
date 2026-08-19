package com.bank.transfer.persistence.bootstrap

import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.r2dbc.core.DatabaseClient
import java.nio.charset.StandardCharsets

@Configuration(proxyBeanMethods = false)
@Profile("local")
@ConditionalOnProperty(
    prefix = "transfer.bootstrap",
    name = ["demo-accounts"],
    havingValue = "true",
)
class LocalDemoDataConfiguration {
    @Bean
    fun localDemoAccounts(databaseClient: DatabaseClient): ApplicationRunner = ApplicationRunner {
        databaseClient
            .sql(localDemoAccountsSql())
            .fetch()
            .rowsUpdated()
            .block()
    }

    private fun localDemoAccountsSql(): String =
        ClassPathResource(LOCAL_DEMO_ACCOUNTS_SQL).getContentAsString(StandardCharsets.UTF_8)

    private companion object {
        const val LOCAL_DEMO_ACCOUNTS_SQL = "db/bootstrap/local-demo-accounts.sql"
    }
}
