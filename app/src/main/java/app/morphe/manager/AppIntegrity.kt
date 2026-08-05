package app.morphe.manager

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

/** Refuses production builds that were modified and re-signed by another party. */
internal object AppIntegrity {
    fun isAuthentic(context: Context): Boolean {
        if (BuildConfig.DEBUG || BuildConfig.ALLOW_INSECURE_TEST_SIGNING) {
            return true
        }
        val expected = BuildConfig.EXPECTED_SIGNING_CERT_SHA256
        if (!expected.matches(Regex("[0-9A-F]{64}"))) {
            return false
        }
        return matchesExpected(expected, installedSigningCertificates(context))
    }

    internal fun matchesExpected(expected: String, actual: Collection<String>): Boolean {
        val normalized = expected.replace(":", "").uppercase()
        return normalized.matches(Regex("[0-9A-F]{64}")) && actual.any {
            it.replace(":", "").uppercase() == normalized
        }
    }

    @Suppress("DEPRECATION")
    private fun installedSigningCertificates(context: Context): List<String> {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES,
            )
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return emptyList()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            packageInfo.signatures
        }
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02X".format(byte) }
        }
    }
}
