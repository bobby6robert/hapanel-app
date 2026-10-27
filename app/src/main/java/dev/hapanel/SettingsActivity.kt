package dev.hapanel

import android.Manifest
import android.app.Activity
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
        Check("Mikrofon (rozmowa przez domofon)", { granted(Manifest.permission.RECORD_AUDIO) },
            { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2) }),
        Check("Kamera (wykrywanie ruchu)", { !Prefs.motionEnabled || granted(Manifest.permission.CAMERA) },
            { requestPermissions(arrayOf(Manifest.permission.CAMERA), 2) }),
        Check("Autostart po restarcie (wyświetlanie nad innymi aplikacjami)", { Settings.canDrawOverlays(this) },
            { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }),
        Check("Bateria bez optymalizacji (system nie usypia aplikacji)",
            { (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) },
            {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }),
        Check("Polski głos komunikatów (TTS)", { plAvailable == true },
            {
                try { startActivity(Intent("com.android.settings.TTS_SETTINGS")) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
            }),
        Check("Adres dashboardu przez HTTPS (wymagane dla mikrofonu)", { Prefs.dashboardUrl.startsWith("https://") }, null),
        Check("Przycisk Home wraca do panelu (HA Panel jako ekran główny)", { isDefaultHome() },
            { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }, required = false),
        Check("Instalacja aktualizacji z HA dozwolona", { packageManager.canRequestPackageInstalls() },
            { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }, required = false),
        Check("PIN do ustawień ustawiony", { Pin.isSet() }, null, required = false),
        Check("Pola „Adres HA” i „token” puste (używasz integracji, bez dubli encji)", { Prefs.haUrl.isBlank() }, null, required = false),
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
        summary.text = if (good) "✓ Gotowe do pracy" else "Do poprawy: $bad"
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
            pairChip.text = "Kod parowania: ${c.substring(0, 3)} ${c.substring(3)}"
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
        val sub = label("Panel ścienny dla Home Assistant · wersja ${versionName()}", 14f)
        sub.setPadding(0, dp(2), 0, dp(14))
        content.addView(sub)
        val chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        summary = chip(); ipChip = chip(); pairChip = chip()
        chips.addView(summary, lp(WRAP_CONTENT, WRAP_CONTENT, right = dp(10)))
        chips.addView(ipChip, lp(WRAP_CONTENT, WRAP_CONTENT, right = dp(10)))
        chips.addView(pairChip, lp(WRAP_CONTENT, WRAP_CONTENT))
        content.addView(chips, lp(MATCH_PARENT, WRAP_CONTENT, bottom = dp(18)))

        // lista kontrolna
        val cCheck = card("Lista kontrolna")
        checkBox = LinearLayout(this)
        checkBox.orientation = LinearLayout.VERTICAL
        cCheck.addView(checkBox)
        cCheck.addView(pill("Test mowy", false, MINT) {
            tts?.language = if (plAvailable == true) Locale("pl", "PL") else Locale.getDefault()
            tts?.speak("To jest test komunikatu głosowego", TextToSpeech.QUEUE_FLUSH, null, "test")
        }, lp(WRAP_CONTENT, WRAP_CONTENT, top = dp(14)))
        content.addView(cCheck)

        // dashboard
        val cDash = card("Dashboard")
        addField(cDash, F("Adres dashboardu", "Główny. Dla mikrofonu musi być https://, np. https://ha.example.com/lovelace/0",
            { Prefs.dashboardUrl }, { Prefs.dashboardUrl = it.trim() }))
        addField(cDash, F("Adresy awaryjne", "Jeden w linii. Użyte, gdy główny nie działa (np. lokalny http://192.168.1.10:8123). Na lokalnym nie działa mikrofon",
            { Prefs.fallbackUrls }, { Prefs.fallbackUrls = it.trim() }, multiline = true))
        addField(cDash, F("Wróć do dashboardu po (sekundy)", "0 = nigdy",
            { Prefs.homeAfterSec.toString() }, { Prefs.homeAfterSec = it.toIntOrNull() ?: 0 }, num = true))
        content.addView(cDash)

        // Home Assistant
        val cHa = card("Home Assistant")
        addField(cHa, F("Adres HA do raportowania", "Zostaw puste, jeśli używasz integracji HA Panel (zalecane)",
            { Prefs.haUrl }, { Prefs.haUrl = it.trim() }))
        addField(cHa, F("Token dostępu HA", "Zostaw puste, jeśli używasz integracji",
            { Prefs.haToken }, { Prefs.haToken = it.trim() }, secret = true))
        content.addView(cHa)

        // tablet i sieć
        val cNet = card("Tablet i sieć")
        addField(cNet, F("Nazwa urządzenia", "Do nazw encji przy raportowaniu bez integracji",
            { Prefs.deviceName }, { Prefs.deviceName = it.trim().ifBlank { "tablet" } }))
        addField(cNet, F("Port serwera HTTP", "Domyślnie 8765",
            { Prefs.port.toString() }, { Prefs.port = it.toIntOrNull() ?: 8765 }, num = true))
        addField(cNet, F("Token API", "Ustawiany automatycznie przy parowaniu z HA",
            { Prefs.apiToken }, { Prefs.apiToken = it.trim() }, secret = true))
        content.addView(cNet)

        // ekran i czujniki
        val cScr = card("Ekran i czujniki")
        addField(cScr, F("Uśpij ekran po (sekundy)", "0 = nigdy. Po bezruchu i bez dotyku zostaje czarna nakładka",
            { Prefs.sleepAfterSec.toString() }, { Prefs.sleepAfterSec = it.toIntOrNull() ?: 0 }, num = true))
        addField(cScr, F("Czułość ruchu", "Mniej = czulej, domyślnie 5",
            { Prefs.motionThreshold.toString() }, { Prefs.motionThreshold = it.toIntOrNull() ?: 5 }, num = true))
        val sp = View(this); cScr.addView(sp, lp(MATCH_PARENT, dp(8)))
        addSwitch(cScr, O("Wykrywanie ruchu z przedniej kamery", { Prefs.motionEnabled }, { Prefs.motionEnabled = it }))
        addSwitch(cScr, O("Wybudzaj ekran po ruchu", { Prefs.wakeOnMotion }, { Prefs.wakeOnMotion = it }))
        addSwitch(cScr, O("Automatyczna jasność (czujnik światła)", { Prefs.autoBrightness }, { Prefs.autoBrightness = it }))
        addSwitch(cScr, O("Blokada ekranu (przypięcie aplikacji)", { Prefs.lockTask }, { Prefs.lockTask = it }))
        addSwitch(cScr, O("Ignoruj błędy certyfikatu SSL", { Prefs.ignoreSsl }, { Prefs.ignoreSsl = it }))
        content.addView(cScr)

        // wygląd i dopasowanie
        val cLook = card("Wygląd i dopasowanie")
        addField(cLook, F("Powiększenie tekstu (%)", "100 = bez zmian. Pomaga na małych ekranach (50-250)",
            { Prefs.zoom.toString() }, { Prefs.zoom = (it.toIntOrNull() ?: 100).coerceIn(50, 250) }, num = true))
        addField(cLook, F("Własny CSS", "Wstrzykiwany do każdej strony, np. :root { --primary-color: #7CF3D4; }",
            { Prefs.injectCss }, { Prefs.injectCss = it }, multiline = true))
        addField(cLook, F("Własny JavaScript", "Uruchamiany po załadowaniu strony. Używaj ostrożnie",
            { Prefs.injectJs }, { Prefs.injectJs = it }, multiline = true))
        content.addView(cLook)

        // tryb nocny
        val cNight = card("Tryb nocny (zegar)")
        addSwitch(cNight, O("Włącz tryb nocny w aplikacji", { Prefs.nightEnabled }, { Prefs.nightEnabled = it }))
        val timeRe = Regex("\\d{1,2}:\\d{2}")
        addField(cNight, F("Początek", "Godzina w formacie GG:MM, np. 22:00",
            { Prefs.nightStart }, { if (timeRe.matches(it.trim())) Prefs.nightStart = it.trim() }))
        addField(cNight, F("Koniec", "np. 06:30",
            { Prefs.nightEnd }, { if (timeRe.matches(it.trim())) Prefs.nightEnd = it.trim() }))
        addField(cNight, F("Jasność w nocy (1-255)", "Dotyczy zegara i dashboardu w nocy",
            { Prefs.nightBrightness.toString() }, { Prefs.nightBrightness = (it.toIntOrNull() ?: 10).coerceIn(1, 255) }, num = true))
        addField(cNight, F("Powrót do zegara po (sekundy)", "Po wybudzeniu dotykiem lub ruchem. Minimum 10",
            { Prefs.nightWakeSec.toString() }, { Prefs.nightWakeSec = (it.toIntOrNull() ?: 60).coerceAtLeast(10) }, num = true))
        content.addView(cNight)

        // Aurora – rozmowa głosowa (tryb rodzica: ustawienia są za PIN-em)
        val cVoice = card("Aurora – rozmowa głosowa")
        cVoice.addView(label("Rozmowa po komunikatach, przycisk ✦, słowo wybudzające i machnięcie ręką. Rozumienie mowy i odpowiedzi robi Home Assistant (integracja HA Panel 1.6+).", 13f))
        addSwitch(cVoice, O("Rozmowa z Aurorą włączona", { Prefs.voiceEnabled }, { Prefs.voiceEnabled = it }))
        addSwitch(cVoice, O("Przycisk ✦ „Porozmawiaj z Aurorą”", { Prefs.talkButton }, { Prefs.talkButton = it }))
        addSwitch(cVoice, O("Dwa machnięcia ręką = rozmowa", { Prefs.proximityTalk }, { Prefs.proximityTalk = it }))
        addSwitch(cVoice, O("Słowo wybudzające (offline, pobiera model ~50 MB)", { Prefs.wakeWord }, { Prefs.wakeWord = it }))
        addSwitch(cVoice, O("Słowo tylko, gdy ktoś jest przy tablecie", { Prefs.wakePresenceOnly }, { Prefs.wakePresenceOnly = it }))
        addField(cVoice, F("Słowo wybudzające", "Domyślnie: aurora (małe litery)",
            { Prefs.wakeWordText }, { if (it.isNotBlank()) Prefs.wakeWordText = it.trim().lowercase() }))
        addField(cVoice, F("Cisza nocna – od", "Słowo wybudzające nie działa, np. 22:00",
            { Prefs.wakeQuietStart }, { if (timeRe.matches(it.trim())) Prefs.wakeQuietStart = it.trim() }))
        addField(cVoice, F("Cisza nocna – do", "np. 06:30",
            { Prefs.wakeQuietEnd }, { if (timeRe.matches(it.trim())) Prefs.wakeQuietEnd = it.trim() }))
        addField(cVoice, F("Czekaj na odpowiedź (sekundy)", "Po komunikacie: ile czekać, aż ktoś zacznie mówić (3-30)",
            { Prefs.listenWaitSec.toString() }, { Prefs.listenWaitSec = (it.toIntOrNull() ?: 8).coerceIn(3, 30) }, num = true))
        addField(cVoice, F("Koniec wypowiedzi po ciszy (ms)", "Domyślnie 1200",
            { Prefs.silenceMs.toString() }, { Prefs.silenceMs = (it.toIntOrNull() ?: 1200).coerceIn(500, 4000) }, num = true))
        cVoice.addView(pill("Test rozmowy", false, MINT) { Voice.start(this, source = "button") }, lp(WRAP_CONTENT, WRAP_CONTENT, top = dp(14)))
        content.addView(cVoice)

        // kopia ustawień
        val cBackup = card("Kopia ustawień")
        cBackup.addView(label("Eksport zawiera ustawienia bez PIN-u, tokenów i adresu webhooka.", 13f))
        cBackup.addView(pill("Kopiuj ustawienia (JSON)", false, MINT) { exportSettings() }, lp(WRAP_CONTENT, WRAP_CONTENT, top = dp(14)))
        cBackup.addView(pill("Wklej ustawienia (JSON)", false, MINT) { importSettings() }, lp(WRAP_CONTENT, WRAP_CONTENT, top = dp(10)))
        content.addView(cBackup)

        // PIN
        val cPin = card("PIN do ustawień")
        cPin.addView(label(
            if (Pin.isSet()) "PIN jest ustawiony. Chroni wejście w ustawienia (5 dotknięć w rogu). Po 5 błędach blokada na minutę."
            else "Brak PIN-u. Ustaw min. 4 znaki, żeby chronić wejście w ustawienia. Można go też ustawić z Home Assistant.", 13f))
        val curEdit = if (Pin.isSet()) addField(cPin, F("Obecny PIN", "", { "" }, {}, secret = true), track = false) else null
        val newEdit = addField(cPin, F("Nowy PIN", "Minimum 4 znaki", { "" }, {}, secret = true), track = false)
        cPin.addView(pill("Zapisz PIN", false, MINT) { savePin(curEdit, newEdit) }, lp(WRAP_CONTENT, WRAP_CONTENT, top = dp(14)))
        if (Pin.isSet()) cPin.addView(pill("Usuń PIN", false, RED) { removePin(curEdit) }, lp(WRAP_CONTENT, WRAP_CONTENT, top = dp(10)))
        content.addView(cPin)

        // przyciski
        val save = pill("Zapisz", true, MINT) { saveAll() }
        save.textSize = 17f
        save.setPadding(dp(18), dp(14), dp(18), dp(14))
        content.addView(save, lp(MATCH_PARENT, WRAP_CONTENT, top = dp(4)))
        content.addView(pill("Zamknij bez zapisu", false, MUTED) { finish() },
            lp(MATCH_PARENT, WRAP_CONTENT, top = dp(10)))

        refreshChecks()
    }

    // ---------- kopia ustawień ----------
    private fun exportSettings() {
        val json = ConfigApply.export().toString(2)
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("hapanel", json))
        toast("Skopiowano do schowka")
    }

    private fun importSettings() {
        val input = EditText(this)
        input.minLines = 6
        input.gravity = Gravity.TOP or Gravity.START
        input.hint = "Wklej JSON"
        val holder = FrameLayout(this)
        holder.setPadding(dp(20), dp(8), dp(20), 0)
        holder.addView(input, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        android.app.AlertDialog.Builder(this)
            .setTitle("Importuj ustawienia")
            .setView(holder)
            .setPositiveButton("Zastosuj") { _, _ ->
                try {
                    val o = org.json.JSONObject(input.text.toString())
                    val m = mutableMapOf<String, String>()
                    for (k in o.keys()) if (k != "pin" && k != "apiToken") m[k] = o.optString(k)
                    val err = ConfigApply.apply(m)
                    restartService()
                    Controller.loadDashboard()
                    toast(if (err == null) "Zastosowano" else "Błąd: $err")
                    recreate()
                } catch (e: Exception) { toast("Nieprawidłowy JSON") }
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    // ---------- PIN ----------
    private fun pinGate(cur: EditText?): Boolean {
        if (!Pin.isSet()) return true
        val lock = Pin.lockRemainingSec()
        if (lock > 0) { toast("Zbyt wiele prób. Spróbuj za $lock s"); return false }
        if (!Pin.verify(cur?.text?.toString() ?: "")) { toast("Zły PIN"); return false }
        return true
    }

    private fun savePin(cur: EditText?, neu: EditText) {
        if (!pinGate(cur)) return
        val v = neu.text.toString()
        if (v.length < 4) { toast("PIN musi mieć min. 4 znaki"); return }
        Pin.set(v); toast("PIN zapisany"); recreate()
    }

    private fun removePin(cur: EditText?) {
        if (!pinGate(cur)) return
        Pin.clear(); toast("PIN usunięty"); recreate()
    }

    private fun saveAll() {
        fieldViews.forEach { (f, e) -> f.set(e.text.toString()) }
        switchViews.forEach { (o, s) -> o.set(s.isChecked) }
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
