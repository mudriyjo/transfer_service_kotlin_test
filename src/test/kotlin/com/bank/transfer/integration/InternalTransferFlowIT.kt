package com.bank.transfer.integration

import com.bank.transfer.controller.AccountController
import com.bank.transfer.service.AccountQueryService
import com.bank.transfer.service.InternalTransferCommand
import com.bank.transfer.service.InternalTransferService
import com.bank.transfer.domain.AccountAccessDeniedException
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.domain.LedgerDirection
import com.bank.transfer.security.CurrentCustomerProvider
import com.bank.transfer.support.TEST_INSTANT
import com.bank.transfer.support.DeterministicIdGenerator
import com.bank.transfer.support.PostgresTestDatabase
import com.bank.transfer.support.TestIds
import com.bank.transfer.support.fixedClock
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder

@Tag("integration")
class InternalTransferFlowIT {
    @Test
    fun `concurrent replay posts one transfer and one balanced ledger pair`() = runBlocking {
        val database = PostgresTestDatabase.create()
        database.reset()
        database.seedAccount(TestIds.SOURCE_ACCOUNT, balance = BigDecimal("1000.0000"))
        database.seedAccount(TestIds.DESTINATION_ACCOUNT, balance = BigDecimal("50.0000"))
        val service = InternalTransferService(
            persistence = database.persistence,
            ledger = database.ledger,
            eventWriter = database.outboxService,
            policy = com.bank.transfer.domain.TransferPolicy(),
            transactionalOperator = database.transactionRunner,
            clock = fixedClock(),
            idGenerator = DeterministicIdGenerator(TestIds.TRANSFER_ONE, TestIds.TRANSFER_TWO),
        )
        val command = InternalTransferCommand(
            customerId = TestIds.CUSTOMER,
            sourceAccountId = TestIds.SOURCE_ACCOUNT,
            destinationAccountId = TestIds.DESTINATION_ACCOUNT,
            amount = BigDecimal("125.00"),
            currency = "EUR",
            idempotencyKey = "internal-concurrent-101",
        )

        val results = coroutineScope {
            listOf(
                async { service.execute(command) },
                async { service.execute(command) },
            ).awaitAll()
        }

        assertEquals(1, results.count { !it.replayed })
        assertEquals(1, results.count { it.replayed })
        assertTrue(results.all { it.transfer.status == TransferStatus.COMPLETED })
        assertEquals(0, database.accountBalance(TestIds.SOURCE_ACCOUNT).compareTo(BigDecimal("875.00")))
        assertEquals(0, database.accountBalance(TestIds.DESTINATION_ACCOUNT).compareTo(BigDecimal("175.00")))
        assertEquals(1L, database.rowCount("transfers"))
        assertEquals(2L, database.rowCount("ledger_entries"))
        assertEquals(1L, database.rowCount("outbox_events"))
    }

    @Test
    fun `account endpoints return an owned paginated statement and movement summary`() = runBlocking {
        val database = PostgresTestDatabase.create()
        database.reset()
        database.seedAccount(TestIds.SOURCE_ACCOUNT, balance = BigDecimal("1000.0000"))
        database.seedAccount(TestIds.DESTINATION_ACCOUNT, balance = BigDecimal("50.0000"))
        val unrelatedAccount = UUID.fromString("10000000-0000-0000-0000-000000000199")
        database.seedAccount(
            id = unrelatedAccount,
            customerId = TestIds.OTHER_CUSTOMER,
            balance = BigDecimal("700.0000"),
        )
        val transfers = InternalTransferService(
            persistence = database.persistence,
            ledger = database.ledger,
            eventWriter = database.outboxService,
            policy = com.bank.transfer.domain.TransferPolicy(),
            transactionalOperator = database.transactionRunner,
            clock = fixedClock(),
            idGenerator = DeterministicIdGenerator(TestIds.TRANSFER_ONE, TestIds.TRANSFER_TWO),
        )
        transfers.execute(command(amount = "10.00", key = "account-read-key-101"))
        transfers.execute(command(amount = "20.00", key = "account-read-key-102"))
        val controller = AccountController(
            AccountQueryService(CurrentCustomerProvider(), database.ledger, fixedClock()),
        )

        val accounts = asCustomer(TestIds.CUSTOMER) {
            controller.listAccounts(active = true, limit = 10, offset = 0)
        }
        val source = asCustomer(TestIds.CUSTOMER) {
            controller.getAccount(TestIds.SOURCE_ACCOUNT)
        }
        val firstPage = asCustomer(TestIds.CUSTOMER) {
            controller.statement(
                accountId = TestIds.SOURCE_ACCOUNT,
                from = TEST_INSTANT.minusSeconds(60),
                to = TEST_INSTANT.plusSeconds(1),
                direction = LedgerDirection.DEBIT,
                limit = 1,
                offset = 0,
            )
        }
        val secondPage = asCustomer(TestIds.CUSTOMER) {
            controller.statement(
                accountId = TestIds.SOURCE_ACCOUNT,
                from = TEST_INSTANT.minusSeconds(60),
                to = TEST_INSTANT.plusSeconds(1),
                direction = LedgerDirection.DEBIT,
                limit = 1,
                offset = 1,
            )
        }

        assertEquals(2, accounts.accounts.size)
        assertFalse(accounts.accounts.any { it.accountId == unrelatedAccount })
        assertEquals(0, source.balance.compareTo(BigDecimal("970.00")))
        assertEquals(1, firstPage.entries.size)
        assertTrue(firstPage.page.hasNext)
        assertFalse(secondPage.page.hasNext)
        assertEquals(2L, firstPage.summary.entryCount)
        assertEquals(0, firstPage.summary.debitAmount.compareTo(BigDecimal("30.00")))
        assertEquals(0, firstPage.summary.creditAmount.compareTo(BigDecimal.ZERO))
        assertEquals(0, firstPage.summary.netAmount.compareTo(BigDecimal("-30.00")))

        val denied = runCatching {
            asCustomer(TestIds.OTHER_CUSTOMER) {
                controller.getAccount(TestIds.SOURCE_ACCOUNT)
            }
        }.exceptionOrNull()
        assertInstanceOf(AccountAccessDeniedException::class.java, denied)
        Unit
    }

    private fun command(amount: String, key: String) = InternalTransferCommand(
        customerId = TestIds.CUSTOMER,
        sourceAccountId = TestIds.SOURCE_ACCOUNT,
        destinationAccountId = TestIds.DESTINATION_ACCOUNT,
        amount = BigDecimal(amount),
        currency = "EUR",
        idempotencyKey = key,
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
}
