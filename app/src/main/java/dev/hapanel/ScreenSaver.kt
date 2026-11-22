package dev.hapanel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 2.12.1 Wygaszacz ekranu: zegar z pogodą, zdjęcia rodziny, kamery albo czarny ekran.
 * Listę zdjęć, adresy kamer i pogodę przysyła integracja HA (/saver). Dotknięcie wraca do dashboardu.
 */
class ScreenSaver(ctx: Context) : FrameLayout(ctx) {
    private val exec = Executors.newFixedThreadPool(2)
    private val dm = resources.displayMetrics
    private fun dp(v: Int) = (v * dm.density).toInt()

    private val photo = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    private val grid = GridLayout(ctx)
    private val camViews = mutableListOf<ImageView>()
    private val bigClock = LinearLayout(ctx)
    private val weatherV = TextView(ctx)
    private val infoV = TextView(ctx)
    private val smallClock = LinearLayout(ctx)
    private val smallWeather = TextView(ctx)
    private var photoIdx = 0
    private var running = false
    private var mode = ""

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            when (mode) {
                "photos" -> nextPhoto()
                "cameras" -> refreshCams()
            }
            val delay = if (mode == "cameras") Prefs.saverCamSec * 1000L else Prefs.saverPhotoSec * 1000L
            postDelayed(this, delay.coerceAtLeast(2000L))
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true
        setOnClickListener { Controller.wake() }
        addView(photo, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        grid.columnCount = 2
        addView(grid, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // duży zegar (tryb „zegar i pogoda”)
        bigClock.orientation = LinearLayout.VERTICAL
        bigClock.gravity = Gravity.CENTER
        bigClock.addView(TextClock(ctx).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"; textSize = 120f
            setTextColor(Color.parseColor("#D8E6F0")); typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            gravity = Gravity.CENTER
        })
        bigClock.addView(TextClock(ctx).apply {
            format24Hour = "EEEE, d MMMM"; format12Hour = "EEEE, d MMMM"; textSize = 28f
            setTextColor(Color.parseColor("#7F93A6")); gravity = Gravity.CENTER
        })
        weatherV.textSize = 34f
        weatherV.setTextColor(Color.parseColor("#6FA8C7"))
        weatherV.gravity = Gravity.CENTER
        weatherV.setPadding(0, dp(26), 0, 0)
        infoV.textSize = 22f
        infoV.setTextColor(Color.parseColor("#5F7083"))
        infoV.gravity = Gravity.CENTER
        infoV.setPadding(dp(24), dp(12), dp(24), 0)
        bigClock.addView(weatherV)
        bigClock.addView(infoV)
        addView(bigClock, LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // mały zegar w rogu (na zdjęciach i kamerach)
        smallClock.orientation = LinearLayout.VERTICAL
        smallClock.gravity = Gravity.END
        smallClock.setPadding(dp(18), dp(10), dp(18), dp(10))
        smallClock.setBackgroundColor(Color.argb(110, 0, 0, 0))
        smallClock.addView(TextClock(ctx).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"; textSize = 44f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })
        smallWeather.textSize = 18f
        smallWeather.setTextColor(Color.parseColor("#DDE7EE"))
        smallClock.addView(smallWeather)
        val lp = LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
        lp.setMargins(dp(20), 0, 0, dp(20))   // lewy dolny róg – prawy zasłania ramka
        addView(smallClock, lp)
        visibility = View.GONE
    }

    /** Pokazuje wygaszacz w wybranym trybie. */
    fun start() {
        mode = Prefs.saverType
        updateInfo()
        photo.visibility = if (mode == "photos") View.VISIBLE else View.GONE
        grid.visibility = if (mode == "cameras") View.VISIBLE else View.GONE
        bigClock.visibility = if (mode == "clock") View.VISIBLE else View.GONE
        smallClock.visibility = if (mode == "photos" || mode == "cameras") View.VISIBLE else View.GONE
        if (mode == "photos" && Controller.saverPhotos.isEmpty()) {   // brak zdjęć → zegar
            photo.visibility = View.GONE; smallClock.visibility = View.GONE; bigClock.visibility = View.VISIBLE
        }
        if (mode == "cameras") buildGrid()
        visibility = View.VISIBLE
        if (!running) { running = true; removeCallbacks(tick); post(tick) }
    }

    fun stop() {
        running = false
        removeCallbacks(tick)
        visibility = View.GONE
        photo.setImageDrawable(null)
        camViews.forEach { it.setImageDrawable(null) }
    }

    fun updateInfo() {
        val w = Controller.saverWeather.ifBlank {
            listOfNotNull(Controller.clockOut.takeIf { it.isNotBlank() }?.let { L.t("na zewnątrz $it°", "outside $it°") },
                Controller.clockIn.takeIf { it.isNotBlank() }?.let { L.t("w domu $it°", "inside $it°") }).joinToString("   ·   ")
        }
        weatherV.text = w
        weatherV.visibility = if (w.isBlank()) View.GONE else View.VISIBLE
        smallWeather.text = w
        smallWeather.visibility = if (w.isBlank()) View.GONE else View.VISIBLE
        infoV.text = Controller.clockInfo
        infoV.visibility = if (Controller.clockInfo.isBlank()) View.GONE else View.VISIBLE
    }

    private fun buildGrid() {
        val cams = Controller.saverCams
        grid.removeAllViews(); camViews.clear()
        val n = cams.size.coerceIn(1, 4)
        grid.columnCount = if (n == 1) 1 else 2
        val rows = if (n <= 2) 1 else 2
        val w = dm.widthPixels / grid.columnCount
        val h = dm.heightPixels / rows
        if (n == 2 && dm.heightPixels > dm.widthPixels) { grid.columnCount = 1 }
        repeat(n) {
            val iv = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.BLACK) }
            val p = GridLayout.LayoutParams()
            if (n == 2 && grid.columnCount == 1) { p.width = dm.widthPixels; p.height = dm.heightPixels / 2 }
            else { p.width = w; p.height = h }
            grid.addView(iv, p); camViews += iv
        }
    }

    private fun nextPhoto() {
        val list = Controller.saverPhotos
        if (list.isEmpty()) return
        val url = if (Prefs.saverShuffle) list.random() else list[photoIdx++ % list.size]
        load(url, dm.widthPixels, dm.heightPixels) { bm ->
            photo.alpha = 0f
            photo.setImageBitmap(bm)
            photo.animate().alpha(1f).setDuration(900).start()
        }
    }

    private fun refreshCams() {
        Controller.saverCams.take(camViews.size).forEachIndexed { i, url ->
            load(url, dm.widthPixels / 2, dm.heightPixels / 2) { bm -> camViews.getOrNull(i)?.setImageBitmap(bm) }
        }
    }

    private fun load(url: String, w: Int, h: Int, done: (Bitmap) -> Unit) {
        exec.execute {
            try {
                val bytes = (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 8000; readTimeout = 15000
                    inputStream.use { it.readBytes() }
                }
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (o.outWidth / (s * 2) >= w && o.outHeight / (s * 2) >= h) s *= 2
                val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
                    ?: return@execute
                post { if (running) done(bm) }
            } catch (_: Exception) {}
        }
    }
}
