package rs.zzijad.tvoffz

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Prikazuje se kad neko ugasi box dugmetom za napajanje van rasporeda.
 * Tačan PIN gasi box; Nazad (ili 60 s bez unosa) vraća na ono što se gledalo.
 */
class SleepGuardActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { finish() }

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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_pin)

        findViewById<TextView>(R.id.tvPinTitle).setText(R.string.guard_title)
        findViewById<TextView>(R.id.tvPinDesc).setText(R.string.guard_desc)
        findViewById<TextView>(R.id.tvPinStep).setText(R.string.guard_back)

        val pad = PinPadView(this)
        findViewById<FrameLayout>(R.id.pinBox).addView(
            pad,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        pad.setTitle(getString(R.string.pin_enter_caps))
        pad.onComplete = { p ->
            if (Prefs.checkPin(this, p)) {
                finish()
                handler.postDelayed({ TvOffService.instance?.sleepNow() }, 400L)
            } else {
                pad.showError(getString(R.string.pin_wrong))
                restartAutoClose()
            }
        }
        pad.post { pad.focusFirst() }
        restartAutoClose()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        restartAutoClose()
    }

    private fun restartAutoClose() {
        handler.removeCallbacks(autoClose)
        handler.postDelayed(autoClose, 60_000L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoClose)
        super.onDestroy()
    }
}
