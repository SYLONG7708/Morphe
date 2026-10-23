/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-manager
 *
 * Original hard forked code:
 * https://github.com/Jman-Github/Universal-ReVanced-Manager/blob/597b3173a004f5a9aae54326046dd7fd4c5b7777/app/src/main/java/app/revanced/manager/util/ExportNameFormatter.kt
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.manager.util

import app.morphe.manager.BuildConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class PatchedAppExportData(
    val appName: String?,
    val packageName: String,
    val appVersion: String?,
    val patchBundleVersions: List<String> = emptyList(),
    val patchBundleNames: List<String> = emptyList(),
    val generatedAt: Instant = Instant.now(),
    val managerVersion: String = BuildConfig.VERSION_NAME
)

object ExportNameFormatter {
    const val DEFAULT_TEMPLATE = "{app name}-{app version}-{patches version}.apk"

    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneId.systemDefault())
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        .withZone(ZoneId.systemDefault())

    fun format(template: String?, data: PatchedAppExportData): String {
        val resolved = replaceVariables(template?.takeIf { it.isNotBlank() } ?: DEFAULT_TEMPLATE, data)
            .trim()
            .ifEmpty { DEFAULT_TEMPLATE }
            .let { if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk" }
        // An app name made only of symbols would leave nothing before the extension
        return FilenameUtils.sanitize(resolved, keepScripts = true)
            .takeUnless { it.substringBeforeLast('.').isBlank() }
            ?: "${FilenameUtils.sanitize(data.packageName)}.apk"
    }

    private fun replaceVariables(template: String, data: PatchedAppExportData): String {
        val replacements = buildMap<String, String> {
            put("{app name}", data.appName?.takeUnless { it.isBlank() } ?: data.packageName)
            put("{package name}", data.packageName)
            put("{app version}", formatVersion(data.appVersion) ?: "unknown")
            put(
                "{patches version}",
                joinValues(
                    data.patchBundleVersions.mapNotNull(::formatPatchesVersion),
                    fallback = "patches-unknown",
                    limit = 1
                )
            )
            put(
                "{patch bundle names}",
                joinValues(data.patchBundleNames, fallback = "bundles", limit = 2, separator = "_")
            )
            put("{manager version}", data.managerVersion.takeUnless { it.isBlank() } ?: "unknown")
            put("{timestamp}", timestampFormatter.format(data.generatedAt))
            put("{date}", dateFormatter.format(data.generatedAt))
        }

        return replacements.entries.fold(template) { acc, (token, value) ->
            acc.replace(token, value)
        }
    }

    private fun formatVersion(raw: String?): String? = bareVersion(raw)?.let { "v$it" }

    private fun formatPatchesVersion(raw: String?): String? = bareVersion(raw)?.let { "patches-v$it" }

    // Lowercased first, so a "V" prefix goes too
    private fun bareVersion(raw: String?): String? =
        raw?.trim()?.lowercase()?.normalizeVersion()?.takeUnless { it.isEmpty() }

    private fun joinValues(
        values: List<String>,
        fallback: String,
        limit: Int,
        separator: String = "+"
    ): String {
        val filtered = values.mapNotNull { it.trim().takeIf(String::isNotEmpty) }.distinct()
        if (filtered.isEmpty()) return fallback
        return (if (limit > 0) filtered.take(limit) else filtered).joinToString(separator)
    }
}
