package rs.zzijad.tvoffz

import android.content.Context
import android.view.KeyEvent
import android.widget.FrameLayout

/** Okvir za overlay koji presreće taster Nazad (Back) na daljinskom. */
class KeyFrame(context: Context) : FrameLayout(context) {

    var onBack: (() -> Unit)? = null

    /** Poziva se na svaki pritisak tastera (npr. da se produži vreme pre automatskog zatvaranja). */
    var onAnyKey: (() -> Unit)? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) onAnyKey?.invoke()
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onBack?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
