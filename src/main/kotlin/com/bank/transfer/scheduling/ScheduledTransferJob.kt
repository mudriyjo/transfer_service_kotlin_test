package com.bank.transfer.scheduling

import com.bank.transfer.service.ScheduledTransferBatchService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "transfer.jobs",
    name = ["scheduling-enabled"],
    havingValue = "true",
)
class ScheduledTransferJob(
    private val service: ScheduledTransferBatchService,
) {
    @Scheduled(fixedDelayString = "#{@transferJobsProperties.schedulingDelay.toMillis()}")
    suspend fun executeDue() = service.executeDue()
}
