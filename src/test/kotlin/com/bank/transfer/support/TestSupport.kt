package com.bank.transfer.support

import com.bank.transfer.service.TransferIdGenerator
import com.bank.transfer.persistence.transaction.ReactiveTransactionRunner
import com.bank.transfer.integration.cbs.CbsOperationStatus
import com.bank.transfer.integration.cbs.CbsStatusResponse
import com.bank.transfer.integration.cbs.CbsTransferClient
import com.bank.transfer.integration.cbs.CbsTransferRequest
import com.bank.transfer.integration.cbs.CbsTransferResponse
import com.bank.transfer.domain.Money
import com.bank.transfer.domain.Transfer
import com.bank.transfer.domain.TransferStatus
import com.bank.transfer.domain.TransferType
import com.bank.transfer.persistence.ledger.R2dbcLedgerRepository
import com.bank.transfer.persistence.outbox.OutboxRepository
import com.bank.transfer.service.TransferOutboxService
import com.bank.transfer.persistence.transfer.R2dbcTransferEntityRepository
import com.bank.transfer.persistence.transfer.R2dbcTransferRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlinx.coroutines.reactor.awaitSingle
import org.flywaydb.core.Flyway
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

val TEST_INSTANT: Instant = Instant.parse("2026-02-03T10:15:30Z")

object TestIds {
    val CUSTOMER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
    val OTHER_CUSTOMER: UUID = UUID.fromString("00000000-0000-0000-0000-000000000102")
    val SOURCE_ACCOUNT: UUID = UUID.fromString("10000000-0000-0000-0000-000000000101")
    val DESTINATION_ACCOUNT: UUID = UUID.fromString("10000000-0000-0000-0000-000000000102")
    val TRANSFER_ONE: UUID = UUID.fromString("20000000-0000-0000-0000-000000000101")
    val TRANSFER_TWO: UUID = UUID.fromString("20000000-0000-0000-0000-000000000102")
}

fun fixedClock(instant: Instant = TEST_INSTANT): Clock = Clock.fixed(instant, ZoneOffset.UTC)

fun externalTransfer(
    id: UUID = TestIds.TRANSFER_ONE,
    status: TransferStatus = TransferStatus.CREATED,
    reference: String? = "cbs-reference-101",
    now: Instant = TEST_INSTANT,
): Transfer {
    val created = Transfer.create(
        id = id,
        customerId = TestIds.CUSTOMER,
        type = TransferType.EXTERNAL,
        sourceAccountId = TestIds.SOURCE_ACCOUNT,
        beneficiaryAccount = "DE89370400440532013000",
        money = Money.of("25.00", "EUR"),
        idempotencyKey = "external-key-101",
        requestFingerprint = "fingerprint-external-101",
        now = now,
        cbsReference = reference,
    )
    return when (status) {
        TransferStatus.CREATED -> created
        TransferStatus.PROCESSING -> created.markProcessing(now)
        TransferStatus.COMPLETED -> created.markProcessing(now).markCompleted(now)
        TransferStatus.FAILED -> created.markProcessing(now).markFailed("REJECTED", "Rejected", now)
        TransferStatus.CANCELLED -> created.cancel(now)
        TransferStatus.SCHEDULED -> error("External transfers cannot be SCHEDULED")
    }
}

class DeterministicIdGenerator(vararg ids: UUID) : TransferIdGenerator {
    private val values = ConcurrentLinkedQueue(ids.toList())

    override fun nextId(): UUID = checkNotNull(values.poll()) { "No deterministic transfer IDs remain" }
}

