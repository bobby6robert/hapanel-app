package dev.hapanel

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * 2.14 Sprzątanie tabletu z HA: lista aplikacji, usypianie aplikacji w tle (zwalnia pamięć),
 * czyszczenie pamięci podręcznej panelu i odinstalowanie (systemowe okno – trzeba potwierdzić na tablecie).
 * Bez roota Android nie pozwala usuwać ani czyścić innych aplikacji po cichu.
 */
object AppCleaner {
    /** Tych nigdy nie proponujemy do usunięcia ani nie usypiamy – potrzebne panelowi i systemowi. */
    val PROTECTED = setOf(
        "dev.hapanel", "com.google.android.webview", "com.android.webview", "com.android.chrome",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.google.android.tts", "com.google.android.inputmethod.latin", "com.android.systemui",
        "com.android.settings", "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.android.phone", "com.android.providers.media", "com.google.android.permissioncontroller",
    )

    fun freeMb(ctx: Context): Long {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        return mi.availMem / 1_048_576
    }

    /** Usypia procesy innych aplikacji działające w tle. Zwraca [przed, po] wolnej pamięci w MB. */
    fun cleanMemory(ctx: Context): Pair<Long, Long> {
        val before = freeMb(ctx)
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        for (a in pm.getInstalledApplications(0)) {
            if (a.packageName in PROTECTED) continue
            try { am.killBackgroundProcesses(a.packageName) } catch (_: Exception) {}
        }
        try { Runtime.getRuntime().gc() } catch (_: Exception) {}
        Thread.sleep(1500)
        return before to freeMb(ctx)
    }

    /** Lista aplikacji: nazwa, pakiet, czy systemowa, czy można odinstalować, czy chroniona. */
    fun list(ctx: Context): JSONArray {
        val pm = ctx.packageManager
        val out = JSONArray()
        @Suppress("DEPRECATION")
        val apps = pm.getInstalledApplications(0).sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        for (a in apps) {
            val system = a.flags and ApplicationInfo.FLAG_SYSTEM != 0
            val updated = a.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
            val launchable = pm.getLaunchIntentForPackage(a.packageName) != null
            if (system && !updated && !launchable) continue          // ukryte części systemu pomijamy
            out.put(JSONObject()
                .put("name", pm.getApplicationLabel(a).toString())
                .put("package", a.packageName)
                .put("system", system)
                .put("enabled", a.enabled)
                .put("removable", !system || updated)
                .put("protected", a.packageName in PROTECTED))
        }
        return out
    }

    /** Systemowe okno odinstalowania (dla aplikacji systemowej: usunięcie aktualizacji). */
    fun uninstall(ctx: Context, pkg: String): Boolean {
        if (pkg in PROTECTED) return false
        return openOnTablet(ctx, Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
    }

    /** Ekran aplikacji w ustawieniach Androida – tam przycisk „Wyłącz” dla wbudowanych aplikacji. */
    fun openSettings(ctx: Context, pkg: String): Boolean {
        if (pkg in PROTECTED) return false
        return openOnTablet(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
    }

    private fun openOnTablet(ctx: Context, i: Intent): Boolean {
        Controller.wake()
        Updater.installingUntil = System.currentTimeMillis() + 2 * 60_000   // panel nie zasłania okna systemu
        Controller.ui.post {
            val a = Controller.activity
            try { a?.stopLockTask() } catch (_: Exception) {}
            Controller.ui.postDelayed({
                try { (Controller.activity ?: ctx).startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }, 900)
        }
        return true
    }
}
