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

    internal fun buildDownloadUrl(packageName: String, versionCode: Int): String =
        "$APKPURE_DOWNLOAD_BASE/$packageName?versionCode=$versionCode"
}
