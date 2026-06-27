package com.vpnproxy.app

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.text.DecimalFormat
import java.util.Locale
import kotlin.random.Random

class MainActivity : ComponentActivity() {

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var statusIndicator: FrameLayout
    private lateinit var statusPulse: ImageView
    private lateinit var statusGlowOverlay: ImageView
    private lateinit var statusIcon: ImageView
    private lateinit var glowRing1: ImageView
    private lateinit var glowRing2: ImageView
    private lateinit var statusText: TextView
    private lateinit var statusSubText: TextView
    private lateinit var serverCard: CardView
    private lateinit var statsCard: CardView
    private lateinit var connectButton: MaterialButton
    private lateinit var connectionProgress: LinearProgressIndicator
    private lateinit var uploadSpeed: TextView
    private lateinit var uploadTotal: TextView
    private lateinit var downloadSpeed: TextView
    private lateinit var downloadTotal: TextView
    private lateinit var serverName: TextView
    private lateinit var serverSelectorIcon: ImageView
    private lateinit var titleText: TextView

    private var isConnected = false
    private var isConnecting = false
    private val serverAddress = BuildConfig.PROXY_SERVER
    private val serverPort = BuildConfig.PROXY_PORT
    private var totalUpload = 0L
    private var totalDownload = 0L
    private var currentUploadSpeed = 0.0
    private var currentDownloadSpeed = 0.0
    private var selectedServer = ""

    private val uploadFormat = DecimalFormat("#.#")

    private lateinit var pulseAnimator: ValueAnimator
    private lateinit var glowRotateAnimator: ValueAnimator
    private var speedAnimator: ValueAnimator? = null

    private val connectedColor = Color.parseColor("#FF4CAF50")
    private val disconnectedColor = Color.parseColor("#FFE53935")
    private val connectingColor = Color.parseColor("#FFFFA726")

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        NativeChecker.load()
        if (!securityCheck()) return

        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT

