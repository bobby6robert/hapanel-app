package dev.hapanel

import java.security.MessageDigest
import java.security.SecureRandom

/** PIN do ustawień: min. 4 znaki, zapisany jako SHA-256 z solą, blokada 60 s po 5 błędach. Nigdy nie jest odczytywany przez API. */
object Pin {
    private var fails = 0

    fun isSet() = Prefs.pinHash.isNotEmpty()

    private fun hash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    fun set(pin: String): Boolean {
        if (pin.length < 4) return false
        val salt = SecureRandom().nextLong().toString(16)
        Prefs.pinSalt = salt
        Prefs.pinHash = hash(pin, salt)
        return true
    }

    fun clear() { Prefs.pinHash = ""; Prefs.pinSalt = "" }

    fun lockRemainingSec(): Int {
        val r = (Prefs.pinLockUntil - System.currentTimeMillis()) / 1000
        return if (r > 0) r.toInt() + 1 else 0
    }

    fun verify(pin: String): Boolean {
        if (!isSet()) return true
        if (lockRemainingSec() > 0) return false
        val ok = hash(pin, Prefs.pinSalt) == Prefs.pinHash
        if (ok) fails = 0
        else {
            fails++
            if (fails >= 5) { Prefs.pinLockUntil = System.currentTimeMillis() + 60_000; fails = 0 }
        }
        return ok
    }
}
