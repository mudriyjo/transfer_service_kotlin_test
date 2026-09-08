package com.bank.transfer.service

import com.bank.transfer.domain.Transfer
import com.bank.transfer.domain.TransferType
import com.bank.transfer.integration.cbs.CbsTransferRequest
import java.time.Clock
import org.springframework.stereotype.Component

/** Maps the local aggregate to the CBS wire command. */
@Component
class CbsRequestMapper(
    private val clock: Clock,
) {
    fun toRequest(transfer: Transfer): CbsTransferRequest {
        require(transfer.type == TransferType.EXTERNAL || transfer.type == TransferType.SCHEDULED) {
            "Only external or scheduled transfers can be sent to CBS"
        }
        val beneficiaryAccount = requireNotNull(transfer.beneficiaryAccount) {
            "CBS transfer ${transfer.id} has no beneficiary account"
        }
        val clientReference = requireNotNull(transfer.cbsReference?.takeIf(String::isNotBlank)) {
            "CBS transfer ${transfer.id} has no provider reference"
        }

        return CbsTransferRequest(
            transferId = transfer.id,
            clientReference = clientReference,
            sourceAccountId = transfer.sourceAccountId,
            destinationAccount = beneficiaryAccount,
            amount = transfer.money.amount,
            currency = transfer.money.currency,
            requestedAt = clock.instant(),
        )
    }
}
