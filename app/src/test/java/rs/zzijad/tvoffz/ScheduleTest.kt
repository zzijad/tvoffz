package rs.zzijad.tvoffz

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class ScheduleTest {

    private lateinit var savedTz: TimeZone
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Before
    fun setUp() {
        savedTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Belgrade"))
        fmt.timeZone = TimeZone.getTimeZone("Europe/Belgrade")
    }

    @After
    fun tearDown() = TimeZone.setDefault(savedTz)

    private fun t(s: String): Long = fmt.parse(if (s.length == 16) "$s:00" else s)!!.time
    private fun s(ms: Long?): String? = ms?.let { fmt.format(java.util.Date(it)) }

    private fun hm(h: Int, m: Int) = h * 60 + m

    // Zi-jev raspored: gašenje u 20:00, zaključano do 01:45. 2026-11-14 je subota.
    private val zi = Config(actionMins = hm(20, 0), lockMins = hm(1, 45), secs = 60)

    // ---------------- lockEnd ----------------

    @Test
    fun lockAcrossMidnight_evening() =
        assertEquals("2026-11-15 01:45:00", s(Schedule.lockEnd(zi, t("2026-11-14 22:01"))))

    @Test
    fun lockAcrossMidnight_afterMidnight() =
        assertEquals("2026-11-15 01:45:00", s(Schedule.lockEnd(zi, t("2026-11-15 01:00"))))

    @Test
    fun lockStartsExactlyAtAction() =
        assertEquals("2026-11-15 01:45:00", s(Schedule.lockEnd(zi, t("2026-11-14 20:00"))))

    @Test
    fun noLockBeforeAction() = assertNull(Schedule.lockEnd(zi, t("2026-11-14 19:59:59")))

    @Test
    fun noLockAtLockEnd() = assertNull(Schedule.lockEnd(zi, t("2026-11-15 01:45")))

    @Test
    fun noLockDuringDay() = assertNull(Schedule.lockEnd(zi, t("2026-11-14 12:00")))

    @Test
    fun lockAfterMidnightNeedsPreviousDayEnabled() {
        // Samo nedelja uključena (bit 6). U ponedeljak 01:00 – nedeljno zaključavanje još traje.
        val sundayOnly = zi.copy(days = 1 shl 6)
        assertEquals("2026-11-16 01:45:00", s(Schedule.lockEnd(sundayOnly, t("2026-11-16 01:00"))))
        // U nedelju 01:00 – subota nije uključena, nema zaključavanja.
        assertNull(Schedule.lockEnd(sundayOnly, t("2026-11-15 01:00")))
    }

    @Test
    fun lockSameDayWindow() {
        val c = Config(actionMins = hm(13, 0), lockMins = hm(15, 0))
        assertEquals("2026-11-14 15:00:00", s(Schedule.lockEnd(c, t("2026-11-14 14:00"))))
        assertNull(Schedule.lockEnd(c, t("2026-11-14 15:30")))
    }

    @Test
    fun noLockWhenTimesEqualOrDisabled() {
        assertNull(Schedule.lockEnd(zi.copy(lockMins = zi.actionMins), t("2026-11-14 22:00")))
        assertNull(Schedule.lockEnd(zi.copy(enabled = false), t("2026-11-14 22:00")))
    }

    // ---------------- nextAction ----------------

    @Test
    fun nextActionToday() =
        assertEquals("2026-11-14 20:00:00", s(Schedule.nextAction(zi, t("2026-11-14 19:00"))))

    @Test
    fun nextActionTomorrowWhenPassed() =
        assertEquals("2026-11-15 20:00:00", s(Schedule.nextAction(zi, t("2026-11-14 20:00"))))

    @Test
    fun nextActionOnlyMonday() =
        assertEquals("2026-11-16 20:00:00", s(Schedule.nextAction(zi.copy(days = 1), t("2026-11-14 10:00"))))

    @Test
    fun nextActionNoDays() = assertNull(Schedule.nextAction(zi.copy(days = 0), t("2026-11-14 10:00")))

    @Test
    fun nextActionAcrossDstChange() {
        // 25.10.2026. sat se vraća sa 03:00 na 02:00.
        val c = Config(actionMins = hm(23, 30))
        assertEquals("2026-10-25 23:30:00", s(Schedule.nextAction(c, t("2026-10-25 01:00"))))
    }

    // ---------------- dueAction (kad kreće odbrojavanje) ----------------

    @Test
    fun dueExactlyAtWarningStart() =
        assertEquals("2026-11-14 20:00:00", s(Schedule.dueAction(zi, t("2026-11-14 19:59:00"))))

    @Test
    fun notDueBeforeWarning() = assertNull(Schedule.dueAction(zi, t("2026-11-14 19:58:59")))

    @Test
    fun dueShortlyAfterActionWhenMissed() =
        assertEquals("2026-11-14 20:00:00", s(Schedule.dueAction(zi, t("2026-11-14 20:01:30"))))

    @Test
    fun notDueLongAfterAction() = assertNull(Schedule.dueAction(zi, t("2026-11-14 20:03")))

    @Test
    fun dueAcrossMidnight() {
        // Gašenje u 00:00, odbrojavanje 60 s mora da krene u 23:59 prethodnog dana.
        val c = Config(actionMins = 0, lockMins = hm(7, 0), secs = 60)
        assertEquals("2026-11-15 00:00:00", s(Schedule.dueAction(c, t("2026-11-14 23:59:10"))))
    }

    @Test
    fun notDueOnDisabledDay() =
        assertNull(Schedule.dueAction(zi.copy(days = 1), t("2026-11-14 19:59:30")))

    @Test
    fun notDueWhenScheduleOff() =
        assertNull(Schedule.dueAction(zi.copy(enabled = false), t("2026-11-14 19:59:30")))

    // ---------------- ostalo ----------------

    @Test
    fun dayIndexMondayIsZero() {
        assertEquals(0, Schedule.dayIndexAt(t("2026-11-16 12:00"), 0)) // ponedeljak
        assertEquals(6, Schedule.dayIndexAt(t("2026-11-15 12:00"), 0)) // nedelja
    }

    @Test
    fun daysFromToday() {
        val now = t("2026-11-14 22:00")
        assertEquals(0, Schedule.daysFromToday(now, t("2026-11-14 23:00")))
        assertEquals(1, Schedule.daysFromToday(now, t("2026-11-15 00:00")))
        assertEquals(2, Schedule.daysFromToday(now, t("2026-11-16 20:00")))
    }

    @Test
    fun formatAndMinutes() {
        assertEquals("07:05", Schedule.fmt(hm(7, 5)))
        assertEquals("00:00", Schedule.fmt(0))
        assertEquals(hm(1, 45), Schedule.minsOf(t("2026-11-15 01:45")))
    }
}
