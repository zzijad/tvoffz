package rs.zzijad.tvoffz

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Numerička tastatura za unos 4-cifrenog PIN-a.
 * Radi i strelicama + OK, i brojevima na daljinskom.
 */
class PinPadView(context: Context) : LinearLayout(context) {

    var onComplete: ((String) -> Unit)? = null

    private var pin = ""
    private val title = TextView(context)
    private val msg = TextView(context)
    private val dots = mutableListOf<View>()
    private val keys = mutableListOf<Button>()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL

        title.apply {
            setTextColor(context.getColor(R.color.muted))
            textSize = 15f
            letterSpacing = 0.12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        addView(title, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        val dotRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        repeat(4) {
            val v = View(context)
            v.setBackgroundResource(R.drawable.dot_off)
            dotRow.addView(v, LayoutParams(dp(18), dp(18)).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            })
            dots += v
        }
        addView(dotRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        msg.apply {
            textSize = 15f
            gravity = Gravity.CENTER
            minHeight = dp(22)
            setTextColor(context.getColor(R.color.error))
        }
        addView(msg, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })

        val labels = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "⌫")
        labels.chunked(3).forEach { rowLabels ->
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            rowLabels.forEach { l ->
                val b = Button(context).apply {
                    text = l
                    contentDescription = when (l) {
                        "C" -> context.getString(R.string.pin_clear)
                        "⌫" -> context.getString(R.string.pin_back)
                        else -> l
                    }
                    setBackgroundResource(R.drawable.bg_key)
                    setTextColor(context.getColor(R.color.text))
                    textSize = 24f
                    typeface = Typeface.DEFAULT_BOLD
                    isAllCaps = false
                    stateListAnimator = null
                    minWidth = 0
                    minHeight = 0
                    minimumWidth = 0
                    minimumHeight = 0
                    setPadding(0, 0, 0, 0)
                    isFocusable = true
                    setOnClickListener { press(l) }
                }
                row.addView(b, LayoutParams(dp(77), dp(54)).apply {
                    setMargins(dp(5), dp(5), dp(5), dp(5))
                })
                keys += b
            }
            addView(row)
        }
    }

    fun setTitle(text: String) {
        title.text = text
    }

    /** Prikaže grešku i obriše unete cifre. */
    fun showError(text: String) {
        pin = ""
        msg.text = text
        refresh()
    }

    fun reset() {
        pin = ""
        msg.text = ""
        refresh()
    }

    /** Fokus na "5", sredinu tastature. */
    fun focusFirst() {
        keys[4].requestFocus()
    }

    private fun press(l: String) {
        when (l) {
            "C" -> {
                pin = ""
                msg.text = ""
            }
            "⌫" -> pin = pin.dropLast(1)
            else -> if (pin.length < 4) {
                pin += l
                msg.text = ""
            }
        }
        refresh()
        if (pin.length == 4 && l != "⌫" && l != "C") {
            val entered = pin
            postDelayed({ onComplete?.invoke(entered) }, 150)
        }
    }

    private fun refresh() {
        dots.forEachIndexed { i, v ->
            v.setBackgroundResource(if (i < pin.length) R.drawable.dot_on else R.drawable.dot_off)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val k = event.keyCode
            if (k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                press((k - KeyEvent.KEYCODE_0).toString())
                return true
            }
            if (k in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9) {
                press((k - KeyEvent.KEYCODE_NUMPAD_0).toString())
                return true
            }
            if (k == KeyEvent.KEYCODE_DEL) {
                press("⌫")
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
