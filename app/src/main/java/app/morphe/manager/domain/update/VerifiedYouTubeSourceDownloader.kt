/*
 * Copyright 2026 SyMorphe.
 */

package app.morphe.manager.domain.update

import android.os.Build
import android.util.Log
import app.morphe.manager.BuildConfig
import app.morphe.manager.data.platform.Filesystem
import app.morphe.manager.network.service.HttpService
import app.morphe.manager.util.PM
import app.morphe.manager.util.sha256OrNull
import com.android.apksig.ApkVerifier
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class VerifiedYouTubeSource(
    val file: File,
    val packageName: String,
    val version: String,
    val versionCode: Long,
    val sha256: String,
    val signerSha256: Set<String>,
)

sealed interface YouTubeSourceDownloadResult {
    data class Success(val source: VerifiedYouTubeSource) : YouTubeSourceDownloadResult
    data class Failure(val reason: String, val cause: Throwable? = null) : YouTubeSourceDownloadResult
}

/**
 * Downloads a private-use original YouTube APK and verifies it before it reaches the patcher.
 *
 * Trust is anchored in the signing fingerprints carried by the already verified official
 * Morphe patch bundle. APK Signature Scheme verification happens independently of Android's
 * PackageManager so truncated, unsigned, or structurally invalid files are rejected on every
 * supported Android version.
 */
