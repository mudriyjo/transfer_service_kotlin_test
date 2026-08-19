package com.bank.transfer.persistence.outbox

import io.r2dbc.spi.Row
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

/** Database operations for persisted transfer events and their delivery lifecycle. */
@Repository
class OutboxRepository(
    private val databaseClient: DatabaseClient,
) {
    suspend fun insert(event: OutboxEvent): OutboxEvent {
        val rowsUpdated = databaseClient.sql(
            """
            INSERT INTO outbox_events (
                id, aggregate_id, event_type, payload, created_at,
                published_at, attempts, last_error
            ) VALUES (
                :id, :aggregateId, :eventType, CAST(:payload AS JSONB), :createdAt,
                NULL, 0, NULL
            )
            """.trimIndent(),
        )
            .bind("id", event.id)
            .bind("aggregateId", event.aggregateId)
            .bind("eventType", event.eventType)
            .bind("payload", event.payload)
            .bind("createdAt", event.createdAt)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        check(rowsUpdated == 1L) { "Expected one inserted outbox event, updated $rowsUpdated rows" }
        return event
    }

    /** Eligibility is based only on persisted attempts, not process time or memory. */
    fun findPending(maxAttempts: Int, batchSize: Int): Flow<OutboxEvent> {
        validatePolicy(maxAttempts, batchSize)
        return databaseClient.sql(
            """
            SELECT $COLUMNS
              FROM outbox_events
             WHERE published_at IS NULL
               AND attempts < :maxAttempts
             ORDER BY created_at, id
             LIMIT :batchSize
            """.trimIndent(),
        )
            .bind("maxAttempts", maxAttempts)
            .bind("batchSize", batchSize)
            .map { row, _ -> row.toOutboxEvent() }
            .all()
            .asFlow()
    }

    /** Returns terminal, unpublished events for operational inspection. */
    fun findExhausted(maxAttempts: Int, limit: Int): Flow<OutboxEvent> {
        validatePolicy(maxAttempts, limit)
        return databaseClient.sql(
            """
            SELECT $COLUMNS
              FROM outbox_events
             WHERE published_at IS NULL
               AND attempts >= :maxAttempts
             ORDER BY attempts DESC, created_at, id
             LIMIT :limit
            """.trimIndent(),
        )
            .bind("maxAttempts", maxAttempts)
            .bind("limit", limit)
            .map { row, _ -> row.toOutboxEvent() }
            .all()
            .asFlow()
    }

    suspend fun deliveryStatistics(maxAttempts: Int): OutboxDeliveryStatistics {
        validateMaxAttempts(maxAttempts)
        return databaseClient.sql(
            """
            SELECT COUNT(*) FILTER (
                       WHERE published_at IS NULL AND attempts < :maxAttempts
                   ) AS pending_count,
                   COUNT(*) FILTER (
                       WHERE published_at IS NULL AND attempts >= :maxAttempts
                   ) AS exhausted_count,
                   COUNT(*) FILTER (
                       WHERE published_at IS NOT NULL
                   ) AS published_count,
                   COALESCE(MAX(attempts), 0) AS highest_attempt_count
              FROM outbox_events
            """.trimIndent(),
        )
            .bind("maxAttempts", maxAttempts)
            .map { row, _ ->
                OutboxDeliveryStatistics(
                    pending = row.required("pending_count", Long::class.javaObjectType),
                    exhausted = row.required("exhausted_count", Long::class.javaObjectType),
                    published = row.required("published_count", Long::class.javaObjectType),
                    highestAttemptCount = row.required("highest_attempt_count", Int::class.javaObjectType),
                )
            }
            .one()
            .awaitSingle()
    }

    /** A null mutation means another publisher changed the selected row first. */
    suspend fun markPublished(
        id: UUID,
        expectedAttempts: Int,
        publishedAt: Instant,
    ): OutboxDeliveryMutation? {
        require(expectedAttempts >= 0) { "Expected attempt count cannot be negative" }
        return databaseClient.sql(
            """
            UPDATE outbox_events
               SET published_at = :publishedAt,
                   attempts = attempts + 1,
                   last_error = NULL
             WHERE id = :id
               AND published_at IS NULL
               AND attempts = :expectedAttempts
            RETURNING attempts
            """.trimIndent(),
        )
            .bind("id", id)
            .bind("expectedAttempts", expectedAttempts)
            .bind("publishedAt", publishedAt)
            .map { row, _ ->
                OutboxDeliveryMutation(
                    eventId = id,
                    previousAttempts = expectedAttempts,
                    attemptCount = row.required("attempts", Int::class.javaObjectType),
                    state = OutboxDeliveryState.PUBLISHED,
                )
            }
            .one()
            .awaitSingleOrNull()
    }

    suspend fun markFailed(
        id: UUID,
        expectedAttempts: Int,
        error: String,
        maxAttempts: Int,
    ): OutboxDeliveryMutation? {
        validateMaxAttempts(maxAttempts)
        require(expectedAttempts in 0 until maxAttempts) {
            "Only retry-eligible events can record another failed attempt"
        }
        val boundedError = boundError(error)
        return databaseClient.sql(
            """
            UPDATE outbox_events
               SET attempts = attempts + 1,
                   last_error = :lastError
             WHERE id = :id
               AND published_at IS NULL
               AND attempts = :expectedAttempts
               AND attempts < :maxAttempts
            RETURNING attempts
            """.trimIndent(),
        )
            .bind("id", id)
            .bind("expectedAttempts", expectedAttempts)
            .bind("maxAttempts", maxAttempts)
            .bind("lastError", boundedError)
            .map { row, _ ->
                val attempts = row.required("attempts", Int::class.javaObjectType)
                OutboxDeliveryMutation(
                    eventId = id,
                    previousAttempts = expectedAttempts,
                    attemptCount = attempts,
                    state = if (attempts >= maxAttempts) {
                        OutboxDeliveryState.EXHAUSTED
                    } else {
                        OutboxDeliveryState.PENDING
                    },
                )
            }
            .one()
            .awaitSingleOrNull()
    }

    suspend fun findByAggregateId(aggregateId: UUID): List<OutboxEvent> = databaseClient
        .sql(
            """
            SELECT $COLUMNS
              FROM outbox_events
             WHERE aggregate_id = :aggregateId
             ORDER BY created_at, id
            """.trimIndent(),
        )
        .bind("aggregateId", aggregateId)
        .map { row, _ -> row.toOutboxEvent() }
        .all()
        .collectList()
        .awaitSingle()

    private fun Row.toOutboxEvent(): OutboxEvent = OutboxEvent(
        id = required("id", UUID::class.java),
        aggregateId = required("aggregate_id", UUID::class.java),
        eventType = required("event_type", String::class.java),
        payload = required("payload", String::class.java),
        createdAt = required("created_at", Instant::class.java),
        publishedAt = get("published_at", Instant::class.java),
        attempts = required("attempts", Int::class.javaObjectType),
        lastError = get("last_error", String::class.java),
    )

    private fun <T : Any> Row.required(name: String, type: Class<T>): T =
        get(name, type) ?: error("Column $name must not be null")

    private fun validatePolicy(maxAttempts: Int, limit: Int) {
        validateMaxAttempts(maxAttempts)
        require(limit in 1..MAX_QUERY_LIMIT) { "Outbox query limit must be between 1 and $MAX_QUERY_LIMIT" }
    }

    private fun validateMaxAttempts(maxAttempts: Int) {
        require(maxAttempts in 1..MAX_ATTEMPTS_LIMIT) { "Invalid maximum delivery attempts: $maxAttempts" }
    }

    private fun boundError(error: String): String = error
        .trim()
        .replace(WHITESPACE, " ")
        .ifBlank { "Unspecified delivery failure" }
        .take(MAX_ERROR_LENGTH)

    companion object {
        const val MAX_ERROR_LENGTH = 512
        const val MAX_QUERY_LIMIT = 1_000
        const val MAX_ATTEMPTS_LIMIT = 100

        private val WHITESPACE = Regex("\\s+")
        private const val COLUMNS = """
            id, aggregate_id, event_type, payload::text AS payload,
            created_at, published_at, attempts, last_error
        """
    }
}
