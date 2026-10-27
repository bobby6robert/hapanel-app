package dev.hapanel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.SystemClock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Rozmowa z Aurorą (2.7): nagranie z mikrofonu z wykrywaniem mowy (start/cisza),
 * wysyłka WAV do webhooka integracji w HA, odtworzenie odpowiedzi i kolejna tura, dopóki ktoś mówi.
 * Bez zewnętrznych bibliotek. Integracja robi rozpoznawanie mowy, rozmowę (AI) i syntezę głosu.
 */
object Voice {
    enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    @Volatile var state = State.IDLE
        private set
    @Volatile var level = 0f            // 0..1 – głośność do animacji zorzy
        private set
    @Volatile var caption = ""          // krótki opis na ekranie („Słucham…”)
        private set
    @Volatile var onState: (() -> Unit)? = null

    private const val RATE = 16000
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var cancel = false
    @Volatile private var running = false
    private var player: MediaPlayer? = null
    private lateinit var app: Context

    fun busy() = running

    private fun setState(s: State, cap: String) {
        state = s; caption = cap
        if (s != State.LISTENING && s != State.SPEAKING) level = 0f
        Controller.ui.post { Controller.activity?.updateVoice() }
        onState?.invoke()
    }

    /**
     * Startuje rozmowę.
     * @param firstAudio adres dźwięku do odtworzenia najpierw (np. komunikat Aurory), potem nasłuch
     * @param person kto rozmawia (jeśli wiadomo, np. po komunikacie do Wiki)
     * @param waitSec ile sekund czekać na początek mowy
     * @param source skąd start: button / wake / proximity / announce / ha
     */
    fun start(ctx: Context, firstAudio: String? = null, person: String = "", waitSec: Int = Prefs.listenWaitSec,
              source: String = "button", listen: Boolean = true) {
        app = ctx.applicationContext
        if (!Prefs.voiceEnabled && source != "announce") return
        if (running) { if (firstAudio == null) return; stop(); waitIdle() }
        cancel = false
        running = true
        setState(State.THINKING, "")
        Controller.wake()
        WakeWord.release()
        exec.execute {
            val session = UUID.randomUUID().toString().take(12)
            var who = person
            var wait = waitSec
            try {
                if (!firstAudio.isNullOrBlank()) {
                    setState(State.SPEAKING, "")
                    play(firstAudio)
                }
                if (!listen) return@execute
                var turn = 0
                while (!cancel) {
                    setState(State.LISTENING, "Słucham…")
                    val pcm = record(wait) ?: break           // nikt nie mówi -> koniec
                    if (cancel) break
                    setState(State.THINKING, "Myślę…")
                    val res = send(pcm, session, who, source, turn)
                    if (res == null) {
                        setState(State.SPEAKING, "Brak połączenia z domem")
                        SystemClock.sleep(1500)
                        break
                    }
                    turn++
                    res.optString("person").takeIf { it.isNotBlank() }?.let { who = it }
                    val audio = res.optString("audio")
                    val reply = res.optString("reply")
                    setState(State.SPEAKING, "")
                    if (audio.isNotBlank()) play(audio)
                    else if (reply.isNotBlank()) speakFallback(reply)
                    if (res.optBoolean("dim")) Controller.ui.postDelayed({ Controller.sleep() }, 1500)
                    if (res.optBoolean("end") || !res.optBoolean("continue", true)) break
                    wait = res.optInt("wait", Prefs.listenWaitSec)
                }
            } catch (_: Exception) {
            } finally {
                running = false
                setState(State.IDLE, "")
                Events.send("voice_end", "source" to source)
            }
        }
    }

    fun stop() {
        cancel = true
        try { player?.stop() } catch (_: Exception) {}
    }

