package dev.hapanel

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import java.util.Locale

/** Ekran ustawień w stylu Aurora: ciemne tło, kafelki, akcenty morski / bursztyn / czerwony. */
class SettingsActivity : Activity() {
    private class F(val label: String, val help: String, val get: () -> String, val set: (String) -> Unit,
                    val num: Boolean = false, val secret: Boolean = false, val multiline: Boolean = false)
    private class O(val label: String, val get: () -> Boolean, val set: (Boolean) -> Unit)
    private class Check(val label: String, val ok: () -> Boolean, val fix: (() -> Unit)? = null, val required: Boolean = true)

    private val BG1 = Color.parseColor("#0E1A22")
    private val BG2 = Color.parseColor("#090D12")
    private val CARD = Color.parseColor("#131A22")
    private val INPUT = Color.parseColor("#0D1218")
    private val BORDER = Color.parseColor("#223040")
    private val TEXT = Color.parseColor("#E8F1F8")
    private val MUTED = Color.parseColor("#8A9BAD")
    private val MINT = Color.parseColor("#7CF3D4")
    private val AMBER = Color.parseColor("#FFC46B")
    private val RED = Color.parseColor("#FF6B6B")

    private lateinit var checkBox: LinearLayout
    private lateinit var summary: TextView
    private lateinit var ipChip: TextView
    private lateinit var pairChip: TextView
    private val fieldViews = mutableListOf<Pair<F, EditText>>()
    private val switchViews = mutableListOf<Pair<O, Switch>>()
    private var tts: TextToSpeech? = null
    private var plAvailable: Boolean? = null

    // ---------- pomocnicze ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a shl 24)

