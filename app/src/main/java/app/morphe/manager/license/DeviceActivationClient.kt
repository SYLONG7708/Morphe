package app.morphe.manager.license

import android.content.Context
import app.morphe.manager.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object DeviceActivationClient {
    data class Session(
        val sessionId: String,
        val userCode: String,
        val pollSecret: String,
        var expiresAt: Long,
        val pollAfterSeconds: Long,
    )

    sealed interface PollResult {
        data object Pending : PollResult
        data object Expired : PollResult
        data class Approved(
            val licenseToken: String,
            val linkTokens: Map<String, String>,
            val entitlementKind: String,
            val licenseExpiresAt: Long,
        ) : PollResult
    }

    suspend fun start(context: Context): Session = withContext(Dispatchers.IO) {
        val response = post(
            "/api/v1/activation/start",
            installationJson(context, BuildConfig.LICENSE_PRODUCT_ID),
        )
        Session(
            sessionId = response.getString("sessionId"),
            userCode = response.getString("userCode"),
            pollSecret = response.getString("pollSecret"),
            expiresAt = response.getLong("expiresAt"),
            pollAfterSeconds = response.optLong("pollAfterSeconds", 3L).coerceIn(2L, 10L),
        )
    }

    suspend fun poll(session: Session): PollResult = withContext(Dispatchers.IO) {
        val response = post(
            "/api/v1/activation/poll",
            JSONObject()
                .put("sessionId", session.sessionId)
                .put("pollSecret", session.pollSecret),
        )
        when (response.getString("status")) {
            "approved" -> PollResult.Approved(
                licenseToken = response.getString("licenseToken"),
                linkTokens = parseLinkTokens(response),
                entitlementKind = response.optString("entitlementKind"),
                licenseExpiresAt = response.optLong("licenseExpiresAt", 0L),
            )
            "expired" -> PollResult.Expired
            else -> {
                response.optLong("expiresAt", 0L).takeIf { it > 0L }?.let { session.expiresAt = it }
                PollResult.Pending
            }
        }
    }

    suspend fun startTrial(session: Session) = withContext(Dispatchers.IO) {
        post(
            "/api/v1/activation/trial",
            JSONObject()
                .put("sessionId", session.sessionId)
                .put("pollSecret", session.pollSecret),
        )
    }

    private fun parseLinkTokens(response: JSONObject): Map<String, String> {
        val result = linkedMapOf<String, String>()
        response.optJSONObject("linkTokens")?.let { links ->
            links.keys().forEach { product ->
                links.optString(product).takeIf(String::isNotBlank)?.let { result[product] = it }
            }
        }
        response.optString("linkToken").takeIf(String::isNotBlank)?.let {
            result.putIfAbsent("voice-assistant", it)
        }
        return result
    }

    internal fun installationJson(context: Context, product: String): JSONObject = JSONObject()
        .put("product", product)
        .put("packageName", context.packageName)
        .put("certSha256", DeviceLicenseManager.signingCertificateSha256(context))
        .put("publicKey", DeviceLicenseManager.publicKeyBase64())
        .put("deviceLabel", DeviceLicenseManager.deviceLabel())
        .put("androidIdHash", DeviceLicenseManager.androidIdHash(context))

    private fun post(path: String, body: JSONObject): JSONObject {
        val base = BuildConfig.LICENSE_API_BASE_URL.trimEnd('/')
        require(base.startsWith("https://")) { "授權伺服器必須使用 HTTPS" }
        val connection = (URL("$base$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "SyMorphe/${BuildConfig.VERSION_NAME}")
        }
        connection.outputStream.use { output ->
            output.write(body.toString().toByteArray(StandardCharsets.UTF_8))
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val raw = stream?.bufferedReader(StandardCharsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
        connection.disconnect()
        val response = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        if (status !in 200..299) {
            throw IllegalStateException(response.optString("error").ifBlank { "授權服務暫時無法使用 ($status)" })
        }
        return response
    }
}
