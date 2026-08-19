package com.bank.transfer.scheduling

import com.bank.transfer.service.OutboxBatchResult
import com.bank.transfer.service.OutboxPublishingService
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    prefix = "transfer.jobs",
    name = ["outbox-enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class OutboxPublishingJob(
    private val service: OutboxPublishingService,
) {
    @Scheduled(fixedDelayString = "#{@transferJobsProperties.outboxDelay.toMillis()}")
    suspend fun publishPending(): OutboxBatchResult = service.publishPending()
}
