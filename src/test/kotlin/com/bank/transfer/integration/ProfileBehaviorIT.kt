package com.bank.transfer.integration

import com.bank.transfer.integration.cbs.CbsTransferClient
import com.bank.transfer.integration.cbs.HttpCbsTransferClient
import com.bank.transfer.integration.cbs.LocalCbsControl
import com.bank.transfer.integration.cbs.LocalCbsTransferClient
import com.bank.transfer.scheduling.OutboxPublishingJob
import com.bank.transfer.scheduling.ScheduledTransferJob
import com.bank.transfer.scheduling.TransferReconciliationJob
import com.bank.transfer.service.OutboxPublishingService
import com.bank.transfer.service.ScheduledTransferBatchService
import com.bank.transfer.service.TransferReconciliationService
import com.bank.transfer.config.CbsHttpProperties
import com.bank.transfer.config.LocalCbsProperties
import com.bank.transfer.config.TransferJobsProperties
import com.bank.transfer.config.TransferOutboxProperties
import com.bank.transfer.support.fixedClock
import java.time.Clock
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.convert.ApplicationConversionService
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.web.reactive.function.client.WebClient

@Tag("integration")
class ProfileBehaviorIT {
    private val loader = YamlPropertySourceLoader()

    @Test
    fun `local profile selects local adapters and enables background jobs`() {
        val properties = properties("application-local.yml")

        assertEquals("mock-header", properties["transfer.security.mode"])
        assertEquals("in-memory", properties["transfer.cbs.mode"])
        assertEquals(1, properties["transfer.cbs.retry.max-attempts"])
        assertEquals(true, properties["transfer.jobs.scheduling-enabled"])
        assertEquals(true, properties["transfer.jobs.reconciliation-enabled"])
        assertEquals(true, properties["transfer.jobs.outbox-enabled"])
        assertEquals(true, properties["transfer.bootstrap.demo-accounts"])

        cbsContext(properties).run { context ->
            assertTrue(context.startupFailure == null)
            assertInstanceOf(LocalCbsTransferClient::class.java, context.getBean(CbsTransferClient::class.java))
            assertEquals("NONE", context.getBean(LocalCbsProperties::class.java).timeoutMode)
            assertEquals(1, context.getBeansOfType(LocalCbsControl::class.java).size)
            assertTrue(context.getBeansOfType(HttpCbsTransferClient::class.java).isEmpty())
        }
        jobsContext(properties).run { context ->
            assertTrue(context.startupFailure == null)
            assertEquals(100, context.getBean(TransferJobsProperties::class.java).batchSize)
            assertEquals(
                Duration.ofSeconds(1),
                context.getBean(TransferJobsProperties::class.java).schedulingDelay,
            )
            assertEquals("transfer-events", context.getBean(TransferOutboxProperties::class.java).topic)
            assertEquals(1, context.getBeansOfType(ScheduledTransferJob::class.java).size)
            assertEquals(1, context.getBeansOfType(TransferReconciliationJob::class.java).size)
            assertEquals(1, context.getBeansOfType(OutboxPublishingJob::class.java).size)
        }
    }

    @Test
    fun `test profile isolates external infrastructure and disables jobs`() {
        val properties = properties("application-test.yml")

        assertEquals("mock-header", properties["transfer.security.mode"])
        assertEquals("http", properties["transfer.cbs.mode"])
        assertEquals(false, properties["transfer.jobs.scheduling-enabled"])
        assertEquals(false, properties["transfer.jobs.reconciliation-enabled"])
        assertEquals(false, properties["transfer.jobs.outbox-enabled"])
        assertEquals(false, properties["transfer.bootstrap.demo-accounts"])

        jobsContext(properties).run { context ->
            assertTrue(context.startupFailure == null)
            assertTrue(context.getBeansOfType(ScheduledTransferJob::class.java).isEmpty())
            assertTrue(context.getBeansOfType(TransferReconciliationJob::class.java).isEmpty())
            assertTrue(context.getBeansOfType(OutboxPublishingJob::class.java).isEmpty())
        }
    }

