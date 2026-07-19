/*
 * AutoPatch Hub modification, 2026.
 */

package app.morphe.manager.domain.update

import app.morphe.manager.domain.manager.PreferencesManager
import app.morphe.manager.network.service.HttpService
import app.morphe.manager.network.utils.getOrThrow
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json

class SignedUpdateManifestRepository(
    private val http: HttpService,
    private val json: Json,
    private val verifier: DetachedSignatureVerifier,
    private val prefs: PreferencesManager,
) {
    suspend fun fetch(): UpdateManifest {
        val manifestText = http.request<String> {
            url(MANIFEST_URL)
            header(HttpHeaders.CacheControl, "no-cache")
        }.getOrThrow()
        val signatureText = http.request<String> {
            url(MANIFEST_SIGNATURE_URL)
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
