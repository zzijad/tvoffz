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
)

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
            .apply()
    }

    // ---- PIN ----
    fun hasPin(c: Context) = sp(c).getString("pin", null) != null

    fun setPin(c: Context, pin: String) {
        sp(c).edit().putString("pin", hash(pin)).apply()
    }

    fun checkPin(c: Context, pin: String) = sp(c).getString("pin", null) == hash(pin)

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
}
