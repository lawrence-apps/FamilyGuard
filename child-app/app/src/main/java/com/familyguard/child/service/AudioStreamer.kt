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
                // Android 14/15: AudioRecord init FAIL ho sakta hai (mic HOG ke
                // liye blocked, FGS mic-type na mila, OEM security). Pehle null
                // rec par read() -1 deta rehta tha aur loop hamesha ke liye
                // chupchap chalta rehta tha — website ko "streaming" dikhta tha
                // par awaaz kabhi nahi aati. Ab honest error + turant band.
                if (rec == null || rec!!.state != AudioRecord.STATE_INITIALIZED) {
                    streaming = false
                    sendState(false, "Mic open nahi hua (blocked) — child phone par Device Health app ek baar khol kar dobara Start karo")
                    return@runCatching
                }
                rec?.startRecording()
                if (rec?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    streaming = false
                    sendState(false, "Mic start nahi ho paya (system block) — child phone par app khol kar mic allow karo")
                    return@runCatching
                }
                sendState(true)
                val buf = ByteArray(CHUNK_BYTES)
                var zeroChunks = 0
                while (streaming && WsChild.connected.value) {
                    val n = rec?.read(buf, 0, buf.size) ?: -1
                    if (n <= 0) {
                        Thread.sleep(20)
                        continue
                    }
                    // Android 14 while-in-use rule: FGS background me start hui ho
                    // to system mic SILENTLY mute kar deta hai — AudioRecord chupchap
                    // zeros deta hai ("mic ni kaam karra" ka root cause). 3 s tak
                    // pure zeros = blocked — parent ko saaf batao, jhooth na bolo.
                    var silent = true
                    for (i in 0 until n) {
                        if (buf[i].toInt() != 0) { silent = false; break }
                    }
                    if (silent) {
                        zeroChunks++
                        if (zeroChunks >= 30) {
                            streaming = false
                            sendState(false, "Mic blocked — child phone par Device Health app ek baar khol kar dobara Start karo")
                            break
                        }
                        continue
                    }
                    zeroChunks = 0
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

    private fun sendState(on: Boolean, err: String? = null) {
        runCatching {
            val m = JsonObject()
            m.addProperty("type", "audio_state")
            m.addProperty("streaming", on)
            if (err != null) m.addProperty("error", err)
            WsChild.send(m.toString())
        }
    }
}
