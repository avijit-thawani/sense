package com.sense.app

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/** Theme colours, named like daisyUI's (base-100/200/300, content, primary) as in ../mandarin. */
data class Palette(
    val dark: Boolean,
    val bg: Int,          // page
    val card: Int,        // cards, tab bar
    val line: Int,        // dividers, inactive tracks
    val text: Int,
    val muted: Int,
    val primary: Int,     // filled buttons, active tab background tint
    val onPrimary: Int,
    val primaryText: Int, // the accent used as text: readable on bg
)

object Ui {
    val DARK = Palette(true, Color.rgb(12, 12, 14), Color.rgb(28, 28, 33), Color.rgb(48, 48, 56),
        Color.WHITE, Color.rgb(150, 150, 160), Color.rgb(255, 204, 0), Color.BLACK, Color.rgb(255, 204, 0))
    val LIGHT = Palette(false, Color.rgb(244, 244, 246), Color.WHITE, Color.rgb(226, 226, 230),
        Color.rgb(28, 28, 32), Color.rgb(110, 110, 118), Color.rgb(255, 200, 0), Color.rgb(28, 28, 32), Color.rgb(160, 110, 0))

    var p = DARK

    /** "system", "light" or "dark", kept in the app's preferences. */
    fun mode(ctx: Context): String = ctx.getSharedPreferences("sense", Context.MODE_PRIVATE).getString("theme", "system")!!

    fun apply(ctx: Context) {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        p = when (mode(ctx)) { "light" -> LIGHT; "dark" -> DARK; else -> if (night) DARK else LIGHT }
    }

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radiusPx: Float, stroke: Int? = null, strokePx: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radiusPx
        stroke?.let { setStroke(strokePx, it) }
    }

    fun text(ctx: Context, s: CharSequence, size: Float, color: Int = p.text, bold: Boolean = false) = TextView(ctx).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** Page title with an optional subtitle, like the settings header in ../mandarin. */
    fun header(ctx: Context, title: String, subtitle: String? = null, onBack: (() -> Unit)? = null) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(ctx, 16), dp(ctx, 44), dp(ctx, 16), dp(ctx, 12))
        onBack?.let {
            addView(text(ctx, "‹", 34f, p.primaryText, true).apply {
                setPadding(0, 0, dp(ctx, 14), dp(ctx, 4)); setOnClickListener { it() }; contentDescription = "Back"
            })
        }
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(ctx, title, 24f, bold = true))
            subtitle?.let { addView(text(ctx, it, 13f, p.muted)) }
        })
    }

    /** Section heading: small caps-style label above a group of cards. */
    fun section(ctx: Context, title: String) = text(ctx, title.uppercase(), 12f, p.muted, true).apply {
        letterSpacing = 0.08f; setPadding(dp(ctx, 4), dp(ctx, 22), 0, dp(ctx, 8))
    }

    fun card(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(p.card, dp(ctx, 14).toFloat(), if (p.dark) null else p.line, 1)
        setPadding(dp(ctx, 16), dp(ctx, 4), dp(ctx, 16), dp(ctx, 4))
    }

    fun divider(ctx: Context) = View(ctx).apply { setBackgroundColor(p.line) }

    /** A setting: title and one-line explanation on the left, a control on the right. */
    fun row(ctx: Context, title: String, detail: String?, control: View?) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(ctx, 12), 0, dp(ctx, 12))
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(ctx, title, 16f, bold = true))
            detail?.let { addView(text(ctx, it, 13f, p.muted)) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        control?.let { addView(it) }
    }

    fun toggle(ctx: Context, on: Boolean, onChange: (Boolean) -> Unit) = Switch(ctx).apply {
        isChecked = on
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(p.primary, if (p.dark) Color.rgb(200, 200, 205) else Color.WHITE))
        trackTintList = ColorStateList(states, intArrayOf(Color.argb(140, Color.red(p.primary), Color.green(p.primary), Color.blue(p.primary)), p.line))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    /** A row of options, one selected (theme: System / Light / Dark). */
    fun segmented(ctx: Context, options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(p.bg, dp(ctx, 12).toFloat())
            setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
            for ((id, label) in options) {
                val on = id == selected
                addView(text(ctx, label, 15f, if (on) p.onPrimary else p.text, on).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(ctx, 10), 0, dp(ctx, 10))
                    if (on) background = rounded(p.primary, dp(ctx, 9).toFloat())
                    contentDescription = label + if (on) ", selected" else ""
                    setOnClickListener { onPick(id) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }

    /** A filled primary button. */
    fun button(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(p.onPrimary)
        background = rounded(p.primary, dp(ctx, 14).toFloat())
        setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        setOnClickListener { onClick() }
    }

    /** A plain secondary button (outlined). */
    fun quietButton(ctx: Context, label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        setTextColor(p.text)
        background = rounded(p.card, dp(ctx, 12).toFloat(), p.line, dp(ctx, 1))
        setPadding(dp(ctx, 14), dp(ctx, 10), dp(ctx, 14), dp(ctx, 10))
        setOnClickListener { onClick() }
    }

    /** A card whose body is hidden until its header is tapped. */
    fun collapsible(ctx: Context, title: String, open: Boolean = false, body: (LinearLayout) -> Unit): View {
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = if (open) View.VISIBLE else View.GONE }
        body(content)
        val chevron = text(ctx, if (open) "⌃" else "⌄", 18f, p.muted, true)
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 14), 0, dp(ctx, 14))
            addView(text(ctx, title, 16f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(chevron)
            contentDescription = "$title, ${if (open) "expanded" else "collapsed"}"
            setOnClickListener {
                val show = content.visibility != View.VISIBLE
                content.visibility = if (show) View.VISIBLE else View.GONE
                chevron.text = if (show) "⌃" else "⌄"
                contentDescription = "$title, ${if (show) "expanded" else "collapsed"}"
            }
        }
        return card(ctx).apply { addView(head); addView(content) }
    }

    /** color null keeps the drawable's own colours (brand logos). */
    fun icon(ctx: Context, res: Int, color: Int?) = ImageView(ctx).apply {
        setImageResource(res); color?.let { imageTintList = ColorStateList.valueOf(it) }
    }
}
