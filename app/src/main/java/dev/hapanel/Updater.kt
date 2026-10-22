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
    /** Pobiera APK z GitHub Releases i uruchamia systemową instalację (jedno potwierdzenie na tablecie). */
    fun start(ctx: Context, url: String): Boolean {
        if (!url.startsWith("https://github.com/")) return false
        val app = ctx.applicationContext
        Thread {
            try {
                val f = File(app.cacheDir, "update.apk")
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 60000
                c.instanceFollowRedirects = true
                c.inputStream.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
                install(app, f)
            } catch (_: Exception) {}
        }.start()
        return true
    }

    private fun install(ctx: Context, f: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
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
        }
    }
}
