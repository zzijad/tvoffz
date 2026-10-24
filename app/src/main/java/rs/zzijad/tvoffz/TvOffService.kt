package rs.zzijad.tvoffz

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

/**
 * Srce aplikacije:
 *  - na svakih 5 s proverava raspored i u pravo vreme prikaže odbrojavanje,
 *  - po isteku vrati na Home ekran i (ako je izabrano) prebaci box u standby,
 *  - u periodu zaključavanja svaku pokrenutu aplikaciju odmah vrati na Home.
 */
class TvOffService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TvOffService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var countdownView: View? = null
    private var lockView: View? = null
    private var timer: CountDownTimer? = null
    private var allowed: Set<String> = emptySet()

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        allowed = buildAllowed()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        timer?.cancel()
        removeCountdown()
        removeLock()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /** Paketi koji se nikad ne blokiraju: launcher, sistem, tastature i sama aplikacija. */
    private fun buildAllowed(): Set<String> {
        val s = mutableSetOf(packageName, "android", "com.android.systemui")
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        val launchers = packageManager.queryIntentActivities(home, 0)
        launchers.forEach { s += it.activityInfo.packageName }
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.inputMethodList.forEach { s += it.packageName }
        return s
    }

    // ------------------------------------------------------------------
    // Zaključavanje aplikacija
    // ------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in allowed) return
        val now = System.currentTimeMillis()
        if (now < Prefs.unlockedUntil(this)) return
        val end = Schedule.lockEnd(Prefs.load(this), now) ?: return
        performGlobalAction(GLOBAL_ACTION_HOME)
        showLock(end)
    }

    // ------------------------------------------------------------------
    // Raspored
    // ------------------------------------------------------------------

    private fun tick() {
        if (countdownView != null) return
        val cfg = Prefs.load(this)
        if (!cfg.enabled) return
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        if (!Schedule.isDayOn(cfg.days, Schedule.dayIndex(cal))) return

        val nowSec = cal.get(Calendar.HOUR_OF_DAY) * 3600 +
            cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)
        val actionSec = cfg.actionMins * 60
        val startSec = maxOf(0, actionSec - cfg.secs)
        if (nowSec < startSec || nowSec >= actionSec + 120) return

        val key = Schedule.dayKey(now) + "|" + cfg.actionMins
        if (Prefs.lastFired(this) == key) return
        Prefs.setLastFired(this, key)

        // Odbrojavanje se završava tačno u zadato vreme.
        val left = maxOf(3, actionSec - nowSec)
        startCountdown(left, maxOf(cfg.secs, left), cfg.shutdown, test = false, cfg = cfg)
    }

    /** Dugme "Testiraj odmah". */
    fun startTest() {
        val cfg = Prefs.load(this)
        startCountdown(cfg.secs, cfg.secs, cfg.shutdown, test = true, cfg = cfg)
    }

    // ------------------------------------------------------------------
    // Odbrojavanje preko ekrana
    // ------------------------------------------------------------------

    private fun themed() = ContextThemeWrapper(this, R.style.Theme_TVOffZ)

    private fun overlayParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    )

    private fun startCountdown(left: Int, total: Int, shutdown: Boolean, test: Boolean, cfg: Config) {
        if (countdownView != null) return
        val ctx = themed()
        val content = LayoutInflater.from(ctx).inflate(R.layout.overlay_countdown, null)
        val root = KeyFrame(ctx).apply {
            addView(content)
            onBack = { } // Nazad ne zatvara odbrojavanje
        }

        val ring = content.findViewById<RingView>(R.id.ring)
        val ringBox = content.findViewById<View>(R.id.ringBox)
        val tvNumber = content.findViewById<TextView>(R.id.tvNumber)
        val tvTitle = content.findViewById<TextView>(R.id.tvTitle)
        val tvSub = content.findViewById<TextView>(R.id.tvSub)
        val tvHint = content.findViewById<TextView>(R.id.tvHint)
        val btnCancel = content.findViewById<Button>(R.id.btnCancel)
        val pinBox = content.findViewById<FrameLayout>(R.id.pinBox)

        val title = getString(if (shutdown) R.string.cd_title_shutdown else R.string.cd_title_home)
        tvTitle.text = title
        tvSub.text = when {
            test -> getString(R.string.cd_sub_test)
            shutdown -> getString(R.string.cd_sub_shutdown, Schedule.fmt(cfg.actionMins))
            else -> getString(R.string.cd_sub_home, Schedule.fmt(cfg.actionMins))
        }

        var pinMode = false
        fun update(sec: Int) {
            tvNumber.text = sec.toString()
            ring.progress = sec.toFloat() / total
            if (pinMode) tvTitle.text = getString(R.string.cd_title_in, title, sec)
        }
        update(left)

        btnCancel.setOnClickListener {
            if (!Prefs.hasPin(this)) {
                cancelCountdown(test, cfg)
                return@setOnClickListener
            }
            // Sa PIN-om: otkazivanje traži PIN, odbrojavanje teče dalje.
            pinMode = true
            ringBox.visibility = View.GONE
            btnCancel.visibility = View.GONE
            tvHint.visibility = View.GONE
            tvSub.visibility = View.GONE
            val pad = PinPadView(ctx)
            pad.setTitle(getString(R.string.pin_to_cancel))
            pad.onComplete = { pin ->
                if (Prefs.checkPin(this, pin)) cancelCountdown(test, cfg)
                else pad.showError(getString(R.string.pin_wrong))
            }
            pinBox.addView(pad)
            pinBox.visibility = View.VISIBLE
            pad.post { pad.focusFirst() }
        }

        countdownView = root
        wm.addView(root, overlayParams())
        btnCancel.post { btnCancel.requestFocus() }

        timer = object : CountDownTimer(left * 1000L, 200L) {
            override fun onTick(msLeft: Long) {
                update(((msLeft + 999L) / 1000L).toInt())
            }

            override fun onFinish() {
                update(0)
                timer = null
                removeCountdown()
                runAction(shutdown)
            }
        }.start()
    }

    private fun cancelCountdown(test: Boolean, cfg: Config) {
        timer?.cancel()
        timer = null
        removeCountdown()
        if (!test) {
            // "Otkazano za danas": ni akcija ni zaključavanje do sledećeg puta.
            val actionAt = Schedule.at(System.currentTimeMillis(), 0, cfg.actionMins)
            Schedule.lockEnd(cfg, actionAt)?.let { Prefs.setUnlockedUntil(this, it) }
        }
        Toast.makeText(
            this,
            if (test) R.string.cd_cancelled_test else R.string.cd_cancelled,
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun runAction(shutdown: Boolean) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        if (shutdown) {
            handler.postDelayed({
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                } else {
                    performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
                }
            }, 1500L)
        }
    }

    private fun removeCountdown() {
        countdownView?.let { v -> runCatching { wm.removeView(v) } }
        countdownView = null
    }

    // ------------------------------------------------------------------
    // Ekran "Aplikacije su zaključane"
    // ------------------------------------------------------------------

    private fun showLock(end: Long) {
        if (lockView != null) return
        val ctx = themed()
        val content = LayoutInflater.from(ctx).inflate(R.layout.overlay_locked, null)
        val root = KeyFrame(ctx).apply {
            addView(content)
            onBack = { removeLock() }
        }

        val until = Schedule.fmt(Schedule.minsOf(end))
        val tvDesc = content.findViewById<TextView>(R.id.tvLockDesc)
        val btnHome = content.findViewById<Button>(R.id.btnBackHome)
        val pinBox = content.findViewById<FrameLayout>(R.id.lockPinBox)
        btnHome.setOnClickListener { removeLock() }

        val pad: PinPadView? = if (Prefs.hasPin(this)) {
            tvDesc.text = getString(R.string.lock_desc, until)
            PinPadView(ctx).also { p ->
                p.setTitle(getString(R.string.pin_enter_caps))
                p.onComplete = { pin ->
                    if (Prefs.checkPin(this, pin)) {
                        Prefs.setUnlockedUntil(this, end)
                        removeLock()
                        Toast.makeText(this, R.string.lock_unlocked, Toast.LENGTH_LONG).show()
                    } else {
                        p.showError(getString(R.string.pin_wrong))
                    }
                }
                pinBox.addView(p)
            }
        } else {
            tvDesc.text = getString(R.string.lock_desc_nopin, until)
            pinBox.visibility = View.GONE
            null
        }

        lockView = root
        wm.addView(root, overlayParams())
        root.post {
            if (pad != null) pad.focusFirst() else btnHome.requestFocus()
        }
    }

    private fun removeLock() {
        lockView?.let { v -> runCatching { wm.removeView(v) } }
        lockView = null
    }
}
