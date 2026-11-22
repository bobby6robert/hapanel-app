package dev.hapanel

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 2.12.1 „Nagraj Aurorę”: własne nagrania słowa od domowników. Vosk wychwytuje kandydata „aurora”,
 * a tu sprawdzamy, czy brzmi jak któreś z nagrań (MFCC + DTW). Telewizor i obce głosy odpadają,
 * a domownicy są rozpoznawani pewniej. Wszystko liczone na tablecie, nic nie wychodzi do sieci.
 */
object VoicePrint {
    const val RATE = 16000
    @Volatile var recording = false
        private set
    private var templates: List<Pair<String, Array<FloatArray>>> = emptyList()
    private var baseDist = 0.0
    @Volatile var lastDist = 0.0
        private set

    private fun dir(ctx: Context) = File(ctx.filesDir, "aurora").apply { mkdirs() }

    fun count(ctx: Context) = dir(ctx).listFiles { f -> f.name.endsWith(".pcm") }?.size ?: 0
    fun people(ctx: Context): Map<String, Int> =
        (dir(ctx).listFiles { f -> f.name.endsWith(".pcm") } ?: emptyArray())
            .groupingBy { it.name.substringBeforeLast('_') }.eachCount()

    fun ready() = templates.size >= 3

    fun deleteAll(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() }; templates = emptyList() }

    /** Wczytuje nagrania i liczy, jak bardzo różnią się między sobą (to jest „miara” do porównań). */
    @Synchronized
    fun load(ctx: Context) {
        val files = dir(ctx).listFiles { f -> f.name.endsWith(".pcm") }?.sortedBy { it.name } ?: emptyList()
        templates = files.mapNotNull { f ->
            val s = readPcm(f); if (s.size < RATE / 5) null else f.name.substringBeforeLast('_') to mfcc(s)
        }
        // średnia odległość między nagraniami tej samej osoby (a gdy jedna osoba – między wszystkimi)
        val d = mutableListOf<Double>()
        for (i in templates.indices) for (j in i + 1 until templates.size)
            if (templates[i].first == templates[j].first) d += dtw(templates[i].second, templates[j].second)
        if (d.isEmpty()) for (i in templates.indices) for (j in i + 1 until templates.size) d += dtw(templates[i].second, templates[j].second)
        baseDist = if (d.isEmpty()) 0.0 else d.sorted()[d.size / 2]
    }

    /** Czy fragment dźwięku to „Aurora” jednego z domowników. Zwraca imię albo null. */
    fun verify(samples: ShortArray): String? {
        val t = templates
        if (t.size < 3 || baseDist <= 0) return null
        val trimmed = trim(samples)
        if (trimmed.size < RATE / 5) return null
        val f = mfcc(trimmed)
        val best = t.map { it.first to dtw(f, it.second) }.minByOrNull { it.second } ?: return null
        lastDist = best.second / baseDist
        val factor = when (Prefs.wakeSensitivity) { 1 -> 1.25; 3 -> 1.75; else -> 1.5 }
        return if (lastDist <= factor) best.first else null
    }

    /** Nagrywa jedno „Aurora” (do 2,5 s), obcina ciszę i zapisuje. Wołać z wątku w tle. */
    fun recordOne(ctx: Context, person: String): Boolean {
        recording = true
        WakeWord.release()
        try {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val ar = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(min, RATE))
            val all = ShortArray(RATE * 5 / 2)
            ar.startRecording()
            var n = 0
            while (n < all.size) { val r = ar.read(all, n, min(1600, all.size - n)); if (r <= 0) break; n += r }
            ar.stop(); ar.release()
            val cut = trim(all.copyOf(n))
            if (cut.size < RATE / 4 || cut.size > RATE * 2) return false     // nic nie powiedziano albo za długo
            val name = person.trim().lowercase().replace(Regex("[^a-ząćęłńóśźż0-9]"), "").ifBlank { "dom" }
            var i = 1
            while (File(dir(ctx), "${name}_$i.pcm").exists()) i++
            val bb = ByteBuffer.allocate(cut.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            cut.forEach { bb.putShort(it) }
            File(dir(ctx), "${name}_$i.pcm").writeBytes(bb.array())
            load(ctx)
            return true
        } catch (_: Exception) {
            return false
        } finally {
            recording = false
        }
    }

    private fun readPcm(f: File): ShortArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(b.remaining() / 2) { b.short }
    }

    /** Obcina ciszę z początku i końca (energia w oknach 20 ms). */
    fun trim(s: ShortArray): ShortArray {
        val w = 320
        val n = s.size / w
        if (n < 3) return s
        val e = DoubleArray(n) { k -> var sum = 0.0; for (i in k * w until (k + 1) * w) sum += s[i].toDouble() * s[i]; sqrt(sum / w) }
        val sorted = e.sorted()
        val noise = sorted[n / 10]
        val peak = sorted[n - 1]
        if (peak < 300) return ShortArray(0)
        val thr = max(noise * 3, peak * 0.08)
        var a = e.indexOfFirst { it > thr }
        var b = e.indexOfLast { it > thr }
        if (a < 0) return ShortArray(0)
        a = max(0, a - 3); b = min(n - 1, b + 3)
        return s.copyOfRange(a * w, (b + 1) * w)
    }

    // ---------- MFCC ----------
    private const val FRAME = 400
    private const val HOP = 160
    private const val NFFT = 512
    private const val MELS = 26
    private const val CEPS = 13
    private val window = DoubleArray(FRAME) { 0.54 - 0.46 * cos(2 * PI * it / (FRAME - 1)) }
    private val melBank: Array<DoubleArray> by lazy {
        fun hz2mel(h: Double) = 2595 * log10(1 + h / 700)
        fun mel2hz(m: Double) = 700 * (Math.pow(10.0, m / 2595) - 1)
        val lo = hz2mel(80.0); val hi = hz2mel(7600.0)
        val pts = DoubleArray(MELS + 2) { mel2hz(lo + (hi - lo) * it / (MELS + 1)) }
        val bins = pts.map { (it / RATE * NFFT).toInt() }
        Array(MELS) { m ->
            DoubleArray(NFFT / 2 + 1) { k ->
                val l = bins[m]; val c = bins[m + 1]; val r = bins[m + 2]
                when {
                    k in l until c && c > l -> (k - l).toDouble() / (c - l)
                    k in c..r && r > c -> (r - k).toDouble() / (r - c)
                    else -> 0.0
                }
            }
        }
    }

    fun mfcc(s: ShortArray): Array<FloatArray> {
        val frames = max(1, (s.size - FRAME) / HOP + 1)
        val out = Array(frames) { FloatArray(CEPS - 1) }
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT)
        for (f in 0 until frames) {
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            var prev = if (f * HOP > 0) s[f * HOP - 1].toDouble() else 0.0
            for (i in 0 until FRAME) {
                val idx = f * HOP + i
                val x = if (idx < s.size) s[idx].toDouble() else 0.0
                re[i] = (x - 0.97 * prev) * window[i]; prev = x
            }
            fft(re, im)
            val mel = DoubleArray(MELS)
            for (m in 0 until MELS) {
                var sum = 0.0
                val bank = melBank[m]
                for (k in 0..NFFT / 2) if (bank[k] > 0) sum += bank[k] * (re[k] * re[k] + im[k] * im[k])
                mel[m] = ln(sum + 1e-10)
            }
            for (c in 1 until CEPS) {
                var sum = 0.0
                for (m in 0 until MELS) sum += mel[m] * cos(PI * c * (m + 0.5) / MELS)
                out[f][c - 1] = sum.toFloat()
            }
        }
        // normalizacja średniej (usuwa wpływ mikrofonu i pomieszczenia)
        for (c in 0 until CEPS - 1) {
            var mean = 0.0
            for (f in out) mean += f[c]
            mean /= out.size
            for (f in out) f[c] = (f[c] - mean).toFloat()
        }
        return out
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Odległość DTW znormalizowana długością ścieżki. */
    fun dtw(a: Array<FloatArray>, b: Array<FloatArray>): Double {
        val n = a.size; val m = b.size
        if (n == 0 || m == 0) return Double.MAX_VALUE
        if (n > 2 * m + 10 || m > 2 * n + 10) return Double.MAX_VALUE
        val inf = Double.MAX_VALUE / 4
        var prev = DoubleArray(m + 1) { inf }; var cur = DoubleArray(m + 1)
        prev[0] = 0.0
        for (i in 1..n) {
            cur.fill(inf)
            for (j in 1..m) {
                var d = 0.0
                val x = a[i - 1]; val y = b[j - 1]
                for (k in x.indices) { val t = x[k] - y[k]; d += t * t }
                cur[j] = sqrt(d) + min(prev[j - 1], min(prev[j], cur[j - 1]))
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[m] / (n + m)
    }
}
