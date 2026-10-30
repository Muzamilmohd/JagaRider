package com.jagarider

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Auto rekod ride. Peringkat 1: sensor gerakan Google (Activity Recognition, hampir tiada bateri) kesan
 * "mula naik kenderaan". Peringkat 2: LocationShareService hidupkan GPS dalam mod auto — rekod bermula
 * bila laju ≥ 20 km/j selama 30 s, batal kalau tak sampai dalam 3 minit, tamat bila berhenti 5 minit.
 */
object AutoRide {

    /** Laju (km/j) untuk sahkan ride, dan berapa lama mesti dikekalkan. */
    const val START_KMH = 20.0
    const val CONFIRM_MS = 30_000L
    /** Batal kalau tak capai START_KMH dalam tempoh ini selepas sensor kesan kenderaan. */
    const val ARM_TIMEOUT_MS = 3 * 60_000L
    /** Ride tamat bila tak bergerak selama ini. */
    const val STOP_MS = 5 * 60_000L
    /** Ride auto baru dalam tempoh ini selepas ride auto sebelumnya → gabung (berhenti isi minyak / makan). */
    const val MERGE_MS = 15 * 60_000L

    private const val ACTION_TRANSITION = "com.jagarider.AUTO_RIDE_TRANSITION"
    const val ACTION_DISCARD = "com.jagarider.AUTO_RIDE_DISCARD"
    const val EXTRA_START = "start"

    private fun sp(c: Context) = c.getSharedPreferences("jagarider", Context.MODE_PRIVATE)
    fun enabled(c: Context): Boolean = sp(c).getBoolean("auto_ride", false)
    fun setEnabled(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("auto_ride", on).apply()
        if (on) register(c) else unregister(c)
    }

    /** Kebenaran "Aktiviti fizikal" (Android 10+; sebelum itu diberi automatik). */
    fun hasActivityPermission(c: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || c.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

    /** Semua yang perlu untuk auto rekod: sensor gerakan + lokasi sepanjang masa (GPS dimulakan dari latar). */
    fun ready(c: Context): Boolean = hasActivityPermission(c) && RainAlert.hasBackgroundLocation(c)

    private fun pending(c: Context): PendingIntent = PendingIntent.getBroadcast(
        c, 7, Intent(c, AutoRideReceiver::class.java).setAction(ACTION_TRANSITION),
        // Play services isi hasil dalam intent — mesti MUTABLE
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
    )

    /** Daftar dengan sensor gerakan. Hilang bila phone restart / app dikemas kini — dipanggil semula dari situ. */
    @SuppressLint("MissingPermission")
    fun register(c: Context) {
        if (!enabled(c) || !hasActivityPermission(c)) return
        val types = listOf(DetectedActivity.IN_VEHICLE, DetectedActivity.ON_BICYCLE)
        val transitions = types.flatMap { type ->
            listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map {
                ActivityTransition.Builder().setActivityType(type).setActivityTransition(it).build()
            }
        }
        runCatching {
            ActivityRecognition.getClient(c.applicationContext)
                .requestActivityTransitionUpdates(ActivityTransitionRequest(transitions), pending(c.applicationContext))
        }
    }

    @SuppressLint("MissingPermission")
    fun unregister(c: Context) {
        runCatching { ActivityRecognition.getClient(c.applicationContext).removeActivityTransitionUpdates(pending(c.applicationContext)) }
        LocationShareService.stopAuto(c)
    }

    /** Sensor kesan mula naik kenderaan → hidupkan GPS untuk sahkan (kalau belum ada kongsi / ride). */
    fun onEnterVehicle(c: Context) {
        if (!enabled(c) || !RainAlert.hasBackgroundLocation(c) || LocationShareService.running) return
        LocationShareService.startAuto(c)
    }
}

/** Terima peristiwa sensor gerakan, dan butang "Bukan ride? Buang" dalam notifikasi. */
class AutoRideReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        I18n.lang = Prefs.lang(ctx)
        if (intent.action == AutoRide.ACTION_DISCARD) {
            RideLog.remove(ctx, intent.getLongExtra(AutoRide.EXTRA_START, 0L))
            ctx.getSystemService(android.app.NotificationManager::class.java).cancel(LocationShareService.AUTO_DONE_ID)
            return
        }
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        val last = result.transitionEvents.lastOrNull() ?: return
        if (last.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER) AutoRide.onEnterVehicle(ctx)
        // EXIT: tiada tindakan — servis tamatkan ride sendiri bila berhenti 5 minit (lampu isyarat, jem)
    }
}