class RecordingCbsClient(
    var transferStatus: CbsOperationStatus = CbsOperationStatus.COMPLETED,
) : CbsTransferClient {
    val transferRequests: MutableList<CbsTransferRequest> = mutableListOf()
    val statusRequests: MutableList<String> = mutableListOf()
    private val statuses = ConcurrentHashMap<String, CbsStatusResponse>()

    override suspend fun transfer(request: CbsTransferRequest): CbsTransferResponse {
        transferRequests += request
        val response = CbsTransferResponse(
            operationId = deterministicOperationId(request.clientReference),
            clientReference = request.clientReference,
            status = transferStatus,
            processedAt = TEST_INSTANT,
        )
        statuses[request.clientReference] = CbsStatusResponse(
            operationId = response.operationId,
            clientReference = request.clientReference,
            status = response.status,
            processedAt = response.processedAt,
        )
        return response
    }

    override suspend fun status(clientReference: String): CbsStatusResponse {
        statusRequests += clientReference
        return statuses[clientReference] ?: CbsStatusResponse(
            operationId = null,
            clientReference = clientReference,
            status = CbsOperationStatus.NOT_FOUND,
            processedAt = null,
        )
    }

    fun registerStatus(reference: String, status: CbsOperationStatus) {
        statuses[reference] = CbsStatusResponse(
            operationId = deterministicOperationId(reference),
            clientReference = reference,
            status = status,
            processedAt = TEST_INSTANT,
        )
    }

    private fun deterministicOperationId(reference: String): String =
        UUID.nameUUIDFromBytes(reference.toByteArray(StandardCharsets.UTF_8)).toString()
}

