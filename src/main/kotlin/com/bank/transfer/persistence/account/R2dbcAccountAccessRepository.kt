package com.bank.transfer.persistence.account

import com.bank.transfer.domain.AccountAccessDeniedException
import java.util.UUID
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

@Repository
class R2dbcAccountAccessRepository(
    private val databaseClient: DatabaseClient,
) : AccountAccessRepository {
    override suspend fun requireOwnedActiveAccount(customerId: UUID, accountId: UUID) {
        val allowed = databaseClient.sql(
            """
            SELECT EXISTS(
                SELECT 1
                  FROM bank_accounts
                 WHERE id = :accountId
                   AND customer_id = :customerId
                   AND active = TRUE
            ) AS allowed
            """.trimIndent(),
        )
            .bind("accountId", accountId)
            .bind("customerId", customerId)
            .map { row, _ -> row.get("allowed", Boolean::class.javaObjectType) ?: false }
            .one()
            .awaitSingleOrNull() ?: false
        if (!allowed) throw AccountAccessDeniedException(accountId)
    }
}
