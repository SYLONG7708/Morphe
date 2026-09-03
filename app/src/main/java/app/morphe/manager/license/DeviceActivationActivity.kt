package app.morphe.manager.license

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.morphe.manager.AppIntegrity
import app.morphe.manager.BuildConfig
import app.morphe.manager.MainActivity
import app.morphe.manager.ManagerApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DeviceActivationActivity : Activity() {
    private val backgroundColor = Color.rgb(5, 14, 21)
    private val panelColor = Color.rgb(14, 31, 41)
    private val foregroundColor = Color.rgb(241, 249, 248)
    private val mutedColor = Color.rgb(145, 167, 173)
    private val accentColor = Color.rgb(104, 234, 217)
    private val activationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var statusView: TextView
    private lateinit var codeView: TextView
    private lateinit var countdownView: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retryButton: Button
    private lateinit var purchaseButton: Button
    private lateinit var enterButton: Button
    private var activationJob: Job? = null
    private var currentUserCode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        if (!AppIntegrity.isAuthentic(this)) {
            finish()
            return
        }
        if (DeviceLicenseManager.isLicensed(this)) {
            enterApp()
            return
        }
        setContentView(buildUi())
        beginActivation()
    }

    override fun onDestroy() {
        activationJob?.cancel()
        activationScope.cancel()
        super.onDestroy()
    }

    private fun beginActivation() {
        activationJob?.cancel()
        activationJob = activationScope.launch {
            setBusy(true, "正在建立安全配對…")
            runCatching { DeviceActivationClient.start(this@DeviceActivationActivity) }
                .onSuccess { session ->
                    currentUserCode = session.userCode
                    codeView.text = session.userCode.chunked(3).joinToString(" ")
                    statusView.text = "正在為這台車機自動開通一次性免費 7 天試用…"
                    runCatching { DeviceActivationClient.startTrial(session) }
                        .onSuccess {
                            statusView.text = "免費 7 天試用已核准，正在安全寫入裝置…"
                        }
                        .onFailure {
                            statusView.text = "此機試用已使用或網路暫時無法核發；可輸入配對碼購買 2 年授權。"
                            purchaseButton.isEnabled = true
                            retryButton.isEnabled = true
                        }
                    pollUntilComplete(session)
                }
                .onFailure { error ->
                    setBusy(false, error.message ?: "無法連線授權服務")
                    retryButton.isEnabled = true
                }
        }
    }

    private suspend fun pollUntilComplete(session: DeviceActivationClient.Session) {
        while (currentCoroutineContext().isActive) {
            val remaining = session.expiresAt - System.currentTimeMillis() / 1000L
            if (remaining <= 0L) {
                setBusy(false, "配對碼已逾時，請重新取得")
                return
            }
            countdownView.text = "有效時間 ${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}"
            delay(session.pollAfterSeconds * 1000L)
            val result = try {
                DeviceActivationClient.poll(session)
            } catch (_: Exception) {
                statusView.text = "網路暫時中斷，正在自動重試…"
                continue
            }
            when (result) {
                DeviceActivationClient.PollResult.Pending -> Unit
                DeviceActivationClient.PollResult.Expired -> {
                    setBusy(false, "配對碼已逾時，請重新取得")
                    return
                }
                is DeviceActivationClient.PollResult.Approved -> {
                    val installed = DeviceLicenseManager.install(this, result.licenseToken)
                    if (!installed.valid) {
                        setBusy(false, installed.message)
                        return
                    }
                    result.linkTokens.forEach(::sendSuiteLinkToken)
                    progress.visibility = View.GONE
                    codeView.text = "✓"
                    countdownView.text = if (result.licenseExpiresAt > 0L) {
                        "有效至 ${formatExpiry(result.licenseExpiresAt)}"
                    } else {
                        "裝置綁定完成"
                    }
                    statusView.text = if (result.entitlementKind == "trial") {
                        "免費 7 天試用已安全寫入。每台車機僅能領取一次，重新安裝不會重置試用。"
                    } else {
                        "三合一授權已安全寫入；同車機的語音助手與影視會一併啟用。"
                    }
                    retryButton.visibility = View.GONE
                    enterButton.visibility = View.VISIBLE
                    Toast.makeText(this, "SyMorphe 授權完成", Toast.LENGTH_LONG).show()
                    return
                }
            }
        }
    }

    private fun sendSuiteLinkToken(product: String, token: String) {
        val target = when (product) {
            "voice-assistant" -> Triple(
                "tw.com.sylong.voicecore.action.LINK_DEVICE_LICENSE",
                "tw.com.sylong.voicecore",
                "tw.com.sylong.voicecore.LicenseLinkActivity",
            )
            "yingshi" -> Triple(
                "tw.com.sylong.tvcar.action.LINK_DEVICE_LICENSE",
                "tw.com.sylong.tvcar",
                "tw.com.sylong.tvcar.LicenseLinkActivity",
            )
            else -> return
        }
        val intent = Intent(target.first).apply {
            setClassName(target.second, target.third)
            putExtra("linkToken", token)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        }
        runCatching { startActivity(intent) }
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(backgroundColor) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(38), dp(28), dp(42))
        }
        scroll.addView(root)

        root.addView(label("SyMorphe", 34, foregroundColor, Typeface.BOLD))
        root.addView(label("這台車機尚未授權", 18, mutedColor, Typeface.NORMAL).apply {
            setPadding(0, dp(8), 0, dp(28))
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
            setBackgroundColor(panelColor)
        }
        root.addView(card, LinearLayout.LayoutParams(-1, -2))
        card.addView(label("一次性 6 位配對碼", 15, mutedColor, Typeface.BOLD))
        codeView = label("••• •••", 44, accentColor, Typeface.BOLD).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(0, dp(18), 0, dp(10))
        }
        card.addView(codeView, LinearLayout.LayoutParams(-1, -2))
        countdownView = label("有效時間 10:00", 14, accentColor, Typeface.NORMAL)
        card.addView(countdownView)
        progress = ProgressBar(this).apply { isIndeterminate = true }
        card.addView(progress, LinearLayout.LayoutParams(dp(42), dp(42)).apply { topMargin = dp(18) })

        statusView = label("正在連線授權服務…", 15, mutedColor, Typeface.NORMAL).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(12))
        }
        root.addView(statusView, LinearLayout.LayoutParams(-1, -2))

        purchaseButton = button("購買三套 App 2 年 NT$1,000", Color.rgb(23, 145, 141)) {
            val code = currentUserCode
            if (code.length != 6) {
                Toast.makeText(this, "請先取得 6 位授權碼", Toast.LENGTH_SHORT).show()
            } else {
                val url = "${BuildConfig.LICENSE_PURCHASE_URL}?code=$code"
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    .onFailure { Toast.makeText(this, "請在手機開啟 $url", Toast.LENGTH_LONG).show() }
            }
        }.apply { isEnabled = false }
        root.addView(purchaseButton, LinearLayout.LayoutParams(-1, dp(58)))
        root.addView(button("手機授權完整教學", panelColor) {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${BuildConfig.LICENSE_API_BASE_URL}/guide"))) }
                .onFailure { Toast.makeText(this, "請在手機開啟授權網站", Toast.LENGTH_SHORT).show() }
        }, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(10) })
        retryButton = button("重新取得配對碼", panelColor) { beginActivation() }.apply { isEnabled = false }
        root.addView(retryButton, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(10) })
        enterButton = button("進入 SyMorphe", Color.rgb(23, 145, 141)) { enterApp() }.apply {
            visibility = View.GONE
        }
        root.addView(enterButton, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(10) })
        root.addView(label("首次安裝會自動嘗試開通 7 天；伺服器以雜湊後的穩定裝置識別防止重複領取。配對碼只能使用一次；開始付款後會保留 30 分鐘。", 12, mutedColor, Typeface.NORMAL).apply {
            setPadding(0, dp(24), 0, 0)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2))
        return scroll
    }

    private fun setBusy(busy: Boolean, message: String) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        statusView.text = message
        if (!busy) countdownView.text = ""
    }

    private fun enterApp() {
        (application as? ManagerApplication)?.onDeviceLicenseAvailable()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun formatExpiry(epochSeconds: Long): String = SimpleDateFormat(
        "yyyy/MM/dd HH:mm",
        Locale.TAIWAN,
    ).format(Date(epochSeconds * 1000L))

    private fun label(value: String, size: Int, color: Int, style: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        setTypeface(Typeface.DEFAULT, style)
    }

    private fun button(value: String, color: Int, listener: (View) -> Unit) = Button(this).apply {
        text = value
        textSize = 15f
        isAllCaps = false
        setTextColor(foregroundColor)
        setBackgroundColor(color)
        setOnClickListener(listener)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
