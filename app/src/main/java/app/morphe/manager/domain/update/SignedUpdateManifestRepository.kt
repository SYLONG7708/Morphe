/*
 * SyMorphe modification, 2026.
 */

package app.morphe.manager.domain.update

import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.network.service.HttpService
import app.morphe.manager.network.utils.getOrThrow
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.morphe.manager.network.dto.GitHubRelease

class SignedUpdateManifestRepository(
    private val http: HttpService,
    private val json: Json,
    private val verifier: DetachedSignatureVerifier,
    private val prefs: PreferencesManager,
) {
    private val mutex = Mutex()

    suspend fun fetch(): UpdateManifest = mutex.withLock {
        try {
            fetchPair(MANIFEST_URL, MANIFEST_SIGNATURE_URL)
        } catch (error: CancellationException) {
            throw error
        } catch (first: Exception) {
            // Resolve both files from one immutable release if latest/CDN raced a publish.
            // The public API is a fallback; it cannot bypass RSA or rollback verification.
            delay(1_000)
            val release = http.request<GitHubRelease> {
                url("https://api.github.com/repos/SYLONG7708/Morphe/releases/latest")
            }.getOrThrow()
            check(!release.draft && !release.prerelease)
            require(release.tagName.matches(Regex("symorphe-v[0-9.]+")))
            val base = "https://github.com/SYLONG7708/Morphe/releases/download/${release.tagName}"
            fetchPair("$base/update-manifest.json", "$base/update-manifest.json.sig")
        }
    }

    private suspend fun fetchPair(manifestUrl: String, signatureUrl: String): UpdateManifest {
        val manifestText = http.request<String> {
            url(manifestUrl)
            header(HttpHeaders.CacheControl, "no-cache")
        }.getOrThrow()
        val signatureText = http.request<String> {
            url(signatureUrl)
            header(HttpHeaders.CacheControl, "no-cache")
        }.getOrThrow()

        check(verifier.verifyBytes(manifestText.toByteArray(Charsets.UTF_8), signatureText)) {
            "Update manifest signature verification failed"
        }

        val manifest = json.decodeFromString<UpdateManifest>(manifestText)
        require(manifest.schema == SUPPORTED_SCHEMA) {
            "Unsupported update manifest schema ${manifest.schema}"
        }
        require(manifest.channel == "stable") { "Untrusted update channel ${manifest.channel}" }
        require(manifest.sequence > 0) { "Invalid update manifest sequence" }

        val acceptedSequence = prefs.lastAcceptedManifestSequence.get()
        require(manifest.sequence >= acceptedSequence) {
            "Rejected rollback manifest ${manifest.sequence} < $acceptedSequence"
        }
        if (manifest.sequence > acceptedSequence) {
            prefs.lastAcceptedManifestSequence.update(manifest.sequence)
        }
        return manifest
    }

    companion object {
        const val SUPPORTED_SCHEMA = 1
        const val MANIFEST_URL =
            "https://github.com/SYLONG7708/Morphe/releases/latest/download/update-manifest.json"
        const val MANIFEST_SIGNATURE_URL =
            "https://github.com/SYLONG7708/Morphe/releases/latest/download/update-manifest.json.sig"
    }
}
