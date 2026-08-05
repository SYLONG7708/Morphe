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
    ): List<YouTubeDownloadCandidate> {
        val version = recommendedVersion?.takeUnless(String::isBlank) ?: return emptyList()
        return compatibleVersions.asSequence()
            .filter { it.version == version }
            .filter { selectedBundleUid == null || it.bundleUid == selectedBundleUid }
            .flatMap { it.versionCodes.orEmpty().asSequence() }
            .filter { it > 0 }
            .distinct()
            .sortedDescending()
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
    ): List<YouTubeDownloadCandidate> {
        val declared = resolve(
            recommendedVersion = recommendedVersion,
            selectedBundleUid = selectedBundleUid,
            compatibleVersions = compatibleVersions,
        )
        if (declared.isNotEmpty()) return declared
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
}
