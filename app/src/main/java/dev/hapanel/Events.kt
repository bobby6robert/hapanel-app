package dev.hapanel

import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Zdarzenia z tabletu wysyłane od razu do webhooka w HA (ruch, dotknięcie, niska bateria, start, ekran). */
object Events {
    private val exec = Executors.newSingleThreadExecutor()
    private var lastTouch = 0L

    fun send(event: String, vararg extra: Pair<String, Any>) {
        val url = Prefs.webhookUrl
        if (url.isBlank()) return
        exec.execute {
            try {
                val body = JSONObject().put("event", event)
                for ((k, v) in extra) body.put(k, v)
                val c = URL(url).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 3000
                c.readTimeout = 3000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                c.responseCode
                c.disconnect()
            } catch (_: Exception) {}
        }
    }

    /** Dotknięcie ekranu: najwyżej raz na 10 sekund. */
    fun touch() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTouch > 10_000) { lastTouch = now; send("touch") }
    }
}
