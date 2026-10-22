package dev.hapanel

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) { if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("hapanel", 0) }
    private fun s(k: String, d: String = "") = sp.getString(k, d) ?: d
    private fun p(k: String, v: Any) = sp.edit().apply {
        when (v) { is String -> putString(k, v); is Int -> putInt(k, v); is Boolean -> putBoolean(k, v); is Long -> putLong(k, v) }
    }.apply()

    var dashboardUrl: String get() = s("dashboardUrl"); set(v) = p("dashboardUrl", v)
    var fallbackUrls: String get() = s("fallbackUrls"); set(v) = p("fallbackUrls", v)
    var webhookUrl: String get() = s("webhookUrl"); set(v) = p("webhookUrl", v)
    var injectCss: String get() = s("injectCss"); set(v) = p("injectCss", v)
    var injectJs: String get() = s("injectJs"); set(v) = p("injectJs", v)
    var zoom: Int get() = sp.getInt("zoom", 100); set(v) = p("zoom", v)
    var haUrl: String get() = s("haUrl"); set(v) = p("haUrl", v)
    var haToken: String get() = s("haToken"); set(v) = p("haToken", v)
    var deviceName: String get() = s("deviceName", "tablet"); set(v) = p("deviceName", v)
    var apiToken: String get() = s("apiToken"); set(v) = p("apiToken", v)
    var port: Int get() = sp.getInt("port", 8765); set(v) = p("port", v)
    var motionThreshold: Int get() = sp.getInt("motionThreshold", 5); set(v) = p("motionThreshold", v)
    var sleepAfterSec: Int get() = sp.getInt("sleepAfterSec", 0); set(v) = p("sleepAfterSec", v)
    var homeAfterSec: Int get() = sp.getInt("homeAfterSec", 0); set(v) = p("homeAfterSec", v)
    var motionEnabled: Boolean get() = sp.getBoolean("motionEnabled", false); set(v) = p("motionEnabled", v)
    var wakeOnMotion: Boolean get() = sp.getBoolean("wakeOnMotion", true); set(v) = p("wakeOnMotion", v)
    var wakeOnProximity: Boolean get() = sp.getBoolean("wakeOnProximity", true); set(v) = p("wakeOnProximity", v)
    var autoBrightness: Boolean get() = sp.getBoolean("autoBrightness", false); set(v) = p("autoBrightness", v)
    var lockTask: Boolean get() = sp.getBoolean("lockTask", true); set(v) = p("lockTask", v)
    var ignoreSsl: Boolean get() = sp.getBoolean("ignoreSsl", false); set(v) = p("ignoreSsl", v)

    // tryb nocny (zegar)
    var nightEnabled: Boolean get() = sp.getBoolean("nightEnabled", false); set(v) = p("nightEnabled", v)
    var nightStart: String get() = s("nightStart", "22:00"); set(v) = p("nightStart", v)
    var nightEnd: String get() = s("nightEnd", "06:30"); set(v) = p("nightEnd", v)
    var nightBrightness: Int get() = sp.getInt("nightBrightness", 10); set(v) = p("nightBrightness", v)
    var nightWakeSec: Int get() = sp.getInt("nightWakeSec", 60); set(v) = p("nightWakeSec", v)

    // PIN (tylko skrót z solą) i blokada po błędach
    var pinHash: String get() = s("pinHash"); set(v) = p("pinHash", v)
    var pinSalt: String get() = s("pinSalt"); set(v) = p("pinSalt", v)
    var pinLockUntil: Long get() = sp.getLong("pinLockUntil", 0L); set(v) = p("pinLockUntil", v)

    /** Kod parowania wyświetlany na tablecie, dopóki nie jest sparowany z HA. */
    val pairCode: String
        get() {
            var c = s("pairCode")
            if (c.isEmpty()) { c = "%06d".format(SecureRandom().nextInt(1_000_000)); p("pairCode", c) }
            return c
        }

    fun fallbackList(): List<String> =
        fallbackUrls.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.startsWith("http") }
}
