package dev.hapanel

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    /** Do kiedy trwa instalacja – wtedy panel nie przypina się z powrotem i nie zasłania okna instalatora. */
    @Volatile var installingUntil = 0L
    val installing get() = System.currentTimeMillis() < installingUntil

    /** Pobiera APK z GitHub Releases i uruchamia systemową instalację (jedno potwierdzenie na tablecie). */
    fun start(ctx: Context, url: String): Boolean {
        if (!url.startsWith("https://github.com/")) return false
        val app = ctx.applicationContext
        installingUntil = System.currentTimeMillis() + 5 * 60_000
        Thread {
            try {
                val f = File(app.cacheDir, "update.apk")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 60000
                c.instanceFollowRedirects = true
                c.inputStream.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
                install(app, f)
            } catch (e: Exception) {
                installingUntil = 0
                toast("Aktualizacja: nie udało się pobrać (${e.javaClass.simpleName})")
            }
        }.start()
        return true
    }

    fun toast(msg: String) {
        Controller.ui.post { Controller.activity?.let { android.widget.Toast.makeText(it, msg, android.widget.Toast.LENGTH_LONG).show() } }
    }

    private fun install(ctx: Context, f: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // Android 12+: aplikacja aktualizująca samą siebie nie potrzebuje potwierdzenia.
        if (android.os.Build.VERSION.SDK_INT >= 31)
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        val session = pi.openSession(id)
        try {
            f.inputStream().use { input ->
                session.openWrite("hapanel.apk", 0, f.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            session.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        } finally {
            session.close()
        }
    }
}

/** Odbiera wynik instalacji; gdy system wymaga potwierdzenia, pokazuje je (z zatrzymaniem przypięcia ekranu). */
class InstallResultReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(c: Context, i: Intent) {
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            Controller.ui.post { Controller.activity?.let { try { it.stopLockTask() } catch (_: Exception) {} } }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { c.startActivity(confirm) } catch (_: Exception) {}
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            Updater.installingUntil = 0
            val msg = when (status) {
                PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                    "inny podpis aplikacji – odinstaluj starą wersję raz ręcznie"
                PackageInstaller.STATUS_FAILURE_BLOCKED -> "zablokowane przez system (Play Protect)"
                PackageInstaller.STATUS_FAILURE_ABORTED -> "anulowano"
                PackageInstaller.STATUS_FAILURE_STORAGE -> "brak miejsca"
                else -> i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "błąd $status"
            }
            Updater.toast("Aktualizacja nieudana: $msg")
        }
    }
}
