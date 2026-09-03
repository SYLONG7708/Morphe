package app.morphe.manager.license

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.morphe.manager.BuildConfig
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale

object DeviceLicenseManager {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "symorphe.device.license.v1"
    private const val PREFS = "symorphe_device_license"
    private const val KEY_TOKEN = "signed_license_token"
    private const val TOKEN_PREFIX = "SML1"
    private const val CLOCK_SKEW_SECONDS = 300L

    data class Status(val valid: Boolean, val message: String)

    fun isLicensed(context: Context): Boolean = status(context).valid

    fun status(context: Context): Status {
        if (BuildConfig.NO_LICENSE_EDITION) return Status(true, "正式免授權版")
        if (!BuildConfig.DEVICE_LICENSE_REQUIRED) return Status(true, "開發測試授權")
        val token = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)
            ?.trim()
            .orEmpty()
        if (token.isEmpty()) return Status(false, "尚未綁定這台車機")
        return verifyToken(context, token)
    }

    fun install(context: Context, token: String): Status {
        val status = verifyToken(context, token)
        if (!status.valid) return status
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token.trim())
            .apply()
        return Status(true, "裝置綁定授權有效")
    }

    fun publicKeyBase64(): String = Base64.encodeToString(
        ensureKeyPair().certificate.publicKey.encoded,
        Base64.NO_WRAP,
    )

    fun publicKeySha256(): String = sha256Hex(ensureKeyPair().certificate.publicKey.encoded)

    @SuppressLint("HardwareIds")
    fun androidIdHash(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            .orEmpty()
        return sha256Hex("${context.packageName}|$androidId".toByteArray(StandardCharsets.UTF_8))
    }

    fun deviceLabel(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString(" ")
        .take(80)
        .ifEmpty { "Android 車機" }

    fun signingCertificateSha256(context: Context): String = installedSigningCertificates(context)
        .firstOrNull()
        .orEmpty()

    internal fun verifyToken(
        context: Context,
        token: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): Status = runCatching {
        val parts = token.trim().split('.')
        require(parts.size == 3 && parts[0] == TOKEN_PREFIX) { "授權格式錯誤" }
        val payloadPart = parts[1]
        val signatureBytes = decodeBase64Url(parts[2])
        val publicKeyBytes = Base64.decode(BuildConfig.LICENSE_PUBLIC_KEY_SPKI_B64, Base64.DEFAULT)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(payloadPart.toByteArray(StandardCharsets.US_ASCII))
        require(verifier.verify(signatureBytes)) { "授權簽章驗證失敗" }

        val claims = JSONObject(String(decodeBase64Url(payloadPart), StandardCharsets.UTF_8))
        require(claims.optInt("version") == 1) { "授權版本不相容" }
        require(claims.optString("iss") == "https://license.sylong.tw") { "授權簽發者不相符" }
        require(claims.optString("aud") == BuildConfig.LICENSE_PRODUCT_ID) { "授權產品不相符" }
        require(claims.optBoolean("offline", false)) { "授權模式不相容" }
        require(claims.optString("packageName") == context.packageName) { "授權 App 身分不符" }
        require(
            claims.optString("certSha256").normalizeFingerprint() ==
                signingCertificateSha256(context).normalizeFingerprint(),
        ) { "授權 App 簽章不符" }
        require(
            claims.optJSONObject("cnf")?.optString("sha256")?.normalizeFingerprint() ==
                publicKeySha256().normalizeFingerprint(),
        ) { "授權不屬於這台車機" }
        require(claims.optLong("nbf", Long.MAX_VALUE) <= nowSeconds + CLOCK_SKEW_SECONDS) {
            "裝置時間或授權生效時間不正確"
        }
        require(claims.optLong("iat", Long.MAX_VALUE) <= nowSeconds + CLOCK_SKEW_SECONDS) {
            "裝置時間或授權簽發時間不正確"
        }
        require(claims.optLong("exp", 0L) >= nowSeconds - CLOCK_SKEW_SECONDS) { "授權已到期" }
        Status(true, "裝置綁定授權有效")
    }.getOrElse { error -> Status(false, error.message ?: "授權驗證失敗") }

    private fun ensureKeyPair(): KeyStore.PrivateKeyEntry {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let { return it }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUserAuthenticationRequired(false)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
        return requireNotNull(keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry) {
            "無法建立裝置安全金鑰"
        }
    }

    @Suppress("DEPRECATION")
    private fun installedSigningCertificates(context: Context): List<String> {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptyList()
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners else signingInfo.signingCertificateHistory
        } else {
            info.signatures
        }
        return signatures.orEmpty().map { sha256Hex(it.toByteArray()) }
    }

    private fun decodeBase64Url(value: String): ByteArray = Base64.decode(
        value.replace('-', '+').replace('_', '/'),
        Base64.NO_WRAP or Base64.NO_PADDING,
    )

    private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { byte -> "%02X".format(byte) }

    private fun String.normalizeFingerprint(): String = replace(":", "").uppercase(Locale.US)
}
