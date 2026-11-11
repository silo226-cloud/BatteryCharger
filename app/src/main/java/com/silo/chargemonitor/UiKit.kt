package com.silo.chargemonitor

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Shared look & feel helpers for the app's screens. */
abstract class BaseScreen : Activity() {
    protected val values = HashMap<String, TextView>()

    protected fun card(c1: Int, c2: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2)).apply {
            cornerRadius = dp(22f); setStroke(dp(1f).toInt(), 0x22FFFFFF)
        }
        val p = dp(16f).toInt(); setPadding(p, p, p, p)
        layoutParams = lp(top = 14f)
    }

    protected fun lp(top: Float = 0f) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top).toInt() }

    protected fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        textDirection = View.TEXT_DIRECTION_RTL
    }

    protected fun title(s: String) = text(s, 17f, Color.WHITE, bold = true)
    protected fun small(s: String) = text(s, 11.5f, 0x99FFFFFF.toInt()).apply { setPadding(0, dp(8f).toInt(), 0, 0) }

    /** Two-column grid of label/value tiles. */
    protected fun grid(items: List<Pair<String, String>>): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.chunked(2).forEach { row ->
            val r = LinearLayout(this)
            row.forEach { (key, label) ->
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply { cornerRadius = dp(14f); setColor(0x14FFFFFF) }
                    val p = dp(10f).toInt(); setPadding(p, p, p, p)
                }
                tile.addView(text(label, 11.5f, 0x99FFFFFF.toInt()))
                val v = text("—", 17f, Color.WHITE, bold = true)
                values[key] = v; tile.addView(v)
                r.addView(tile, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    val m = dp(4f).toInt(); setMargins(m, m, m, m)
                })
            }
            if (row.size == 1) r.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
            col.addView(r)
        }
        return col.apply { layoutParams = lp(top = 8f) }
    }

    protected fun set(key: String, v: String, color: Int = Color.WHITE) {
        values[key]?.apply { text = v; setTextColor(color) }
    }


    protected fun button(label: String, c1: Int, c2: Int, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        val p = dp(14f).toInt(); setPadding(p, p, p, p)
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2)).apply { cornerRadius = dp(16f) }
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
        layoutParams = lp(top = 10f)
    }
}
