package com.jagarider

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Widget skrin utama: tahap hujan sekarang + ramalan ringkas. Dikemas kini bila app dibuka (MainActivity)
 * dan oleh RainAlertJob (~15 minit) — data terakhir disimpan supaya widget baru terus ada isi.
 */
class RainWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, mgr: AppWidgetManager, ids: IntArray) {
        push(c)
        RainAlert.schedule(c) // pastikan semakan latar berjalan untuk widget
    }

    override fun onEnabled(c: Context) = RainAlert.schedule(c)

    companion object {
        private fun sp(c: Context) = c.getSharedPreferences("widget", Context.MODE_PRIVATE)

        fun has(c: Context): Boolean =
            AppWidgetManager.getInstance(c).getAppWidgetIds(ComponentName(c, RainWidget::class.java)).isNotEmpty()

        /** Simpan data terkini dan kemas kini semua widget. */
        fun update(c: Context, icon: String, level: String, levelColor: Int, next: String) {
            sp(c).edit()
                .putString("icon", icon)
                .putString("level", level)
                .putInt("color", levelColor)
                .putString("next", next)
                .putLong("time", System.currentTimeMillis())
                .apply()
            push(c)
        }

        private fun push(c: Context) {
            val mgr = AppWidgetManager.getInstance(c)
            val ids = mgr.getAppWidgetIds(ComponentName(c, RainWidget::class.java))
            if (ids.isEmpty()) return
            I18n.lang = Prefs.lang(c)
            Clock.h24 = Prefs.clock24(c)
            val p = sp(c)
            val v = RemoteViews(c.packageName, R.layout.widget_rain)
            v.setTextViewText(R.id.w_icon, p.getString("icon", "🏍️"))
            v.setTextViewText(R.id.w_level, p.getString("level", null) ?: "JagaRide")
            v.setTextColor(R.id.w_level, p.getInt("color", 0xFF263238.toInt()))
            v.setTextViewText(R.id.w_next, p.getString("next", null) ?: t("Buka app untuk semak hujan", "Open the app to check rain", "打开应用查看降雨", "மழையைச் சரிபார்க்க ஆப்பைத் திறக்கவும்"))
            val time = p.getLong("time", 0L)
            v.setTextViewText(R.id.w_time, if (time > 0) "JagaRide · " + Clock.time(time) else "JagaRide")
            val open = PendingIntent.getActivity(
                c, 6, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            mgr.updateAppWidget(ids, v)
        }
    }
}
