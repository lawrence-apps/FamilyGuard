package com.familyguard.child.service

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject

/**
 * Live audio — mic ka 16 kHz mono PCM stream, JSON (base64) chunks me server ko.
 *
 * Mirror frames binary me jaate hain; audio JSON me bhejne se parent apps /
 * dashboards ka binary protocol break nahi hota (backward compatible).
 * 16 kHz mono 16-bit = 32 KB/s raw, ~43 KB/s base64 — WS ke liye trivial.
 *
 * Loop har chunk par ws health check karta hai — internet girne par khud band
 * ho jata hai, aur audio_state message se parents ka UI bhi turant reset hota hai.
 */
object AudioStreamer {

    @Volatile
    var streaming: Boolean = false
        private set

    private var thread: Thread? = null

    private const val SAMPLE_RATE = 16000
    private const val CHUNK_MS = 100L
    private const val CHUNK_BYTES = (SAMPLE_RATE * 2 * CHUNK_MS / 1000).toInt() // 3200 bytes = 100ms

    fun start(ctx: Context): Pair<Boolean, JsonObject> {
        val data = JsonObject()
        if (streaming) {
            data.addProperty("status", "already_streaming")
            return Pair(true, data)
        }
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            data.addProperty("error", "mic permission nahi hai — setup me mic allow karo")
            return Pair(false, data)
        }
        streaming = true
        thread = Thread {
            var rec: AudioRecord? = null
            runCatching {
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, CHUNK_BYTES * 4)
                )
                rec?.startRecording()
                sendState(true)
                val buf = ByteArray(CHUNK_BYTES)
                while (streaming && WsChild.connected.value) {
                    val n = rec?.read(buf, 0, buf.size) ?: -1
                    if (n <= 0) {
                        Thread.sleep(20)
                        continue
                    }
                    val m = JsonObject()
                    m.addProperty("type", "audio")
                    m.addProperty("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP))
                    if (!WsChild.send(m.toString())) break
                }
            }
            runCatching { rec?.stop() }
            runCatching { rec?.release() }
            if (streaming) {
                // ws drop hua — khud band ho gaya, parents ko bata do
                streaming = false
                sendState(false)
            }
        }.also { it.start() }
        data.addProperty("status", "streaming")
        return Pair(true, data)
    }

    fun stop() {
        val was = streaming
        streaming = false
        thread?.join(800)
        thread = null
        if (was) sendState(false)
    }

    private fun sendState(on: Boolean) {
        runCatching {
            val m = JsonObject()
            m.addProperty("type", "audio_state")
            m.addProperty("streaming", on)
            WsChild.send(m.toString())
        }
    }
}
