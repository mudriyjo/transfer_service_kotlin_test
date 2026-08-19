package com.bank.transfer.integration.cbs
import com.bank.transfer.config.LocalCbsProperties
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

enum class LocalCbsTimeoutMode {
    NONE,
    BEFORE_COMMIT,
    AFTER_COMMIT,
}

data class LocalCbsStatistics(
    val commands: Int,
    val statusLookups: Int,
    val idempotentReplays: Int,
    val referenceConflicts: Int,
)

data class LocalCbsOperationSnapshot(
    val operationId: String,
    val transferId: UUID,
    val clientReference: String,
    val sourceAccountId: UUID,
    val destinationAccount: String,
    val amount: BigDecimal,
    val currency: String,
    val requestedAt: Instant,
    val status: CbsOperationStatus,
    val processedAt: Instant?,
    val message: String?,
)

internal data class LocalCbsResponsePlan(
    val status: CbsOperationStatus,
    val message: String?,
)

/**
 * Test/local control surface. A mode is consumed by exactly one command, which makes
 * timeout-before-commit and timeout-after-commit scenarios deterministic without sleep.
 */
@Component
@ConditionalOnProperty(prefix = "transfer.cbs", name = ["mode"], havingValue = "in-memory")
class LocalCbsControl @Autowired constructor(
    properties: LocalCbsProperties,
) {
    private val defaultStatus = properties.defaultStatus.toOperationStatus()
    private val nextMode = AtomicReference(LocalCbsTimeoutMode.valueOf(properties.timeoutMode.uppercase()))
    private val nextResponse = AtomicReference<LocalCbsResponsePlan?>(null)
    private val commandCount = AtomicInteger()
    private val statusLookupCount = AtomicInteger()
    private val replayCount = AtomicInteger()
    private val conflictCount = AtomicInteger()

    internal constructor(
        configuredMode: String,
        configuredStatus: String = "COMPLETED",
    ) : this(LocalCbsProperties.of(configuredMode, configuredStatus))

    fun timeoutNext(mode: LocalCbsTimeoutMode) {
        require(mode != LocalCbsTimeoutMode.NONE) { "Use reset() instead of scheduling NONE" }
        nextMode.set(mode)
    }

    fun respondNext(status: CbsOperationStatus, message: String? = null) {
        require(status in SUPPORTED_RESPONSE_STATUSES) {
            "Local CBS command status must be ACCEPTED, PROCESSING, COMPLETED, or REJECTED"
        }
        nextResponse.set(LocalCbsResponsePlan(status, message?.take(MAX_LOCAL_CBS_MESSAGE_LENGTH)))
    }

    fun reset() {
        nextMode.set(LocalCbsTimeoutMode.NONE)
        nextResponse.set(null)
        commandCount.set(0)
        statusLookupCount.set(0)
        replayCount.set(0)
        conflictCount.set(0)
    }

    internal fun consumeMode(): LocalCbsTimeoutMode = nextMode.getAndSet(LocalCbsTimeoutMode.NONE)

    internal fun consumeResponse(): LocalCbsResponsePlan =
        nextResponse.getAndSet(null) ?: LocalCbsResponsePlan(defaultStatus, null)

    internal fun recordCommand() {
        commandCount.incrementAndGet()
    }

    internal fun recordStatusLookup() {
        statusLookupCount.incrementAndGet()
    }

    internal fun recordReplay() {
        replayCount.incrementAndGet()
    }

    internal fun recordConflict() {
        conflictCount.incrementAndGet()
    }

    fun commandsReceived(): Int = commandCount.get()

    fun statistics(): LocalCbsStatistics = LocalCbsStatistics(
        commands = commandCount.get(),
        statusLookups = statusLookupCount.get(),
        idempotentReplays = replayCount.get(),
        referenceConflicts = conflictCount.get(),
    )

    private fun String.toOperationStatus(): CbsOperationStatus {
        val status = runCatching { CbsOperationStatus.valueOf(trim().uppercase()) }
            .getOrElse { throw IllegalArgumentException("Unsupported local CBS status: $this", it) }
        require(status in SUPPORTED_RESPONSE_STATUSES) { "Unsupported local CBS status: $status" }
        return status
    }

    private companion object {
        val SUPPORTED_RESPONSE_STATUSES = setOf(
            CbsOperationStatus.ACCEPTED,
            CbsOperationStatus.PROCESSING,
            CbsOperationStatus.COMPLETED,
            CbsOperationStatus.REJECTED,
        )
    }
}

/**
 * Stateful CBS simulation used for local development. It implements the documented CBS
 * idempotency semantics: the same reference and payload returns the original result,
 * while reuse with a different payload is rejected.
 */
