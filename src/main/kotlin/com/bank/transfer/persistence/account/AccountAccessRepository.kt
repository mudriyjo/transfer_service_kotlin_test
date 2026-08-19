package com.bank.transfer.persistence.account

import java.util.UUID

fun interface AccountAccessRepository {
    suspend fun requireOwnedActiveAccount(customerId: UUID, accountId: UUID)
}
