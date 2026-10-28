package com.jagarider

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Skrin utama JagaRider (asas). Fungsi rider akan ditambah kemudian. */
class MainActivity : Activity() {

    private val grey = Color.parseColor("#607D8B")
    private var updateText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(col)
        }
        // targetSdk 36 = edge-to-edge: elak kandungan di bawah status/nav bar
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)

        col.addView(label("🏍️ JagaRider", 28f, bold = true, color = Color.parseColor("#E65100")))
        col.addView(label("Teman keselamatan untuk penunggang motor.", 16f, color = grey))
        col.addView(label("Fungsi utama akan datang dalam versi seterusnya — app ini akan kemas kini sendiri.", 15f))

        col.addView(header("Kemas kini"))
        col.addView(label("Versi sekarang: " + Updater.currentVersion, 15f, color = grey))
        updateText = label("", 15f).also { col.addView(it) }
        col.addView(Button(this).apply {
            text = "Semak kemas kini"
            setOnClickListener { checkUpdate(quiet = false) }
        })
        col.addView(Switch(this).apply {
            text = "Semak kemas kini automatik setiap hari"
            textSize = 15f
            isChecked = Prefs.autoUpdate(this@MainActivity)
            setPadding(0, dp(12), 0, dp(12))
            setOnCheckedChangeListener { _, on ->
                Prefs.setAutoUpdate(this@MainActivity, on)
                Updater.schedule(this@MainActivity)
            }
        })
        if (!Updater.enabled) updateText?.text = "Kemas kini dimatikan (tiada repo)."

        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        if (Updater.enabled) {
            Updater.schedule(this)
            val fromNotification = intent.getBooleanExtra(EXTRA_CHECK_UPDATE, false)
            val stale = System.currentTimeMillis() - Prefs.lastUpdateCheck(this) > 12 * 60 * 60_000L
            if (fromNotification || (Prefs.autoUpdate(this) && stale)) checkUpdate(quiet = !fromNotification)
        }
    }

    // ------------------------------------------------------------ kemas kini

    private fun checkUpdate(quiet: Boolean) {
        if (!Updater.enabled) return
        if (!quiet) updateText?.text = "Sedang semak…"
        Updater.check { rel, err ->
            if (isFinishing) return@check
            if (err == null) Prefs.setLastUpdateCheck(this, System.currentTimeMillis())
            when {
                err != null -> if (!quiet) updateText?.text = "Tak dapat semak (internet?): " + err.message
                rel == null -> if (!quiet) updateText?.text = "✅ Dah versi terkini."
                else -> {
                    updateText?.text = "Versi baru ada: " + rel.version
                    showUpdateDialog(rel)
                }
            }
        }
    }

    private fun showUpdateDialog(rel: Updater.Release) {
        AlertDialog.Builder(this)
            .setTitle("Versi baru ${rel.version}")
            .setMessage(Updater.shortNotes(rel.notes).ifBlank { "Ada kemas kini untuk JagaRider." })
            .setPositiveButton("Kemas kini") { _, _ -> startInstall(rel) }
            .setNegativeButton("Nanti", null)
            .show()
    }

    private fun startInstall(rel: Updater.Release) {
        if (!Updater.canInstall(this)) {
            Toast.makeText(this, "Benarkan JagaRider pasang app, kemudian tekan Kemas kini sekali lagi", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        updateText?.text = "Muat turun… 0%"
        Updater.install(
            this, rel,
            onProgress = { pct -> updateText?.text = "Muat turun… $pct%" },
            onError = { e -> updateText?.text = "Muat turun gagal: " + e.message },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CHECK_UPDATE, false)) checkUpdate(quiet = false)
    }

    // ------------------------------------------------------------ UI kecil

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, bold: Boolean = false, color: Int = Color.parseColor("#263238")) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
            gravity = Gravity.START
            visibility = View.VISIBLE
        }

    private fun header(text: String) = label(text, 20f, bold = true).apply { setPadding(0, dp(24), 0, dp(4)) }

    companion object {
        const val EXTRA_CHECK_UPDATE = "check_update"
    }
}
