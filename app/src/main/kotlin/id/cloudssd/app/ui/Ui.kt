package id.cloudssd.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView

/** Palet & helper tampilan (tanpa AndroidX, hanya framework). Mode gelap, aksen biru. */
object C {
    val BG = Color.parseColor("#0A0B0D")
    val SURFACE = Color.parseColor("#121419")
    val SURFACE2 = Color.parseColor("#1A1D24")
    val BORDER = Color.parseColor("#252A33")
    val TEXT = Color.parseColor("#F3F5F8")
    val TEXT2 = Color.parseColor("#C3C9D3")
    val MUTED = Color.parseColor("#8A93A3")
    val ACCENT = Color.parseColor("#3B82F6")
    val OK = Color.parseColor("#34D399")
    val WARN = Color.parseColor("#FBBF24")
    val BAD = Color.parseColor("#F87171")
}

class Ui(private val ctx: Context) {
    fun dp(v: Number): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun rounded(color: Int, radiusDp: Int = 14, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    fun vertical(padding: Int = 0, spacing: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
        if (spacing > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(1, dp(spacing)); setColor(Color.TRANSPARENT) }
        }
    }

    fun horizontal(spacing: Int = 10): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        dividerDrawable = GradientDrawable().apply { setSize(dp(spacing), 1); setColor(Color.TRANSPARENT) }
    }

    fun card(): LinearLayout = vertical(18, 10).apply { background = rounded(C.SURFACE, 16, C.BORDER) }

    fun text(s: CharSequence, size: Float = 15f, color: Int = C.TEXT, bold: Boolean = false, mono: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s; textSize = size; setTextColor(color)
            typeface = when { mono -> Typeface.MONOSPACE; bold -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD); else -> Typeface.DEFAULT }
            setLineSpacing(0f, 1.15f)
        }

    fun label(s: String): TextView = text(s.uppercase(), 12f, C.MUTED, bold = true).apply { letterSpacing = 0.06f }

    fun button(s: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit): Button = Button(ctx).apply {
        text = s; isAllCaps = false; textSize = 14.5f
        setTextColor(if (primary) Color.WHITE else if (danger) C.BAD else C.TEXT)
        background = rounded(if (primary) C.ACCENT else C.SURFACE2, 10, if (primary) null else C.BORDER)
        minHeight = dp(44); minimumHeight = dp(44)
        setPadding(dp(16), 0, dp(16), 0)
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    fun input(hint: String, password: Boolean = false, number: Boolean = false): EditText = EditText(ctx).apply {
        this.hint = hint; setHintTextColor(C.MUTED); setTextColor(C.TEXT); textSize = 15f
        background = rounded(C.BG, 10, C.BORDER)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            number -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        isSingleLine = true
    }

    @Suppress("DEPRECATION")
    fun switch(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val sw = Switch(ctx).apply {
            isChecked = checked
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(Color.WHITE, C.TEXT2))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(C.ACCENT, C.BORDER))
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        val texts = vertical(0, 2).apply { addView(text(title, 15f)); addView(text(desc, 13f, C.MUTED)) }
        return horizontal(14).apply {
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sw)
            setPadding(0, dp(6), 0, dp(6))
            tag = sw
        }
    }

    fun dot(color: Int): View = View(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
    }

    fun meter(): ProgressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(C.ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(C.BORDER)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10))
    }

    fun banner(title: String, body: String, color: Int): LinearLayout = vertical(14, 4).apply {
        background = rounded(Color.argb(28, Color.red(color), Color.green(color), Color.blue(color)), 12, Color.argb(90, Color.red(color), Color.green(color), Color.blue(color)))
        addView(text(title, 15f, color, bold = true))
        addView(text(body, 13.5f, C.TEXT2))
    }

    fun matchWidth(v: View): View { v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); return v }
}

fun formatBytes(n: Long?): String {
    if (n == null) return "—"
    if (n < 1000) return "$n B"
    val units = arrayOf("kB", "MB", "GB", "TB", "PB")
    var v = n.toDouble(); var i = -1
    do { v /= 1000; i++ } while (v >= 1000 && i < units.size - 1)
    return String.format(java.util.Locale("id", "ID"), if (v < 10) "%.2f %s" else "%.1f %s", v, units[i])
}
