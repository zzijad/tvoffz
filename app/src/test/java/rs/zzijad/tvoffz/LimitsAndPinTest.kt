package rs.zzijad.tvoffz

import org.junit.Assert.assertEquals
import org.junit.Test

class LimitsAndPinTest {

    @Test
    fun limitStepsUp() {
        assertEquals(15, Limits.next(0, +1))
        assertEquals(30, Limits.next(15, +1))
        assertEquals(180, Limits.next(120, +1))
        assertEquals(180, Limits.next(180, +1)) // ne ide preko maksimuma
    }

    @Test
    fun limitStepsDown() {
        assertEquals(0, Limits.next(15, -1))
        assertEquals(0, Limits.next(0, -1)) // ne ide ispod "bez limita"
        assertEquals(90, Limits.next(120, -1))
    }

    @Test
    fun limitOffStepValueSnaps() {
        // Vrednost koja nije u listi (npr. iz starije verzije) skače na susednu.
        assertEquals(60, Limits.next(50, +1))
        assertEquals(45, Limits.next(50, -1))
        assertEquals(180, Limits.next(300, -1))
    }

    @Test
    fun pinLockAfterFiveWrong() {
        var fails = 0
        var until = 0L
        repeat(4) {
            val r = PinPolicy.afterWrong(fails, 1_000L)
            fails = r.first
            until = r.second
            assertEquals(0L, until)
        }
        assertEquals(4, fails)
        val r = PinPolicy.afterWrong(fails, 1_000L)
        assertEquals(0, r.first) // brojač kreće ispočetka
        assertEquals(1_000L + PinPolicy.LOCK_MS, r.second)
    }
}
