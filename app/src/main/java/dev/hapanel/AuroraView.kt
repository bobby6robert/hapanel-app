package dev.hapanel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.sin

/**
 * „Twarz” Aurory: płynąca zorza polarna na ciemnym tle.
 * Słucha – fale reagują na głos; myśli – spokojne falowanie; mówi – pulsowanie w rytm wypowiedzi.
 */
class AuroraView(c: Context) : View(c) {
    private val ribbon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bg = Paint()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CFE8F0")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5F7083")
        textAlign = Paint.Align.CENTER
    }
    private val path = Path()
    private var t = 0f
    private var lvl = 0f
    private var lastW = 0
    private var lastH = 0

    // kolory wstęg: zieleń, turkus, fiolet, róż (jak prawdziwa zorza)
    private val colors = intArrayOf(
        Color.parseColor("#3DFFB0"), Color.parseColor("#2EC8FF"),
        Color.parseColor("#9B6BFF"), Color.parseColor("#FF6BD6"))

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        lastW = w; lastH = h
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.parseColor("#F2030810"), Color.parseColor("#F20A1424"), Color.parseColor("#F2050B12")),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        text.textSize = h * 0.055f
        hint.textSize = h * 0.028f
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        cv.drawRect(0f, 0f, w, h, bg)

        val st = Voice.state
        val target = when (st) {
            Voice.State.LISTENING -> 0.15f + Voice.level * 1.6f
            Voice.State.SPEAKING -> 0.2f + Voice.level * 1.2f
            Voice.State.THINKING -> 0.25f
            else -> 0.1f
        }
        lvl += (target - lvl) * 0.15f
        val speed = when (st) { Voice.State.THINKING -> 2.2f; Voice.State.SPEAKING -> 1.4f; else -> 1f }
        t += 0.016f * speed

        // poświata pod zorzą
        val gy = h * 0.52f
        glow.shader = RadialGradient(w / 2f, gy, maxOf(w, h) * (0.45f + lvl * 0.15f),
            intArrayOf(Color.argb((60 + lvl * 80).toInt().coerceAtMost(160), 60, 220, 190), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, w, h, glow)

        for (i in colors.indices) drawRibbon(cv, w, h, i)

        val cap = Voice.caption
        if (cap.isNotBlank()) cv.drawText(cap, w / 2f, h * 0.86f, text)
        cv.drawText("Dotknij, aby zakończyć rozmowę", w / 2f, h * 0.94f, hint)

        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }

    private fun drawRibbon(cv: Canvas, w: Float, h: Float, i: Int) {
        val phase = i * 1.7f
        val mid = h * (0.38f + i * 0.055f)
        val amp = h * (0.05f + 0.03f * i) * (0.6f + lvl * 1.4f)
        val thick = h * (0.10f + 0.04f * i) * (0.8f + lvl * 0.8f)
        val step = maxOf(8f, w / 90f)
        path.reset()
        var x = 0f
        var first = true
        while (x <= w + step) {
            val y = mid + amp * sin(x / w * 6.3f * (1.1f + i * 0.25f) + t * (1.3f + i * 0.4f) + phase) +
                amp * 0.45f * sin(x / w * 13f + t * 2.1f - phase)
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            x += step
        }
        x = w + step
        while (x >= -step) {
            val y = mid + thick + amp * 0.6f * sin(x / w * 5.1f + t * (1.0f + i * 0.3f) + phase * 0.7f)
            path.lineTo(x, y)
            x -= step
        }
        path.close()
        val c = colors[i]
        val a = (70 + lvl * 90).toInt().coerceIn(40, 190)
        ribbon.shader = LinearGradient(0f, mid - amp, 0f, mid + thick + amp,
            intArrayOf(Color.argb(a, Color.red(c), Color.green(c), Color.blue(c)), Color.argb(a / 3, Color.red(c), Color.green(c), Color.blue(c)), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        cv.drawPath(path, ribbon)
    }
}
