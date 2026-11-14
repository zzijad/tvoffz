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

    /** Radi screensaver (box neaktivan) – gašenje posle toga je automatsko, ne bebac. */
    private var dreaming = false

    /** Box je probuđen zbog zaštite od gašenja i čeka PIN ekran. */
    private var guardPending = false

    private val resumeRunnable = Runnable { resumeLastApp() }

    /** Do kada posle paljenja čekamo da se pojavi Home ekran (pa vraćamo aplikaciju). */
    private var resumeWindowUntil = 0L
    private var resumeTries = 0
    private var lastResumeAt = 0L

    /** Pauze pre ponovnog otvaranja kad box vrati Home (posle svakog pokušaja duže). */
    private val RESUME_DELAYS = longArrayOf(800, 1_500, 3_000, 5_000, 8_000, 10_000, 15_000, 20_000)

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, 5_000L)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_SCREEN_ON -> onScreenOn()
                Intent.ACTION_DREAMING_STARTED -> dreaming = true
                Intent.ACTION_DREAMING_STOPPED -> dreaming = false
            }
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
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
        }
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
        removeGuard()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /**
     * launchers = Home ekran(i); ignored = sistem, tastature i sama aplikacija.
     * Ni jedni ni drugi se nikad ne blokiraju.
     */
    private fun buildPackageSets() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        // Podešavanja imaju rezervni "FallbackHome" (negativan prioritet). Nije pravi launcher,
        // a da se računa kao launcher, Podešavanja se noću ne bi blokirala.
        @Suppress("DEPRECATION")
        val l = packageManager.queryIntentActivities(home, 0)
            .filter { it.priority >= 0 && !it.activityInfo.name.contains("FallbackHome") }
            .map { it.activityInfo.packageName }.toMutableSet()
        // Podrazumevani Home ekran (ako je izabran) je uvek launcher.
        @Suppress("DEPRECATION")
        packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
            ?.takeIf { it != "android" }
            ?.let { l += it }
        launchers = l
        val s = mutableSetOf(packageName, "android", "com.android.systemui")
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.inputMethodList.forEach { s += it.packageName }
        ignored = s
        EventLog.add("Servis pokrenut. Launcher: " + launchers.joinToString())
    }

    private val launchable = mutableMapOf<String, Boolean>()

    /** Da li aplikacija ima ikonicu za pokretanje (pravu aplikaciju, ne sistemski prozor). */
    private fun isLaunchable(pkg: String): Boolean = launchable.getOrPut(pkg) {
        launchIntent(pkg) != null
    }

    private fun launchIntent(pkg: String): Intent? =
        packageManager.getLeanbackLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage(pkg)

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
        // Toast poruka aplikacije iz pozadine nije otvaranje te aplikacije.
        if (event.className?.toString()?.startsWith("android.widget.Toast") == true) return
        countUsage()
        if (pkg != fgPkg) EventLog.add("Na ekranu: $pkg")
        fgPkg = pkg
        val now = System.currentTimeMillis()
        if (pkg in launchers) {
            // Box se upravo upalio i pojavio se Home ekran: vrati poslednju aplikaciju.
            if (now < resumeWindowUntil && resumeTries < RESUME_DELAYS.size) {
                if (lastResumeAt > 0L) {
                    EventLog.add("Vraćeno na Home ${(now - lastResumeAt) / 100 / 10.0} s posle otvaranja")
                }
                // Svaki sledeći pokušaj čeka duže, dok box ne prestane da vraća Home.
                handler.removeCallbacks(resumeRunnable)
                handler.postDelayed(resumeRunnable, RESUME_DELAYS[resumeTries])
            }
            return
        }

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
            return
        }

        if (isLaunchable(pkg) && Prefs.lastApp(this) != pkg) {
            // Korisnik je sam otvorio drugu aplikaciju – nastavak više nije potreban.
            if (resumeWindowUntil > 0L) {
                resumeWindowUntil = 0L
                handler.removeCallbacks(resumeRunnable)
            }
            Prefs.setLastApp(this, pkg)
            EventLog.add("Poslednja aplikacija: $pkg")
        }
    }

    /** Da li je aplikacija sada blokirana (noćno zaključavanje ili potrošen limit). */
    private fun isBlocked(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        if (now >= Prefs.unlockedUntil(this) && Schedule.lockEnd(Prefs.load(this), now) != null) return true
        val left = Limits.leftSec(this, pkg)
        return left != null && left <= 0
    }

    /** Nastavljena aplikacija se zadržala 10 s – nastavak je uspeo, dalji Home je namerni. */
    private val resumeStableCheck = Runnable {
        val pkg = Prefs.lastApp(this)
        if (resumeWindowUntil > 0L && fgPkg != null && fgPkg == pkg) {
            resumeWindowUntil = 0L
            EventLog.add("Nastavak uspeo: $pkg ostaje otvoren")
        }
    }

    private fun goHome() {
        EventLog.add("TVOffZ vraća na Home")
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
        val now = System.currentTimeMillis()
        val t = Schedule.dueAction(cfg, now) ?: return false

        val key = Schedule.dayKey(t) + "|" + cfg.actionMins
        if (Prefs.lastFired(this) == key) return false
        Prefs.setLastFired(this, key)

        // Odbrojavanje se završava tačno u zadato vreme.
        val left = maxOf(3, ((t - now + 999L) / 1000L).toInt())
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
            pad.onComplete = { pin -> pad.verify(pin) { cancel() } }
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
        guardPending = false
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
        EventLog.add("Ekran ugašen" + if (dreaming) " (posle screensavera)" else "")
        handler.removeCallbacks(resumeRunnable)
        resumeWindowUntil = 0L
        val wasDreaming = dreaming
        dreaming = false
        val cfg = Prefs.load(this)
        if (!cfg.pinToSleep || !Prefs.hasPin(this)) return
        val now = System.currentTimeMillis()
        // Bez PIN-a se gasi samo zakazano gašenje (ili posle tačnog PIN-a).
        if (now < Prefs.allowSleepUntil(this)) return
        // Box je sam otišao na spavanje posle screensavera (niko ga nije koristio).
        if (wasDreaming) return

        guardPending = true
        EventLog.add("Gašenje bez PIN-a – budim box")
        // Drži procesor budnim dok ne probudimo ekran.
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvoffz:guard").acquire(5_000L)
        handler.postDelayed({ wakeWithGuard() }, 800L)
    }

    private fun onScreenOn() {
        EventLog.add("Ekran upaljen" + if (guardPending) " (PIN ekran zaštite)" else "")
        // Kad zaštita budi box, poslednja aplikacija se otvara tek kad se PIN ekran zatvori.
        if (guardPending) {
            showGuard()
            return
        }
        startResumeWindow(2_500L)
    }

    // ---- PIN ekran zaštite (prozor preko svega, launcher ga ne može prekriti) ----

    private var guardView: View? = null
    private val guardTimeout = Runnable { dismissGuard() }

    private fun restartGuardTimeout() {
        handler.removeCallbacks(guardTimeout)
        handler.postDelayed(guardTimeout, 60_000L)
    }

    private fun showGuard() {
        if (guardView != null) {
            restartGuardTimeout()
            return
        }
        val ctx = themed()
        val content = LayoutInflater.from(ctx).inflate(R.layout.overlay_locked, null)
        val root = KeyFrame(ctx).apply {
            addView(content)
            onBack = { dismissGuard() }
            onAnyKey = { restartGuardTimeout() }
        }
        content.findViewById<TextView>(R.id.tvLockTitle).setText(R.string.guard_title)
        content.findViewById<TextView>(R.id.tvLockDesc).setText(R.string.guard_desc)
        val btn = content.findViewById<Button>(R.id.btnBackHome)
        btn.setText(R.string.guard_continue)
        btn.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_play, 0, 0, 0)
        btn.setOnClickListener { dismissGuard() }
        val pad = PinPadView(ctx).apply {
            setTitle(getString(R.string.pin_enter_caps))
            onComplete = { pin ->
                verify(pin) {
                    EventLog.add("Tačan PIN – gasim box")
                    removeGuard()
                    handler.postDelayed({ sleepNow() }, 400L)
                }
            }
        }
        content.findViewById<FrameLayout>(R.id.lockPinBox).addView(pad)
        guardView = root
        wm.addView(root, overlayParams())
        root.post { pad.focusFirst() }
        restartGuardTimeout()
        EventLog.add("PIN ekran zaštite prikazan")
    }

    private fun removeGuard() {
        handler.removeCallbacks(guardTimeout)
        guardView?.let { v -> runCatching { wm.removeView(v) } }
        guardView = null
    }

    /** PIN ekran zaštite je zatvoren bez gašenja (Nazad, dugme ili isteklo vreme). */
    private fun dismissGuard() {
        removeGuard()
        guardPending = false
        EventLog.add("PIN ekran zatvoren bez gašenja")
        startResumeWindow(800L)
    }

    /**
     * Narednih 40 s: kad god se pojavi Home ekran, vrati poslednju aplikaciju
     * (neki boxovi pokažu Home tek nekoliko sekundi posle paljenja).
     */
    private fun startResumeWindow(firstDelay: Long) {
        resumeWindowUntil = System.currentTimeMillis() + 120_000L
        resumeTries = 0
        lastResumeAt = 0L
        handler.removeCallbacks(resumeRunnable)
        handler.postDelayed(resumeRunnable, firstDelay)
    }

    // ------------------------------------------------------------------
    // Nastavak poslednje aplikacije posle paljenja
    // ------------------------------------------------------------------

    private fun resumeLastApp() {
        fun skip(why: String) = EventLog.add("Nastavak preskočen: $why")
        if (!pm.isInteractive) return skip("ekran nije upaljen")
        if (!Prefs.load(this).resumeLast) return skip("opcija je isključena")
        val pkg = Prefs.lastApp(this) ?: return skip("nema zapamćene aplikacije")
        // Box se probudio pravo u neku aplikaciju – ništa ne treba.
        val fg = fgPkg
        if (fg != null && fg !in launchers && isLaunchable(fg)) {
            return skip("već je otvorena aplikacija $fg")
        }
        if (countdownView != null || blockView != null || guardView != null) {
            return skip("prikazano je odbrojavanje/blokada/PIN")
        }
        if (isBlocked(pkg)) return skip("$pkg je zaključana (noćno zaključavanje ili limit)")
        val intent = launchIntent(pkg) ?: return skip("$pkg nema ikonicu za pokretanje")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        resumeTries++
        lastResumeAt = System.currentTimeMillis()
        handler.removeCallbacks(resumeStableCheck)
        handler.postDelayed(resumeStableCheck, 10_000L)
        runCatching { startActivity(intent) }
            .onSuccess { EventLog.add("Otvaram poslednju aplikaciju: $pkg") }
            .onFailure { EventLog.add("Greška pri otvaranju $pkg: ${it.message}") }
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
                    p.verify(pin) {
                        removeBlock()
                        onUnlock()
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
