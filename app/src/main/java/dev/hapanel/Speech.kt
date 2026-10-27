package dev.hapanel

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Głos Androida (zapasowy) z informacją o końcu wypowiedzi – wspólny dla komunikatów i rozmowy. */
object Speech {
    private var tts: TextToSpeech? = null
    @Volatile var ready = false
        private set
    private val callbacks = ConcurrentHashMap<String, () -> Unit>()
    private var counter = 0

    fun init(c: Context) {
        if (tts != null) return
        tts = TextToSpeech(c.applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) {
                val pl = Locale("pl", "PL")
                val ok = (tts?.isLanguageAvailable(pl) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE
                tts?.language = if (ok) pl else Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { id?.let { callbacks.remove(it)?.invoke() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { id?.let { callbacks.remove(it)?.invoke() } }
                })
                ready = true
            }
        }
    }

    /** Mówi tekst; onDone wywoływane po zakończeniu (albo od razu, gdy TTS niedostępny). */
    @Synchronized
    fun say(text: String, onDone: (() -> Unit)? = null) {
        val t = tts
        if (!ready || t == null || text.isBlank()) { onDone?.invoke(); return }
        val id = "hp" + (++counter)
        if (onDone != null) callbacks[id] = onDone
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null; ready = false
        callbacks.values.forEach { try { it() } catch (_: Exception) {} }
        callbacks.clear()
    }
}