    @Test
    fun `production profile selects external adapters and configured retries`() {
        val properties = properties("application-production.yml")

        assertEquals("jwt", properties["transfer.security.mode"])
        assertEquals("http", properties["transfer.cbs.mode"])
        assertEquals(3, properties["transfer.cbs.retry.max-attempts"])
        assertEquals(true, properties["transfer.jobs.scheduling-enabled"])
        assertEquals(true, properties["transfer.jobs.reconciliation-enabled"])
        assertEquals(true, properties["transfer.jobs.outbox-enabled"])
        assertEquals(false, properties["transfer.bootstrap.demo-accounts"])

        cbsContext(properties).run { context ->
            assertTrue(context.startupFailure == null)
            assertInstanceOf(HttpCbsTransferClient::class.java, context.getBean(CbsTransferClient::class.java))
            assertEquals(3L, context.getBean(CbsHttpProperties::class.java).retry.maxAttempts)
            assertEquals(1, context.getBeansOfType(HttpCbsTransferClient::class.java).size)
            assertTrue(context.getBeansOfType(LocalCbsTransferClient::class.java).isEmpty())
        }
    }

    @Test
    fun `invalid operational properties fail during context startup`() {
        ApplicationContextRunner()
            .withUserConfiguration(JobConditionTestConfiguration::class.java)
            .withInitializer { context ->
                context.beanFactory.conversionService = ApplicationConversionService.getSharedInstance()
            }
            .withPropertyValues(
                "transfer.jobs.scheduling-enabled=false",
                "transfer.jobs.reconciliation-enabled=false",
                "transfer.jobs.outbox-enabled=false",
                "transfer.jobs.batch-size=0",
            )
            .run { context -> assertTrue(context.startupFailure != null) }
    }

    private fun cbsContext(properties: Map<String, Any?>): ApplicationContextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(CbsConditionTestConfiguration::class.java)
            .withInitializer { context ->
                context.beanFactory.conversionService = ApplicationConversionService.getSharedInstance()
            }
            .withPropertyValues(
                "transfer.cbs.mode=${properties.getValue("transfer.cbs.mode")}",
                "transfer.cbs.base-url=http://127.0.0.1:18090",
                "transfer.cbs.read-timeout=PT1S",
                "transfer.cbs.retry.max-attempts=${properties.getValue("transfer.cbs.retry.max-attempts")}",
            )

    private fun jobsContext(properties: Map<String, Any?>): ApplicationContextRunner =
        ApplicationContextRunner()
            .withUserConfiguration(JobConditionTestConfiguration::class.java)
            .withInitializer { context ->
                context.beanFactory.conversionService = ApplicationConversionService.getSharedInstance()
            }
            .withPropertyValues(
                "transfer.jobs.scheduling-enabled=${properties.getValue("transfer.jobs.scheduling-enabled")}",
                "transfer.jobs.reconciliation-enabled=${properties.getValue("transfer.jobs.reconciliation-enabled")}",
                "transfer.jobs.outbox-enabled=${properties.getValue("transfer.jobs.outbox-enabled")}",
            )

    private fun properties(resource: String): Map<String, Any?> {
        val sources = loader.load(resource, ClassPathResource(resource))
        return buildMap {
            sources.forEach { source ->
                REQUIRED_KEYS.forEach { key ->
                    source.getProperty(key)?.let { value -> put(key, value) }
                }
            }
        }
    }

    companion object {
        private val REQUIRED_KEYS = setOf(
            "transfer.security.mode",
            "transfer.cbs.mode",
            "transfer.cbs.retry.max-attempts",
            "transfer.jobs.scheduling-enabled",
            "transfer.jobs.reconciliation-enabled",
            "transfer.jobs.outbox-enabled",
            "transfer.bootstrap.demo-accounts",
        )
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties
@Import(
    CbsHttpProperties::class,
    LocalCbsProperties::class,
    LocalCbsControl::class,
    LocalCbsTransferClient::class,
    HttpCbsTransferClient::class,
)
private class CbsConditionTestConfiguration {
    @Bean
    fun clock(): Clock = fixedClock()

    @Bean
    fun webClientBuilder(): WebClient.Builder = WebClient.builder()
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties
@Import(
    TransferJobsProperties::class,
    TransferOutboxProperties::class,
    ScheduledTransferJob::class,
    TransferReconciliationJob::class,
    OutboxPublishingJob::class,
)
private class JobConditionTestConfiguration {
    @Bean
    fun scheduledTransferBatchService(): ScheduledTransferBatchService =
        mock(ScheduledTransferBatchService::class.java)

    @Bean
    fun reconciliationService(): TransferReconciliationService = mock(TransferReconciliationService::class.java)

    @Bean
    fun outboxPublishingService(): OutboxPublishingService = mock(OutboxPublishingService::class.java)
}