@Component
@ConditionalOnProperty(prefix = "transfer.cbs", name = ["mode"], havingValue = "in-memory")
class LocalCbsTransferClient(
    private val control: LocalCbsControl,
    private val clock: Clock,
) : CbsTransferClient {
    private data class StoredOperation(
        val request: CbsTransferRequest,
        val fingerprint: String,
        val response: AtomicReference<CbsTransferResponse>,
    )

    private val operations = ConcurrentHashMap<String, StoredOperation>()

    override suspend fun transfer(request: CbsTransferRequest): CbsTransferResponse {
        request.validate()
        control.recordCommand()
        val fingerprint = request.payloadFingerprint()
        operations[request.clientReference]?.let { stored ->
            if (stored.fingerprint != fingerprint) {
                control.recordConflict()
                throw CbsReferenceConflictException(request.clientReference)
            }
            control.recordReplay()
            return stored.response.get()
        }

        val timeoutMode = control.consumeMode()
        if (timeoutMode == LocalCbsTimeoutMode.BEFORE_COMMIT) {
            throw CbsTimeoutException(request.clientReference, mayHaveCommitted = false)
        }

        val plan = control.consumeResponse()
        val respondedAt = clock.instant()
        val response = CbsTransferResponse(
            operationId = deterministicOperationId(request.clientReference),
            clientReference = request.clientReference,
            status = plan.status,
            processedAt = respondedAt.takeIf {
                plan.status == CbsOperationStatus.COMPLETED || plan.status == CbsOperationStatus.REJECTED
            },
            message = plan.message ?: if (plan.status == CbsOperationStatus.REJECTED) {
                "Transfer rejected by local CBS policy"
            } else {
                null
            },
        )
        val previous = operations.putIfAbsent(
            request.clientReference,
            StoredOperation(request, fingerprint, AtomicReference(response)),
        )
        if (previous != null) {
            if (previous.fingerprint != fingerprint) {
                control.recordConflict()
                throw CbsReferenceConflictException(request.clientReference)
            }
            control.recordReplay()
            return previous.response.get()
        }

        if (timeoutMode == LocalCbsTimeoutMode.AFTER_COMMIT) {
            throw CbsTimeoutException(request.clientReference, mayHaveCommitted = true)
        }
        return response
    }

    override suspend fun status(clientReference: String): CbsStatusResponse {
        control.recordStatusLookup()
        val stored = operations[clientReference]
            ?: return CbsStatusResponse(
                operationId = null,
                clientReference = clientReference,
                status = CbsOperationStatus.NOT_FOUND,
                processedAt = null,
            )
        val response = stored.response.get()
        return CbsStatusResponse(
            operationId = response.operationId,
            clientReference = clientReference,
            status = response.status,
            processedAt = response.processedAt,
            message = response.message,
        )
    }

    fun complete(clientReference: String): LocalCbsOperationSnapshot =
        transition(clientReference, CbsOperationStatus.COMPLETED, null)

    fun reject(clientReference: String, message: String): LocalCbsOperationSnapshot =
        transition(clientReference, CbsOperationStatus.REJECTED, message.take(MAX_LOCAL_CBS_MESSAGE_LENGTH))

    fun operation(clientReference: String): LocalCbsOperationSnapshot? =
        operations[clientReference]?.snapshot()

    fun operations(): List<LocalCbsOperationSnapshot> =
        operations.values
            .map { stored -> stored.snapshot() }
            .sortedWith(compareBy(LocalCbsOperationSnapshot::requestedAt, LocalCbsOperationSnapshot::clientReference))

    fun clear() {
        operations.clear()
        control.reset()
    }

    private fun transition(
        clientReference: String,
        target: CbsOperationStatus,
        message: String?,
    ): LocalCbsOperationSnapshot {
        val stored = operations[clientReference]
            ?: throw NoSuchElementException("CBS operation $clientReference does not exist")
        while (true) {
            val current = stored.response.get()
            require(current.status == CbsOperationStatus.ACCEPTED || current.status == CbsOperationStatus.PROCESSING) {
                "CBS operation $clientReference is already terminal (${current.status})"
            }
            val updated = current.copy(
                status = target,
                processedAt = clock.instant(),
                message = message,
            )
            if (stored.response.compareAndSet(current, updated)) return stored.snapshot()
        }
    }

    private fun StoredOperation.snapshot(): LocalCbsOperationSnapshot {
        val current = response.get()
        return LocalCbsOperationSnapshot(
            operationId = current.operationId,
            transferId = request.transferId,
            clientReference = request.clientReference,
            sourceAccountId = request.sourceAccountId,
            destinationAccount = request.destinationAccount,
            amount = request.amount,
            currency = request.currency,
            requestedAt = request.requestedAt,
            status = current.status,
            processedAt = current.processedAt,
            message = current.message,
        )
    }

    private fun CbsTransferRequest.validate() {
        require(clientReference.isNotBlank()) { "CBS client reference must not be blank" }
        require(destinationAccount.isNotBlank()) { "CBS destination account must not be blank" }
        require(destinationAccount.length <= MAX_DESTINATION_ACCOUNT_LENGTH) { "CBS destination account is too long" }
        require(amount.signum() > 0) { "CBS transfer amount must be positive" }
        require(currency.matches(ISO_CURRENCY)) { "CBS currency must be an uppercase ISO code" }
    }

    private fun CbsTransferRequest.payloadFingerprint(): String =
        listOf(sourceAccountId, destinationAccount, amount.stripTrailingZeros(), currency.uppercase())
            .joinToString("|")

    private fun deterministicOperationId(reference: String): String =
        UUID.nameUUIDFromBytes(reference.toByteArray(StandardCharsets.UTF_8)).toString()
}

private const val MAX_LOCAL_CBS_MESSAGE_LENGTH = 500
private const val MAX_DESTINATION_ACCOUNT_LENGTH = 128
private val ISO_CURRENCY = Regex("[A-Z]{3}")
