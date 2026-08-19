package com.bank.transfer.service

import com.bank.transfer.config.TransferJobsProperties
import com.bank.transfer.persistence.transfer.TransferRepository
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/** Claims and executes one bounded batch of due scheduled transfers. */
@Service
@ConditionalOnProperty(
    prefix = "transfer.jobs",
    name = ["scheduling-enabled"],
    havingValue = "true",
)
class ScheduledTransferBatchService(
    private val persistence: TransferRepository,
    private val scheduledTransferService: ScheduledTransferService,
    private val clock: Clock,
    properties: TransferJobsProperties,
) {
    private val batchSize = properties.batchSize
    private val leaseDuration = properties.schedulingLease
    private val failureDelay = properties.schedulingFailureDelay
    private val logger = LoggerFactory.getLogger(javaClass)
    private val workerId = "scheduler:${UUID.randomUUID()}"

    suspend fun executeDue() {
        require(!leaseDuration.isZero && !leaseDuration.isNegative) {
            "Scheduling lease duration must be positive"
        }
        require(!failureDelay.isZero && !failureDelay.isNegative) {
            "Scheduling failure delay must be positive"
        }

        val maximumClaims = batchSize.coerceIn(1, TransferJobsProperties.MAX_BATCH_SIZE)
        var claimed = 0
        var released = 0
        var deferred = 0

        for (ignored in 0 until maximumClaims) {
            val claimTime = clock.instant()
            val transfer = persistence.claimScheduledReady(
                now = claimTime,
                leaseUntil = claimTime.plus(leaseDuration),
                claimedBy = workerId,
                limit = 1,
            ).singleOrNull() ?: break
            claimed++

            try {
                scheduledTransferService.execute(transfer.id)
                if (persistence.releaseSchedulingClaim(transfer.id, workerId)) {
                    released++
                } else {
                    logger.warn(
                        "Scheduling claim for transfer {} changed before release by {}",
                        transfer.id,
                        workerId,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val retryAt = clock.instant().plus(failureDelay)
                if (persistence.deferSchedulingClaim(transfer.id, workerId, retryAt)) {
                    deferred++
                } else if (persistence.releaseSchedulingClaim(transfer.id, workerId)) {
                    released++
                }
                logger.error(
                    "Scheduled execution failed for transfer {}: {}",
                    transfer.id,
                    error.message,
                    error,
                )
            }
        }

        if (claimed > 0) {
            logger.info(
                "Scheduled execution worker {} claimed={}, released={}, deferred={}",
                workerId,
                claimed,
                released,
                deferred,
            )
        }
    }
}
