/*
 * SyMorphe modification, 2026.
 * Released under GPL-3.0-or-later with the upstream NOTICE conditions.
 */

package app.morphe.manager.domain.update

import android.content.Context
import app.morphe.manager.R
import java.io.File
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * Verifies detached SHA-256/RSA signatures against the certificate pinned in the APK.
 *
 * The private key exists only in the repository's GitHub Actions secrets and the user's ignored
 * local release-keystore directory. A compromised download host therefore cannot publish a valid
 * manager, patch bundle, dependency, or update manifest.
 */
class DetachedSignatureVerifier(context: Context) {
    private val publicKey = context.resources
        .openRawResource(R.raw.update_signing_cert)
        .use { input ->
            (CertificateFactory.getInstance("X.509")
                .generateCertificate(input) as X509Certificate).publicKey
        }

    fun verifyBytes(payload: ByteArray, base64Signature: String): Boolean =
        runCatching {
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initVerify(publicKey)
            signature.update(payload)
            signature.verify(decodeSignature(base64Signature))
        }.getOrDefault(false)

    fun verifyFile(file: File, base64Signature: String): Boolean =
        runCatching {
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initVerify(publicKey)
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    signature.update(buffer, 0, read)
                }
            }
            signature.verify(decodeSignature(base64Signature))
        }.getOrDefault(false)

    private fun decodeSignature(value: String): ByteArray =
        Base64.getMimeDecoder().decode(value.trim())
}