    private fun waitIdle() {
        val until = SystemClock.elapsedRealtime() + 3000
        while (running && SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
    }

    // ---------------- nagrywanie z wykrywaniem mowy ----------------
    private fun record(waitSec: Int): ByteArray? {
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
        val minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val frame = RATE / 50                      // 20 ms = 320 próbek
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, frame * 2 * 10))
        } catch (_: Exception) { return null }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
        val out = ByteArrayOutputStream()
        val pre = ArrayDeque<ShortArray>()        // 300 ms przed startem mowy
        val buf = ShortArray(frame)
        var noise = 0.0
        var calib = 0
        var loud = 0
        var speaking = false
        var silentMs = 0
        val start = SystemClock.elapsedRealtime()
        var speechStart = 0L
        try {
            rec.startRecording()
            while (!cancel) {
                val n = rec.read(buf, 0, frame)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
                val rms = sqrt(sum / n)
                level = (rms / 3000.0).coerceIn(0.0, 1.0).toFloat()
                if (calib < 15) { noise = if (calib == 0) rms else noise * 0.8 + rms * 0.2; calib++ }
                val thr = maxOf(noise * 2.8, 450.0)
                val now = SystemClock.elapsedRealtime()
                val copy = buf.copyOf(n)
                if (!speaking) {
                    pre.addLast(copy); if (pre.size > 15) pre.removeFirst()
                    if (rms > thr) loud++ else { loud = 0; if (calib >= 15) noise = noise * 0.97 + rms * 0.03 }
                    if (loud >= 3) {
                        speaking = true; speechStart = now
                        for (f in pre) writeShorts(out, f)
                        pre.clear()
                    } else if (now - start > waitSec * 1000L) return null
                } else {
                    writeShorts(out, copy)
                    if (rms > thr * 0.8) silentMs = 0 else silentMs += 20
                    if (silentMs >= Prefs.silenceMs) break
                    if (now - speechStart > Prefs.listenMaxSec * 1000L) break
                }
            }
        } catch (_: Exception) {
            return null
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
            level = 0f
        }
        if (cancel || !speaking) return null
        val bytes = out.toByteArray()
        return if (bytes.size < RATE / 2) null else bytes        // krócej niż ~0,25 s = szum
    }

    private fun writeShorts(out: ByteArrayOutputStream, a: ShortArray) {
        for (v in a) { val x = v.toInt(); out.write(x and 0xff); out.write((x shr 8) and 0xff) }
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(pcm.size + 44)
        fun i32(v: Int) { o.write(v and 0xff); o.write((v shr 8) and 0xff); o.write((v shr 16) and 0xff); o.write((v shr 24) and 0xff) }
        fun i16(v: Int) { o.write(v and 0xff); o.write((v shr 8) and 0xff) }
        o.write("RIFF".toByteArray()); i32(36 + pcm.size); o.write("WAVE".toByteArray())
        o.write("fmt ".toByteArray()); i32(16); i16(1); i16(1); i32(RATE); i32(RATE * 2); i16(2); i16(16)
        o.write("data".toByteArray()); i32(pcm.size); o.write(pcm)
        return o.toByteArray()
    }

    // ---------------- wysyłka do HA ----------------
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun send(pcm: ByteArray, session: String, person: String, source: String, turn: Int): JSONObject? {
        val base = Prefs.webhookUrl
        if (base.isBlank()) return null
        val url = base + (if (base.contains('?')) "&" else "?") +
            "voice=1&session=${enc(session)}&person=${enc(person)}&source=${enc(source)}&turn=$turn"
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 5000
            c.readTimeout = 60000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "audio/wav")
            val body = wav(pcm)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            c.disconnect()
            if (code !in 200..299 || txt.isBlank()) null else JSONObject(txt)
        } catch (_: Exception) { null }
    }

    // ---------------- odtwarzanie ----------------
    /** Odtwarza dźwięk do końca (blokuje wątek). Poziom do animacji pulsuje sztucznie. */
    private fun play(url: String) {
        val done = CountDownLatch(1)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setDataSource(url)
            mp.setOnCompletionListener { done.countDown() }
            mp.setOnErrorListener { _, _, _ -> done.countDown(); true }
            mp.prepare()
            mp.start()
            val t0 = SystemClock.elapsedRealtime()
            while (!done.await(60, TimeUnit.MILLISECONDS)) {
                if (cancel) break
                val t = (SystemClock.elapsedRealtime() - t0) / 1000.0
                level = (0.35 + 0.3 * kotlin.math.sin(t * 9.0) * kotlin.math.sin(t * 2.3)).toFloat().coerceIn(0.05f, 1f)
                if (t > 600) break
            }
        } catch (_: Exception) {
        } finally {
            try { mp.release() } catch (_: Exception) {}
            if (player === mp) player = null
            level = 0f
        }
    }

    /** Gdy HA nie przysłał dźwięku – głos Androida (i czekanie na koniec). */
    private fun speakFallback(text: String) {
        val done = CountDownLatch(1)
        Speech.say(text) { done.countDown() }
        done.await(60, TimeUnit.SECONDS)
    }
}
