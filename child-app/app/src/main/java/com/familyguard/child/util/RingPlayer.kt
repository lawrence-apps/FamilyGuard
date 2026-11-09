package com.familyguard.child.util

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import com.familyguard.child.service.WsChild
import com.google.gson.JsonObject

object RingPlayer {

    /** Real ring state — parents ka "Ring band karo" button isi se sync hota hai. */
    @Volatile
    var playing: Boolean = false
        private set

    private var mp: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val stopHandler = Handler(Looper.getMainLooper())

    fun start(ctx: Context) {
        stopInternal()
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: return@runCatching
            val player = MediaPlayer()
            player.setDataSource(ctx, uri)
            player.isLooping = true
            player.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_ALARM)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.prepare()
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            player.start()
            mp = player

            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator = vib
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createWaveform(longArrayOf(600, 400), 0))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(longArrayOf(600, 400), 0)
            }
            playing = true
            sendState(true)
            stopHandler.postDelayed({ stop() }, 30_000)
        }
    }

    fun stop() {
        val was = playing
        stopInternal()
        if (was) sendState(false)
    }

    private fun stopInternal() {
        stopHandler.removeCallbacksAndMessages(null)
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
        mp = null
        runCatching { vibrator?.cancel() }
        vibrator = null
        playing = false
    }

    private fun sendState(on: Boolean) {
        runCatching {
            val m = JsonObject()
            m.addProperty("type", "ring_state")
            m.addProperty("playing", on)
            WsChild.send(m.toString())
        }
    }
}
