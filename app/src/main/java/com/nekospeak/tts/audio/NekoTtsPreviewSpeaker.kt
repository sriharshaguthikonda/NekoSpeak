package com.nekospeak.tts.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.nekospeak.tts.data.PrefsManager
import java.util.concurrent.atomic.AtomicBoolean

object NekoTtsPreviewSpeaker {
    fun speak(
        context: Context,
        prefs: PrefsManager,
        tts: TextToSpeech?,
        text: String,
        voiceId: String
    ): Int {
        if (tts == null || text.isBlank()) return TextToSpeech.ERROR

        val audioStream = NekoAudioStream.fromPref(prefs.audioStream)
        val routeToken = NekoAudioRouting.applyPreferredSpeaker(
            context = context,
            stream = audioStream,
            preferSpeaker = prefs.preferSpeakerForCallStream
        )

        val cleanupDone = AtomicBoolean(false)
        fun cleanupRoute() {
            if (cleanupDone.compareAndSet(false, true)) {
                NekoAudioRouting.clearPreferredSpeaker(context, routeToken)
            }
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                cleanupRoute()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                cleanupRoute()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                cleanupRoute()
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                cleanupRoute()
            }
        })

        val params = Bundle().apply {
            putString("voiceName", voiceId)
            audioStream.applyTo(this)
        }

        tts.setSpeechRate(prefs.speechSpeed)
        val result = tts.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            params,
            "preview_${System.currentTimeMillis()}"
        )

        if (result == TextToSpeech.ERROR) {
            cleanupRoute()
        }

        return result
    }
}
