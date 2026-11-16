package app.drafty.service

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.drafty.R

/** Small view kit for Drafty's floating cards: compact, rounded, theme-aware. */
class OverlayUi(private val ctx: Context) {
    fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()
    fun color(res: Int) = ctx.getColor(res)

    val accent get() = color(R.color.accent)
    val fg get() = color(R.color.panel_fg)
    val muted get() = color(R.color.panel_muted)
    val bg get() = color(R.color.panel_bg)
    val bg2 get() = color(R.color.panel_bg2)
    val line get() = color(R.color.panel_line)

    fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        stroke?.let { setStroke(dp(1f), it) }
    }

    fun ripple(content: Drawable): Drawable = RippleDrawable(ColorStateList.valueOf(0x33D97757), content, null)

    fun card(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(bg, 24f, line)
        elevation = dp(18f).toFloat()
        setPadding(dp(14f), dp(12f), dp(14f), dp(14f))
    }

    fun text(s: CharSequence, sizeSp: Float = 14f, color: Int = fg, bold: Boolean = false) = TextView(ctx).apply {
        text = s
        textSize = sizeSp
        setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setLineSpacing(0f, 1.15f)
    }

    fun logo(sizeDp: Float = 24f) = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_spark)
        background = rounded(accent, sizeDp * 0.32f)
        val p = dp(sizeDp * 0.24f)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    fun header(title: String, sub: String?, onClose: () -> Unit) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(logo())
        addView(text(title, 15f, fg, true).apply { setPadding(dp(10f), 0, dp(8f), 0) })
        addView(text(sub ?: "", 11.5f, muted).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(icon(R.drawable.ic_close, "Close", sizeDp = 32f, filled = false, onClick = onClose))
    }

    fun icon(res: Int, desc: String, sizeDp: Float = 40f, filled: Boolean = false, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageResource(res)
        contentDescription = desc
        val p = dp(sizeDp * 0.26f)
        setPadding(p, p, p, p)
        background = ripple(rounded(if (filled) accent else bg2, sizeDp / 2))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
        setOnClickListener { onClick() }
    }

    fun chip(label: String, primary: Boolean = false, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 13f
        setTextColor(if (primary) accent else fg)
        typeface = Typeface.create(Typeface.DEFAULT, if (primary) Typeface.BOLD else Typeface.NORMAL)
        background = ripple(if (primary) rounded(0x1FD97757, 999f) else rounded(bg2, 999f, line))
        setPadding(dp(13f), dp(7f), dp(13f), dp(7f))
        setOnClickListener { onClick() }
    }

    fun row(views: List<View>, spacingDp: Float = 6f, scroll: Boolean = true): View {
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            views.forEachIndexed { i, v ->
                val lp = (v.layoutParams as? LinearLayout.LayoutParams)
                    ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                if (i > 0) lp.marginStart = dp(spacingDp)
                addView(v, lp)
            }
        }
        if (!scroll) return inner
        return HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(inner)
        }
    }

    fun input(hint: String) = EditText(ctx).apply {
        this.hint = hint
        textSize = 14f
        setTextColor(fg)
        setHintTextColor(muted)
        background = rounded(bg2, 18f)
        setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_SEND
        maxLines = 3
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    fun primaryButton(label: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(0xFFFFFFFF.toInt())
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        background = ripple(rounded(accent, 999f))
        setPadding(dp(18f), dp(10f), dp(18f), dp(10f))
        setOnClickListener { onClick() }
    }

    fun spacer(hDp: Float) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(hDp)) }
}
