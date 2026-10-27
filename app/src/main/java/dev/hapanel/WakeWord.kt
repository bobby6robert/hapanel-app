package dev.hapanel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Słowo wybudzające („Aurora”) rozpoznawane w całości na tablecie (Vosk, mały model polski ~50 MB,
 * pobierany raz przy pierwszym włączeniu). Dźwięk nie opuszcza tabletu, dopóki słowo nie padnie.
 */
object WakeWord {
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip"
    private const val MODEL_DIR = "vosk-pl"

    @Volatile var status = "off"        // off / downloading / loading / listening / paused / error
        private set
    private var model: Model? = null
    private var service: SpeechService? = null
    @Volatile private var loading = false
    private var lastHit = 0L
    @Volatile private var lastFail = 0L

    fun active() = service != null

    /** Wywoływane co kilka sekund z serwisu: włącza/wyłącza nasłuch zależnie od warunków. */
    @Synchronized
    fun tick(ctx: Context, allowed: Boolean) {
        if (!allowed || Voice.busy()) { if (service != null) release(); if (!allowed && status != "error") status = "off"; return }
        if (service != null) return
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { status = "error"; return }
        val m = model
        if (m == null) {
            if (System.currentTimeMillis() - lastFail > 10 * 60_000L) load(ctx.applicationContext)
            return
        }
        try {
            val word = Prefs.wakeWordText.ifBlank { "aurora" }
            val grammar = "[\"" + word.replace("\"", "") + "\", \"[unk]\"]"
            val rec = Recognizer(m, 16000f, grammar)
            val s = SpeechService(rec, 16000f)
            s.startListening(listener)
            service = s
            status = "listening"
        } catch (_: Exception) {
            status = "error"; service = null
        }
    }

    /** Zwalnia mikrofon (np. na czas rozmowy). Model zostaje w pamięci. */
    @Synchronized
    fun release() {
        val s = service ?: return
        service = null
        try { s.stop() } catch (_: Exception) {}
        try { s.shutdown() } catch (_: Exception) {}
        if (status == "listening") status = "paused"
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) { check(hypothesis, "partial") }
        override fun onResult(hypothesis: String?) { check(hypothesis, "text") }
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) { release(); status = "error" }
        override fun onTimeout() { release() }
    }

    private fun check(json: String?, key: String) {
        if (json == null) return
        val txt = try { JSONObject(json).optString(key) } catch (_: Exception) { "" }
        val word = Prefs.wakeWordText.ifBlank { "aurora" }
        if (!txt.contains(word)) return
        val now = System.currentTimeMillis()
        if (now - lastHit < 3000) return
        lastHit = now
        val ctx = Controller.activity ?: return
        Controller.ui.post {
            release()
            Events.send("wake_word", "word" to word)
            Voice.start(ctx, source = "wake")
        }
    }

    private fun load(ctx: Context) {
        if (loading) return
        loading = true
        Thread {
            try {
                val dir = File(ctx.filesDir, MODEL_DIR)
                val marker = File(dir, ".ok")
                if (!marker.exists()) {
                    status = "downloading"
                    dir.deleteRecursively(); dir.mkdirs()
                    val c = URL(MODEL_URL).openConnection() as HttpURLConnection
                    c.connectTimeout = 15000; c.readTimeout = 60000; c.instanceFollowRedirects = true
                    ZipInputStream(c.inputStream.buffered()).use { zip ->
                        var e = zip.nextEntry
                        while (e != null) {
                            // pomija katalog główny archiwum (vosk-model-small-pl-0.22/...)
                            val rel = e.name.substringAfter('/', "")
                            if (rel.isNotEmpty() && !rel.contains("..")) {
                                val f = File(dir, rel)
                                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
                            }
                            e = zip.nextEntry
                        }
                    }
                    c.disconnect()
                    marker.writeText("ok")
                }
                status = "loading"
                model = Model(dir.absolutePath)
                status = "paused"
            } catch (_: Throwable) {
                status = "error"; lastFail = System.currentTimeMillis()
            } finally {
                loading = false
            }
        }.start()
    }
}
