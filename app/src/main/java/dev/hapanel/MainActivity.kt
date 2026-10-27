package dev.hapanel

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.http.SslError
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import java.net.HttpURLConnection
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    lateinit var web: WebView
    private lateinit var overlay: View
    private lateinit var clockLayer: LinearLayout
    private lateinit var popupLayer: FrameLayout
    private lateinit var popupWeb: WebView
    private lateinit var banner: TextView
    private lateinit var clockTemps: TextView
    private lateinit var clockInfoV: TextView
    private lateinit var popupTitle: TextView
    private lateinit var aurora: AuroraView
    private lateinit var talkBtn: TextView
    private var taps = 0
    private var firstTap = 0L
    private var retryPending = false
    private var recheckPending = false
    private var urlIndex = 0
    private val popupClose = Runnable { closePopup() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun configureWeb(w: WebView) {
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        // Mikrofon dla kart WebRTC (rozmowa przez domofon). Strona musi być po HTTPS.
        w.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(this)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        val bg = Color.parseColor("#090D12")
        val root = FrameLayout(this)
        root.setBackgroundColor(bg)

        // --- główny dashboard ---
        web = WebView(this)
        web.setBackgroundColor(bg)
        configureWeb(web)
        applyWebSettings()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView?, url: String?) {
                if (url != null && url.startsWith("http")) {
                    Controller.currentUrl = url
                    Controller.lastLoad = System.currentTimeMillis()
                    if (v != null) inject(v)
                    updateBanner()
                    if (urlIndex > 0) scheduleRecheck()
                }
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (!r.isForMainFrame) return
                val c = candidates()
                v.loadDataWithBaseURL(null, errorHtml(c.getOrNull(urlIndex) ?: ""), "text/html", "utf-8", null)
                if (!retryPending) {
                    retryPending = true
                    Controller.ui.postDelayed({
                        retryPending = false
                        val cc = candidates()
                        if (cc.size > 1) urlIndex = (urlIndex + 1) % cc.size
                        loadCandidate()
                    }, 10_000)
                }
            }
            override fun onReceivedSslError(v: WebView, h: SslErrorHandler, e: SslError) {
                if (Prefs.ignoreSsl) h.proceed() else h.cancel()
            }
            override fun onRenderProcessGone(v: WebView?, d: RenderProcessGoneDetail?): Boolean {
                if (v != null) { (v.parent as? ViewGroup)?.removeView(v); v.destroy() }
                Controller.ui.post { recreate() }
                return true
            }
        }
        root.addView(web, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // --- oznaczenie adresu awaryjnego ---
        banner = TextView(this)
        banner.textSize = 13f
        banner.setTextColor(Color.parseColor("#FFC46B"))
        banner.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        banner.setPadding(dp(12), dp(6), dp(12), dp(6))
        banner.background = pillBg(Color.argb(46, 255, 196, 107), Color.parseColor("#FFC46B"))
        banner.visibility = View.GONE
        val bl = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END)
        bl.setMargins(0, dp(10), dp(10), 0)
        root.addView(banner, bl)

        // --- zegar nocny ---
        clockLayer = LinearLayout(this)
        clockLayer.orientation = LinearLayout.VERTICAL
        clockLayer.gravity = Gravity.CENTER
        clockLayer.setBackgroundColor(Color.parseColor("#05080B"))
        clockLayer.visibility = View.GONE
        clockLayer.isClickable = true
        clockLayer.setOnClickListener { Controller.wake() }
        val timeV = TextClock(this)
        timeV.format24Hour = "HH:mm"
        timeV.format12Hour = "HH:mm"
        timeV.textSize = 140f
        timeV.setTextColor(Color.parseColor("#4FB8A0"))
        timeV.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
        val dateV = TextClock(this)
        dateV.format24Hour = "EEEE, d MMMM"
        dateV.format12Hour = "EEEE, d MMMM"
        dateV.textSize = 26f
        dateV.setTextColor(Color.parseColor("#5F7083"))
        clockLayer.addView(timeV)
        clockLayer.addView(dateV)
        // temperatury i krótka informacja z HA (np. „Jutro: odpady”) – wysyłane przez integrację (/clock)
        clockTemps = TextView(this)
        clockTemps.textSize = 30f
        clockTemps.setTextColor(Color.parseColor("#6FA8C7"))
        clockTemps.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        clockTemps.gravity = Gravity.CENTER
        clockTemps.setPadding(0, dp(22), 0, 0)
        clockTemps.visibility = View.GONE
        clockInfoV = TextView(this)
        clockInfoV.textSize = 20f
        clockInfoV.setTextColor(Color.parseColor("#5F7083"))
        clockInfoV.gravity = Gravity.CENTER
        clockInfoV.setPadding(dp(24), dp(10), dp(24), 0)
        clockInfoV.visibility = View.GONE
        clockLayer.addView(clockTemps)
        clockLayer.addView(clockInfoV)
        root.addView(clockLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // --- okno podglądu (popup) ---
        popupLayer = FrameLayout(this)
        popupLayer.setBackgroundColor(Color.BLACK)
        popupLayer.visibility = View.GONE
        popupLayer.isClickable = true
        popupWeb = WebView(this)
        popupWeb.setBackgroundColor(Color.BLACK)
        configureWeb(popupWeb)
        popupWeb.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(v: WebView, h: SslErrorHandler, e: SslError) {
                if (Prefs.ignoreSsl) h.proceed() else h.cancel()
            }
        }
        popupLayer.addView(popupWeb, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val closeBtn = TextView(this)
        closeBtn.text = "Zamknij  ✕"
        closeBtn.textSize = 16f
        closeBtn.setTextColor(Color.parseColor("#FF6B6B"))
        closeBtn.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        closeBtn.setPadding(dp(18), dp(10), dp(18), dp(10))
        closeBtn.background = pillBg(Color.argb(70, 20, 10, 12), Color.parseColor("#FF6B6B"))
        closeBtn.setOnClickListener { closePopup() }
        val cl = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END)
        cl.setMargins(0, dp(14), dp(14), 0)
        popupLayer.addView(closeBtn, cl)
        popupTitle = TextView(this)
        popupTitle.textSize = 18f
        popupTitle.setTextColor(Color.parseColor("#E8F1F8"))
        popupTitle.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        popupTitle.setPadding(dp(18), dp(10), dp(18), dp(10))
        popupTitle.background = pillBg(Color.argb(150, 9, 13, 18), Color.parseColor("#7CF3D4"))
        popupTitle.visibility = View.GONE
        val tl = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START)
        tl.setMargins(dp(14), dp(14), 0, 0)
        popupLayer.addView(popupTitle, tl)
        root.addView(popupLayer, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // --- Aurora: zorza podczas rozmowy (dotknięcie kończy rozmowę) ---
        aurora = AuroraView(this)
        aurora.visibility = View.GONE
        aurora.isClickable = true
        aurora.setOnClickListener { Voice.stop() }
        root.addView(aurora, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // --- przycisk „Porozmawiaj z Aurorą” ---
        talkBtn = TextView(this)
        talkBtn.text = "✦"
        talkBtn.textSize = 26f
        talkBtn.gravity = Gravity.CENTER
        talkBtn.setTextColor(Color.parseColor("#7CF3D4"))
        val tb = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.argb(200, 20, 60, 70), Color.argb(200, 60, 30, 90)))
        tb.shape = GradientDrawable.OVAL
        tb.setStroke(dp(1), Color.argb(160, 124, 243, 212))
        talkBtn.background = tb
        talkBtn.elevation = dp(6).toFloat()
        talkBtn.contentDescription = "Porozmawiaj z Aurorą"
        talkBtn.setOnClickListener { Voice.start(this, source = "button") }
        val tl2 = FrameLayout.LayoutParams(dp(64), dp(64), Gravity.BOTTOM or Gravity.END)
        tl2.setMargins(0, 0, dp(18), dp(18))
        root.addView(talkBtn, tl2)

        // --- czarna nakładka (uśpienie) ---
        overlay = View(this)
        overlay.setBackgroundColor(Color.BLACK)
        overlay.visibility = View.GONE
        overlay.isClickable = true
        overlay.setOnClickListener { Controller.wake() }
        root.addView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        Controller.activity = this
        updateClockInfo()
        loadCandidate()

        startForegroundService(Intent(this, KioskService::class.java))
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.RECORD_AUDIO
        if (Prefs.motionEnabled && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.CAMERA
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    private fun pillBg(fill: Int, stroke: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(fill)
        d.cornerRadius = dp(14).toFloat()
        d.setStroke(dp(1), stroke)
        return d
    }

    // ---------- adresy: główny + awaryjne ----------
    private fun candidates(): List<String> {
        val l = mutableListOf<String>()
        if (Prefs.dashboardUrl.isNotBlank()) l.add(Prefs.dashboardUrl)
        l.addAll(Prefs.fallbackList())
        return l
    }

    fun loadCandidate() {
        val c = candidates()
        if (c.isEmpty()) {
            web.loadDataWithBaseURL(null, waitHtml(), "text/html", "utf-8", null)
            return
        }
        if (urlIndex >= c.size) urlIndex = 0
        web.loadUrl(c[urlIndex])
    }

    fun resetAndLoad() { urlIndex = 0; banner.visibility = View.GONE; loadCandidate() }

    private fun updateBanner() {
        Controller.fallback = urlIndex > 0
        if (urlIndex > 0) {
            val secure = Controller.currentUrl.startsWith("https://")
            banner.text = if (secure) "Adres awaryjny" else "Adres awaryjny · bez mikrofonu"
            banner.visibility = View.VISIBLE
        } else banner.visibility = View.GONE
    }

    /** Na adresie awaryjnym co 5 minut sprawdza po cichu, czy główny już działa. */
    private fun scheduleRecheck() {
        if (recheckPending) return
        recheckPending = true
        Controller.ui.postDelayed({ recheckPending = false; recheckPrimary() }, 5 * 60_000L)
    }

    private fun recheckPrimary() {
        val primary = Prefs.dashboardUrl
        if (urlIndex == 0 || primary.isBlank()) return
        Thread {
            val ok = try {
                val c = URL(primary).openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 4000
                val code = c.responseCode
                c.disconnect()
                code > 0
            } catch (_: Exception) { false }
            Controller.ui.post { if (ok) { urlIndex = 0; loadCandidate() } else scheduleRecheck() }
        }.start()
    }

    private fun errorHtml(tried: String): String {
        val host = try { URL(tried).host } catch (_: Exception) { tried }
        return """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><body style="margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;background:linear-gradient(135deg,#0E1A22,#090D12);color:#E8F1F8;font-family:sans-serif"><div style="width:14px;height:14px;border-radius:50%;background:#FFC46B;box-shadow:0 0 24px #FFC46B;margin-bottom:28px"></div><div style="font-size:30px;font-weight:300">Brak połączenia z Home Assistant</div><div style="margin-top:12px;color:#8A9BAD;font-size:17px">Próbuję: $host · ponawiam co 10 sekund…</div></body>"""
    }

    @Suppress("DEPRECATION")
    private fun waitHtml(): String {
        val ip = try {
            val a = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).connectionInfo.ipAddress
            "${a and 0xff}.${(a shr 8) and 0xff}.${(a shr 16) and 0xff}.${(a shr 24) and 0xff}"
        } catch (_: Exception) { "?" }
        val code = Prefs.pairCode.let { it.substring(0, 3) + " " + it.substring(3) }
        return """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><body style="margin:0;min-height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;background:linear-gradient(135deg,#0E1A22,#090D12);color:#E8F1F8;font-family:sans-serif;padding:24px;box-sizing:border-box"><div style="width:14px;height:14px;border-radius:50%;background:#7CF3D4;box-shadow:0 0 24px #7CF3D4;margin-bottom:28px"></div><div style="font-size:30px;font-weight:300">Czekam na konfigurację z Home Assistant</div><div style="margin-top:16px;color:#8A9BAD;font-size:17px;max-width:640px;line-height:1.5">W Home Assistant wejdź w Ustawienia → Urządzenia i usługi. Pojawi się wykryty tablet „HA Panel”. Kliknij Skonfiguruj i wpisz kod parowania:</div><div style="margin-top:20px;font-size:54px;letter-spacing:8px;color:#7CF3D4;font-weight:300">$code</div><div style="margin-top:22px;padding:8px 16px;border-radius:999px;background:rgba(138,155,173,.15);color:#8A9BAD;font-size:15px">Adres tabletu: $ip : ${Prefs.port}</div><div style="margin-top:28px;color:#5F7083;font-size:14px">Ręczna konfiguracja: 5 szybkich dotknięć w lewy górny róg ekranu</div></body>"""
    }

    // ---------- wygląd: zoom, CSS, JS ----------
    fun applyWebSettings() { web.settings.textZoom = Prefs.zoom.coerceIn(50, 250) }

    fun injectNow() { inject(web) }

    private fun inject(w: WebView) {
        val css = Prefs.injectCss
        val js = Prefs.injectJs
        val sb = StringBuilder("(function(){var s=document.getElementById('hapanel-css');")
        if (css.isBlank()) sb.append("if(s)s.remove();")
        else sb.append("if(!s){s=document.createElement('style');s.id='hapanel-css';(document.head||document.documentElement).appendChild(s);}s.textContent=")
            .append(JSONObject.quote(css)).append(";")
        sb.append("})();")
        w.evaluateJavascript(sb.toString(), null)
        if (js.isNotBlank()) w.evaluateJavascript("(function(){try{" + js + "}catch(e){}})();", null)
    }

    /** Zrzut ekranu tabletu (JPEG) na żądanie, dla encji camera w HA. Bez strumienia. */
    fun captureJpeg(quality: Int, scale: Float): ByteArray? {
        val holder = arrayOfNulls<ByteArray>(1)
        val latch = CountDownLatch(1)
        Controller.ui.post {
            try {
                val v = window.decorView
                val w = v.width
                val h = v.height
                if (w <= 0 || h <= 0) { latch.countDown(); return@post }
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                PixelCopy.request(window, bmp, PixelCopy.OnPixelCopyFinishedListener { res ->
                    try {
                        if (res == PixelCopy.SUCCESS) {
                            val small = Bitmap.createScaledBitmap(bmp, (w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), true)
                            val out = ByteArrayOutputStream()
                            small.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 95), out)
                            holder[0] = out.toByteArray()
                        }
                    } catch (_: Exception) {}
                    latch.countDown()
                }, Controller.ui)
            } catch (_: Exception) { latch.countDown() }
        }
        latch.await(4, TimeUnit.SECONDS)
        return holder[0]
    }

    // ---------- okno podglądu ----------
    private fun resolveUrl(u: String): String {
        if (u.startsWith("http")) return u
        val base = candidates().getOrNull(urlIndex) ?: Prefs.dashboardUrl
        return try {
            val b = URL(base)
            b.protocol + "://" + b.authority + (if (u.startsWith("/")) u else "/$u")
        } catch (_: Exception) { u }
    }

    fun showPopup(rawUrl: String, seconds: Int, title: String = "") {
        Controller.ui.removeCallbacks(popupClose)
        popupTitle.text = title
        popupTitle.visibility = if (title.isBlank()) View.GONE else View.VISIBLE
        popupWeb.loadUrl(resolveUrl(rawUrl))
        popupLayer.visibility = View.VISIBLE
        if (seconds > 0) Controller.ui.postDelayed(popupClose, seconds * 1000L)
    }

    fun closePopup() {
        Controller.ui.removeCallbacks(popupClose)
        if (popupLayer.visibility == View.VISIBLE) {
            popupLayer.visibility = View.GONE
            popupWeb.loadUrl("about:blank")   // zatrzymuje strumień kamery
        }
    }

    /** Temperatury i informacja na zegarze nocnym (z HA). */
    fun updateClockInfo() {
        val parts = mutableListOf<String>()
        if (Controller.clockOut.isNotBlank()) parts += "na zewnątrz ${Controller.clockOut}°"
        if (Controller.clockIn.isNotBlank()) parts += "w domu ${Controller.clockIn}°"
        clockTemps.text = parts.joinToString("   ·   ")
        clockTemps.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        clockInfoV.text = Controller.clockInfo
        clockInfoV.visibility = if (Controller.clockInfo.isBlank()) View.GONE else View.VISIBLE
    }

    // ---------- ustawienia za PIN-em ----------
    private fun openSettings() {
        try { stopLockTask() } catch (_: Exception) {}
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun requestSettings() {
        if (!Pin.isSet()) { openSettings(); return }
        val lock = Pin.lockRemainingSec()
        if (lock > 0) { Toast.makeText(this, "Zbyt wiele prób. Spróbuj za $lock s", Toast.LENGTH_SHORT).show(); return }
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.hint = "PIN"
        val holder = FrameLayout(this)
        holder.setPadding(dp(24), dp(8), dp(24), 0)
        holder.addView(input, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        AlertDialog.Builder(this)
            .setTitle("Podaj PIN")
            .setView(holder)
            .setPositiveButton("OK") { _, _ ->
                if (Pin.verify(input.text.toString())) openSettings()
                else Toast.makeText(this, "Zły PIN", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    // ---------- ekran ----------
    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        stopService(Intent(this, KioskService::class.java))
        startForegroundService(Intent(this, KioskService::class.java))
    }

    fun applyScreen() {
        val lp = window.attributes
        lp.screenBrightness = when {
            Controller.sleeping -> 0.01f
            Controller.night && Prefs.nightEnabled -> (Prefs.nightBrightness / 255f).coerceIn(0.01f, 1f)
            else -> Controller.brightness
        }
        window.attributes = lp
        overlay.visibility = if (Controller.sleeping) View.VISIBLE else View.GONE
        clockLayer.visibility = if (Controller.clock) View.VISIBLE else View.GONE
        updateVoice()
    }

    /** Zorza i przycisk rozmowy zależnie od stanu Aurory. */
    fun updateVoice() {
        if (!::aurora.isInitialized) return
        val talking = Voice.state != Voice.State.IDLE
        if (talking && aurora.visibility != View.VISIBLE) { aurora.visibility = View.VISIBLE; aurora.invalidate() }
        else if (!talking && aurora.visibility == View.VISIBLE) aurora.visibility = View.GONE
        talkBtn.visibility = if (Prefs.talkButton && Prefs.voiceEnabled && !talking && !Controller.sleeping && !Controller.clock)
            View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        Controller.activity = this
        applyScreen()
        if (Prefs.lockTask) try { startLockTask() } catch (_: Exception) {}
    }

    override fun onDestroy() { if (Controller.activity === this) Controller.activity = null; super.onDestroy() }

    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    /** 5 szybkich dotknięć w lewym górnym rogu = ustawienia (z PIN-em, jeśli ustawiony). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        Controller.touch()
        if (ev.action == MotionEvent.ACTION_DOWN) Events.touch()
        if (ev.action == MotionEvent.ACTION_DOWN && ev.x < 150 && ev.y < 150) {
            val now = SystemClock.elapsedRealtime()
            if (now - firstTap > 4000) { taps = 0; firstTap = now }
            if (++taps >= 5) { taps = 0; requestSettings() }
        }
        return super.dispatchTouchEvent(ev)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (Voice.state != Voice.State.IDLE) Voice.stop()
        else if (popupLayer.visibility == View.VISIBLE) closePopup()
        else if (web.canGoBack()) web.goBack()
    }
}
