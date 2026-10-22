package dev.hapanel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        c.startForegroundService(Intent(c, KioskService::class.java))
        // Start aktywności z tła wymaga zgody "wyświetlanie nad innymi aplikacjami".
        c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
