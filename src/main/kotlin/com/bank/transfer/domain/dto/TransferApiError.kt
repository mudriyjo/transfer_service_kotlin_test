package com.bank.transfer.domain.dto

import java.time.Instant

data class TransferApiError(
    val code: String,
    val message: String,
    val timestamp: Instant,
    val status: Int,
    val method: String,
    val path: String,
    val requestId: String,
    val details: Map<String, String> = emptyMap(),
)
