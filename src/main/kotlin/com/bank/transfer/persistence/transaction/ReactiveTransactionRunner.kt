package com.bank.transfer.persistence.transaction

import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

@Component
class ReactiveTransactionRunner(
    private val operator: TransactionalOperator,
) {
    suspend fun <T : Any> inTransaction(block: suspend () -> T): T =
        requireNotNull(operator.executeAndAwait { block() })
}
