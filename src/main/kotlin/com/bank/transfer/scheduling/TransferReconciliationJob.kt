package com.bank.transfer.scheduling

import com.bank.transfer.service.TransferReconciliationService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "transfer.jobs",
    name = ["reconciliation-enabled"],
    havingValue = "true",
)
class TransferReconciliationJob(
    private val reconciliationService: TransferReconciliationService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "#{@transferJobsProperties.reconciliationDelay.toMillis()}")
    suspend fun reconcileTransfers() {
        val report = reconciliationService.reconcile()
        logger.info(
            "Reconciliation selected={}, completed={}, failed={}, unchanged={}, errors={}",
            report.selected,
            report.completed,
            report.failed,
            report.unchanged,
            report.errors,
        )
    }
}
