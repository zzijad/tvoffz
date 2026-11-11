package rs.zzijad.tvoffz

import android.content.Context

/**
 * Dnevni limiti po aplikaciji (u minutima) i potrošeno vreme za danas (u sekundama).
 * Potrošnja se sama briše kad počne novi dan.
 */
object Limits {

    /** Vrednosti koje se biraju strelicama; 0 = bez limita. */
    val STEPS = intArrayOf(0, 15, 30, 45, 60, 90, 120, 180)

    private fun lp(c: Context) = c.getSharedPreferences("limits", Context.MODE_PRIVATE)
    private fun up(c: Context) = c.getSharedPreferences("usage", Context.MODE_PRIVATE)

    fun get(c: Context, pkg: String): Int = lp(c).getInt(pkg, 0)

    fun set(c: Context, pkg: String, mins: Int) {
        if (mins <= 0) lp(c).edit().remove(pkg).apply() else lp(c).edit().putInt(pkg, mins).apply()
    }

    fun count(c: Context): Int = lp(c).all.size

    private fun today(c: Context) {
        val day = Schedule.dayKey(System.currentTimeMillis())
        val p = up(c)
        if (p.getString("day", null) != day) p.edit().clear().putString("day", day).commit()
    }

    fun usedSec(c: Context, pkg: String): Int {
        today(c)
        return up(c).getInt("u:$pkg", 0)
    }

    fun addSec(c: Context, pkg: String, sec: Int) {
        today(c)
        val p = up(c)
        p.edit().putInt("u:$pkg", p.getInt("u:$pkg", 0) + sec).apply()
    }

    /** Otključano PIN-om ili otkazivanjem do kraja dana. */
    fun isUnlocked(c: Context, pkg: String): Boolean {
        today(c)
        return up(c).getBoolean("x:$pkg", false)
    }

    fun unlock(c: Context, pkg: String) {
        today(c)
        up(c).edit().putBoolean("x:$pkg", true).apply()
    }

    /** Sekunde preostale danas, ili null ako aplikacija nema limit (ili je otključana). */
    fun leftSec(c: Context, pkg: String): Int? {
        val lim = get(c, pkg)
        if (lim <= 0 || isUnlocked(c, pkg)) return null
        return lim * 60 - usedSec(c, pkg)
    }

    fun next(mins: Int, dir: Int): Int {
        val i = STEPS.indexOfFirst { it >= mins }.let { if (it < 0) STEPS.size - 1 else it }
        return STEPS[(i + dir).coerceIn(0, STEPS.size - 1)]
    }
}
