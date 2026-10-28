package com.jagarider

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Warna app untuk mod cerah / gelap. */
class Palette(val dark: Boolean) {
    private fun c(dk: String, lt: String) = Color.parseColor(if (dark) dk else lt)

    val bg = c("#0F1418", "#F5F7F9")
    val card = c("#1B2329", "#FFFFFF")
    val text = c("#ECEFF1", "#263238")
    val sub = c("#90A4AE", "#607D8B")
    val border = c("#37474F", "#CFD8DC")
    val mapBg = c("#263238", "#E5E3DF")
    val rainTint = c("#12324A", "#E3F2FD")
    val stormTint = c("#4A2E0F", "#FFE0B2")
    val probHigh = c("#90CAF9", "#1565C0")
    val probMid = c("#64B5F6", "#1E88E5")
    val probLow = c("#78909C", "#90A4AE")
    val orange = Color.parseColor("#E65100")
}

object Ui {
    fun isDark(ctx: Context): Boolean = when (Prefs.theme(ctx)) {
        Prefs.THEME_DARK -> true
        Prefs.THEME_LIGHT -> false
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun palette(ctx: Context) = Palette(isDark(ctx))
}

/**
 * Asas skrin JagaRide: pasang bahasa + tema sebelum UI dibina, dan bina semula skrin
 * bila bahasa / tema ditukar di skrin Tetapan.
 */
abstract class BaseActivity : Activity() {

    lateinit var pal: Palette
    private var appliedLang: Lang? = null
    private var appliedDark: Boolean? = null
    private var appliedClock: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        I18n.lang = Prefs.lang(this)
        Clock.h24 = Prefs.clock24(this)
        appliedClock = Clock.h24
        pal = Ui.palette(this)
        appliedLang = I18n.lang
        appliedDark = pal.dark
        setTheme(if (pal.dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        I18n.lang = Prefs.lang(this)
        Clock.h24 = Prefs.clock24(this)
        if (I18n.lang != appliedLang || Ui.isDark(this) != appliedDark || Clock.h24 != appliedClock) recreate()
    }

    /** Selepas setContentView: latar, edge-to-edge (targetSdk 36) dan warna ikon status bar. */
    protected fun setupWindow(root: View) {
        root.setBackgroundColor(pal.bg)
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        val light = !pal.dark
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) flags else 0, flags)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (light) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        }
    }

    // ------------------------------------------------------------ UI kecil

    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            setColor(pal.card)
            cornerRadius = dp(16).toFloat()
        }
        elevation = dp(2).toFloat()
    }

    fun label(text: String, size: Float, bold: Boolean = false, color: Int = pal.text) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        }

    fun header(text: String) = label(text, 18f, bold = true).apply { setPadding(0, dp(20), 0, dp(2)) }

    /** Butang bulat: utama (oren) atau biasa (warna kad + garis tepi). */
    fun pill(text: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 15f
        stateListAnimator = null
        gravity = Gravity.CENTER
        stylePill(this, primary)
        setOnClickListener { onClick() }
    }

    fun stylePill(b: Button, primary: Boolean) {
        b.background = GradientDrawable().apply {
            setColor(if (primary) pal.orange else pal.card)
            setStroke(dp(1), if (primary) pal.orange else pal.border)
            cornerRadius = dp(22).toFloat()
        }
        b.setTextColor(if (primary) Color.WHITE else pal.text)
        b.setTypeface(null, if (primary) Typeface.BOLD else Typeface.NORMAL)
    }
}
