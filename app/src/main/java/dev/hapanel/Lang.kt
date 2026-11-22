package dev.hapanel

import java.util.Locale

/** 2.13: język aplikacji – systemowy, polski albo angielski (napisy, słowo wybudzające, rozmowa w HA). */
object L {
    /** true = angielski */
    val en: Boolean get() = when (Prefs.language) {
        "pl" -> false
        "en" -> true
        else -> Locale.getDefault().language != "pl"
    }
    val code: String get() = if (en) "en" else "pl"
    fun t(pl: String, en: String): String = if (this.en) en else pl
}
