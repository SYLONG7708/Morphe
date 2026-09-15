/*
 * Copyright 2026 SyMorphe.
 */

package app.morphe.manager.domain.update

/**
 * Minimal compatibility metadata needed to resolve an exact original YouTube APK.
 */
data class YouTubeVersionBuild(
    val version: String?,
    val bundleUid: Int,
    val versionCodes: Set<Int>?,
    val codesByAbi: Map<String, Int> = emptyMap(),
)

data class YouTubeDownloadCandidate(
    val packageName: String,
    val version: String,
    val versionCode: Int,
    val downloadUrl: String,
    val sha256: String? = null,
)

/**
 * Resolves only exact version/versionCode pairs declared by the loaded patch bundle.
 *
 * A version name without a build code is deliberately not downloadable: it cannot be
 * cryptographically tied back to the exact compatibility entry after download.
 */
object YouTubeSourceResolver {
    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val APKPURE_DOWNLOAD_BASE = "https://d.apkpure.net/b/APK"
    private val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")

    fun resolve(
        recommendedVersion: String?,
        selectedBundleUid: Int?,
        compatibleVersions: List<YouTubeVersionBuild>,
        deviceAbis: List<String> = emptyList(),
    ): List<YouTubeDownloadCandidate> {
        val version = recommendedVersion?.takeUnless(String::isBlank) ?: return emptyList()
        return compatibleVersions.asSequence()
            .filter { it.version == version }
            .filter { selectedBundleUid == null || it.bundleUid == selectedBundleUid }
            .flatMap { entry ->
                if (entry.codesByAbi.isNotEmpty() && deviceAbis.isNotEmpty()) {
                    deviceAbis.mapNotNull(entry.codesByAbi::get).asSequence()
                } else {
                    entry.versionCodes.orEmpty().sortedDescending().asSequence()
                }
            }
            .filter { it > 0 }
            .distinct()
            .map { versionCode ->
                YouTubeDownloadCandidate(
                    packageName = YOUTUBE_PACKAGE,
                    version = version,
                    versionCode = versionCode,
                    downloadUrl = buildDownloadUrl(YOUTUBE_PACKAGE, versionCode),
                )
            }
            .toList()
    }

    /**
     * Uses bundle-declared build codes first, then the release-pinned exact original when
     * the active bundle only declares a compatible version name. The pinned fallback is
     * still tied to an exact versionCode and SHA-256 before the downloader accepts it.
     */
    fun resolveWithPinnedFallback(
        recommendedVersion: String?,
        selectedBundleUid: Int?,
        compatibleVersions: List<YouTubeVersionBuild>,
        allowPinnedFallback: Boolean,
        pinnedVersion: String,
        pinnedVersionCode: Int,
        pinnedSha256: String,
        deviceAbis: List<String> = emptyList(),
    ): List<YouTubeDownloadCandidate> {
        val declared = resolve(
            recommendedVersion = recommendedVersion,
            selectedBundleUid = selectedBundleUid,
            compatibleVersions = compatibleVersions,
            deviceAbis = deviceAbis,
        )
        if (declared.isNotEmpty()) return declared
        // A known incompatible ABI must never turn into the old pinned ARM64 download.
        if (compatibleVersions.any {
            it.version == recommendedVersion &&
                (selectedBundleUid == null || it.bundleUid == selectedBundleUid) &&
                it.codesByAbi.isNotEmpty()
        }) return emptyList()
        if (
            !allowPinnedFallback ||
            recommendedVersion != pinnedVersion ||
            pinnedVersionCode <= 0 ||
            !pinnedSha256.matches(SHA256_HEX)
        ) {
            return emptyList()
        }
        return listOf(
            YouTubeDownloadCandidate(
                packageName = YOUTUBE_PACKAGE,
                version = pinnedVersion,
                versionCode = pinnedVersionCode,
                downloadUrl = buildDownloadUrl(YOUTUBE_PACKAGE, pinnedVersionCode),
                sha256 = pinnedSha256.lowercase(),
            )
        )
    }

    internal fun buildDownloadUrl(packageName: String, versionCode: Int): String =
        "$APKPURE_DOWNLOAD_BASE/$packageName?versionCode=$versionCode"

    fun abiName(patcherAbi: String): String = when (patcherAbi) {
        "ARM64_V8A" -> "arm64-v8a"
        "ARMEABI_V7A" -> "armeabi-v7a"
        "X86_64" -> "x86_64"
        "X86" -> "x86"
        else -> patcherAbi
    }
}
