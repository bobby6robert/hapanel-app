package dev.hapanel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Słowo wybudzające („Aurora”) rozpoznawane w całości na tablecie (Vosk, mały model polski ~50 MB,
 * pobierany raz przy pierwszym włączeniu). Dźwięk nie opuszcza tabletu, dopóki słowo nie padnie.
 */
object WakeWord {
    /** Minimalna pewność rozpoznania słowa (0–1) wg czułości z ustawień (1 mało czuła … 3 bardzo czuła).
     *  Mniej = czulej, ale więcej fałszywych wybudzeń (np. od telewizora). */
    private val MIN_CONF get() = when (Prefs.wakeSensitivity) { 1 -> 0.95; 3 -> 0.8; else -> 0.9 }
    /** Słowo „dom” jest krótkie i częste w mowie – wyższy próg pewności. */
    private val MIN_CONF_HOME get() = when (Prefs.wakeSensitivity) { 1 -> 0.99; 3 -> 0.9; else -> 0.96 }

    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-pl-0.22.zip"
    private const val MODEL_DIR = "vosk-pl"

    @Volatile var status = "off"        // off / downloading / loading / listening / paused / error
        private set
    private var model: Model? = null
    // 2.12.1: własna pętla mikrofonu (zamiast SpeechService) – trzymamy ostatnie 6 s dźwięku,
    // żeby porównać usłyszane „aurora” z nagraniami domowników (VoicePrint)
    private class Service(val rec: Recognizer) {
        @Volatile var running = true
        var thread: Thread? = null
        fun stop() { running = false; thread?.let { if (it !== Thread.currentThread()) it.join(800) }; try { rec.close() } catch (_: Exception) {} }
    }
    private var service: Service? = null
    private val ring = ShortArray(16000 * 6)
    @Volatile private var fed = 0L          // ile próbek podano do rozpoznawania od startu
    @Volatile private var loading = false
    private var lastHit = 0L
    @Volatile private var active: List<String> = emptyList()
    @Volatile private var lastFail = 0L

    fun active() = service != null

    /** Wywoływane co kilka sekund z serwisu: włącza/wyłącza nasłuch zależnie od warunków. */
    @Synchronized
    fun tick(ctx: Context, allowWake: Boolean, allowHome: Boolean = false) {
        val allowed = allowWake || allowHome
        if (!allowed || Voice.busy() || VoicePrint.recording) { if (service != null) release(); if (!allowed && status != "error") status = "off"; return }
        val words = listOfNotNull(
            Prefs.wakeWordText.ifBlank { "aurora" }.takeIf { allowWake },
            Prefs.homeWordText.ifBlank { "dom" }.takeIf { allowHome })
        if (service != null && words == active) return
        if (service != null) release()
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { status = "error"; return }
        val m = model
        if (m == null) {
            if (System.currentTimeMillis() - lastFail > 10 * 60_000L) load(ctx.applicationContext)
            return
        }
        try {
            val grammar = "[" + words.joinToString(", ") { "\"" + it.replace("\"", "") + "\"" } + ", \"[unk]\"]"
            val rec = Recognizer(m, 16000f, grammar)
            rec.setWords(true)
            val s = Service(rec)
            s.thread = Thread({ loop(s) }, "aurora-wake").apply { isDaemon = true; start() }
            service = s
            active = words
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
        if (status == "listening") status = "paused"
    }

    /** Czyta mikrofon, karmi Vosk i bufor; wybudzamy tylko na wyniku końcowym (częściowe są „nerwowe”). */
    private fun loop(s: Service) {
        var ar: AudioRecord? = null
        try {
            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16000))
            ar.startRecording()
            fed = 0L
            val buf = ShortArray(1600)
            while (s.running) {
                val n = ar.read(buf, 0, buf.size)
                if (n <= 0) { Thread.sleep(50); continue }
                synchronized(ring) {
                    for (i in 0 until n) ring[((fed + i) % ring.size).toInt()] = buf[i]
                    fed += n
                }
                if (s.rec.acceptWaveForm(buf, n)) check(s.rec.result, "text")
            }
        } catch (_: Throwable) {
            if (s.running) { status = "error"; Controller.ui.post { release() } }
        } finally {
            try { ar?.stop() } catch (_: Exception) {}
            try { ar?.release() } catch (_: Exception) {}
        }
    }

    /** Wycina z bufora dźwięk między sekundami a–b strumienia (z zapasem). */
    private fun slice(a: Double, b: Double): ShortArray? = synchronized(ring) {
        val from = ((a - 0.2) * 16000).toLong().coerceAtLeast(fed - ring.size + 1).coerceAtLeast(0)
        val to = ((b + 0.25) * 16000).toLong().coerceAtMost(fed)
        if (to - from < 3200) return null
        ShortArray((to - from).toInt()) { ring[((from + it) % ring.size).toInt()] }
    }

    private fun check(json: String?, key: String) {
        if (json == null) return
        val aurora = Prefs.wakeWordText.ifBlank { "aurora" }
        val home = Prefs.homeWordText.ifBlank { "dom" }
        // które słowo padło (tylko spośród aktualnie nasłuchiwanych); „dom” jest krótki – wymaga większej pewności
        var heard = ""
        var conf = 0.0
        var who: String? = null
        var dist = 0.0
        val personal = VoicePrint.ready()
        val word = try {
            val o = JSONObject(json)
            heard = o.optString(key)
            val res = o.optJSONArray("result")
            if (res != null && res.length() > 0) {
                val ws = (0 until res.length()).map { res.getJSONObject(it) }
                val hit = ws.firstOrNull { it.optString("word") in active }
                conf = hit?.optDouble("conf", 0.0) ?: 0.0
                // 2.12: „dom” tylko jako osobne, krótkie zawołanie (nie w środku zdania z telewizora)
                ws.firstOrNull { w ->
                    val wd = w.optString("word")
                    // 2.12.1: gdy są nagrania domowników, Vosk tylko wskazuje kandydata, a decyduje porównanie głosu
                    val need = if (wd == home) MIN_CONF_HOME else if (personal) 0.5 else MIN_CONF
                    var ok = wd in active && w.optDouble("conf", 0.0) >= need && (wd != home || ws.size <= 3)
                    if (ok && personal && wd == aurora) {
                        val pcm = slice(w.optDouble("start", 0.0), w.optDouble("end", 0.0))
                        who = pcm?.let { VoicePrint.verify(it) }
                        dist = VoicePrint.lastDist
                        ok = who != null
                    }
                    ok
                }?.optString("word")
            } else null      // bez pewności słowa nie wybudzamy (wcześniej wystarczył sam tekst)
        } catch (_: Exception) { null }
        if (word == null) {
            // odrzucone „prawie wybudzenie” – do dziennika w HA (żeby było widać, co słyszy tablet)
            if (conf > 0.5) Events.send("wake_reject", "heard" to heard.take(80), "conf" to conf,
                "dist" to Math.round(dist * 100) / 100.0)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastHit < 3000) return
        lastHit = now
        val ctx = Controller.activity ?: return
        Controller.ui.post {
            release()
            Events.send("wake_word", "word" to word, "conf" to conf, "heard" to heard.take(80),
                "who" to (who ?: ""), "dist" to Math.round(dist * 100) / 100.0)
            if (word == home && word != aurora) Voice.start(ctx, source = "command", waitSec = 5)
            else Voice.start(ctx, source = "wake")
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
                try { VoicePrint.load(ctx) } catch (_: Throwable) {}
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
