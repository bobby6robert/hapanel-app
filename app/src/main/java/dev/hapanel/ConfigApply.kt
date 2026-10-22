package dev.hapanel

import org.json.JSONObject

/** Zastosowanie ustawień z integracji HA albo z importu JSON (te same nazwy kluczy co w /config i /export). */
object ConfigApply {
    private val timeRe = Regex("\\d{1,2}:\\d{2}")
    private fun b(v: Boolean) = if (v) "1" else "0"

    /** Zwraca komunikat błędu albo null. */
    fun apply(p: Map<String, String>): String? {
        p["dashboard"]?.takeIf { it.isNotBlank() }?.let {
            if (it != Prefs.dashboardUrl) { Prefs.dashboardUrl = it; Controller.loadDashboard() }
        }
        p["fallback"]?.let { Prefs.fallbackUrls = it.replace(",", "\n").trim() }
        p["homeAfter"]?.toIntOrNull()?.let { Prefs.homeAfterSec = it }
        p["sleepAfter"]?.toIntOrNull()?.let { Prefs.sleepAfterSec = it }
        p["motionThreshold"]?.toIntOrNull()?.let { Prefs.motionThreshold = it }
        p["motion"]?.let { Prefs.motionEnabled = it == "1" }
        p["wakeOnMotion"]?.let { Prefs.wakeOnMotion = it == "1" }
        p["wakeOnProximity"]?.let { Prefs.wakeOnProximity = it == "1" }
        p["auto"]?.let { Prefs.autoBrightness = it == "1" }
        p["lockTask"]?.let { Prefs.lockTask = it == "1" }
        p["nightEnabled"]?.let { Prefs.nightEnabled = it == "1" }
        p["night"]?.let { Prefs.nightEnabled = it == "1" }      // zgodność z wersją 2.4 (v1)
        p["nightStart"]?.takeIf { timeRe.matches(it.trim()) }?.let { Prefs.nightStart = it.trim() }
        p["nightEnd"]?.takeIf { timeRe.matches(it.trim()) }?.let { Prefs.nightEnd = it.trim() }
        p["nightBrightness"]?.toIntOrNull()?.let { Prefs.nightBrightness = it.coerceIn(1, 255) }
        p["nightWake"]?.toIntOrNull()?.let { Prefs.nightWakeSec = it.coerceAtLeast(10) }
        var look = false
        p["css"]?.let { Prefs.injectCss = it; look = true }
        p["js"]?.let { Prefs.injectJs = it; look = true }
        p["zoom"]?.toIntOrNull()?.let { Prefs.zoom = it.coerceIn(50, 250); look = true }
        if (look) Controller.ui.post { Controller.activity?.applyWebSettings(); Controller.activity?.injectNow() }
        p["webhook"]?.let { Prefs.webhookUrl = it.trim() }
        val pin = p["pin"]
        if (pin != null) {
            if (pin.isEmpty()) Pin.clear() else if (!Pin.set(pin)) return "pin min 4"
        }
        p["apiToken"]?.takeIf { it.isNotBlank() }?.let { Prefs.apiToken = it }
        return null
    }

    /** Kopia ustawień bez PIN-u, tokenów i adresu webhooka. */
    fun export(): JSONObject = JSONObject()
        .put("dashboard", Prefs.dashboardUrl)
        .put("fallback", Prefs.fallbackUrls.replace("\n", ","))
        .put("homeAfter", Prefs.homeAfterSec.toString())
        .put("sleepAfter", Prefs.sleepAfterSec.toString())
        .put("motion", b(Prefs.motionEnabled))
        .put("motionThreshold", Prefs.motionThreshold.toString())
        .put("wakeOnMotion", b(Prefs.wakeOnMotion))
        .put("wakeOnProximity", b(Prefs.wakeOnProximity))
        .put("auto", b(Prefs.autoBrightness))
        .put("lockTask", b(Prefs.lockTask))
        .put("nightEnabled", b(Prefs.nightEnabled))
        .put("nightStart", Prefs.nightStart)
        .put("nightEnd", Prefs.nightEnd)
        .put("nightBrightness", Prefs.nightBrightness.toString())
        .put("nightWake", Prefs.nightWakeSec.toString())
        .put("css", Prefs.injectCss)
        .put("js", Prefs.injectJs)
        .put("zoom", Prefs.zoom.toString())
}
