package com.bank.transfer.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("unit")
class ArchitectureTest {
    @Test
    fun `source packages match their paths`() {
        val violations = kotlinSources(SOURCE_ROOT).mapNotNull { source ->
            val relativeParent = SOURCE_ROOT.relativize(source.parent)
            val expected = buildString {
                append(ROOT_PACKAGE)
                if (relativeParent.toString().isNotEmpty()) {
                    append('.')
                    append(relativeParent.joinToString("."))
                }
            }
            val actual = PACKAGE_DECLARATION.find(source.readText())?.groupValues?.get(1)
            if (actual == expected) null else "$source: expected $expected, found $actual"
        }

        assertTrue(violations.isEmpty(), violations.message("Package/path mismatch"))
    }

    @Test
    fun `domain has no framework dependencies`() {
        assertNoReferences(
            root = SOURCE_ROOT.resolve("domain"),
            forbidden = listOf("org.springframework.", "io.r2dbc."),
        )
    }

    @Test
    fun `REST DTOs are confined to domain dto`() {
        val dtoRoot = SOURCE_ROOT.resolve("domain/dto")
        val actualDtos = kotlinSources(dtoRoot)
            .flatMap { source -> DATA_CLASS.findAll(source.readText()).map { it.groupValues[1] }.toList() }
            .toSet()
        assertEquals(REST_DTOS, actualDtos, "Unexpected REST DTO set in domain/dto")

        val controllerDtos = kotlinSources(SOURCE_ROOT.resolve("controller"))
            .flatMap { source -> DATA_CLASS.findAll(source.readText()).map { it.groupValues[1] }.toList() }
            .filter { name -> name.endsWith("Request") || name.endsWith("Response") || name == "TransferApiError" }

        assertTrue(controllerDtos.isEmpty(), controllerDtos.message("REST DTO remains in controller"))
    }

    @Test
    fun `controllers and schedulers do not bypass services`() {
        listOf("controller", "scheduling").forEach { layer ->
            assertNoReferences(
                root = SOURCE_ROOT.resolve(layer),
                forbidden = listOf(
                    "com.bank.transfer.persistence.",
                    "com.bank.transfer.integration.",
                    "com.bank.transfer.messaging.",
                    "com.bank.transfer.observability.",
                ),
            )
        }
    }

    @Test
    fun `infrastructure does not depend on controllers services or schedulers`() {
        listOf("persistence", "integration", "messaging", "observability", "security").forEach { layer ->
            assertNoReferences(
                root = SOURCE_ROOT.resolve(layer),
                forbidden = listOf(
                    "com.bank.transfer.controller.",
                    "com.bank.transfer.service.",
                    "com.bank.transfer.scheduling.",
                ),
            )
        }
    }

    @Test
    fun `DatabaseClient is confined to persistence`() {
        val persistenceRoot = SOURCE_ROOT.resolve("persistence").normalize()
        val violations = kotlinSources(SOURCE_ROOT)
            .filter { source ->
                DATABASE_CLIENT.containsMatchIn(source.readText()) &&
                    !source.normalize().startsWith(persistenceRoot)
            }

        assertTrue(
            violations.isEmpty(),
            violations.message("DatabaseClient may only be used in persistence"),
        )
    }

    @Test
    fun `scheduled methods are confined to scheduling`() {
        val schedulingRoot = SOURCE_ROOT.resolve("scheduling").normalize()
        val violations = kotlinSources(SOURCE_ROOT)
            .filter { source ->
                SCHEDULED_ANNOTATION.containsMatchIn(source.readText()) &&
                    !source.normalize().startsWith(schedulingRoot)
            }

        assertTrue(
            violations.isEmpty(),
            violations.message("@Scheduled may only be used in scheduling"),
        )
    }

    @Test
    fun `legacy architecture packages are gone`() {
        LEGACY_DIRECTORIES.forEach { legacy ->
            assertFalse(Files.exists(SOURCE_ROOT.resolve(legacy)), "Legacy source package still exists: $legacy")
        }
        val source = kotlinSources(SOURCE_ROOT).joinToString("\n") { it.readText() }
        assertFalse(source.contains("com.bank.transfer.adapter."))
        assertFalse(source.contains("com.bank.transfer.application."))
        assertEquals(-1, source.indexOf("application.port"))
    }

    private fun assertNoReferences(root: Path, forbidden: List<String>) {
        check(Files.isDirectory(root)) { "Source directory does not exist: $root" }
        val violations = kotlinSources(root).mapNotNull { source ->
            val matched = forbidden.filter(source.readText()::contains)
            if (matched.isEmpty()) null else source to matched
        }

        assertTrue(violations.isEmpty(), violations.message("Forbidden dependency found"))
    }

    private fun kotlinSources(root: Path): List<Path> {
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { paths ->
            paths
                .filter(Files::isRegularFile)
                .filter { it.fileName.toString().endsWith(".kt") }
                .sorted()
                .toList()
        }
    }

    private fun List<*>.message(summary: String): String =
        joinToString(prefix = "$summary:\n", separator = "\n")

    private companion object {
        const val ROOT_PACKAGE = "com.bank.transfer"
        val SOURCE_ROOT: Path = Path.of("src/main/kotlin/com/bank/transfer")
        val PACKAGE_DECLARATION = Regex("(?m)^package\\s+([A-Za-z0-9_.]+)\\s*$")
        val DATABASE_CLIENT = Regex("\\bDatabaseClient\\b")
        val SCHEDULED_ANNOTATION = Regex("@Scheduled\\b")
        val DATA_CLASS = Regex("(?m)^data class\\s+([A-Za-z0-9_]+)")
        val LEGACY_DIRECTORIES = listOf("adapter", "application")
        val REST_DTOS = setOf(
            "InternalTransferRequest",
            "ExternalTransferRequest",
            "ScheduledTransferRequest",
            "RescheduleScheduledTransferRequest",
            "TransferResponse",
            "TransferListResponse",
            "AccountResponse",
            "PageResponse",
            "AccountListResponse",
            "LedgerEntryResponse",
            "LedgerSummaryResponse",
            "LedgerStatementResponse",
            "TransferApiError",
        )
    }
}
