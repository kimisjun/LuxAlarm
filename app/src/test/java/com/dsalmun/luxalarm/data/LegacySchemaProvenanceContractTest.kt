/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.data

import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class LegacySchemaProvenanceContractTest {
    private fun repositoryRoot(): File =
        generateSequence(File(System.getProperty("user.dir") ?: error("user.dir is unavailable"))) {
            it.parentFile
        }
            .first { File(it, "settings.gradle.kts").isFile }

    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        check(start >= 0) { "Missing function $name" }
        val openingBrace = source.indexOf('{', start)
        check(openingBrace >= 0) { "Missing body for $name" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("Unclosed body for $name")
    }

    private fun git(root: File, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git") + arguments).directory(root).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        check(process.waitFor() == 0) { process.errorStream.bufferedReader().use { it.readText() } }
        return output
    }

    @Test
    fun everyProvenanceRowWithAFixtureNamesAnInstrumentedMigrationTest() {
        val root = repositoryRoot()
        val provenance = File(root, "LEGACY_SCHEMA_PROVENANCE.md").readLines()
        val migrationTest =
            File(root, "app/src/androidTest/java/com/dsalmun/luxalarm/MigrationTest.kt").readText()
        val releaseEvidence =
            File(root, "app/schemas/legacy/release-evidence.tsv")
                .readLines()
                .filterNot { it.isBlank() || it.startsWith("#") }
                .drop(1)
                .map { it.split('\t') }
                .associateBy { it[0] }
        val manifestLines =
            File(root, "app/schemas/legacy/manifest.tsv")
                .readLines()
                .filterNot { it.isBlank() || it.startsWith("#") }
                .drop(1)
                .map { it.split('\t') }
        assertEquals(
            manifestLines.size,
            manifestLines.map { it[0] }.distinct().size,
            "Fixture IDs must be unique",
        )
        val manifestRows = manifestLines.associateBy { it[0] }

        val provenanceRows =
            provenance
                .dropWhile { it != "## Release-by-release ledger" }
                .drop(1)
                .takeWhile { it.isBlank() || it.startsWith("|") }
                .filter { it.startsWith("|") && !it.contains("---") }
                .drop(1)
                .map { row -> row.trim('|').split('|').map(String::trim) }
        val fixtureIds = provenanceRows.map { it[7] }.filterNot { it == "unavailable" }.toSet()

        assertEquals(manifestRows.keys, fixtureIds)
        assertEquals(releaseEvidence.keys, provenanceRows.map { it[0] }.toSet())

        for (row in provenanceRows) {
            assertEquals(8, row.size, "Each ledger row must preserve the full evidence chain")
            assertTrue(row[1].toIntOrNull() != null, "versionCode must be numeric")
            assertTrue(Regex("`[0-9a-f]{40}`").containsMatchIn(row[2]), "commit must be exact")
            assertTrue(Regex("`[0-9a-f]{32}`").containsMatchIn(row[4]), "Room identity is required")
            assertTrue(
                row[6] == "unavailable" || Regex("`[0-9a-f]{64}`").matches(row[6]),
                "APK hash must be exact or unavailable",
            )
            val evidence = checkNotNull(releaseEvidence[row[0]])
            val commit = checkNotNull(Regex("`([0-9a-f]{40})`").find(row[2])).groupValues[1]
            val identity = checkNotNull(Regex("`([0-9a-f]{32})`").find(row[4])).groupValues[1]
            val apkHash = row[6].removeSurrounding("`")
            assertEquals(listOf(row[1], commit, row[3], identity, apkHash), evidence.subList(1, 6))
            if (row[0].startsWith("v")) {
                assertEquals(commit, git(root, "rev-list", "-n", "1", row[0]))
            }
            val buildScript = git(root, "show", "$commit:app/build.gradle.kts")
            val databaseSource =
                git(
                    root,
                    "show",
                    "$commit:app/src/main/java/com/dsalmun/luxalarm/data/AlarmDatabase.kt",
                )
            assertTrue(buildScript.contains("versionCode = ${row[1]}"))
            assertTrue(Regex("@Database\\([^\\n]*version = ${row[3]}[,)]").containsMatchIn(databaseSource))
            val fixtureId = row[7]
            if (fixtureId != "unavailable") {
                val manifest = checkNotNull(manifestRows[fixtureId])
                assertEquals(row[3], manifest[1], "$fixtureId ledger/manifest version mismatch")
                val fingerprint = checkNotNull(Regex("`([0-9a-f]{64})`").find(row[5]))
                assertEquals(fingerprint.groupValues[1], manifest[3], "$fixtureId fingerprint mismatch")
            }
        }

        for ((fixtureId, columns) in manifestRows) {
            assertTrue(columns.size == 6, "$fixtureId must have six manifest columns")
            val fixture = File(root, columns[2])
            assertTrue(fixture.isFile, "$fixtureId must point to a committed schema fixture")
            val actualSha256 =
                MessageDigest.getInstance("SHA-256")
                    .digest(fixture.readBytes())
                    .joinToString("") { byte ->
                        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                    }
            assertEquals(columns[3], actualSha256, "$fixtureId fixture hash must be current")
            val fixtureText = fixture.readText()
            val versionMarker =
                if (fixture.extension == "json") {
                    "\"version\": ${columns[1]}"
                } else {
                    "PRAGMA user_version = ${columns[1]};"
                }
            assertTrue(fixtureText.contains(versionMarker), "$fixtureId must freeze its version")
            val migrationTestName = columns[4]
            val loaderName = columns[5]
            val testBody = functionBody(migrationTest, migrationTestName)
            assertTrue(testBody.contains("$loaderName("), "$migrationTestName must call $loaderName")
            val assetPath = columns[2].removePrefix("app/schemas/")
            assertTrue(
                functionBody(migrationTest, loaderName).contains("\"$assetPath\""),
                "$loaderName must load $assetPath",
            )
        }
        assertEquals(
            2,
            manifestRows.values.count { it[1] == "2" },
            "Both known user_version=2 schemas must remain frozen",
        )
    }
}
