package rs.zzijad.tvoffz

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * Prozirna aktivnost koja samo pali ekran posle gašenja dugmetom za napajanje.
 * Sam PIN ekran prikazuje servis kao prozor preko svega (launcher ne može da ga prekrije).
 */
class SleepGuardActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1_500L)
    }
}
