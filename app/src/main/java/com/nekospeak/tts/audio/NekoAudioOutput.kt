package com.nekospeak.tts.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * User-facing audio stream choices for NekoSpeak preview playback.
 *
 * For normal Android TTS calls, the caller can still pass its own stream in
 * TextToSpeech.Engine.KEY_PARAM_STREAM. NekoSpeak uses this preference for its
 * own in-app preview playback and any future direct playback paths.
 */
enum class NekoAudioStream(
    val prefValue: String,
    val title: String,
    val description: String,
    val streamType: Int?
) {
    SYSTEM_DEFAULT(
        prefValue = "system_default",
        title = "System default",
        description = "Let Android or the calling app choose the stream.",
        streamType = null
    ),
    MEDIA(
        prefValue = "media",
        title = "Media volume",
        description = "Normal speaker, headset, and Bluetooth media volume. Safest default.",
        streamType = AudioManager.STREAM_MUSIC
    ),
    CALL(
        prefValue = "call",
        title = "Call volume",
        description = "Uses the voice-call volume stream. Routing can vary by device.",
        streamType = AudioManager.STREAM_VOICE_CALL
    ),
    ALARM(
        prefValue = "alarm",
        title = "Alarm volume",
        description = "Uses alarm volume. Can be very loud; use only for urgent prompts.",
        streamType = AudioManager.STREAM_ALARM
    ),
    NOTIFICATION(
        prefValue = "notification",
        title = "Notification volume",
        description = "Uses notification volume for short alert-style speech.",
        streamType = AudioManager.STREAM_NOTIFICATION
    );

    fun applyTo(params: Bundle) {
        streamType?.let { stream ->
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, stream)
        }
    }

    companion object {
        fun fromPref(value: String?): NekoAudioStream {
            return values().firstOrNull { it.prefValue == value } ?: MEDIA
        }
    }
}

data class NekoSpeakerRouteToken(
    val applied: Boolean,
    val previousMode: Int? = null,
    val previousSpeakerphoneOn: Boolean? = null
)

object NekoAudioRouting {
    fun applyPreferredSpeaker(
        context: Context,
        stream: NekoAudioStream,
        preferSpeaker: Boolean
    ): NekoSpeakerRouteToken {
        if (!preferSpeaker || stream != NekoAudioStream.CALL) {
            return NekoSpeakerRouteToken(applied = false)
        }

        val audioManager = context.getSystemService(AudioManager::class.java)
            ?: return NekoSpeakerRouteToken(applied = false)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = audioManager.availableCommunicationDevices.firstOrNull { device ->
                    device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speaker != null && audioManager.setCommunicationDevice(speaker)) {
                    NekoSpeakerRouteToken(applied = true)
                } else {
                    NekoSpeakerRouteToken(applied = false)
                }
            } else {
                @Suppress("DEPRECATION")
                val previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
                val previousMode = audioManager.mode

                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true

                NekoSpeakerRouteToken(
                    applied = true,
                    previousMode = previousMode,
                    previousSpeakerphoneOn = previousSpeakerphoneOn
                )
            }
        } catch (_: Throwable) {
            NekoSpeakerRouteToken(applied = false)
        }
    }

    fun clearPreferredSpeaker(context: Context, token: NekoSpeakerRouteToken) {
        if (!token.applied) return

        val audioManager = context.getSystemService(AudioManager::class.java) ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                token.previousMode?.let { audioManager.mode = it }
                token.previousSpeakerphoneOn?.let { previous ->
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = previous
                }
            }
        } catch (_: Throwable) {
            // Best-effort cleanup only.
        }
    }
}