class VerifiedYouTubeSourceDownloader(
    private val httpService: HttpService,
    private val filesystem: Filesystem,
    private val pm: PM,
) {
    suspend fun download(
        candidate: YouTubeDownloadCandidate,
        expectedSignerSha256: Set<String>,
        onProgress: (bytesRead: Long, contentLength: Long?) -> Unit = { _, _ -> },
    ): YouTubeSourceDownloadResult {
        val expectedSigners = expectedSignerSha256
            .mapTo(mutableSetOf()) { it.normalizedFingerprint() }
            .filterTo(mutableSetOf()) { it.length == SHA256_HEX_LENGTH }
            .intersect(PINNED_GOOGLE_YOUTUBE_SIGNERS)
        if (expectedSigners.isEmpty()) {
            return YouTubeSourceDownloadResult.Failure(
                "The active patch bundle does not declare the pinned Google YouTube signer"
            )
        }

        val partial = File(
            filesystem.uiTempDir,
            "${candidate.packageName}_${candidate.versionCode}.apk.part",
        )
        val completed = File(
            filesystem.uiTempDir,
            "${candidate.packageName}_${candidate.versionCode}.apk",
        )

        return try {
            withContext(Dispatchers.IO) {
                partial.parentFile?.mkdirs()
                partial.delete()
                completed.delete()
            }
            httpService.downloadToFile(
                saveLocation = partial,
                threads = DOWNLOAD_THREADS,
                builder = {
                    url(candidate.downloadUrl)
                    header(
                        HttpHeaders.UserAgent,
                        "SyMorphe/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.SDK_INT})",
                    )
                },
                onProgress = onProgress,
            )

            val verified = withContext(Dispatchers.IO) {
                verifyDownloadedApk(partial, candidate, expectedSigners)
            }
            when (verified) {
                is YouTubeSourceDownloadResult.Failure -> verified
                is YouTubeSourceDownloadResult.Success -> {
                    withContext(Dispatchers.IO) {
                        if (!partial.renameTo(completed)) {
                            partial.copyTo(completed, overwrite = true)
                            partial.delete()
                        }
                    }
                    verified.copy(source = verified.source.copy(file = completed))
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "YouTube source download failed", error)
            YouTubeSourceDownloadResult.Failure(
                reason = error.message ?: error::class.java.simpleName,
                cause = error,
            )
        } finally {
            withContext(Dispatchers.IO) {
                partial.delete()
            }
        }.also { result ->
            if (result is YouTubeSourceDownloadResult.Failure) {
                withContext(Dispatchers.IO) {
                    completed.delete()
                }
            }
        }
    }

    private fun verifyDownloadedApk(
        file: File,
        candidate: YouTubeDownloadCandidate,
        expectedSigners: Set<String>,
    ): YouTubeSourceDownloadResult {
        if (!file.isFile || file.length() < MIN_APK_BYTES) {
            return YouTubeSourceDownloadResult.Failure("Downloaded file is not a complete APK")
        }

        val verification = ApkVerifier.Builder(file).build().verify()
        if (!verification.isVerified) {
            val details = verification.errors
                .take(3)
                .joinToString("; ") { it.toString() }
                .ifBlank { "APK signature scheme verification failed" }
            return YouTubeSourceDownloadResult.Failure(details)
        }

        val signerHashes = verification.signerCertificates
            .mapTo(mutableSetOf()) { certificate ->
                MessageDigest.getInstance("SHA-256")
                    .digest(certificate.encoded)
                    .joinToString("") { byte -> "%02x".format(byte) }
            }
        if (signerHashes.intersect(expectedSigners).isEmpty()) {
            return YouTubeSourceDownloadResult.Failure(
                "APK signer does not match the official patch bundle"
            )
        }

        val packageInfo = pm.getPackageInfo(file)
            ?: return YouTubeSourceDownloadResult.Failure("Android cannot parse the downloaded APK")
        val actualVersion = packageInfo.versionName.orEmpty()
        val actualVersionCode = pm.getVersionCode(packageInfo)
        if (packageInfo.packageName != candidate.packageName) {
            return YouTubeSourceDownloadResult.Failure(
                "Unexpected package ${packageInfo.packageName}"
            )
        }
        if (actualVersion != candidate.version || actualVersionCode != candidate.versionCode.toLong()) {
            return YouTubeSourceDownloadResult.Failure(
                "Expected ${candidate.version} (${candidate.versionCode}), " +
                    "received $actualVersion ($actualVersionCode)"
            )
        }
        val minSdk = packageInfo.applicationInfo?.minSdkVersion
        if (minSdk != null && minSdk > Build.VERSION.SDK_INT) {
            return YouTubeSourceDownloadResult.Failure(
                "APK requires Android API $minSdk; this device is API ${Build.VERSION.SDK_INT}"
            )
        }
        val sha256 = file.sha256OrNull()
            ?: return YouTubeSourceDownloadResult.Failure("Unable to hash the downloaded APK")
        candidate.sha256?.let { expected ->
            if (!sha256.equals(expected, ignoreCase = true)) {
                return YouTubeSourceDownloadResult.Failure(
                    "APK SHA-256 does not match the build-pinned original"
                )
            }
        }

        return YouTubeSourceDownloadResult.Success(
            VerifiedYouTubeSource(
                file = file,
                packageName = packageInfo.packageName,
                version = actualVersion,
                versionCode = actualVersionCode,
                sha256 = sha256,
                signerSha256 = signerHashes,
            )
        )
    }

    private fun String.normalizedFingerprint(): String =
        lowercase().replace(":", "").replace(" ", "")

    private companion object {
        const val TAG = "AutoPatch YouTube"
        const val DOWNLOAD_THREADS = 4
        const val MIN_APK_BYTES = 1_000_000L
        const val SHA256_HEX_LENGTH = 64
        val PINNED_GOOGLE_YOUTUBE_SIGNERS = setOf(
            // Google LLC YouTube production certificates (SHA-256 of DER certificate).
            // Android 13+ selects the rotated 4096-bit signer; older platforms use the
            // original signer below. ApkVerifier returns the signer for the current SDK.
            "5aad2bee6db95d17e05a08d7d1e64c10a1511879154483916b6ae6c7fd9cb0c6",
            "3d7a1223019aa39d9ea0e3436ab7c0896bfb4fb679f4de5fe7c23f326c8f994a",
        )
    }
}
