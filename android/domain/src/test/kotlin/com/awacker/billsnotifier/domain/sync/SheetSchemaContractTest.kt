package com.awacker.billsnotifier.domain.sync

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-language contract test.
 *
 * The Kotlin column lists and the ones in `appsscript/Common.gs` are two halves of the same
 * wire format, written in two languages that never see each other at compile time. Renaming
 * a column on one side produces no error anywhere: the sheet still fills in, the sync still
 * returns ok, and the digest email quietly stops finding anything due. This test parses the
 * .gs source and fails loudly instead.
 *
 * The repo root is injected by the build (see domain/build.gradle.kts) rather than guessed
 * from a relative path, so it survives being run from a different working directory.
 */
class SheetSchemaContractTest {

    private val appsScriptCommon: File by lazy {
        val repoRoot = System.getProperty("repoRoot")
            ?: error("repoRoot system property not set — check domain/build.gradle.kts")
        File(repoRoot, "appsscript/Common.gs")
    }

    @Test
    fun `the Apps Script source is where the build expects it`() {
        assertTrue(
            appsScriptCommon.isFile,
            "Expected the Apps Script at ${appsScriptCommon.absolutePath}. If it moved, update " +
                "the repoRoot property in domain/build.gradle.kts.",
        )
    }

    @Test
    fun `bill columns match the Apps Script definition exactly`() {
        assertEquals(
            parseColumnArray("BILL_COLUMNS"),
            SheetSchema.BILL_COLUMNS,
            "Bills tab columns have drifted between Kotlin and Apps Script",
        )
    }

    @Test
    fun `occurrence columns match the Apps Script definition exactly`() {
        assertEquals(
            parseColumnArray("OCCURRENCE_COLUMNS"),
            SheetSchema.OCCURRENCE_COLUMNS,
            "Occurrences tab columns have drifted between Kotlin and Apps Script",
        )
    }

    @Test
    fun `script-owned columns match the Apps Script definition`() {
        assertEquals(
            parseColumnArray("SCRIPT_OWNED_OCCURRENCE_COLUMNS", File(appsScriptCommon.parentFile, "Sync.gs")),
            SheetSchema.SCRIPT_OWNED_COLUMNS,
            "The set of columns the script owns has drifted",
        )
    }

    @Test
    fun `every script-owned column is part of the occurrence schema`() {
        assertTrue(
            SheetSchema.OCCURRENCE_COLUMNS.containsAll(SheetSchema.SCRIPT_OWNED_COLUMNS),
            "A script-owned column is missing from OCCURRENCE_COLUMNS",
        )
    }

    @Test
    fun `date columns declared in the Apps Script exist in the schema`() {
        val occurrenceDates = parseColumnArray("OCCURRENCE_DATE_COLUMNS")
        val billDates = parseColumnArray("BILL_DATE_COLUMNS")
        assertTrue(
            SheetSchema.OCCURRENCE_COLUMNS.containsAll(occurrenceDates),
            "Apps Script formats $occurrenceDates as text but they are not in the occurrence schema",
        )
        assertTrue(
            SheetSchema.BILL_COLUMNS.containsAll(billDates),
            "Apps Script formats $billDates as text but they are not in the bill schema",
        )
    }

    @Test
    fun `column names are unique within each tab`() {
        assertEquals(
            SheetSchema.BILL_COLUMNS.size,
            SheetSchema.BILL_COLUMNS.distinct().size,
            "Duplicate column in BILL_COLUMNS",
        )
        assertEquals(
            SheetSchema.OCCURRENCE_COLUMNS.size,
            SheetSchema.OCCURRENCE_COLUMNS.distinct().size,
            "Duplicate column in OCCURRENCE_COLUMNS",
        )
    }

    /** Pulls `var NAME = ['a', 'b', ...];` out of a .gs source, ignoring comments. */
    private fun parseColumnArray(name: String, source: File = appsScriptCommon): List<String> {
        val text = source.readText()
        val declaration = Regex("""var\s+$name\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(text)
            ?: error("Could not find `var $name = [...]` in ${source.name}")

        return Regex("""['"]([^'"]+)['"]""")
            .findAll(stripComments(declaration.groupValues[1]))
            .map { it.groupValues[1] }
            .toList()
    }

    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
        .lines()
        .joinToString("\n") { it.substringBefore("//") }
}
