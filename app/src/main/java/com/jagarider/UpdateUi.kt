package com.jagarider

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

/** Semak / tawar / pasang kemas kini — dikongsi skrin utama (semakan automatik) dan skrin Tetapan. */
object UpdateUi {

    /** status: teks kemajuan untuk dipapar (boleh diabaikan bila semakan senyap). */
    fun check(act: Activity, quiet: Boolean, status: (String) -> Unit) {
        if (!Updater.enabled) return
        if (!quiet) status(t("Sedang semak…", "Checking…", "正在检查…", "சரிபார்க்கிறது…"))
        Updater.check { rel, err ->
            if (act.isFinishing || act.isDestroyed) return@check
            if (err == null) Prefs.setLastUpdateCheck(act, System.currentTimeMillis())
            when {
                err != null -> if (!quiet) status(
                    t("Tak dapat semak (internet?): ", "Couldn't check (internet?): ", "无法检查（网络？）：", "சரிபார்க்க முடியவில்லை (இணையம்?): ") + err.message
                )
                rel == null -> if (!quiet) status(
                    t("✅ Dah versi terkini.", "✅ You have the latest version.", "✅ 已是最新版本。", "✅ சமீபத்திய பதிப்பு உள்ளது.")
                )
                else -> {
                    status(t("Versi baru ada: ", "New version available: ", "有新版本：", "புதிய பதிப்பு உள்ளது: ") + rel.version)
                    showDialog(act, rel, status)
                }
            }
        }
    }

    private fun showDialog(act: Activity, rel: Updater.Release, status: (String) -> Unit) {
        AlertDialog.Builder(act)
            .setTitle(t("Versi baru ${rel.version}", "New version ${rel.version}", "新版本 ${rel.version}", "புதிய பதிப்பு ${rel.version}"))
            .setMessage(
                Updater.shortNotes(rel.notes).ifBlank {
                    t("Ada kemas kini untuk JagaRide.", "An update for JagaRide is available.", "JagaRide 有可用更新。", "JagaRide-க்கு புதுப்பிப்பு உள்ளது.")
                }
            )
            .setPositiveButton(t("Kemas kini", "Update", "更新", "புதுப்பி")) { _, _ -> install(act, rel, status) }
            .setNegativeButton(t("Nanti", "Later", "稍后", "பின்னர்"), null)
            .show()
    }

    private fun install(act: Activity, rel: Updater.Release, status: (String) -> Unit) {
        if (!Updater.canInstall(act)) {
            Toast.makeText(
                act,
                t(
                    "Benarkan JagaRide pasang app, kemudian tekan Kemas kini sekali lagi",
                    "Allow JagaRide to install apps, then tap Update again",
                    "请允许 JagaRide 安装应用，然后再点一次更新",
                    "ஆப்களை நிறுவ JagaRide-ஐ அனுமதித்து, மீண்டும் புதுப்பி அழுத்தவும்",
                ),
                Toast.LENGTH_LONG,
            ).show()
            act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
            return
        }
        fun downloading(pct: Int) = t("Muat turun… $pct%", "Downloading… $pct%", "下载中… $pct%", "பதிவிறக்குகிறது… $pct%")
        status(downloading(0))
        Updater.install(
            act, rel,
            onProgress = { pct -> status(downloading(pct)) },
            onError = { e -> status(t("Muat turun gagal: ", "Download failed: ", "下载失败：", "பதிவிறக்கம் தோல்வி: ") + e.message) },
        )
    }
}