        bindViews()
        setupEntryAnimations()
        setupPulseAnimation()
        setupGlowAnimation()
        setupClickListeners()
        simulateTraffic()
    }

    private fun securityCheck(): Boolean {
        val warnings = SecurityChecker.checkAll(this)
        if (warnings.isNotEmpty()) {
            val message = when {
                warnings.contains("DEBUG") -> "检测到调试环境，已退出"
                warnings.contains("EMULATOR") -> "模拟器环境不支持"
                warnings.contains("FRIDA") -> "检测到不安全环境，已退出"
                warnings.contains("XPOSED") -> "检测到不安全环境，已退出"
                else -> "检测到不安全环境 (${warnings.joinToString()})"
            }
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("安全检测")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("退出") { _, _ -> finish() }
                .show()
            return false
        }
        return true
    }

    private fun bindViews() {
        rootLayout = findViewById(R.id.rootLayout)
        statusIndicator = findViewById(R.id.statusIndicator)
        statusPulse = findViewById(R.id.statusPulse)
        statusGlowOverlay = findViewById(R.id.statusGlowOverlay)
        statusIcon = findViewById(R.id.statusIcon)
        glowRing1 = findViewById(R.id.glowRing1)
        glowRing2 = findViewById(R.id.glowRing2)
        statusText = findViewById(R.id.statusText)
        statusSubText = findViewById(R.id.statusSubText)
        serverCard = findViewById(R.id.serverCard)
        statsCard = findViewById(R.id.statsCard)
        connectButton = findViewById(R.id.connectButton)
        connectionProgress = findViewById(R.id.connectionProgress)
        uploadSpeed = findViewById(R.id.uploadSpeed)
        uploadTotal = findViewById(R.id.uploadTotal)
        downloadSpeed = findViewById(R.id.downloadSpeed)
        downloadTotal = findViewById(R.id.downloadTotal)
        serverName = findViewById(R.id.serverName)
        serverSelectorIcon = findViewById(R.id.serverSelectorIcon)
        titleText = findViewById(R.id.titleText)
    }

    // ─── ENTRY ANIMATIONS ────────────────────────────────────────

    private fun setupEntryAnimations() {
        val entries = listOf(
            serverCard to 0L,
            statsCard to 120L,
            connectButton to 240L
        )
        for ((view, delay) in entries) {
            view.alpha = 0f
            view.translationY = 60f
            view.postDelayed({
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(600)
                    .setInterpolator(DecelerateInterpolator())
                    .withLayer()
                    .start()
            }, delay)
        }
        serverSelectorIcon.postDelayed({
            serverSelectorIcon.animate()
                .rotation(360f)
                .setDuration(800)
                .setInterpolator(OvershootInterpolator())
                .withLayer()
                .start()
        }, 400)
    }

    // ─── PULSE ANIMATION ─────────────────────────────────────────

    private fun setupPulseAnimation() {
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.12f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val value = anim.animatedValue as Float
                statusPulse.scaleX = value
                statusPulse.scaleY = value
                statusGlowOverlay.scaleX = 1.1f + (value - 1f) * 0.8f
                statusGlowOverlay.scaleY = 1.1f + (value - 1f) * 0.8f
                statusGlowOverlay.alpha = 0.3f + (value - 1f) * 2.0f
            }
            start()
        }

        statusIndicator.postDelayed({
            statusIndicator.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(400)
                .setInterpolator(OvershootInterpolator())
                .withLayer()
                .start()
        }, 200)
    }

    // ─── GLOW RINGS ROTATION ─────────────────────────────────────

    private fun setupGlowAnimation() {
        glowRotateAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 8000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val deg = anim.animatedValue as Float
                glowRing1.rotation = deg
                glowRing2.rotation = -deg * 0.7f
            }
            start()
        }
    }

    // ─── CLICK LISTENERS ─────────────────────────────────────────

    private fun setupClickListeners() {
        connectButton.setOnClickListener {
            if (isConnecting) return@setOnClickListener
            if (isConnected) disconnectVpn() else connectVpn()
        }
        serverSelectorIcon.setOnClickListener { showServerSheet() }
        serverCard.setOnClickListener { showServerSheet() }
        serverName.setOnClickListener { showServerSheet() }
    }

    // ─── VPN CONNECT / DISCONNECT ────────────────────────────────

    private var mockConnectionCount = 0

    private fun connectVpn() {
        isConnecting = true
        animateButtonToConnecting()
        animateStatusToConnecting()

        connectionProgress.animate()
            .alpha(1f)
            .setDuration(300)
            .withLayer()
            .start()

        animateGlowRingEntry()

        mockConnectionCount++
        val intent = Intent(this, VpnProxyService::class.java).apply {
            putExtra(VpnProxyService.EXTRA_SERVER_ADDR, serverAddress)
            putExtra(VpnProxyService.EXTRA_SERVER_PORT, serverPort)
            putExtra(VpnProxyService.EXTRA_USERNAME, "")
            putExtra(VpnProxyService.EXTRA_PASSWORD, "")
        }

        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE)
        } else {
            onVpnPrepared()
        }
    }

    private fun onVpnPrepared() {
        val intent = Intent(this, VpnProxyService::class.java).apply {
            putExtra(VpnProxyService.EXTRA_SERVER_ADDR, serverAddress)
            putExtra(VpnProxyService.EXTRA_SERVER_PORT, serverPort)
            putExtra(VpnProxyService.EXTRA_USERNAME, "")
            putExtra(VpnProxyService.EXTRA_PASSWORD, "")
        }
        ContextCompat.startForegroundService(this, intent)

        statusIndicator.postDelayed({
            if (isConnecting) {
                isConnecting = false
                isConnected = true
                animateButtonToConnected()
                animateStatusToConnected()
                connectionProgress.animate()
                    .alpha(0f)
                    .setDuration(400)
                    .withLayer()
                    .start()
            }
        }, 1800)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            onVpnPrepared()
        } else if (requestCode == VPN_REQUEST_CODE) {
            isConnecting = false
            animateButtonToDisconnected()
            animateStatusToDisconnected()
            connectionProgress.animate()
                .alpha(0f)
                .setDuration(200)
                .withLayer()
                .start()
        }
    }

    private fun disconnectVpn() {
        isConnecting = true
        animateStatusToConnecting()

        connectionProgress.animate()
            .alpha(1f)
            .setDuration(300)
            .withLayer()
            .start()

        statusIndicator.postDelayed({
            isConnecting = false
            isConnected = false
            animateButtonToDisconnected()
            animateStatusToDisconnected()
            connectionProgress.animate()
                .alpha(0f)
                .setDuration(400)
                .withLayer()
                .start()
        }, 600)
    }

    // ─── BUTTON ANIMATIONS ───────────────────────────────────────

    private fun animateButtonToConnecting() {
        ValueAnimator.ofObject(ArgbEvaluator(), disconnectedColor, connectingColor).apply {
            duration = 400
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                connectButton.backgroundTintList = ColorStateList.valueOf(anim.animatedValue as Int)
            }
            start()
        }

        connectButton.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withLayer()
            .withEndAction {
                connectButton.text = "连接中..."
                connectButton.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(300)
                    .setInterpolator(OvershootInterpolator())
                    .withLayer()
                    .start()
            }
            .start()
    }

    private fun animateButtonToConnected() {
        ValueAnimator.ofObject(ArgbEvaluator(), connectingColor, connectedColor).apply {
            duration = 500
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                connectButton.backgroundTintList = ColorStateList.valueOf(anim.animatedValue as Int)
            }
            start()
        }

        connectButton.animate()
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withLayer()
            .withEndAction {
                connectButton.text = "已连接"
                connectButton.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(400)
                    .setInterpolator(BounceInterpolator())
                    .withLayer()
                    .start()
            }
            .start()
    }

    private fun animateButtonToDisconnected() {
        val targetColor = if (isConnected) connectedColor else disconnectedColor
        ValueAnimator.ofObject(ArgbEvaluator(), connectingColor, targetColor).apply {
            duration = 400
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                connectButton.backgroundTintList = ColorStateList.valueOf(anim.animatedValue as Int)
            }
            start()
        }

        connectButton.animate()
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(150)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withLayer()
            .withEndAction {
                connectButton.text = if (isConnected) "已连接" else "连接"
                connectButton.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(300)
                    .setInterpolator(OvershootInterpolator())
                    .withLayer()
                    .start()
            }
            .start()
    }

    // ─── STATUS ANIMATIONS ───────────────────────────────────────

    private fun animateStatusToConnecting() {
        statusText.text = "连接中"
        statusText.setTextColor(connectingColor)
        statusSubText.text = "请稍候..."

        statusPulse.animate()
            .scaleX(1.15f)
            .scaleY(1.15f)
            .setDuration(300)
            .withLayer()
            .start()

        animateStatusColor(disconnectedColor, connectingColor)
    }

    private fun animateStatusToConnected() {
        statusText.text = "已连接"
        statusText.setTextColor(connectedColor)
        statusSubText.text = "您的连接已受到保护"

        animateStatusColor(connectingColor, connectedColor)

        statusIndicator.postDelayed({
            statusIndicator.animate()
                .scaleX(1.08f)
                .scaleY(1.08f)
                .setDuration(250)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withLayer()
                .withEndAction {
                    statusIndicator.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(350)
                        .setInterpolator(BounceInterpolator())
                        .withLayer()
                        .start()
                }
                .start()
        }, 100)

        statusIcon.animate()
            .rotation(360f)
            .setDuration(600)
            .setInterpolator(OvershootInterpolator())
            .withLayer()
            .start()
    }

    private fun animateStatusToDisconnected() {
        statusText.text = "未连接"
        statusText.setTextColor(disconnectedColor)
        statusSubText.text = "点击连接以保护您的连接"

        val startColor = if (isConnected) connectedColor else connectingColor
        animateStatusColor(startColor, disconnectedColor)

        statusPulse.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300)
            .withLayer()
            .start()
    }

    private fun animateStatusColor(fromColor: Int, toColor: Int) {
        ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor).apply {
            duration = 400
            addUpdateListener { anim ->
                statusPulse.setColorFilter(anim.animatedValue as Int, android.graphics.PorterDuff.Mode.SRC_IN)
            }
            start()
        }
    }

    // ─── GLOW RING ENTRY ─────────────────────────────────────────

    private fun animateGlowRingEntry() {
        listOf(glowRing1, glowRing2).forEach { ring ->
            ring.alpha = 0f
            ring.scaleX = 0.5f
            ring.scaleY = 0.5f
            ring.animate()
                .alpha(0.3f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(600)
                .setInterpolator(OvershootInterpolator())
                .withLayer()
                .start()
        }
    }

    // ─── TRAFFIC SIMULATION ──────────────────────────────────────

    private var trafficRunning = false
    private var simulatedUpload = 0.0
    private var simulatedDownload = 0.0

    private fun simulateTraffic() {
        trafficRunning = true
        rootLayout.postDelayed(object : Runnable {
            override fun run() {
                if (!trafficRunning) return
                if (isConnected) {
                    simulatedUpload += Random.nextDouble() * 5000 + 100
                    simulatedDownload += Random.nextDouble() * 15000 + 500
                    currentUploadSpeed = Random.nextDouble() * 80000 + 2000
                    currentDownloadSpeed = Random.nextDouble() * 250000 + 10000
                } else {
                    currentUploadSpeed *= 0.85
                    currentDownloadSpeed *= 0.85
                    if (currentUploadSpeed < 1) currentUploadSpeed = 0.0
                    if (currentDownloadSpeed < 1) currentDownloadSpeed = 0.0
                }
                animateSpeedText(uploadSpeed, currentUploadSpeed)
                animateSpeedText(downloadSpeed, currentDownloadSpeed)
                animateTotalText(uploadTotal, simulatedUpload)
                animateTotalText(downloadTotal, simulatedDownload)
                rootLayout.postDelayed(this, 1200)
            }
        }, 1200)
    }

    private fun animateSpeedText(view: TextView, targetSpeed: Double) {
        speedAnimator?.cancel()
        val currentVal = parseSpeed(view.text.toString())
        speedAnimator = ValueAnimator.ofFloat(currentVal.toFloat(), targetSpeed.toFloat()).apply {
            duration = 800
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val v = anim.animatedValue as Float
                view.text = formatSpeed(v.toDouble())
            }
            start()
        }
    }

    private fun animateTotalText(view: TextView, totalBytes: Double) {
        view.text = "总计: ${formatBytes(totalBytes)}"
    }

    private fun parseSpeed(text: String): Double {
        return try {
            val parts = text.split(" ")
            val num = parts[0].replace(",", "").toDouble()
            val unit = parts.getOrElse(1) { "B/s" }
            when {
                unit.contains("K") -> num * 1024
                unit.contains("M") -> num * 1024 * 1024
                else -> num
            }
        } catch (_: Exception) { 0.0 }
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1_000_000 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1_000_000)
            bytesPerSec >= 1_000 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1_000)
            else -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
        }
    }

    private fun formatBytes(bytes: Double): String {
        return when {
            bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000)
            bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000)
            bytes >= 1_000 -> String.format(Locale.US, "%.1f KB", bytes / 1_000)
            else -> String.format(Locale.US, "%.0f B", bytes)
        }
    }

    // ─── SERVER SHEET ────────────────────────────────────────────

    private fun showServerSheet() {
        val servers = listOf(
            "🇯🇵 日本 - Tokyo #1" to "jp-tokyo-01.vpnservice.com",
            "🇺🇸 美国 - Los Angeles #2" to "us-la-02.vpnservice.com",
            "🇭🇰 香港 - Hong Kong #3" to "hk-hkg-03.vpnservice.com",
            "🇸🇬 新加坡 - Singapore #4" to "sg-sin-04.vpnservice.com",
            "🇰🇷 韩国 - Seoul #5" to "kr-sel-05.vpnservice.com"
        )

        val sheet = ServerBottomSheet { name, host ->
            serverName.text = name
            selectedServer = host
            animateServerSelection()
        }
        sheet.show(supportFragmentManager, "server_sheet")
    }

    private fun animateServerSelection() {
        serverCard.animate()
            .scaleX(0.98f)
            .scaleY(0.98f)
            .setDuration(100)
            .withLayer()
            .withEndAction {
                serverCard.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(300)
                    .setInterpolator(OvershootInterpolator())
                    .withLayer()
                    .start()
            }
            .start()

        serverSelectorIcon.animate()
            .rotationBy(180f)
            .setDuration(500)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withLayer()
            .start()
    }

    // ─── SPRING ANIMATIONS (Physics-based) ───────────────────────

    private fun springScale(view: View) {
        val springAnim = SpringAnimation(view, DynamicAnimation.SCALE_X).apply {
            spring = SpringForce().apply {
                stiffness = SpringForce.STIFFNESS_MEDIUM
                dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            }
        }
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        springAnim.animateToFinalPosition(1f)
        SpringAnimation(view, DynamicAnimation.SCALE_Y).apply {
            spring = SpringForce().apply {
                stiffness = SpringForce.STIFFNESS_MEDIUM
                dampingRatio = SpringForce.DAMPING_RATIO_LOW_BOUNCY
            }
            setStartValue(0.92f)
        }.animateToFinalPosition(1f)
    }

    // ─── LIFECYCLE ────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        trafficRunning = false
        if (::pulseAnimator.isInitialized) pulseAnimator.cancel()
        if (::glowRotateAnimator.isInitialized) glowRotateAnimator.cancel()
        speedAnimator?.cancel()
    }

    companion object {
        private const val VPN_REQUEST_CODE = 1000
    }
}
