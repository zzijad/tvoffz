package rs.zzijad.tvoffz

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/** Unos PIN-a: provera (pre podešavanja) ili postavljanje novog PIN-a u dva koraka. */
class PinActivity : Activity() {

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_VERIFY = "verify"
        const val MODE_SETUP = "setup"
    }

    private var first: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pin)

        val tvTitle = findViewById<TextView>(R.id.tvPinTitle)
        val tvDesc = findViewById<TextView>(R.id.tvPinDesc)
        val tvStep = findViewById<TextView>(R.id.tvPinStep)
        val box = findViewById<FrameLayout>(R.id.pinBox)

        val pad = PinPadView(this)
        box.addView(
            pad,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        if (intent.getStringExtra(EXTRA_MODE) == MODE_SETUP) {
            tvTitle.setText(R.string.pin_setup_title)
            tvDesc.setText(R.string.pin_setup_desc)

            fun stepOne() {
                first = null
                tvStep.setText(R.string.pin_step1)
                pad.setTitle(getString(R.string.pin_new_caps))
            }
            stepOne()

            pad.onComplete = { p ->
                val f = first
                when {
                    f == null -> {
                        first = p
                        pad.reset()
                        tvStep.setText(R.string.pin_step2)
                        pad.setTitle(getString(R.string.pin_confirm_caps))
                    }
                    p == f -> {
                        Prefs.setPin(this, p)
                        Toast.makeText(this, R.string.pin_saved, Toast.LENGTH_LONG).show()
                        setResult(RESULT_OK)
                        finish()
                    }
                    else -> {
                        stepOne()
                        pad.showError(getString(R.string.pin_mismatch))
                    }
                }
            }
        } else {
            tvTitle.setText(R.string.pin_verify_title)
            tvDesc.setText(R.string.pin_verify_desc)
            tvStep.visibility = View.GONE
            pad.setTitle(getString(R.string.pin_enter_caps))
            pad.onComplete = { p ->
                if (Prefs.checkPin(this, p)) {
                    setResult(RESULT_OK)
                    finish()
                } else {
                    pad.showError(getString(R.string.pin_wrong))
                }
            }
        }

        pad.post { pad.focusFirst() }
    }
}
