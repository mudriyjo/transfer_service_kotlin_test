package com.bank.transfer.config

import com.bank.transfer.domain.TransferPolicy
import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator

@Configuration(proxyBeanMethods = false)
class TransferRuntimeConfiguration {
    @Bean
    fun transferClock(): Clock = Clock.systemUTC()

    @Bean
    fun transferPolicy(): TransferPolicy = TransferPolicy()

    @Bean
    fun transactionalOperator(transactionManager: ReactiveTransactionManager): TransactionalOperator =
        TransactionalOperator.create(transactionManager)
}
