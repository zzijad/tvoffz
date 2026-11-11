package rs.zzijad.tvoffz

import android.app.Activity
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Lista instaliranih aplikacija sa dnevnim limitom za svaku. */
class LimitsActivity : Activity() {

    private data class App(val pkg: String, val label: String, val info: ResolveInfo)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_limits)
        val list = findViewById<LinearLayout>(R.id.list)

        var first: ImageButton? = null
        loadApps().forEach { app ->
            val btn = addRow(list, app)
            if (first == null) first = btn
        }
        first?.post { first?.requestFocus() }
    }

    private fun loadApps(): List<App> {
        val found = linkedMapOf<String, ResolveInfo>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(i, 0).forEach { r ->
                val p = r.activityInfo.packageName
                if (p != packageName && p !in found) found[p] = r
            }
        }
        return found.map { (p, r) -> App(p, r.loadLabel(packageManager).toString(), r) }
            .sortedWith(compareBy({ Limits.get(this, it.pkg) == 0 }, { it.label.lowercase() }))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Vraća levo dugme reda (za početni fokus). */
    private fun addRow(list: LinearLayout, app: App): ImageButton {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_row)
            setPadding(dp(16), 0, dp(12), 0)
        }
        list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply {
            topMargin = dp(8)
        })

        val icon = ImageView(this).apply {
            setImageDrawable(app.info.loadIcon(packageManager))
            importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val name = TextView(this).apply {
            text = app.label
            setTextColor(getColor(R.color.text))
            textSize = 18f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val used = TextView(this).apply {
            setTextColor(getColor(R.color.muted))
            textSize = 13f
        }
        texts.addView(name)
        texts.addView(used)
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(14)
        })

        val minus = ImageButton(this, null, 0, R.style.IconBtn).apply {
            setImageResource(R.drawable.ic_chevron_left)
            contentDescription = getString(R.string.earlier)
        }
        val value = TextView(this, null, 0, R.style.RowValue).apply { textSize = 20f }
        val plus = ImageButton(this, null, 0, R.style.IconBtn).apply {
            setImageResource(R.drawable.ic_chevron_right)
            contentDescription = getString(R.string.later)
        }
        row.addView(minus, LinearLayout.LayoutParams(dp(44), dp(44)))
        row.addView(value, LinearLayout.LayoutParams(dp(120), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(plus, LinearLayout.LayoutParams(dp(44), dp(44)))

        fun render() {
            val m = Limits.get(this, app.pkg)
            value.text = if (m == 0) getString(R.string.limit_none) else getString(R.string.limit_mins, m)
            value.setTextColor(getColor(if (m == 0) R.color.muted else R.color.accent))
            value.typeface = if (m == 0) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            val u = Limits.usedSec(this, app.pkg) / 60
            used.text = if (Limits.isUnlocked(this, app.pkg)) {
                getString(R.string.limit_used_unlocked, u)
            } else {
                getString(R.string.limit_used, u)
            }
        }
        minus.setOnClickListener {
            Limits.set(this, app.pkg, Limits.next(Limits.get(this, app.pkg), -1)); render()
        }
        plus.setOnClickListener {
            Limits.set(this, app.pkg, Limits.next(Limits.get(this, app.pkg), +1)); render()
        }
        render()
        return minus
    }
}
