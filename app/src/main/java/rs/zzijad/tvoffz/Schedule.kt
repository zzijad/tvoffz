package rs.zzijad.tvoffz

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Schedule {

    /** 0 = ponedeljak … 6 = nedelja. */
    fun dayIndex(cal: Calendar): Int = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7

    fun isDayOn(days: Int, idx: Int): Boolean = (days shr idx) and 1 == 1

    private fun cal(now: Long, dayOffset: Int): Calendar =
        Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, dayOffset)
        }

    fun dayIndexAt(now: Long, dayOffset: Int): Int = dayIndex(cal(now, dayOffset))

    /** Trenutak "danas + dayOffset dana" u zadato vreme (minuti od ponoći). */
    fun at(now: Long, dayOffset: Int, mins: Int): Long =
        cal(now, dayOffset).apply {
            set(Calendar.HOUR_OF_DAY, mins / 60)
            set(Calendar.MINUTE, mins % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * Ako je [now] unutar perioda zaključavanja, vraća kraj tog perioda.
     * Period počinje u vreme akcije (izabranog dana) i traje do "Zaključano do",
     * ako treba i preko ponoći.
     */
    fun lockEnd(cfg: Config, now: Long): Long? {
        if (!cfg.enabled || cfg.lockMins == cfg.actionMins) return null
        for (offset in intArrayOf(0, -1)) {
            if (!isDayOn(cfg.days, dayIndexAt(now, offset))) continue
            val start = at(now, offset, cfg.actionMins)
            val end = if (cfg.lockMins > cfg.actionMins) {
                at(now, offset, cfg.lockMins)
            } else {
                at(now, offset + 1, cfg.lockMins)
            }
            if (now in start until end) return end
        }
        return null
    }

    /** Sledeće zakazano vreme akcije, ili null ako nijedan dan nije izabran. */
    fun nextAction(cfg: Config, now: Long): Long? {
        if (!cfg.enabled || cfg.days == 0) return null
        for (d in 0..7) {
            if (!isDayOn(cfg.days, dayIndexAt(now, d))) continue
            val t = at(now, d, cfg.actionMins)
            if (t > now) return t
        }
        return null
    }

    /** Za koliko dana od danas je [t] (0 = danas, 1 = sutra …). */
    fun daysFromToday(now: Long, t: Long): Int {
        for (d in 0..7) if (t < at(now, d + 1, 0)) return d
        return 7
    }

    fun minsOf(t: Long): Int {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun fmt(mins: Int): String = String.format(Locale.US, "%02d:%02d", mins / 60, mins % 60)

    fun dayKey(now: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
}
