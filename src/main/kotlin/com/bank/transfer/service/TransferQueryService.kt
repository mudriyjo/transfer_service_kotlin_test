package com.bank.transfer.service

import com.bank.transfer.domain.Transfer
import com.bank.transfer.domain.TransferNotFoundException
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.domain.TransferType
import com.bank.transfer.persistence.transfer.TransferRepository
import com.bank.transfer.security.CurrentCustomerProvider
import org.springframework.stereotype.Service
import java.util.UUID

data class TransferQueryPage(
    val items: List<Transfer>,
    val limit: Int,
    val offset: Long,
    val hasMore: Boolean,
) {
    init {
        require(limit > 0) { "Page limit must be positive" }
        require(offset >= 0) { "Page offset cannot be negative" }
        require(items.size <= limit) { "A page cannot contain more items than its limit" }
    }

    val returned: Int
        get() = items.size

    val nextOffset: Long?
        get() = if (hasMore) offset + returned else null

    val previousOffset: Long?
        get() = when {
            offset == 0L -> null
            offset <= limit -> 0L
            else -> offset - limit
        }

    val pageNumber: Long
        get() = offset / limit

    val firstPage: Boolean
        get() = offset == 0L

    val rangeStart: Long?
        get() = if (items.isEmpty()) null else offset + 1

    val rangeEnd: Long?
        get() = if (items.isEmpty()) null else offset + returned
}

@Service
class TransferQueryService(
    private val persistence: TransferRepository,
    private val currentCustomer: CurrentCustomerProvider,
) {
    suspend fun get(transferId: UUID): Transfer {
        val customerId = currentCustomer.currentCustomerId()
        // Ownership is part of the SQL predicate so callers cannot distinguish a
        // missing transfer from another customer's transfer.
        return persistence.findOwnedById(transferId, customerId)
            ?: throw TransferNotFoundException(transferId)
    }

    suspend fun list(
        status: TransferStatus?,
        type: TransferType?,
        limit: Int,
        offset: Long,
    ): TransferQueryPage {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        require(offset >= 0) { "offset cannot be negative" }
        val customerId = currentCustomer.currentCustomerId()
        val loaded = persistence.listOwned(
            customerId = customerId,
            status = status,
            type = type,
            limit = limit + 1,
            offset = offset,
        )
        return TransferQueryPage(
            items = loaded.take(limit),
            limit = limit,
            offset = offset,
            hasMore = loaded.size > limit,
        )
    }
}
