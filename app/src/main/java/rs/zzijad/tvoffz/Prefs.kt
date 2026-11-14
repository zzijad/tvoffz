package rs.zzijad.tvoffz

import android.content.Context
import java.security.MessageDigest

/** Podešavanja rasporeda. Vremena su u minutima od ponoći. */
data class Config(
    val enabled: Boolean = true,
    val actionMins: Int = 23 * 60 + 30,
    val lockMins: Int = 7 * 60,
    val shutdown: Boolean = true,
    val secs: Int = 60,
    /** Bit 0 = ponedeljak … bit 6 = nedelja. */
    val days: Int = 0b1111111,
    /** Gašenje dugmetom za napajanje van rasporeda traži PIN. */
    val pinToSleep: Boolean = false,
    /** Posle paljenja boxa ponovo otvori poslednju aplikaciju. */
    val resumeLast: Boolean = true,
)

/** Zaštita od pogađanja PIN-a: posle 5 pogrešnih pokušaja unos se blokira na 2 min. */
object PinPolicy {
    const val MAX_FAILS = 5
    const val LOCK_MS = 2 * 60_000L

    /** Novi broj grešaka i vreme do kog je unos blokiran (0 = nije) posle pogrešnog PIN-a. */
    fun afterWrong(fails: Int, now: Long): Pair<Int, Long> {
        val f = fails + 1
        return if (f >= MAX_FAILS) 0 to now + LOCK_MS else f to 0L
    }
}

enum class PinResult { OK, WRONG, LOCKED }

object Prefs {
    private const val NAME = "tvoffz"

    private fun sp(c: Context) = c.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun load(c: Context): Config {
        val p = sp(c)
        val d = Config()
        return Config(
            enabled = p.getBoolean("enabled", d.enabled),
            actionMins = p.getInt("actionMins", d.actionMins),
            lockMins = p.getInt("lockMins", d.lockMins),
            shutdown = p.getBoolean("shutdown", d.shutdown),
            secs = p.getInt("secs", d.secs),
            days = p.getInt("days", d.days),
            pinToSleep = p.getBoolean("pinToSleep", d.pinToSleep),
            resumeLast = p.getBoolean("resumeLast", d.resumeLast),
        )
    }

    fun save(c: Context, cfg: Config) {
        sp(c).edit()
            .putBoolean("enabled", cfg.enabled)
            .putInt("actionMins", cfg.actionMins)
            .putInt("lockMins", cfg.lockMins)
            .putBoolean("shutdown", cfg.shutdown)
            .putInt("secs", cfg.secs)
            .putInt("days", cfg.days)
            .putBoolean("pinToSleep", cfg.pinToSleep)
            .putBoolean("resumeLast", cfg.resumeLast)
            .apply()
    }

    // ---- PIN ----
    fun hasPin(c: Context) = sp(c).getString("pin", null) != null

    fun setPin(c: Context, pin: String) {
        sp(c).edit().putString("pin", hash(pin)).putInt("pinFails", 0).putLong("pinLockUntil", 0L).apply()
    }

    private fun checkPin(c: Context, pin: String) = sp(c).getString("pin", null) == hash(pin)

    /** Proverava PIN uz blokadu posle više pogrešnih pokušaja. */
    fun tryPin(c: Context, pin: String): PinResult {
        val p = sp(c)
        val now = System.currentTimeMillis()
        if (now < p.getLong("pinLockUntil", 0L)) return PinResult.LOCKED
        if (checkPin(c, pin)) {
            p.edit().putInt("pinFails", 0).apply()
            return PinResult.OK
        }
        val (fails, until) = PinPolicy.afterWrong(p.getInt("pinFails", 0), now)
        p.edit().putInt("pinFails", fails).putLong("pinLockUntil", until).commit()
        return if (until > 0L) PinResult.LOCKED else PinResult.WRONG
    }

    fun pinLockLeftSec(c: Context): Int {
        val left = sp(c).getLong("pinLockUntil", 0L) - System.currentTimeMillis()
        return if (left <= 0) 0 else ((left + 999L) / 1000L).toInt()
    }

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("tvoffz:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    // ---- Stanje servisa ----
    /** Ključ "datum|vreme" poslednjeg pokrenutog odbrojavanja, da se ne pokrene dvaput. */
    fun lastFired(c: Context): String? = sp(c).getString("lastFired", null)

    fun setLastFired(c: Context, key: String) {
        sp(c).edit().putString("lastFired", key).apply()
    }

    /** Do kada je zaključavanje privremeno ukinuto (PIN ili otkazivanje). */
    fun unlockedUntil(c: Context): Long = sp(c).getLong("unlockedUntil", 0L)

    fun setUnlockedUntil(c: Context, millis: Long) {
        sp(c).edit().putLong("unlockedUntil", millis).apply()
    }

    /** Do kada je gašenje dozvoljeno bez PIN-a (zakazano gašenje ili posle tačnog PIN-a). */
    fun allowSleepUntil(c: Context): Long = sp(c).getLong("allowSleepUntil", 0L)

    fun setAllowSleepUntil(c: Context, millis: Long) {
        sp(c).edit().putLong("allowSleepUntil", millis).commit()
    }

    /** Poslednja aplikacija koja je bila otvorena (za nastavak posle paljenja). */
    fun lastApp(c: Context): String? = sp(c).getString("lastApp", null)

    fun setLastApp(c: Context, pkg: String) {
        sp(c).edit().putString("lastApp", pkg).apply()
    }
}

/** Da li je PIN u podešavanjima već unet u ovoj sesiji (dok se ne izađe iz aplikacije). */
object Session {
    @Volatile
    var verified = false
}
