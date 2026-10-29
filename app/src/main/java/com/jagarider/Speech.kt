package com.jagarider

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Amaran suara (keluar melalui speaker / headset helmet) — ikut bahasa app. Bahasa Melayu jatuh balik ke
 * Bahasa Indonesia kalau enjin suara tiada ms-MY.
 */
object Speech {

    /** Cakap dan tunggu sehingga habis (maks 15 s) — untuk job latar. Senyap kalau dimatikan / tiada enjin. */
    fun speakBlocking(c: Context, text: String) {
        if (!Prefs.voiceAlert(c)) return
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        var tts: TextToSpeech? = null
        var ok = false
        tts = TextToSpeech(c.applicationContext) { status ->
            ok = status == TextToSpeech.SUCCESS
            ready.countDown()
        }
        try {
            if (!ready.await(5, TimeUnit.SECONDS) || !ok) return
            val t = tts
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            val wanted = when (I18n.lang) {
                Lang.MS -> listOf(Locale("ms", "MY"), Locale("id", "ID"))
                Lang.EN -> listOf(Locale.UK, Locale.US)
                Lang.ZH -> listOf(Locale.SIMPLIFIED_CHINESE)
                Lang.TA -> listOf(Locale("ta", "IN"), Locale("ta", "MY"))
            }
            val loc = wanted.firstOrNull { t.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE } ?: return
            t.language = loc
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = done.countDown()
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) = done.countDown()
            })
            // emoji tak disebut dengan baik — buang
            t.speak(text.replace(Regex("[^\\p{L}\\p{N}\\p{P}\\s]"), "").trim(), TextToSpeech.QUEUE_FLUSH, null, "jagaride")
            done.await(15, TimeUnit.SECONDS)
        } finally {
            tts.shutdown()
        }
    }
}
