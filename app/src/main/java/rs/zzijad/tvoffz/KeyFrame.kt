package rs.zzijad.tvoffz

import android.content.Context
import android.view.KeyEvent
import android.widget.FrameLayout

/** Okvir za overlay koji presreće taster Nazad (Back) na daljinskom. */
class KeyFrame(context: Context) : FrameLayout(context) {

    var onBack: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
