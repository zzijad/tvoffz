package rs.zzijad.tvoffz

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val REQ_VERIFY = 1
        private const val REQ_SETUP = 2
        private val DAY_NAMES = arrayOf(
            "ponedeljak", "utorak", "sreda", "četvrtak", "petak", "subota", "nedelja",
        )
    }

    private var cfg = Config()
    private var verified = false
    private var childOpen = false

    private lateinit var mainContent: View
    private lateinit var setupContent: View
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var tvNextTime: TextView
    private lateinit var tvNextAction: TextView
    private lateinit var tvWarn: TextView
    private lateinit var tvLock: TextView
    private lateinit var tvPinHint: TextView
    private lateinit var btnEnabled: Button
    private lateinit var tvTime: TextView
    private lateinit var tvLockTime: TextView
    private lateinit var btnShutdown: Button
    private lateinit var btnHome: Button
    private lateinit var tvSecs: TextView
    private lateinit var btnPin: Button
    private lateinit var btnOpenSettings: Button
    private lateinit var dayButtons: List<Button>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mainContent = findViewById(R.id.mainContent)
        setupContent = findViewById(R.id.setupContent)
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        tvNextTime = findViewById(R.id.tvNextTime)
        tvNextAction = findViewById(R.id.tvNextAction)
        tvWarn = findViewById(R.id.tvWarn)
        tvLock = findViewById(R.id.tvLock)
        tvPinHint = findViewById(R.id.tvPinHint)
        btnEnabled = findViewById(R.id.btnEnabled)
        tvTime = findViewById(R.id.tvTime)
        tvLockTime = findViewById(R.id.tvLockTime)
        btnShutdown = findViewById(R.id.btnShutdown)
        btnHome = findViewById(R.id.btnHome)
        tvSecs = findViewById(R.id.tvSecs)
        btnPin = findViewById(R.id.btnPin)
        btnOpenSettings = findViewById(R.id.btnOpenSettings)
        dayButtons = listOf(
            R.id.day0, R.id.day1, R.id.day2, R.id.day3, R.id.day4, R.id.day5, R.id.day6,
        ).map { findViewById<Button>(it) }

        btnEnabled.setOnClickListener { update { it.copy(enabled = !it.enabled) } }

        findViewById<ImageButton>(R.id.btnTimeMinus).setOnClickListener {
            update { it.copy(actionMins = (it.actionMins + 1440 - 5) % 1440) }
        }
        findViewById<ImageButton>(R.id.btnTimePlus).setOnClickListener {
            update { it.copy(actionMins = (it.actionMins + 5) % 1440) }
        }
        findViewById<ImageButton>(R.id.btnLockMinus).setOnClickListener {
            update { it.copy(lockMins = (it.lockMins + 1440 - 15) % 1440) }
        }
        findViewById<ImageButton>(R.id.btnLockPlus).setOnClickListener {
            update { it.copy(lockMins = (it.lockMins + 15) % 1440) }
        }
        findViewById<ImageButton>(R.id.btnSecMinus).setOnClickListener {
            update { it.copy(secs = maxOf(10, it.secs - 10)) }
        }
        findViewById<ImageButton>(R.id.btnSecPlus).setOnClickListener {
            update { it.copy(secs = minOf(300, it.secs + 10)) }
        }

        btnShutdown.setOnClickListener { update { it.copy(shutdown = true) } }
        btnHome.setOnClickListener { update { it.copy(shutdown = false) } }

        dayButtons.forEachIndexed { i, b ->
            b.setOnClickListener { update { it.copy(days = it.days xor (1 shl i)) } }
        }

        findViewById<Button>(R.id.btnTest).setOnClickListener {
            val s = TvOffService.instance
            if (s == null) {
                Toast.makeText(this, R.string.service_off, Toast.LENGTH_LONG).show()
            } else {
                s.startTest()
            }
        }

        btnPin.setOnClickListener {
            childOpen = true
            startActivityForResult(
                Intent(this, PinActivity::class.java)
                    .putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_SETUP),
                REQ_SETUP,
            )
        }

        btnOpenSettings.setOnClickListener { openAccessibilitySettings() }
        findViewById<Button>(R.id.btnLater).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        cfg = Prefs.load(this)
        val serviceOn = isServiceEnabled()
        mainContent.visibility = if (serviceOn) View.VISIBLE else View.GONE
        setupContent.visibility = if (serviceOn) View.GONE else View.VISIBLE
        render()

        if (serviceOn && Prefs.hasPin(this) && !verified) {
            childOpen = true
            startActivityForResult(
                Intent(this, PinActivity::class.java)
                    .putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_VERIFY),
                REQ_VERIFY,
            )
            return
        }

        val focusTarget: View = if (serviceOn) btnEnabled else btnOpenSettings
        if (currentFocus == null || currentFocus?.visibility != View.VISIBLE) {
            focusTarget.post { focusTarget.requestFocus() }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!childOpen) verified = false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        childOpen = false
        if (requestCode == REQ_VERIFY) {
            if (resultCode == RESULT_OK) verified = true else finish()
        }
    }

    private fun update(change: (Config) -> Config) {
        cfg = change(cfg)
        Prefs.save(this, cfg)
        render()
    }

    private fun render() {
        btnEnabled.isActivated = cfg.enabled
        btnEnabled.setText(if (cfg.enabled) R.string.on else R.string.off)
        statusDot.setBackgroundResource(if (cfg.enabled) R.drawable.dot_green else R.drawable.dot_gray)
        statusText.setText(if (cfg.enabled) R.string.status_on else R.string.status_off)

        tvTime.text = Schedule.fmt(cfg.actionMins)
        val noLock = cfg.lockMins == cfg.actionMins
        tvLockTime.text = if (noLock) "—" else Schedule.fmt(cfg.lockMins)
        btnShutdown.isActivated = cfg.shutdown
        btnHome.isActivated = !cfg.shutdown
        tvSecs.text = getString(R.string.secs_value, cfg.secs)
        dayButtons.forEachIndexed { i, b -> b.isActivated = Schedule.isDayOn(cfg.days, i) }

        val now = System.currentTimeMillis()
        val next = Schedule.nextAction(cfg, now)
        val actionLabel = getString(
            if (cfg.shutdown) R.string.action_label_shutdown else R.string.action_label_home,
        )
        if (next == null) {
            tvNextTime.text = "--:--"
            tvNextAction.text = getString(if (cfg.enabled) R.string.next_none else R.string.status_off)
        } else {
            tvNextTime.text = Schedule.fmt(cfg.actionMins)
            tvNextAction.text = "$actionLabel · ${dayLabel(now, next)}"
        }
        tvWarn.text = getString(R.string.warn_before, cfg.secs)
        tvLock.text = if (noLock) getString(R.string.lock_none) else getString(R.string.lock_until, Schedule.fmt(cfg.lockMins))

        val hasPin = Prefs.hasPin(this)
        tvPinHint.visibility = if (!noLock && !hasPin) View.VISIBLE else View.GONE
        btnPin.setText(if (hasPin) R.string.pin_change else R.string.pin_set)
    }

    private fun dayLabel(now: Long, t: Long): String =
        when (val d = Schedule.daysFromToday(now, t)) {
            0 -> getString(R.string.today)
            1 -> getString(R.string.tomorrow)
            else -> DAY_NAMES[Schedule.dayIndexAt(now, d)]
        }

    private fun isServiceEnabled(): Boolean {
        if (TvOffService.instance != null) return true
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val cn = ComponentName(this, TvOffService::class.java)
        return enabled.split(':').any {
            it.equals(cn.flattenToString(), ignoreCase = true) ||
                it.equals(cn.flattenToShortString(), ignoreCase = true)
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, R.string.setup_step2, Toast.LENGTH_LONG).show()
            }
        }
    }
}
