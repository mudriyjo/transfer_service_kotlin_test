package com.bank.transfer.config

import com.bank.transfer.integration.cbs.CbsTransferClient
import com.bank.transfer.persistence.transaction.ReactiveTransactionRunner
import com.bank.transfer.persistence.transfer.TransferRepository
import com.bank.transfer.service.TransferOutboxService
import com.bank.transfer.service.TransferReconciliationService
import java.time.Clock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class TransferReconciliationConfiguration {
    @Bean
    fun transferReconciliationService(
        persistence: TransferRepository,
        cbsClient: CbsTransferClient,
        outboxService: TransferOutboxService,
        transactions: ReactiveTransactionRunner,
        clock: Clock,
        properties: TransferReconciliationProperties,
    ): TransferReconciliationService = TransferReconciliationService(
        persistence = persistence,
        cbsClient = cbsClient,
        outboxService = outboxService,
        transactions = transactions,
        clock = clock,
        minimumAge = properties.minimumAge,
    )
}
