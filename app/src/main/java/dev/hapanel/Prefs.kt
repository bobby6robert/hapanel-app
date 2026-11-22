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
    /** Tryb oszczędny: auto (na baterii i w nocy), always, never. */
    var saverMode: String get() = s("saverMode", "auto"); set(v) = p("saverMode", v)
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

    // Aurora – rozmowa głosowa (2.7)
    var talkButton: Boolean get() = sp.getBoolean("talkButton", true); set(v) = p("talkButton", v)
    var wakeWord: Boolean get() = sp.getBoolean("wakeWord", false); set(v) = p("wakeWord", v)
    var wakeWordText: String get() = s("wakeWordText", "aurora"); set(v) = p("wakeWordText", v)
    var wakeQuietStart: String get() = s("wakeQuietStart", "22:00"); set(v) = p("wakeQuietStart", v)
    var wakeQuietEnd: String get() = s("wakeQuietEnd", "06:30"); set(v) = p("wakeQuietEnd", v)
    var homeWord: Boolean get() = sp.getBoolean("homeWord", true); set(v) = p("homeWord", v)
    var homeWordText: String get() = s("homeWordText", "dom"); set(v) = p("homeWordText", v)
    /** 2.13: język – system / pl / en */
    var language: String get() = s("language", "system"); set(v) = p("language", v)
    /** Słowo „dom” w bieżącym języku (po angielsku domyślnie „home”). */
    val homeWordNow: String get() = homeWordText.ifBlank { "dom" }.let { if (L.en && it == "dom") "home" else it }
    var wakePresenceOnly: Boolean get() = sp.getBoolean("wakePresenceOnly", true); set(v) = p("wakePresenceOnly", v)
    var proximityTalk: Boolean get() = sp.getBoolean("proximityTalk", false); set(v) = p("proximityTalk", v)
    // 2.12: komunikaty bez nasłuchu, cisza gdy nikogo nie ma, czułość słowa, wejście do menu
    var announceListen: Boolean get() = sp.getBoolean("announceListen", false); set(v) = p("announceListen", v)
    var awayMute: Boolean get() = sp.getBoolean("awayMute", true); set(v) = p("awayMute", v)
    /** 1 = mniej czuła (mniej pomyłek), 2 = średnia, 3 = bardzo czuła. */
    var wakeSensitivity: Int get() = sp.getInt("wakeSensitivity", 2); set(v) = p("wakeSensitivity", v)
    var menuAnyCorner: Boolean get() = sp.getBoolean("menuAnyCorner", true); set(v) = p("menuAnyCorner", v)
    var menuTwoFinger: Boolean get() = sp.getBoolean("menuTwoFinger", true); set(v) = p("menuTwoFinger", v)
    // 2.12.1: ściszanie TV i głośność komunikatów (wykonuje HA; tu tylko ustawienie z menu)
    var duckEnabled: Boolean get() = sp.getBoolean("duckEnabled", true); set(v) = p("duckEnabled", v)
    var duckLevel: Int get() = sp.getInt("duckLevel", 15); set(v) = p("duckLevel", v)
    var announceVolume: Int get() = sp.getInt("announceVolume", 0); set(v) = p("announceVolume", v)
    // 2.12.1: wygaszacz ekranu
    var saverEnabled: Boolean get() = sp.getBoolean("saverEnabled", false); set(v) = p("saverEnabled", v)
    /** clock / photos / cameras / black */
    var saverType: String get() = s("saverType", "clock"); set(v) = p("saverType", v)
    var saverAfterSec: Int get() = sp.getInt("saverAfterSec", 120); set(v) = p("saverAfterSec", v)
    var saverPhotoSec: Int get() = sp.getInt("saverPhotoSec", 15); set(v) = p("saverPhotoSec", v)
    var saverCamSec: Int get() = sp.getInt("saverCamSec", 5); set(v) = p("saverCamSec", v)
    var saverShuffle: Boolean get() = sp.getBoolean("saverShuffle", true); set(v) = p("saverShuffle", v)
    var duckName: String get() = sp.getString("duckName", "") ?: ""; set(v) = p("duckName", v)
    var listenWaitSec: Int get() = sp.getInt("listenWaitSec", 8); set(v) = p("listenWaitSec", v)
    var listenMaxSec: Int get() = sp.getInt("listenMaxSec", 30); set(v) = p("listenMaxSec", v)
    var silenceMs: Int get() = sp.getInt("silenceMs", 1200); set(v) = p("silenceMs", v)
    var voiceEnabled: Boolean get() = sp.getBoolean("voiceEnabled", true); set(v) = p("voiceEnabled", v)

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
