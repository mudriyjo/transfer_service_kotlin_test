package com.bank.transfer.unit

import com.bank.transfer.domain.AccountAccessDeniedException
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.observability.TransferMetrics
import com.bank.transfer.persistence.account.AccountAccessRepository
import com.bank.transfer.security.CurrentCustomerProvider
import com.bank.transfer.service.ExternalTransferCommand
import com.bank.transfer.service.ExternalTransferResult
import com.bank.transfer.service.InternalTransferService
import com.bank.transfer.service.ScheduledTransferService
import com.bank.transfer.service.TransferApplicationService
import com.bank.transfer.service.TransferCommandService
import com.bank.transfer.support.TestIds
import com.bank.transfer.support.externalTransfer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder

@Tag("unit")
class TransferCommandServiceTest {
    @Test
    fun `external transfer uses the authenticated customer and owned source account`() = runBlocking {
        val accounts = mock(AccountAccessRepository::class.java)
        val external = mock(TransferApplicationService::class.java)
        val completed = externalTransfer(status = TransferStatus.COMPLETED)
        var captured: ExternalTransferCommand? = null
        `when`(external.execute(anyValue())).thenAnswer { invocation ->
            captured = invocation.getArgument(0)
            ExternalTransferResult(completed, replayed = false)
        }
        val service = commandService(accounts, external)

        val result = asCustomer(TestIds.CUSTOMER) {
            service.createExternal(
                requestedCustomerId = TestIds.OTHER_CUSTOMER,
                sourceAccountId = TestIds.SOURCE_ACCOUNT,
                beneficiaryAccount = "DE89370400440532013000",
                amount = BigDecimal("15.00"),
                currency = "EUR",
                idempotencyKey = "external-authz-101",
            )
        }

        assertEquals(completed.id, result.transfer.id)
        verify(accounts).requireOwnedActiveAccount(TestIds.CUSTOMER, TestIds.SOURCE_ACCOUNT)
        assertEquals(TestIds.CUSTOMER, captured?.customerId)
        assertEquals(TestIds.SOURCE_ACCOUNT, captured?.sourceAccountId)
        assertEquals("external-authz-101", captured?.idempotencyKey)
    }

    @Test
    fun `external transfer does not submit when the source account is not owned`() = runBlocking {
        val accounts = mock(AccountAccessRepository::class.java)
        val external = mock(TransferApplicationService::class.java)
        `when`(accounts.requireOwnedActiveAccount(TestIds.CUSTOMER, TestIds.SOURCE_ACCOUNT))
            .thenThrow(AccountAccessDeniedException(TestIds.SOURCE_ACCOUNT))
        val service = commandService(accounts, external)

        val denied = runCatching {
            asCustomer(TestIds.CUSTOMER) {
                service.createExternal(
                    requestedCustomerId = TestIds.OTHER_CUSTOMER,
                    sourceAccountId = TestIds.SOURCE_ACCOUNT,
                    beneficiaryAccount = "DE89370400440532013000",
                    amount = BigDecimal("15.00"),
                    currency = "EUR",
                    idempotencyKey = "external-authz-102",
                )
            }
        }.exceptionOrNull()

        assertInstanceOf(AccountAccessDeniedException::class.java, denied)
        verify(external, never()).execute(anyValue())
        Unit
    }

    @Test
    fun `external transfer does not invent an idempotency key when the header is absent`() = runBlocking {
        val accounts = mock(AccountAccessRepository::class.java)
        val external = mock(TransferApplicationService::class.java)
        val completed = externalTransfer(status = TransferStatus.COMPLETED)
        var captured: ExternalTransferCommand? = null
        `when`(external.execute(anyValue())).thenAnswer { invocation ->
            captured = invocation.getArgument(0)
            ExternalTransferResult(completed, replayed = false)
        }
        val service = commandService(accounts, external)

        asCustomer(TestIds.CUSTOMER) {
            service.createExternal(
                requestedCustomerId = null,
                sourceAccountId = TestIds.SOURCE_ACCOUNT,
                beneficiaryAccount = "DE89370400440532013000",
                amount = BigDecimal("15.00"),
                currency = "EUR",
                idempotencyKey = null,
            )
        }

        assertNull(captured?.idempotencyKey)
    }

    private fun commandService(
        accounts: AccountAccessRepository,
        external: TransferApplicationService,
    ) = TransferCommandService(
        currentCustomer = CurrentCustomerProvider(),
        accountAccessValidator = accounts,
        internalTransferService = mock(InternalTransferService::class.java),
        externalTransferService = external,
        scheduledTransferService = mock(ScheduledTransferService::class.java),
        metrics = TransferMetrics(SimpleMeterRegistry()),
    )

    private suspend fun <T : Any> asCustomer(customerId: UUID, action: suspend () -> T): T {
        val authentication = UsernamePasswordAuthenticationToken.authenticated(
            customerId.toString(),
            "test",
            listOf(SimpleGrantedAuthority("SCOPE_transfers")),
        )
        return mono { action() }
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
            .awaitSingle()
    }

    private companion object {
        @Suppress("UNCHECKED_CAST")
        fun <T> anyValue(): T {
            org.mockito.ArgumentMatchers.any<T>()
            return null as T
        }
    }
}