    private fun rounded(fill: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(fill)
        d.cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke)
        return d
    }

    private fun oval(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setShape(GradientDrawable.OVAL)
        d.setColor(color)
        return d
    }

    private fun label(t: String, size: Float = 13f, color: Int = MUTED, bold: Boolean = false): TextView {
        val v = TextView(this)
        v.text = t
        v.textSize = size
        v.setTextColor(color)
        if (bold) v.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        return v
    }

    private fun lp(w: Int, h: Int, top: Int = 0, bottom: Int = 0, right: Int = 0): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(w, h)
        p.topMargin = top; p.bottomMargin = bottom; p.rightMargin = right
        return p
    }

    private fun chip(): TextView {
        val v = TextView(this)
        v.textSize = 13f
        v.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        v.setPadding(dp(14), dp(7), dp(14), dp(7))
        return v
    }

    private fun pill(t: String, filled: Boolean, accent: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.setAllCaps(false)
        b.textSize = 15f
        b.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        b.stateListAnimator = null
        b.minimumHeight = 0
        b.minHeight = 0
        b.setPadding(dp(18), dp(10), dp(18), dp(10))
        if (filled) {
            b.background = rounded(accent, 14)
            b.setTextColor(Color.parseColor("#06201A"))
        } else {
            b.background = rounded(withAlpha(accent, 24), 14, accent, 1)
            b.setTextColor(accent)
        }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun card(title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(20), dp(18), dp(20), dp(18))
        c.background = rounded(CARD, 20, BORDER, 1)
        c.layoutParams = lp(MATCH_PARENT, WRAP_CONTENT, bottom = dp(16))
        c.tag = title
        val h = TextView(this)
        h.text = title.uppercase(Locale("pl", "PL"))
        h.textSize = 12f
        h.letterSpacing = 0.14f
        h.setTextColor(MINT)
        h.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        h.setPadding(0, 0, 0, dp(8))
        c.addView(h)
        return c
    }

    private fun divider(): View {
        val v = View(this)
        v.setBackgroundColor(withAlpha(BORDER, 170))
        v.layoutParams = lp(MATCH_PARENT, dp(1))
        return v
    }

    private fun addField(parent: LinearLayout, f: F, track: Boolean = true): EditText {
        val t = label(f.label, 14f, TEXT, true)
        t.setPadding(0, dp(12), 0, 0)
        parent.addView(t)
        if (f.help.isNotEmpty()) {
            val h = label(f.help, 12f, MUTED)
            h.setPadding(0, dp(2), 0, dp(6))
            parent.addView(h)
        }
        val e = EditText(this)
        e.setText(f.get())
        e.textSize = 16f
        e.setTextColor(TEXT)
        e.setHintTextColor(MUTED)
        e.background = rounded(INPUT, 12, BORDER, 1)
        e.setPadding(dp(14), dp(12), dp(14), dp(12))
        e.inputType = when {
            f.num -> InputType.TYPE_CLASS_NUMBER
            f.secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            f.multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        if (f.multiline) { e.minLines = 3; e.gravity = Gravity.TOP or Gravity.START }
        parent.addView(e, lp(MATCH_PARENT, WRAP_CONTENT))
        if (track) fieldViews.add(Pair(f, e))
        return e
    }

    private fun addSwitch(parent: LinearLayout, o: O) {
        val sw = Switch(this)
        sw.text = o.label
        sw.textSize = 16f
        sw.setTextColor(TEXT)
        sw.isChecked = o.get()
        val st = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        sw.thumbTintList = ColorStateList(st, intArrayOf(MINT, Color.parseColor("#7C8A99")))
        sw.trackTintList = ColorStateList(st, intArrayOf(withAlpha(MINT, 110), Color.parseColor("#2A3541")))
        sw.setPadding(0, dp(12), 0, dp(12))
        parent.addView(sw, lp(MATCH_PARENT, WRAP_CONTENT))
        switchViews.add(Pair(o, sw))
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun isDefaultHome(): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == packageName
    }

    private fun versionName(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    // ---------- lista kontrolna ----------
    private fun checks(): List<Check> = listOf(
        Check(L.t("Mikrofon (rozmowa przez domofon)", "Microphone (intercom talk)"), { granted(Manifest.permission.RECORD_AUDIO) },
            { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2) }),
        Check(L.t("Kamera (wykrywanie ruchu)", "Camera (motion detection)"), { !Prefs.motionEnabled || granted(Manifest.permission.CAMERA) },
            { requestPermissions(arrayOf(Manifest.permission.CAMERA), 2) }),
        Check(L.t("Autostart po restarcie (wyświetlanie nad innymi aplikacjami)", "Autostart after reboot (display over other apps)"), { Settings.canDrawOverlays(this) },
            { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }),
        Check(L.t("Bateria bez optymalizacji (system nie usypia aplikacji)", "Battery unrestricted (system does not put the app to sleep)"),
            { (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) },
            {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }),
        Check(L.t("Polski głos komunikatów (TTS)", "Voice for announcements (TTS)"), { plAvailable == true },
            {
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }),
        Check(L.t("Adres dashboardu przez HTTPS (wymagane dla mikrofonu)", "Dashboard over HTTPS (required for the microphone)"), { Prefs.dashboardUrl.startsWith("https://") }, null),
        Check(L.t("Przycisk Home wraca do panelu (HA Panel jako ekran główny)", "Home button returns to the panel (HA Panel as home screen)"), { isDefaultHome() },
            { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }, required = false),
        Check(L.t("Instalacja aktualizacji z HA dozwolona", "Installing updates from HA allowed"), { packageManager.canRequestPackageInstalls() },
            { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }, required = false),
        Check(L.t("PIN do ustawień ustawiony", "Settings PIN set"), { Pin.isSet() }, null, required = false),
        Check(L.t("Pola „Adres HA” i „token” puste (używasz integracji, bez dubli encji)", "“HA address” and “token” empty (using the integration, no duplicate entities)"), { Prefs.haUrl.isBlank() }, null, required = false),
    )

    @Suppress("DEPRECATION")
    private fun refreshChecks() {
        checkBox.removeAllViews()
        val list = checks()
        var bad = 0
        list.forEachIndexed { idx, c ->
            val ok = try { c.ok() } catch (_: Exception) { false }
            if (!ok && c.required) bad++
            val color = if (ok) MINT else if (c.required) RED else AMBER
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(10), 0, dp(10))
            val dot = View(this)
            dot.background = oval(color)
            row.addView(dot, lp(dp(10), dp(10), right = dp(14)))
            row.addView(label(c.label, 15f, TEXT), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val fx = c.fix
            if (!ok && fx != null) row.addView(pill("Napraw", false, color) { fx() })
            checkBox.addView(row)
            if (idx < list.size - 1) checkBox.addView(divider())
        }
        val good = bad == 0
        summary.text = if (good) L.t("✓ Gotowe do pracy", "✓ Ready") else L.t("Do poprawy: $bad", "To fix: $bad")
        summary.setTextColor(if (good) MINT else RED)
        summary.background = rounded(withAlpha(if (good) MINT else RED, 36), 999)
        val ip = try {
            val a = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager).connectionInfo.ipAddress
            "${a and 0xff}.${(a shr 8) and 0xff}.${(a shr 16) and 0xff}.${(a shr 24) and 0xff}"
        } catch (_: Exception) { "?" }
        ipChip.text = "Tablet: $ip : ${Prefs.port}"
        ipChip.setTextColor(MUTED)
        ipChip.background = rounded(withAlpha(MUTED, 30), 999)
        if (Prefs.apiToken.isEmpty()) {
            val c = Prefs.pairCode
            pairChip.text = L.t("Kod parowania: ${c.substring(0, 3)} ${c.substring(3)}", "Pairing code: ${c.substring(0, 3)} ${c.substring(3)}")
            pairChip.setTextColor(AMBER)
            pairChip.background = rounded(withAlpha(AMBER, 36), 999)
            pairChip.visibility = View.VISIBLE
        } else pairChip.visibility = View.GONE
    }

    // ---------- budowa ekranu ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Prefs.init(this)
        window.statusBarColor = BG2
        window.navigationBarColor = BG2
        tts = TextToSpeech(this) { st ->
            val r = if (st == TextToSpeech.SUCCESS) tts?.isLanguageAvailable(Locale("pl", "PL")) else null
            plAvailable = r != null && r >= TextToSpeech.LANG_AVAILABLE
            runOnUiThread { refreshChecks() }
        }

        val root = FrameLayout(this)
        root.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(BG1, BG2))
        val sv = ScrollView(this)
        sv.isFillViewport = true
        sv.isVerticalScrollBarEnabled = false
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(20), dp(28), dp(20), dp(32))
        val maxW = minOf(resources.displayMetrics.widthPixels, dp(820))
        sv.addView(content, FrameLayout.LayoutParams(maxW, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        root.addView(sv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        // nagłówek
        val title = TextView(this)
        title.text = "HA Panel"
        title.textSize = 32f
        title.setTextColor(TEXT)
        title.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        content.addView(title)
        val sub = label(L.t("Panel ścienny dla Home Assistant · wersja ${versionName()}", "Wall panel for Home Assistant · version ${versionName()}"), 14f)
        sub.setPadding(0, dp(2), 0, dp(14))
        content.addView(sub)
        // 2.13: język / language
        val langRow = LinearLayout(this); langRow.orientation = LinearLayout.HORIZONTAL; langRow.gravity = Gravity.CENTER_VERTICAL
        langRow.addView(label("Język / Language:", 13f), lp(WRAP_CONTENT, WRAP_CONTENT, right = dp(10)))
        listOf("system" to L.t("Systemowy", "System"), "pl" to "Polski", "en" to "English").forEach { (code, name) ->
            langRow.addView(pill(name, Prefs.language == code, MINT) {
                if (Prefs.language != code) {
                    Prefs.language = code
                    try { Events.send("settings", "data" to ConfigApply.export()) } catch (_: Exception) {}
                    Speech.shutdown(); restartService(); recreate()
                }
            }, lp(WRAP_CONTENT, WRAP_CONTENT, right = dp(8)))
        }
        val langScroll = HorizontalScrollView(this); langScroll.isHorizontalScrollBarEnabled = false; langScroll.addView(langRow)
        content.addView(langScroll, lp(MATCH_PARENT, WRAP_CONTENT, bottom = dp(14)))
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        summary = chip(); ipChip = chip(); pairChip = chip()
        chips.addView(summary, lp(WRAP_CONTENT, WRAP_CONTENT, right = dp(10)))
        chips.addView(ipChip, lp(WRAP_CONTENT, WRAP_CONTENT, right = dp(10)))
        chips.addView(pairChip, lp(WRAP_CONTENT, WRAP_CONTENT))
        content.addView(chips, lp(MATCH_PARENT, WRAP_CONTENT, bottom = dp(18)))
        val tabsAt = content.childCount

        // lista kontrolna
        val cCheck = card(L.t("Lista kontrolna", "Checklist"))
        checkBox = LinearLayout(this)
        checkBox.orientation = LinearLayout.VERTICAL
        cCheck.addView(checkBox)
        cCheck.addView(pill(L.t("Test mowy", "Speech test"), false, MINT) {
            tts?.language = if (plAvailable == true) Locale("pl", "PL") else Locale.getDefault()
            tts?.speak(L.t("To jest test komunikatu głosowego", "This is a voice announcement test"), TextToSpeech.QUEUE_FLUSH, null, "test")
        }, lp(WRAP_CONTENT, WRAP_CONTENT, top = dp(14)))
        content.addView(cCheck)

        // dashboard
        val cDash = card("Dashboard")
        addField(cDash, F(L.t("Adres dashboardu", "Dashboard address"), L.t("Główny. Dla mikrofonu musi być https://, np. https://ha.example.com/lovelace/0", "Main. For the microphone it must be https://, e.g. https://ha.example.com/lovelace/0"),
            { Prefs.dashboardUrl }, { Prefs.dashboardUrl = it.trim() }))
        addField(cDash, F(L.t("Adresy awaryjne", "Fallback addresses"), L.t("Jeden w linii. Użyte, gdy główny nie działa (np. lokalny http://192.168.1.10:8123). Na lokalnym nie działa mikrofon", "One per line. Used when the main one fails (e.g. local http://192.168.1.10:8123). The microphone does not work on a local address"),
            { Prefs.fallbackUrls }, { Prefs.fallbackUrls = it.trim() }, multiline = true))
        addField(cDash, F(L.t("Wróć do dashboardu po (sekundy)", "Back to dashboard after (seconds)"), "0 = nigdy",
            { Prefs.homeAfterSec.toString() }, { Prefs.homeAfterSec = it.toIntOrNull() ?: 0 }, num = true))
        content.addView(cDash)

        // Home Assistant
        val cHa = card("Home Assistant")
        addField(cHa, F(L.t("Adres HA do raportowania", "HA address for reporting"), L.t("Zostaw puste, jeśli używasz integracji HA Panel (zalecane)", "Leave empty if you use the HA Panel integration (recommended)"),
            { Prefs.haUrl }, { Prefs.haUrl = it.trim() }))
        addField(cHa, F(L.t("Token dostępu HA", "HA access token"), L.t("Zostaw puste, jeśli używasz integracji", "Leave empty if you use the integration"),
            { Prefs.haToken }, { Prefs.haToken = it.trim() }, secret = true))
        content.addView(cHa)

        // tablet i sieć
        val cNet = card(L.t("Tablet i sieć", "Tablet and network"))
        addField(cNet, F(L.t("Nazwa urządzenia", "Device name"), L.t("Do nazw encji przy raportowaniu bez integracji", "Used for entity names when reporting without the integration"),
            { Prefs.deviceName }, { Prefs.deviceName = it.trim().ifBlank { "tablet" } }))
        addField(cNet, F(L.t("Port serwera HTTP", "HTTP server port"), L.t("Domyślnie 8765", "Default 8765"),
            { Prefs.port.toString() }, { Prefs.port = it.toIntOrNull() ?: 8765 }, num = true))
        addField(cNet, F(L.t("Token API", "API token"), L.t("Ustawiany automatycznie przy parowaniu z HA", "Set automatically when pairing with HA"),
            { Prefs.apiToken }, { Prefs.apiToken = it.trim() }, secret = true))
        content.addView(cNet)

        // ekran i czujniki
        val cScr = card(L.t("Ekran i czujniki", "Screen and sensors"))
        addField(cScr, F(L.t("Uśpij ekran po (sekundy)", "Sleep screen after (seconds)"), L.t("0 = nigdy. Po bezruchu i bez dotyku zostaje czarna nakładka", "0 = never. After no motion and no touch a black overlay is shown"),
            { Prefs.sleepAfterSec.toString() }, { Prefs.sleepAfterSec = it.toIntOrNull() ?: 0 }, num = true))
        addField(cScr, F(L.t("Czułość ruchu", "Motion sensitivity"), L.t("Mniej = czulej, domyślnie 5", "Lower = more sensitive, default 5"),
            { Prefs.motionThreshold.toString() }, { Prefs.motionThreshold = it.toIntOrNull() ?: 5 }, num = true))
        val sp = View(this); cScr.addView(sp, lp(MATCH_PARENT, dp(8)))
        addSwitch(cScr, O(L.t("Wykrywanie ruchu z przedniej kamery", "Motion detection with the front camera"), { Prefs.motionEnabled }, { Prefs.motionEnabled = it }))
        addSwitch(cScr, O(L.t("Wybudzaj ekran po ruchu", "Wake screen on motion"), { Prefs.wakeOnMotion }, { Prefs.wakeOnMotion = it }))
        addSwitch(cScr, O(L.t("Automatyczna jasność (czujnik światła)", "Auto brightness (light sensor)"), { Prefs.autoBrightness }, { Prefs.autoBrightness = it }))
        addSwitch(cScr, O(L.t("Blokada ekranu (przypięcie aplikacji)", "Screen lock (app pinning)"), { Prefs.lockTask }, { Prefs.lockTask = it }))
        addSwitch(cScr, O(L.t("Ignoruj błędy certyfikatu SSL", "Ignore SSL certificate errors"), { Prefs.ignoreSsl }, { Prefs.ignoreSsl = it }))
        content.addView(cScr)

        // wygląd i dopasowanie
        val cLook = card(L.t("Wygląd i dopasowanie", "Look and fit"))
        addField(cLook, F(L.t("Powiększenie tekstu (%)", "Text zoom (%)"), L.t("100 = bez zmian. Pomaga na małych ekranach (50-250)", "100 = unchanged. Helps on small screens (50-250)"),
            { Prefs.zoom.toString() }, { Prefs.zoom = (it.toIntOrNull() ?: 100).coerceIn(50, 250) }, num = true))
        addField(cLook, F(L.t("Własny CSS", "Custom CSS"), L.t("Wstrzykiwany do każdej strony, np. :root { --primary-color: #7CF3D4; }", "Injected into every page, e.g. :root { --primary-color: #7CF3D4; }"),
            { Prefs.injectCss }, { Prefs.injectCss = it }, multiline = true))
        addField(cLook, F(L.t("Własny JavaScript", "Custom JavaScript"), L.t("Uruchamiany po załadowaniu strony. Używaj ostrożnie", "Run after the page loads. Use with care"),
            { Prefs.injectJs }, { Prefs.injectJs = it }, multiline = true))
        content.addView(cLook)

        // tryb nocny
        val cNight = card(L.t("Tryb nocny (zegar)", "Night mode (clock)"))
        addSwitch(cScr, O(L.t("Automatyczne sprzątanie pamięci (co 3 h usypia aplikacje w tle, w niedzielę o 3:30 restart panelu)", "Automatic memory cleanup (every 3 h puts background apps to sleep, Sunday 3:30 panel restart)"), { Prefs.autoClean }, { Prefs.autoClean = it }))
        addSwitch(cScr, O(L.t("Oszczędzanie baterii (na baterii i w nocy ekran naprawdę gaśnie, tablet zasypia)", "Battery saving (on battery and at night the screen really turns off, the tablet sleeps)"),
            { Prefs.saverMode != "never" }, { Prefs.saverMode = if (it) "auto" else "never" }))
        addSwitch(cNight, O(L.t("Włącz tryb nocny w aplikacji", "Enable night mode in the app"), { Prefs.nightEnabled }, { Prefs.nightEnabled = it }))
        val timeRe = Regex("\\d{1,2}:\\d{2}")
        addField(cNight, F(L.t("Początek", "Start"), L.t("Godzina w formacie GG:MM, np. 22:00", "Time as HH:MM, e.g. 22:00"),
            { Prefs.nightStart }, { if (timeRe.matches(it.trim())) Prefs.nightStart = it.trim() }))
        addField(cNight, F("Koniec", "np. 06:30",
            { Prefs.nightEnd }, { if (timeRe.matches(it.trim())) Prefs.nightEnd = it.trim() }))
        addField(cNight, F(L.t("Jasność w nocy (1-255)", "Night brightness (1-255)"), L.t("Dotyczy zegara i dashboardu w nocy", "Applies to the clock and dashboard at night"),
            { Prefs.nightBrightness.toString() }, { Prefs.nightBrightness = (it.toIntOrNull() ?: 10).coerceIn(1, 255) }, num = true))
        addField(cNight, F(L.t("Powrót do zegara po (sekundy)", "Back to the clock after (seconds)"), L.t("Po wybudzeniu dotykiem lub ruchem. Minimum 10", "After waking by touch or motion. Minimum 10"),
            { Prefs.nightWakeSec.toString() }, { Prefs.nightWakeSec = (it.toIntOrNull() ?: 60).coerceAtLeast(10) }, num = true))
        content.addView(cNight)

        // Aurora – rozmowa głosowa (tryb rodzica: ustawienia są za PIN-em)
        val cVoice = card(L.t("Aurora – rozmowa głosowa", "Aurora – voice conversation"))
        cVoice.addView(label(L.t("Rozmowa po komunikatach, przycisk ✦, słowo wybudzające i machnięcie ręką. Rozumienie mowy i odpowiedzi robi Home Assistant (integracja HA Panel 1.6+).", "Conversation after announcements, the ✦ button, wake word and hand wave. Speech understanding and replies are done by Home Assistant (HA Panel integration 1.6+)."), 13f))
        addSwitch(cVoice, O(L.t("Rozmowa z Aurorą włączona", "Conversation with Aurora on"), { Prefs.voiceEnabled }, { Prefs.voiceEnabled = it }))
        addSwitch(cVoice, O(L.t("Przycisk ✦ „Porozmawiaj z Aurorą”", "✦ button “Talk to Aurora”"), { Prefs.talkButton }, { Prefs.talkButton = it }))
        addSwitch(cVoice, O(L.t("Dwa machnięcia ręką = rozmowa", "Two hand waves = conversation"), { Prefs.proximityTalk }, { Prefs.proximityTalk = it }))
        addSwitch(cVoice, O(L.t("Słowo wybudzające (offline, pobiera model ~50 MB)", "Wake word (offline, downloads a ~50 MB model)"), { Prefs.wakeWord }, { Prefs.wakeWord = it }))
        addSwitch(cVoice, O(L.t("Słowo „Dom” – szybkie sterowanie domem (np. „Dom, zapal światło w kuchni”)", "Home word – quick house control (e.g. “Home, kitchen light on”)"), { Prefs.homeWord }, { Prefs.homeWord = it }))
        addSwitch(cVoice, O("„Aurora” tylko, gdy kamera widzi ruch", { Prefs.wakePresenceOnly }, { Prefs.wakePresenceOnly = it }))
        addSwitch(cVoice, O(L.t("Po komunikacie z HA czekaj na odpowiedź (domyślnie nie)", "Wait for a reply after an HA announcement (default off)"), { Prefs.announceListen }, { Prefs.announceListen = it }))
        addSwitch(cVoice, O("Milcz, gdy nikogo nie ma w domu", { Prefs.awayMute }, { Prefs.awayMute = it }))
        addField(cVoice, F(L.t("Czułość słowa wybudzającego", "Wake word sensitivity"), L.t("1 = mało czuła (najmniej pomyłek), 2 = średnia, 3 = bardzo czuła", "1 = low (fewest false wakes), 2 = medium, 3 = high"),
            { Prefs.wakeSensitivity.toString() }, { Prefs.wakeSensitivity = (it.toIntOrNull() ?: 2).coerceIn(1, 3) }, num = true))
        addSwitch(cVoice, O(L.t("Menu: 5 dotknięć w dowolnym rogu ekranu", "Menu: 5 taps in any screen corner"), { Prefs.menuAnyCorner }, { Prefs.menuAnyCorner = it }))
        addSwitch(cVoice, O(L.t("Menu: przytrzymanie dwoma palcami przez 2 s", "Menu: hold two fingers for 2 s"), { Prefs.menuTwoFinger }, { Prefs.menuTwoFinger = it }))
        addField(cVoice, F(L.t("Słowo wybudzające", "Wake word"), L.t("Domyślnie: aurora (małe litery)", "Default: aurora (lower case)"),
            { Prefs.wakeWordText }, { if (it.isNotBlank()) Prefs.wakeWordText = it.trim().lowercase() }))
        addField(cVoice, F(L.t("Cisza nocna – od", "Quiet hours – from"), L.t("Słowo wybudzające nie działa, np. 22:00", "Wake word is off, e.g. 22:00"),
            { Prefs.wakeQuietStart }, { if (timeRe.matches(it.trim())) Prefs.wakeQuietStart = it.trim() }))
        addField(cVoice, F(L.t("Cisza nocna – do", "Quiet hours – until"), "np. 06:30",
            { Prefs.wakeQuietEnd }, { if (timeRe.matches(it.trim())) Prefs.wakeQuietEnd = it.trim() }))
        addField(cVoice, F(L.t("Czekaj na odpowiedź (sekundy)", "Wait for a reply (seconds)"), L.t("Po komunikacie: ile czekać, aż ktoś zacznie mówić (3-30)", "After an announcement: how long to wait for someone to speak (3-30)"),
            { Prefs.listenWaitSec.toString() }, { Prefs.listenWaitSec = (it.toIntOrNull() ?: 8).coerceIn(3, 30) }, num = true))
        addField(cVoice, F(L.t("Koniec wypowiedzi po ciszy (ms)", "End of speech after silence (ms)"), L.t("Domyślnie 1200", "Default 1200"),
            { Prefs.silenceMs.toString() }, { Prefs.silenceMs = (it.toIntOrNull() ?: 1200).coerceIn(500, 4000) }, num = true))
        cVoice.addView(pill(L.t("Test rozmowy", "Conversation test"), false, MINT) { Voice.start(this, source = "button") }, lp(WRAP_CONTENT, WRAP_CONTENT, top = dp(14)))
        content.addView(cVoice)

        val cRec = card(L.t("Nagraj Aurorę – głosy domowników", "Record Aurora – household voices"))
        cRec.addView(label(L.t("Każdy domownik nagrywa słowo „Aurora” 5 razy (zwykłym głosem, z miejsca, z którego zwykle woła). Potem Aurora reaguje na Wasze głosy, a telewizor i obce głosy są odrzucane. Nagrania zostają tylko na tablecie.", "Each household member records the word “Aurora” 5 times (normal voice, from where they usually call). Aurora then reacts to your voices and rejects the TV and unknown voices. Recordings stay on the tablet only."), 13f))
        val recInfo = label(recSummary(), 14f)
        cRec.addView(recInfo)
        val recName = addField(cRec, F(L.t("Kto nagrywa", "Who is recording"), L.t("np. Robert, Ela, Wiki, Błażej, Bartek, gość", "e.g. Robert, Anna, Tom, guest"), { "" }, { }), track = false)
        cRec.addView(pill(L.t("● Nagraj „Aurora”", "● Record “Aurora”"), true, MINT) { recordAurora(recName.text.toString(), recInfo) }, lp(WRAP_CONTENT, WRAP_CONTENT, top = dp(12)))
        cRec.addView(pill(L.t("Usuń wszystkie nagrania", "Delete all recordings"), false, Color.parseColor("#FF8A80")) {
            AlertDialog.Builder(this).setTitle(L.t("Usunąć nagrania?", "Delete recordings?")).setMessage(L.t("Aurora wróci do zwykłego rozpoznawania słowa.", "Aurora will go back to normal word detection."))
                .setPositiveButton(L.t("Usuń", "Delete")) { _, _ -> VoicePrint.deleteAll(this); recInfo.text = recSummary() }
                .setNegativeButton(L.t("Anuluj", "Cancel"), null).show()
        }, lp(WRAP_CONTENT, WRAP_CONTENT, top = dp(8)))
        content.addView(cRec)

        val cSaver = card(L.t("Wygaszacz ekranu", "Screensaver"))
        cSaver.addView(label(L.t("Po bezczynności zamiast dashboardu. Dotknięcie wraca do dashboardu, ruch go nie zamyka. Zdjęcia (folder w HA) i kamery wybierasz w HA – opcje integracji HA Panel.", "Shown instead of the dashboard when idle. A touch returns to the dashboard, motion does not close it. Photos (folder in HA) and cameras are chosen in HA – HA Panel integration options."), 13f))
        addSwitch(cSaver, O(L.t("Wygaszacz włączony", "Screensaver on"), { Prefs.saverEnabled }, { Prefs.saverEnabled = it }))
        val types = listOf("clock", "photos", "cameras", "black")
        addField(cSaver, F(L.t("Rodzaj wygaszacza", "Screensaver type"), L.t("1 = zegar i pogoda, 2 = zdjęcia rodziny, 3 = kamery, 4 = czarny ekran", "1 = clock and weather, 2 = family photos, 3 = cameras, 4 = black screen"),
            { (types.indexOf(Prefs.saverType) + 1).coerceAtLeast(1).toString() },
            { Prefs.saverType = types[((it.toIntOrNull() ?: 1) - 1).coerceIn(0, 3)] }, num = true))
        addField(cSaver, F(L.t("Włącz po (sekundy)", "Start after (seconds)"), L.t("Domyślnie 120 (min. 15)", "Default 120 (min. 15)"),
            { Prefs.saverAfterSec.toString() }, { Prefs.saverAfterSec = (it.toIntOrNull() ?: 120).coerceIn(15, 3600) }, num = true))
        addField(cSaver, F(L.t("Zmiana zdjęcia co (sekundy)", "Change photo every (seconds)"), L.t("Domyślnie 15", "Default 15"),
            { Prefs.saverPhotoSec.toString() }, { Prefs.saverPhotoSec = (it.toIntOrNull() ?: 15).coerceIn(3, 600) }, num = true))
        addField(cSaver, F(L.t("Odświeżanie kamer co (sekundy)", "Refresh cameras every (seconds)"), L.t("Domyślnie 5", "Default 5"),
            { Prefs.saverCamSec.toString() }, { Prefs.saverCamSec = (it.toIntOrNull() ?: 5).coerceIn(2, 60) }, num = true))
        addSwitch(cSaver, O(L.t("Zdjęcia w losowej kolejności", "Photos in random order"), { Prefs.saverShuffle }, { Prefs.saverShuffle = it }))
        cSaver.addView(pill(L.t("Pokaż wygaszacz", "Show screensaver"), false, MINT) { saveAll(); Controller.ui.postDelayed({ Controller.showSaver() }, 800) }, lp(WRAP_CONTENT, WRAP_CONTENT, top = dp(14)))
        content.addView(cSaver)

        val cAnn = card(L.t("Komunikaty i telewizor", "Announcements and TV"))
        cAnn.addView(label(L.t("Na czas komunikatu Aurory HA ścisza telewizor, a potem przywraca głośność. Telewizor wybierasz w HA (opcje integracji HA Panel).", "During Aurora's announcements HA lowers the TV volume and restores it afterwards. Choose the TV in HA (HA Panel integration options)."), 13f))
        addSwitch(cAnn, O(L.t("Ściszaj telewizor", "Duck the TV") + (if (Prefs.duckName.isNotBlank()) " (${Prefs.duckName})" else ""), { Prefs.duckEnabled }, { Prefs.duckEnabled = it }))
        addField(cAnn, F(L.t("Ściszenie telewizora do (%)", "Lower the TV to (%)"), L.t("0-100, domyślnie 15", "0-100, default 15"),
            { Prefs.duckLevel.toString() }, { Prefs.duckLevel = (it.toIntOrNull() ?: 15).coerceIn(0, 100) }, num = true))
        addField(cAnn, F(L.t("Głośność komunikatów (%)", "Announcement volume (%)"), "0 = bez zmiany, np. 70",
            { Prefs.announceVolume.toString() }, { Prefs.announceVolume = (it.toIntOrNull() ?: 0).coerceIn(0, 100) }, num = true))
        content.addView(cAnn)

        // kopia ustawień
        val cBackup = card(L.t("Kopia ustawień", "Settings backup"))
        cBackup.addView(label(L.t("Eksport zawiera ustawienia bez PIN-u, tokenów i adresu webhooka.", "The export contains settings without the PIN, tokens and webhook address."), 13f))
        cBackup.addView(pill(L.t("Kopiuj ustawienia (JSON)", "Copy settings (JSON)"), false, MINT) { exportSettings() }, lp(WRAP_CONTENT, WRAP_CONTENT, top = dp(14)))
        cBackup.addView(pill(L.t("Wklej ustawienia (JSON)", "Paste settings (JSON)"), false, MINT) { importSettings() }, lp(WRAP_CONTENT, WRAP_CONTENT, top = dp(10)))
        content.addView(cBackup)

        // PIN
        val cPin = card(L.t("PIN do ustawień", "Settings PIN"))
        cPin.addView(label(
            if (Pin.isSet()) L.t("PIN jest ustawiony. Chroni wejście w ustawienia (5 dotknięć w rogu). Po 5 błędach blokada na minutę.", "PIN is set. It protects the settings. After 5 wrong tries it locks for a minute.")
            else L.t("Brak PIN-u. Ustaw min. 4 znaki, żeby chronić wejście w ustawienia. Można go też ustawić z Home Assistant.", "No PIN. Set at least 4 characters to protect the settings. It can also be set from Home Assistant."), 13f))
        val curEdit = if (Pin.isSet()) addField(cPin, F(L.t("Obecny PIN", "Current PIN"), "", { "" }, {}, secret = true), track = false) else null
        val newEdit = addField(cPin, F(L.t("Nowy PIN", "New PIN"), L.t("Minimum 4 znaki", "At least 4 characters"), { "" }, {}, secret = true), track = false)
        cPin.addView(pill(L.t("Zapisz PIN", "Save PIN"), false, MINT) { savePin(curEdit, newEdit) }, lp(WRAP_CONTENT, WRAP_CONTENT, top = dp(14)))
        if (Pin.isSet()) cPin.addView(pill(L.t("Usuń PIN", "Remove PIN"), false, RED) { removePin(curEdit) }, lp(WRAP_CONTENT, WRAP_CONTENT, top = dp(10)))
        content.addView(cPin)

        // przyciski
        // 2.12.1: kategorie zamiast jednej długiej listy
        val groups = linkedMapOf(
            L.t("Start", "Start") to listOf(L.t("Lista kontrolna", "Checklist"), "Dashboard", "Home Assistant"),
            L.t("Ekran", "Screen") to listOf(L.t("Ekran i czujniki", "Screen and sensors"), L.t("Wygląd i dopasowanie", "Look and fit"), L.t("Tryb nocny (zegar)", "Night mode (clock)"), L.t("Wygaszacz ekranu", "Screensaver")),
            "Aurora" to listOf(L.t("Aurora – rozmowa głosowa", "Aurora – voice conversation"), L.t("Nagraj Aurorę – głosy domowników", "Record Aurora – household voices")),
            L.t("Komunikaty", "Announcements") to listOf(L.t("Komunikaty i telewizor", "Announcements and TV")),
            L.t("Tablet", "Tablet") to listOf(L.t("Tablet i sieć", "Tablet and network"), L.t("Kopia ustawień", "Settings backup"), L.t("PIN do ustawień", "Settings PIN")))
        val cards = (0 until content.childCount).map { content.getChildAt(it) }.filter { it.tag is String }
        val tabRow = LinearLayout(this); tabRow.orientation = LinearLayout.HORIZONTAL
        val tabBtns = mutableListOf<Pair<String, Button>>()
        fun showGroup(g: String) {
            val names = groups[g] ?: emptyList()
            cards.forEach { c -> c.visibility = if (c.tag in names || groups.values.none { c.tag in it }) View.VISIBLE else View.GONE }
            tabBtns.forEach { (n, b) ->
                val on = n == g
                b.background = if (on) rounded(MINT, 14) else rounded(withAlpha(MINT, 24), 14, MINT, 1)
                b.setTextColor(if (on) Color.parseColor("#06201A") else MINT)
            }
            try { getSharedPreferences("ui", MODE_PRIVATE).edit().putString("tab", g).apply() } catch (_: Exception) {}
        }
        groups.keys.forEach { g ->
            val b = pill(g, false, MINT) { showGroup(g) }
            tabBtns += g to b
            tabRow.addView(b, lp(WRAP_CONTENT, WRAP_CONTENT, right = dp(8)))
        }
        val tabScroll = HorizontalScrollView(this); tabScroll.isHorizontalScrollBarEnabled = false
        tabScroll.addView(tabRow)
        content.addView(tabScroll, tabsAt, lp(MATCH_PARENT, WRAP_CONTENT, bottom = dp(16)))
        val last = try { getSharedPreferences("ui", MODE_PRIVATE).getString("tab", L.t("Start", "Start")) } catch (_: Exception) { L.t("Start", "Start") }
        showGroup(if (last in groups) last!! else L.t("Start", "Start"))

        val save = pill(L.t("Zapisz", "Save"), true, MINT) { saveAll() }
        save.textSize = 17f
        save.setPadding(dp(18), dp(14), dp(18), dp(14))
        content.addView(save, lp(MATCH_PARENT, WRAP_CONTENT, top = dp(4)))
        content.addView(pill(L.t("Zamknij bez zapisu", "Close without saving"), false, MUTED) { finish() },
            lp(MATCH_PARENT, WRAP_CONTENT, top = dp(10)))

        refreshChecks()
    }

    // ---------- kopia ustawień ----------
    private fun exportSettings() {
        val json = ConfigApply.export().toString(2)
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("hapanel", json))
        toast(L.t("Skopiowano do schowka", "Copied to clipboard"))
    }

    private fun importSettings() {
        val input = EditText(this)
        input.minLines = 6
        input.gravity = Gravity.TOP or Gravity.START
        input.hint = L.t("Wklej JSON", "Paste JSON")
        val holder = FrameLayout(this)
        holder.setPadding(dp(20), dp(8), dp(20), 0)
        holder.addView(input, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        android.app.AlertDialog.Builder(this)
            .setTitle(L.t("Importuj ustawienia", "Import settings"))
            .setView(holder)
            .setPositiveButton(L.t("Zastosuj", "Apply")) { _, _ ->
                try {
                    val o = org.json.JSONObject(input.text.toString())
                    val m = mutableMapOf<String, String>()
                    for (k in o.keys()) if (k != "pin" && k != "apiToken") m[k] = o.optString(k)
                    val err = ConfigApply.apply(m)
                    restartService()
                    Controller.loadDashboard()
                    toast(if (err == null) "Zastosowano" else L.t("Błąd: $err", "Error: $err"))
                    recreate()
                } catch (e: Exception) { toast(L.t("Nieprawidłowy JSON", "Invalid JSON")) }
            }
            .setNegativeButton(L.t("Anuluj", "Cancel"), null)
            .show()
    }

    // ---------- PIN ----------
    private fun pinGate(cur: EditText?): Boolean {
        if (!Pin.isSet()) return true
        val lock = Pin.lockRemainingSec()
        if (lock > 0) { toast(L.t("Zbyt wiele prób. Spróbuj za $lock s", "Too many attempts. Try again in $lock s")); return false }
        if (!Pin.verify(cur?.text?.toString() ?: "")) { toast(L.t("Zły PIN", "Wrong PIN")); return false }
        return true
    }

    private fun savePin(cur: EditText?, neu: EditText) {
        if (!pinGate(cur)) return
        val v = neu.text.toString()
        if (v.length < 4) { toast(L.t("PIN musi mieć min. 4 znaki", "The PIN needs at least 4 characters")); return }
        Pin.set(v); toast("PIN zapisany"); recreate()
    }

    private fun removePin(cur: EditText?) {
        if (!pinGate(cur)) return
        Pin.clear(); toast(L.t("PIN usunięty", "PIN removed")); recreate()
    }

    private fun recSummary(): String {
        val p = VoicePrint.people(this)
        if (p.isEmpty()) return L.t("Brak nagrań – Aurora działa jak dotąd.", "No recordings – Aurora works as before.")
        val total = p.values.sum()
        return p.entries.joinToString(", ") { "${it.key.replaceFirstChar(Char::uppercase)}: ${it.value}" } +
            (if (total < 3) L.t("  (potrzeba min. 3 nagrań)", "  (at least 3 recordings needed)") else L.t("  ✓ rozpoznawanie głosów włączone", "  ✓ voice recognition on"))
    }

    private fun recordAurora(who: String, info: TextView) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2); return
        }
        if (who.isBlank()) { Toast.makeText(this, L.t("Wpisz, kto nagrywa", "Enter who is recording"), Toast.LENGTH_SHORT).show(); return }
        val dlg = AlertDialog.Builder(this).setTitle(L.t("Powiedz teraz: „Aurora”", "Say now: “Aurora”")).setMessage(L.t("Nagrywam… (2 sekundy)", "Recording… (2 seconds)")).setCancelable(false).create()
        dlg.show()
        Thread {
            Thread.sleep(300)
            val ok = VoicePrint.recordOne(applicationContext, who)
            runOnUiThread {
                dlg.dismiss()
                info.text = recSummary()
                val n = VoicePrint.people(this)[who.trim().lowercase().replace(Regex("[^a-ząćęłńóśźż0-9]"), "")] ?: 0
                Toast.makeText(this, if (ok) L.t("Zapisano ($n). ", "Saved ($n). ") + (if (n < 5) L.t("Nagraj jeszcze raz.", "Record once more.") else L.t("Wystarczy, dziękuję!", "That's enough, thank you!"))
                    else L.t("Nie usłyszałem – powiedz wyraźnie „Aurora” i spróbuj jeszcze raz", "I didn't hear it – say “Aurora” clearly and try again"), Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun saveAll() {
        fieldViews.forEach { (f, e) -> f.set(e.text.toString()) }
        switchViews.forEach { (o, s) -> o.set(s.isChecked) }
        try { Events.send("settings", "data" to ConfigApply.export()) } catch (_: Exception) {}  // 2.12.1: zmiany z menu trafiają też do HA
        restartService()
        Controller.loadDashboard()
        val needCam = Prefs.motionEnabled && !granted(Manifest.permission.CAMERA)
        if (needCam) requestPermissions(arrayOf(Manifest.permission.CAMERA), 1) else finish()
    }

    override fun onResume() { super.onResume(); refreshChecks() }

    override fun onDestroy() { tts?.shutdown(); super.onDestroy() }

    private fun restartService() {
        stopService(Intent(this, KioskService::class.java))
        startForegroundService(Intent(this, KioskService::class.java))
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        restartService()
        if (c == 1) finish() else refreshChecks()
    }
}
