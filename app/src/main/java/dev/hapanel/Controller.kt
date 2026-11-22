package dev.hapanel

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Most między serwisem (HTTP, czujniki) a ekranem. */
object Controller {
    val ui = Handler(Looper.getMainLooper())
    @Volatile var activity: MainActivity? = null
    @Volatile var sleeping = false
    @Volatile var clock = false              // nakładka z zegarem (tryb nocny)
    @Volatile var night = false              // czy trwa okno nocne
    @Volatile var saver = false              // tryb oszczędny aktywny (bateria / noc)
    @Volatile var screensaver = false        // 2.12.1: wygaszacz ekranu widoczny
    @Volatile var saverPhotos: List<String> = emptyList()
    @Volatile var saverCams: List<String> = emptyList()
    @Volatile var saverWeather = ""
    @Volatile var quietUntil = 0L            // 2.12.1: „cisza na godzinę” z szybkiego menu
    val quiet: Boolean get() = System.currentTimeMillis() < quietUntil
    @Volatile var uiBeat = SystemClock.elapsedRealtime()   // 2.12.1: strażnik – ostatni „oddech” wątku ekranu
    @Volatile var homeEmpty = false          // 2.12: nikogo nie ma w domu (z HA) – Aurora milczy
    /** Głębokie uśpienie: ekran może naprawdę zgasnąć, strona i nasłuch stoją. */
    val deep: Boolean get() = saver && (sleeping || clock)
    @Volatile var brightness = -1f           // -1 = jasność systemowa
    @Volatile var lastActivity = SystemClock.elapsedRealtime()
    @Volatile var currentUrl = ""
    @Volatile var lastLoad = 0L              // ostatnie załadowanie strony (ms)
    @Volatile var onChange: (() -> Unit)? = null
    @Volatile var fallback = false           // czy działa adres awaryjny
    // dane na zegarze nocnym wysyłane z HA (/clock)
    @Volatile var clockOut = ""
    @Volatile var clockIn = ""
    @Volatile var clockInfo = ""

    fun touch() { lastActivity = SystemClock.elapsedRealtime() }
    fun loadUrl(u: String) { ui.post { activity?.web?.loadUrl(u) } }
    fun loadDashboard() { ui.post { activity?.resetAndLoad() } }
    fun reload() { ui.post { activity?.web?.reload() } }
    fun restartPanel() { ui.post { activity?.recreate() } }
    fun popup(url: String, seconds: Int, title: String = "") { ui.post { activity?.showPopup(url, seconds, title) } }
    fun setClockData(out: String?, inn: String?, info: String?) {
        out?.let { clockOut = it.trim() }
        inn?.let { clockIn = it.trim() }
        info?.let { clockInfo = it.trim() }
        ui.post { activity?.updateClockInfo() }
    }
    fun setSaverData(photos: String?, cams: String?, weather: String?) {
        photos?.let { saverPhotos = it.split("\n").map(String::trim).filter { u -> u.startsWith("http") } }
        cams?.let { saverCams = it.split("\n").map(String::trim).filter { u -> u.startsWith("http") } }
        weather?.let { saverWeather = it.trim() }
        ui.post { activity?.saverDataChanged() }
    }
    fun showSaver() { if (!screensaver) { screensaver = true; ui.post { activity?.applyScreen() }; onChange?.invoke() } }
    fun hideSaver() { if (screensaver) { screensaver = false; ui.post { activity?.applyScreen() }; onChange?.invoke() } }
    fun closePopup() { ui.post { activity?.closePopup() } }

    @JvmName("changeBrightness")
    fun setBrightness(f: Float) {
        brightness = f.coerceIn(0.01f, 1f)
        ui.post { activity?.applyScreen() }
    }

    fun sleep() { if (!sleeping) { sleeping = true; ui.post { activity?.applyScreen() }; onChange?.invoke() } }

    fun showClock() { if (!clock) { clock = true; ui.post { activity?.applyScreen() }; onChange?.invoke() } }
    fun hideClock() { if (clock) { clock = false; ui.post { activity?.applyScreen() }; onChange?.invoke() } }

    /** Wybudza z uśpienia i z zegara. */
    fun wake(keepSaver: Boolean = false) {
        touch()
        var changed = false
        if (screensaver && !keepSaver) { screensaver = false; changed = true }
        if (sleeping) { sleeping = false; changed = true }
        if (clock) { clock = false; changed = true }
        if (changed) { ui.post { activity?.applyScreen() }; onChange?.invoke() }
        ui.post { activity?.turnScreenOn() }
    }
}