class PostgresTestDatabase private constructor(
    val connectionFactory: ConnectionFactory,
) {
    val client: DatabaseClient = DatabaseClient.create(connectionFactory)
    val transactions: TransactionalOperator = TransactionalOperator.create(
        R2dbcTransactionManager(connectionFactory),
    )
    val transactionRunner = ReactiveTransactionRunner(transactions)
    val persistence: R2dbcTransferRepository = R2dbcTransferRepository(
        R2dbcTransferEntityRepository(client),
    )
    val ledger: R2dbcLedgerRepository = R2dbcLedgerRepository(client)
    val outboxRepository: OutboxRepository = OutboxRepository(client)
    val outboxService: TransferOutboxService = TransferOutboxService(
        outboxRepository,
        jacksonObjectMapper().findAndRegisterModules(),
        fixedClock(),
    )

    suspend fun reset() {
        listOf("outbox_events", "ledger_entries", "transfers", "bank_accounts").forEach { table ->
            client.sql("DELETE FROM $table").fetch().rowsUpdated().awaitSingle()
        }
    }

    suspend fun seedAccount(
        id: UUID,
        customerId: UUID = TestIds.CUSTOMER,
        balance: BigDecimal = BigDecimal("1000.0000"),
        currency: String = "EUR",
        active: Boolean = true,
    ) {
        client.sql(
            """
            INSERT INTO bank_accounts (id, customer_id, currency, balance, active, created_at, updated_at)
            VALUES (:id, :customerId, :currency, :balance, :active, :now, :now)
            """.trimIndent(),
        )
            .bind("id", id)
            .bind("customerId", customerId)
            .bind("currency", currency)
            .bind("balance", balance)
            .bind("active", active)
            .bind("now", TEST_INSTANT)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun accountBalance(id: UUID): BigDecimal = client.sql(
        "SELECT balance FROM bank_accounts WHERE id = :id",
    )
        .bind("id", id)
        .map { row, _ -> requireNotNull(row.get("balance", BigDecimal::class.java)) }
        .one()
        .awaitSingle()

    suspend fun rowCount(table: String): Long {
        require(table in setOf("transfers", "ledger_entries", "outbox_events"))
        return client.sql("SELECT COUNT(*) AS count FROM $table")
            .map { row, _ -> requireNotNull(row.get("count", Long::class.javaObjectType)) }
            .one()
            .awaitSingle()
    }

    companion object {
        fun create(): PostgresTestDatabase {
            val dockerAvailable = DockerClientFactory.instance().isDockerAvailable
            check(dockerAvailable) {
                "Docker is required for PostgreSQL integration tests; start Docker and rerun integrationTest"
            }
            val container = SharedPostgres.container
            val connectionFactory = ConnectionFactories.get(
                "r2dbc:postgresql://${container.username}:${container.password}" +
                    "@${container.host}:${container.firstMappedPort}/${container.databaseName}",
            )
            return PostgresTestDatabase(connectionFactory)
        }
    }
}

private class TestPostgresContainer(image: String) :
    PostgreSQLContainer<TestPostgresContainer>(image)

private object SharedPostgres {
    val container: TestPostgresContainer by lazy {
        TestPostgresContainer("postgres:16.14-alpine")
            .withDatabaseName("transfers")
            .withUsername("transfers")
            .withPassword("transfers")
            .also { postgres ->
                postgres.start()
                Flyway.configure()
                    .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate()
            }
    }
}

enum class StubFailureMode {
    NONE,
    BEFORE_COMMIT,
    AFTER_COMMIT,
}

/** Deterministic stateful HTTP implementation of the small CBS contract. */
class DeterministicCbsHttpStub private constructor(
    private val server: HttpServer,
    private val mapper: ObjectMapper,
) : AutoCloseable {
    private data class StoredOperation(
        val fingerprint: String,
        val response: CbsTransferResponse,
    )

    private val operations = ConcurrentHashMap<String, StoredOperation>()
    @Volatile
    private var nextFailure: StubFailureMode = StubFailureMode.NONE

    val baseUrl: String
        get() = "http://127.0.0.1:${server.address.port}"

    fun failNext(mode: StubFailureMode) {
        require(mode != StubFailureMode.NONE)
        nextFailure = mode
    }

    private fun handle(exchange: HttpExchange) {
        when (exchange.requestMethod) {
            "POST" -> handleTransfer(exchange)
            "GET" -> handleStatus(exchange)
            else -> exchange.respond(405, "")
        }
    }

    private fun handleTransfer(exchange: HttpExchange) {
        val request = mapper.readValue(exchange.requestBody, CbsTransferRequest::class.java)
        val fingerprint = listOf(
            request.sourceAccountId,
            request.destinationAccount,
            request.amount.stripTrailingZeros(),
            request.currency.uppercase(),
        ).joinToString("|")
        operations[request.clientReference]?.let { stored ->
            if (stored.fingerprint != fingerprint) {
                exchange.respond(409, "reference already used")
            } else {
                exchange.respondJson(200, stored.response)
            }
            return
        }

        val failure = nextFailure.also { nextFailure = StubFailureMode.NONE }
        if (failure == StubFailureMode.BEFORE_COMMIT) {
            exchange.respond(504, "upstream timeout")
            return
        }

        val response = CbsTransferResponse(
            operationId = UUID.nameUUIDFromBytes(
                request.clientReference.toByteArray(StandardCharsets.UTF_8),
            ).toString(),
            clientReference = request.clientReference,
            status = CbsOperationStatus.COMPLETED,
            processedAt = TEST_INSTANT,
        )
        operations[request.clientReference] = StoredOperation(fingerprint, response)
        if (failure == StubFailureMode.AFTER_COMMIT) {
            exchange.respond(504, "result unavailable")
        } else {
            exchange.respondJson(200, response)
        }
    }

    private fun handleStatus(exchange: HttpExchange) {
        val reference = exchange.requestURI.path.substringAfterLast('/')
        val stored = operations[reference]
        if (stored == null) {
            exchange.respond(404, "not found")
            return
        }
        exchange.respondJson(
            200,
            CbsStatusResponse(
                operationId = stored.response.operationId,
                clientReference = reference,
                status = stored.response.status,
                processedAt = stored.response.processedAt,
            ),
        )
    }

    private fun HttpExchange.respondJson(status: Int, value: Any) {
        responseHeaders.set("Content-Type", "application/json")
        respond(status, mapper.writeValueAsString(value))
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }

    override fun close() {
        server.stop(0)
    }

    companion object {
        fun start(): DeterministicCbsHttpStub {
            val mapper = jacksonObjectMapper().findAndRegisterModules()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            lateinit var stub: DeterministicCbsHttpStub
            stub = DeterministicCbsHttpStub(server, mapper)
            server.createContext("/api/v1/transfers") { exchange -> stub.handle(exchange) }
            server.executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "cbs-contract-stub").apply { isDaemon = true }
            }
            server.start()
            return stub
        }
    }
}
