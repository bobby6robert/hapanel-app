package dev.hapanel

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ln

class KioskService : Service() {
    private var server: HttpServer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var motion: MotionDetector? = null
    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null
    private val exec = Executors.newSingleThreadScheduledExecutor()
    private lateinit var sm: SensorManager
    private var lastHomed = 0L
    private var lastNightReloadDay = -1
    @Volatile private var lux = -1f
    @Volatile private var battery = -1
    @Volatile private var charging = false
    @Volatile private var motionActive = false
    @Volatile private var playing = false
    @Volatile private var batteryTemp = -100.0
    @Volatile private var batteryHealth = "unknown"
    private var lowSent = false
    @Volatile private var proxPresent = false
    @Volatile private var proxNear = false

    private val batteryRcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { readBattery(i); checkLowBattery(); push() }
    }
    private val lightListener = object : SensorEventListener {
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values[0]
            lux = if (lux < 0) v else lux * 0.8f + v * 0.2f
            if (Prefs.autoBrightness && !Controller.sleeping) {
                val f = (0.04f + 0.96f * (ln(1 + lux) / ln(501f))).coerceIn(0.04f, 1f)
                if (abs(f - Controller.brightness) > 0.04f) Controller.setBrightness(f)
            }
        }
    }

    /** Czujnik zbliżeniowy (jeśli tablet go ma): ręka przy ekranie wybudza panel. */
    private val proxListener = object : SensorEventListener {
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        override fun onSensorChanged(e: SensorEvent) {
            val near = e.values[0] < (e.sensor?.maximumRange ?: 5f)
            if (near == proxNear) return
            proxNear = near
            if (near) {
                Controller.touch()
                if (Prefs.wakeOnProximity) Controller.wake()
            }
            Events.send("proximity", "state" to near)
            push()
        }
    }

    @Suppress("DEPRECATION")
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("hapanel", "HA Panel", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, Notification.Builder(this, "hapanel")
            .setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle("HA Panel działa").build())

        // Tablet nie zasypia w sieci ani w procesorze (dzwonek w nocy musi dojść).
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hapanel:svc").apply { setReferenceCounted(false); acquire() }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hapanel:wifi").apply { setReferenceCounted(false); acquire() }

        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val pl = Locale("pl", "PL")
                val ok = (tts?.isLanguageAvailable(pl) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE
                tts?.language = if (ok) pl else Locale.getDefault()
                ttsReady = true
            }
        }
        registerReceiver(batteryRcv, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { readBattery(it) }

        sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(lightListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            proxPresent = true
            sm.registerListener(proxListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }

        server = HttpServer(Prefs.port, ::route).also { it.start() }

        restartMotion()
        registerNsd()

        Controller.onChange = { push(); Events.send("screen", "sleeping" to Controller.sleeping, "clock" to Controller.clock) }
        exec.scheduleAtFixedRate({ try { doPush() } catch (_: Exception) {} }, 5, 30, TimeUnit.SECONDS)
        exec.scheduleAtFixedRate({ try { idleTick() } catch (_: Exception) {} }, 5, 5, TimeUnit.SECONDS)
        exec.schedule({ Events.send("start", "version" to appVersion()) }, 5, TimeUnit.SECONDS)
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY
    override fun onBind(i: Intent?): IBinder? = null

    override fun onDestroy() {
        Controller.onChange = null
        server?.stop(); motion?.stop(); tts?.shutdown(); exec.shutdownNow()
        try { nsdListener?.let { nsd?.unregisterService(it) } } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        try { unregisterReceiver(batteryRcv); sm.unregisterListener(lightListener); sm.unregisterListener(proxListener) } catch (_: Exception) {}
        try { wakeLock?.release(); wifiLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun readBattery(i: Intent) {
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (l >= 0) battery = l * 100 / sc
        charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val tr = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        batteryTemp = if (tr == Int.MIN_VALUE) -100.0 else tr / 10.0
        batteryHealth = healthText(i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN))
    }

    private fun healthText(h: Int): String = when (h) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        else -> "unknown"
    }

    /** Zdarzenie niskiej baterii: raz po spadku poniżej 15%, ponownie dopiero po wzroście powyżej 20% lub ładowaniu. */
    private fun checkLowBattery() {
        if (battery in 0..14 && !charging) {
            if (!lowSent) { lowSent = true; Events.send("low_battery", "level" to battery) }
        } else if (battery >= 20 || charging) lowSent = false
    }

    /** Usypianie po bezczynności, powrót do dashboardu, nocne przeładowanie WebView. */
    private fun idleTick() {
        val now = SystemClock.elapsedRealtime()
        val idle = now - Controller.lastActivity
        val s = Prefs.sleepAfterSec
        if (s > 0 && !Controller.sleeping && idle > s * 1000L) Controller.sleep()
        // oszczędzanie: kamera ruchu stoi, gdy ktoś właśnie używa ekranu (dotyk w ciągu 30 s)
        motion?.let { if (!Controller.sleeping && !Controller.clock && idle < 30_000) it.pause() else it.resume() }
        val h = Prefs.homeAfterSec
        if (h > 0 && idle > h * 1000L && Controller.lastActivity > lastHomed && Prefs.dashboardUrl.isNotBlank()) {
            lastHomed = now
            Controller.loadDashboard()
        }
        // tryb nocny: zegar po bezczynności, w dzień zawsze dashboard
        val night = Prefs.nightEnabled && inNightWindow()
        if (night != Controller.night) {
            Controller.night = night
            Controller.ui.post { Controller.activity?.applyScreen() }
        }
        if (night) {
            if (!Controller.clock && !Controller.sleeping && idle > Prefs.nightWakeSec * 1000L) Controller.showClock()
        } else if (Controller.clock) Controller.hideClock()
        val c = Calendar.getInstance()
        val day = c.get(Calendar.DAY_OF_YEAR)
        if (c.get(Calendar.HOUR_OF_DAY) == 3 && c.get(Calendar.MINUTE) >= 30 && day != lastNightReloadDay) {
            lastNightReloadDay = day
            Controller.reload()
        }
    }

    private fun parseMin(s: String, def: Int): Int = try {
        val parts = s.trim().split(":")
        parts[0].toInt() * 60 + parts[1].toInt()
    } catch (_: Exception) { def }

    private fun inNightWindow(): Boolean {
        val s = parseMin(Prefs.nightStart, 22 * 60)
        val e = parseMin(Prefs.nightEnd, 6 * 60 + 30)
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (s <= e) now in s until e else (now >= s || now < e)
    }

    private fun handle(path: String, p: Map<String, String>): Pair<Int, String> {
        // /info jest bez tokena: pozwala integracji HA rozpoznać tablet i sprawdzić, czy wymaga tokena
        if (path == "/info") return 200 to JSONObject()
            .put("id", deviceId()).put("name", Prefs.deviceName)
            .put("version", appVersion()).put("auth", Prefs.apiToken.isNotEmpty())
            .put("paired", Prefs.apiToken.isNotEmpty()).put("pin", Pin.isSet()).toString()
        if (Prefs.apiToken.isNotEmpty() && p["token"] != Prefs.apiToken) return 401 to """{"error":"token"}"""
        when (path) {
            "/load" -> p["url"]?.let { Controller.loadUrl(it) }
            "/reload" -> Controller.reload()
            "/restart" -> Controller.restartPanel()
            "/brightness" -> { val v = p["value"]?.toIntOrNull() ?: return 400 to """{"error":"value 0-255"}"""
                Prefs.autoBrightness = false; Controller.setBrightness(v / 255f) }
            "/auto" -> Prefs.autoBrightness = p["on"] != "0"
            "/sleep" -> Controller.sleep()
            "/wake" -> Controller.wake()
            "/tts" -> { if (p["wake"] == "1") Controller.wake()
                p["volume"]?.toIntOrNull()?.let { setVolume(it) }
                if (ttsReady) tts?.speak(p["text"] ?: "", TextToSpeech.QUEUE_FLUSH, null, "ha") }
            "/playsound" -> { val u = p["url"] ?: return 400 to """{"error":"url"}"""
                playSound(u, p["volume"]?.toIntOrNull()) }
            "/stopsound" -> Controller.ui.post { stopSoundNow(); push() }
            "/volume" -> { val v = p["value"]?.toIntOrNull() ?: return 400 to """{"error":"value 0-100"}"""
                setVolume(v) }
            "/toast" -> { if (p["wake"] == "1") Controller.wake(); val t = p["text"] ?: ""; Controller.ui.post { Toast.makeText(applicationContext, t, Toast.LENGTH_LONG).show() } }
            "/config" -> {   // zdalna konfiguracja z integracji HA
                // nieparowany tablet przyjmuje konfigurację tylko z kodem wyświetlonym na ekranie
                if (Prefs.apiToken.isEmpty() && p["code"] != Prefs.pairCode) return 403 to """{"error":"code"}"""
                val err = ConfigApply.apply(p)
                if (err != null) return 400 to """{"error":"$err"}"""
                restartMotion()
            }
            "/export" -> return 200 to ConfigApply.export().toString()
            "/update" -> {
                val u = p["url"] ?: return 400 to """{"error":"url"}"""
                if (!Updater.start(this, u)) return 400 to """{"error":"url"}"""
            }
            "/popup" -> { val u = p["url"] ?: return 400 to """{"error":"url"}"""
                if (p["wake"] != "0") Controller.wake()
                Controller.touch()
                Controller.popup(u, p["seconds"]?.toIntOrNull() ?: 30, p["title"] ?: "") }
            "/closepopup", "/popup_close" -> Controller.closePopup()
            "/clock" -> Controller.setClockData(p["out"], p["in"], p["info"])
            "/status" -> return 200 to statusJson()
            else -> return 404 to """{"error":"not found"}"""
        }
        return 200 to """{"ok":true}"""
    }

    private fun setVolume(v: Int) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v.coerceIn(0, 100) * max / 100, 0)
    }

    private fun playSound(url: String, vol: Int?) {
        Controller.ui.post {
            stopSoundNow()
            try {
                if (vol != null) setVolume(vol)
                val mp = MediaPlayer()
                mp.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                mp.setDataSource(url)
                mp.setOnPreparedListener { it.start(); playing = true; push() }
                mp.setOnCompletionListener { finishPlayer(it) }
                mp.setOnErrorListener { m, _, _ -> finishPlayer(m); true }
                player = mp
                mp.prepareAsync()
            } catch (_: Exception) { stopSoundNow() }
        }
    }

    private fun finishPlayer(mp: MediaPlayer) {
        try { mp.release() } catch (_: Exception) {}
        if (player === mp) { player = null; playing = false; push() }
    }

    private fun stopSoundNow() {
        player?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        player = null; playing = false
    }

    @Suppress("DEPRECATION")
    private fun statusJson(): String {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo
        val ip = info.ipAddress
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        return JSONObject()
            .put("battery", battery).put("charging", charging)
            .put("motion", motionActive).put("lux", lux.toDouble())
            .put("sleeping", Controller.sleeping)
            .put("brightness", if (Controller.brightness < 0) -1 else Math.round(Controller.brightness * 255))
            .put("auto", Prefs.autoBrightness)
            .put("volume", am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max)
            .put("playing", playing)
            .put("wifi_rssi", info.rssi)
            .put("ip", "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}")
            .put("version", ver)
            .put("url", Controller.currentUrl)
            .put("configured", Prefs.dashboardUrl.isNotBlank())
            .put("missing", missingPermissions())
            .put("paired", Prefs.apiToken.isNotEmpty())
            .put("pin", Pin.isSet())
            .put("night", Controller.night)
            .put("clock", Controller.clock)
            .put("battery_temp", batteryTemp)
            .put("battery_health", batteryHealth)
            .put("mem_free", memFreeMb())
            .put("uptime", Math.round(SystemClock.elapsedRealtime() / 360000.0) / 10.0)
            .put("last_load", Controller.lastLoad / 1000)
            .put("webview", webviewVersion())
            .put("webhook", Prefs.webhookUrl.isNotBlank())
            .put("night_enabled", Prefs.nightEnabled)
            .put("fallback", Controller.fallback)
            .apply { if (proxPresent) put("proximity", proxNear) }
            .toString()
    }

    private fun memFreeMb(): Long {
        val mi = ActivityManager.MemoryInfo()
        (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.availMem / 1048576
    }

    private fun webviewVersion(): String =
        try { WebView.getCurrentWebViewPackage()?.versionName ?: "?" } catch (_: Throwable) { "?" }

    /** Zrzut ekranu zwraca obraz, reszta to JSON. */
    private fun route(path: String, p: Map<String, String>): Response {
        if (path == "/screenshot") {
            if (Prefs.apiToken.isNotEmpty() && p["token"] != Prefs.apiToken)
                return Response(401, """{"error":"token"}""".toByteArray())
            val jpg = Controller.activity?.captureJpeg(p["quality"]?.toIntOrNull() ?: 70, (p["scale"]?.toFloatOrNull() ?: 0.5f).coerceIn(0.1f, 1f))
            return if (jpg != null) Response(200, jpg, "image/jpeg")
            else Response(503, """{"error":"no screenshot"}""".toByteArray())
        }
        val (code, body) = handle(path, p)
        return Response(code, body.toByteArray())
    }

    private fun appVersion(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }

    private fun deviceId(): String =
        "hapanel-" + (Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown")

    /** Uprawnienia, których nie da się nadać zdalnie – integracja pokazuje je jako czujnik diagnostyczny. */
    private fun missingPermissions(): String {
        val m = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) m += "mikrofon"
        if (Prefs.motionEnabled && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) m += "kamera"
        if (!Settings.canDrawOverlays(this)) m += "autostart"
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) m += "bateria"
        return m.joinToString(", ")
    }

    private fun restartMotion() {
        try { motion?.stop() } catch (_: Exception) {}
        motion = null
        if (Prefs.motionEnabled && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            motion = MotionDetector(this, Prefs.motionThreshold,
                onTrigger = { Controller.touch(); if (Prefs.wakeOnMotion) Controller.wake() },
                onState = { motionActive = it; push(); Events.send("motion", "state" to it) }).also {
                try { it.start() } catch (_: Exception) {}
            }
        }
    }

    /** Ogłasza tablet w sieci lokalnej (mDNS), żeby HA wykrył go sam. */
    private fun registerNsd() {
        try {
            val info = NsdServiceInfo()
            info.serviceName = "HAPanel-" + deviceId().takeLast(6)
            info.serviceType = "_hapanel._tcp."
            info.port = Prefs.port
            info.setAttribute("id", deviceId())
            info.setAttribute("ver", appVersion())
            val l = object : NsdManager.RegistrationListener {
                override fun onRegistrationFailed(s: NsdServiceInfo, e: Int) {}
                override fun onUnregistrationFailed(s: NsdServiceInfo, e: Int) {}
                override fun onServiceRegistered(s: NsdServiceInfo) {}
                override fun onServiceUnregistered(s: NsdServiceInfo) {}
            }
            val mgr = getSystemService(NSD_SERVICE) as NsdManager
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = mgr; nsdListener = l
        } catch (_: Exception) {}
    }

    // --- opcjonalny raport do HA przez REST API (gdy nie używasz integracji) ---
    private fun push() { try { exec.execute { try { doPush() } catch (_: Exception) {} } } catch (_: Exception) {} }

    private fun doPush() {
        if (Prefs.haUrl.isBlank() || Prefs.haToken.isBlank()) return
        val n = Prefs.deviceName.lowercase().replace(Regex("[^a-z0-9]+"), "_")
        val name = Prefs.deviceName
        if (battery >= 0) post("sensor.${n}_battery", battery.toString(),
            mapOf("unit_of_measurement" to "%", "device_class" to "battery", "friendly_name" to "$name bateria"))
        post("binary_sensor.${n}_charging", if (charging) "on" else "off",
            mapOf("device_class" to "battery_charging", "friendly_name" to "$name ładowanie"))
        post("binary_sensor.${n}_screen", if (Controller.sleeping) "off" else "on", mapOf("friendly_name" to "$name ekran"))
        if (Prefs.motionEnabled) post("binary_sensor.${n}_motion", if (motionActive) "on" else "off",
            mapOf("device_class" to "motion", "friendly_name" to "$name ruch"))
        if (lux >= 0) post("sensor.${n}_illuminance", lux.toInt().toString(),
            mapOf("unit_of_measurement" to "lx", "device_class" to "illuminance", "friendly_name" to "$name światło"))
    }

    private fun post(entity: String, state: String, attrs: Map<String, String>) {
        val c = URL(Prefs.haUrl.trimEnd('/') + "/api/states/$entity").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.connectTimeout = 5000; c.readTimeout = 5000; c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer ${Prefs.haToken}")
            c.setRequestProperty("Content-Type", "application/json")
            val body = JSONObject().put("state", state).put("attributes", JSONObject(attrs)).toString()
            c.outputStream.use { it.write(body.toByteArray()) }
            c.responseCode
        } finally { c.disconnect() }
    }
}
