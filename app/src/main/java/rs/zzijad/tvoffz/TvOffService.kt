package rs.zzijad.tvoffz

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
 *  - u periodu zaključavanja svaku pokrenutu aplikaciju odmah vrati na Home,
 *  - meri vreme po aplikaciji i zatvara je kad potroši dnevni limit,
 *  - (opciono) poništava gašenje dugmetom za napajanje bez PIN-a.
 */
class TvOffService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TvOffService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var pm: PowerManager
    private var countdownView: View? = null
    private var blockView: View? = null
    private var timer: CountDownTimer? = null
    private var ignored: Set<String> = emptySet()
    private var launchers: Set<String> = emptySet()

    /** Aplikacija koja je trenutno na ekranu. */
    private var fgPkg: String? = null
    private var lastTick = 0L

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 5_000L)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF) onScreenOff()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        pm = getSystemService(POWER_SERVICE) as PowerManager
        buildPackageSets()
        lastTick = System.currentTimeMillis()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, f)
        }
    }

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(screenReceiver) }
        handler.removeCallbacksAndMessages(null)
        timer?.cancel()
        removeCountdown()
        removeBlock()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /**
     * launchers = Home ekran(i); ignored = sistem, tastature i sama aplikacija.
     * Ni jedni ni drugi se nikad ne blokiraju.
     */
    private fun buildPackageSets() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        @Suppress("DEPRECATION")
        launchers = packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo.packageName }.toSet()
        val s = mutableSetOf(packageName, "android", "com.android.systemui")
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.inputMethodList.forEach { s += it.packageName }
        ignored = s
    }

    private fun appName(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    // ------------------------------------------------------------------
    // Pokretanje aplikacija: zaključavanje i limiti
    // ------------------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in ignored) return
        countUsage()
        fgPkg = pkg
        if (pkg in launchers) return

        val now = System.currentTimeMillis()

        // Noćno zaključavanje
        if (now >= Prefs.unlockedUntil(this)) {
            val end = Schedule.lockEnd(Prefs.load(this), now)
            if (end != null) {
                goHome()
                val until = Schedule.fmt(Schedule.minsOf(end))
                showBlock(
                    title = getString(R.string.lock_title),
                    descPin = getString(R.string.lock_desc, until),
                    descNoPin = getString(R.string.lock_desc_nopin, until),
                ) {
                    Prefs.setUnlockedUntil(this, end)
                    Toast.makeText(this, R.string.lock_unlocked, Toast.LENGTH_LONG).show()
                }
                return
            }
        }

        // Dnevni limit potrošen
        val left = Limits.leftSec(this, pkg)
        if (left != null && left <= 0 && countdownView == null) {
            goHome()
            blockLimit(pkg)
        }
    }

    private fun goHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        fgPkg = null
    }

    private fun blockLimit(pkg: String) {
        val name = appName(pkg)
        val mins = Limits.get(this, pkg)
        showBlock(
            title = getString(R.string.limit_block_title, name),
            descPin = getString(R.string.limit_block_desc, mins),
            descNoPin = getString(R.string.limit_block_desc_nopin, mins),
        ) {
            Limits.unlock(this, pkg)
            Toast.makeText(this, getString(R.string.limit_unlocked, name), Toast.LENGTH_LONG).show()
        }
    }

    /** Doda vreme provedeno u aplikaciji koja je na ekranu od poslednjeg merenja. */
    private fun countUsage() {
        val now = System.currentTimeMillis()
        val sec = ((now - lastTick) / 1000L).toInt()
        lastTick = now
        val pkg = fgPkg ?: return
        if (pkg in launchers || sec <= 0 || sec > 60) return
        if (!pm.isInteractive) return
        if (Limits.get(this, pkg) <= 0) return
        Limits.addSec(this, pkg, sec)
    }

    // ------------------------------------------------------------------
    // Raspored i limiti (na svakih 5 s)
    // ------------------------------------------------------------------

    private fun tick() {
        countUsage()
        if (countdownView != null) return
        if (checkSchedule()) return
        checkLimit()
    }

    private fun checkLimit() {
        val pkg = fgPkg ?: return
        if (pkg in launchers || !pm.isInteractive) return
        val left = Limits.leftSec(this, pkg) ?: return
        val warn = Prefs.load(this).secs
        if (left > warn) return
        val name = appName(pkg)
        val mins = Limits.get(this, pkg)
        startCountdown(
            left = maxOf(3, left),
            total = warn,
            title = getString(R.string.cd_title_limit, name),
            sub = getString(R.string.cd_sub_limit, mins),
            done = getString(R.string.cd_done_home),
            shutdownIcon = false,
            onFinish = {
                goHome()
                blockLimit(pkg)
            },
            onCancel = {
                Limits.unlock(this, pkg)
                Toast.makeText(this, getString(R.string.limit_unlocked, name), Toast.LENGTH_LONG).show()
            },
        )
    }

    /** Vraća true ako je pokrenulo zakazano odbrojavanje. */
    private fun checkSchedule(): Boolean {
        val cfg = Prefs.load(this)
        if (!cfg.enabled) return false
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        if (!Schedule.isDayOn(cfg.days, Schedule.dayIndex(cal))) return false

        val nowSec = cal.get(Calendar.HOUR_OF_DAY) * 3600 +
            cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)
        val actionSec = cfg.actionMins * 60
        val startSec = maxOf(0, actionSec - cfg.secs)
        if (nowSec < startSec || nowSec >= actionSec + 120) return false

        val key = Schedule.dayKey(now) + "|" + cfg.actionMins
        if (Prefs.lastFired(this) == key) return false
        Prefs.setLastFired(this, key)

        // Odbrojavanje se završava tačno u zadato vreme.
        val left = maxOf(3, actionSec - nowSec)
        scheduledCountdown(cfg, left, maxOf(cfg.secs, left), test = false)
        return true
    }

    /** Dugme "Testiraj odmah". */
    fun startTest() {
        val cfg = Prefs.load(this)
        scheduledCountdown(cfg, cfg.secs, cfg.secs, test = true)
    }

    private fun scheduledCountdown(cfg: Config, left: Int, total: Int, test: Boolean) {
        val at = Schedule.fmt(cfg.actionMins)
        startCountdown(
            left = left,
            total = total,
            title = getString(if (cfg.shutdown) R.string.cd_title_shutdown else R.string.cd_title_home),
            sub = when {
                test -> getString(R.string.cd_sub_test)
                cfg.shutdown -> getString(R.string.cd_sub_shutdown, at)
                else -> getString(R.string.cd_sub_home, at)
            },
            done = getString(if (cfg.shutdown) R.string.cd_done_shutdown else R.string.cd_done_home),
            shutdownIcon = cfg.shutdown,
            onFinish = {
                goHome()
                if (cfg.shutdown) handler.postDelayed({ sleepNow() }, 1500L)
            },
            onCancel = {
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
            },
        )
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

    @Suppress("UNUSED_PARAMETER")
    private fun startCountdown(
        left: Int,
        total: Int,
        title: String,
        sub: String,
        done: String,
        shutdownIcon: Boolean,
        onFinish: () -> Unit,
        onCancel: () -> Unit,
    ) {
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

        tvTitle.text = title
        tvSub.text = sub
        val safeTotal = maxOf(total, left, 1)

        var pinMode = false
        fun update(sec: Int) {
            tvNumber.text = sec.toString()
            ring.progress = sec.toFloat() / safeTotal
            if (pinMode) tvTitle.text = getString(R.string.cd_title_in, title, sec)
        }
        update(left)

        fun cancel() {
            timer?.cancel()
            timer = null
            removeCountdown()
            onCancel()
        }

        btnCancel.setOnClickListener {
            if (!Prefs.hasPin(this)) {
                cancel()
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
                if (Prefs.checkPin(this, pin)) cancel()
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
                tvTitle.text = done
                timer = null
                removeCountdown()
                onFinish()
            }
        }.start()
    }

    private fun removeCountdown() {
        countdownView?.let { v -> runCatching { wm.removeView(v) } }
        countdownView = null
    }

    // ------------------------------------------------------------------
    // Gašenje i zaštita od gašenja (dugme za napajanje)
    // ------------------------------------------------------------------

    /** Prebaci box u standby; zaštita od gašenja ga tada ne budi. */
    fun sleepNow() {
        Prefs.setAllowSleepUntil(this, System.currentTimeMillis() + 120_000L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else {
            performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        }
    }

    /**
     * Dugme za napajanje ne može da se presretne, pa se gašenje poništi:
     * box se odmah ponovo probudi i prikaže ekran za PIN.
     */
    private fun onScreenOff() {
        countUsage()
        val cfg = Prefs.load(this)
        if (!cfg.pinToSleep || !Prefs.hasPin(this)) return
        val now = System.currentTimeMillis()
        if (now < Prefs.allowSleepUntil(this)) return
        // Noću, u periodu zaključavanja, gašenje je uvek dozvoljeno.
        if (Schedule.lockEnd(cfg, now) != null) return

        // Drži procesor budnim dok ne probudimo ekran.
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvoffz:guard").acquire(5_000L)
        handler.postDelayed({ wakeWithGuard() }, 800L)
    }

    private fun wakeWithGuard() {
        @Suppress("DEPRECATION")
        runCatching {
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "tvoffz:wake",
            ).acquire(3_000L)
        }
        startActivity(
            Intent(this, SleepGuardActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    // ------------------------------------------------------------------
    // Ekran "zaključano" (noćno zaključavanje ili istekao limit)
    // ------------------------------------------------------------------

    private fun showBlock(title: String, descPin: String, descNoPin: String, onUnlock: () -> Unit) {
        if (blockView != null) return
        val ctx = themed()
        val content = LayoutInflater.from(ctx).inflate(R.layout.overlay_locked, null)
        val root = KeyFrame(ctx).apply {
            addView(content)
            onBack = { removeBlock() }
        }

        content.findViewById<TextView>(R.id.tvLockTitle).text = title
        val tvDesc = content.findViewById<TextView>(R.id.tvLockDesc)
        val btnHome = content.findViewById<Button>(R.id.btnBackHome)
        val pinBox = content.findViewById<FrameLayout>(R.id.lockPinBox)
        btnHome.setOnClickListener { removeBlock() }

        val pad: PinPadView? = if (Prefs.hasPin(this)) {
            tvDesc.text = descPin
            PinPadView(ctx).also { p ->
                p.setTitle(getString(R.string.pin_enter_caps))
                p.onComplete = { pin ->
                    if (Prefs.checkPin(this, pin)) {
                        removeBlock()
                        onUnlock()
                    } else {
                        p.showError(getString(R.string.pin_wrong))
                    }
                }
                pinBox.addView(p)
            }
        } else {
            tvDesc.text = descNoPin
            pinBox.visibility = View.GONE
            null
        }

        blockView = root
        wm.addView(root, overlayParams())
        root.post {
            if (pad != null) pad.focusFirst() else btnHome.requestFocus()
        }
    }

    private fun removeBlock() {
        blockView?.let { v -> runCatching { wm.removeView(v) } }
        blockView = null
    }
}
