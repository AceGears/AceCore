package com.acecore.app.runtime.windows

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Plays the Windows runtime's sound: PulseAudio inside the runtime streams everything Windows programs play as raw
 * 16-bit 44.1 kHz stereo on 127.0.0.1:4713 (module-simple-protocol-tcp); this writes it to an Android AudioTrack.
 */
class PcmAudio(private val port: Int = 4713) {
    @Volatile private var closed = false
    @Volatile private var paused = false
    private var socket: Socket? = null
    private var track: AudioTrack? = null

    /** Blocking: connects (retrying while the runtime starts) and plays until [close]. */
    fun run() {
        val rate = 44100
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setBufferSizeInBytes(maxOf(min * 2, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        val buf = ByteArray(4096)
        val target = InetSocketAddress("127.0.0.1", port) // outside apply: inside it, "port" is the Socket's own
        while (!closed) {
            val s = try {
                Socket().apply { tcpNoDelay = true; connect(target, 2000) }
            } catch (_: IOException) {
                Thread.sleep(500); continue
            }
            socket = s
            try {
                val input = s.getInputStream()
                var rem = 0 // bytes of an incomplete stereo frame (4 bytes) kept for the next read
                while (!closed) {
                    val n = input.read(buf, rem, buf.size - rem)
                    if (n < 0) break
                    val total = rem + n
                    val whole = total and 3.inv()
                    if (!paused && whole > 0) t.write(buf, 0, whole)
                    rem = total - whole
                    if (rem > 0) System.arraycopy(buf, whole, buf, 0, rem)
                }
            } catch (_: IOException) {
            } finally {
                runCatching { s.close() }
            }
            if (!closed) Thread.sleep(500)
        }
        runCatching { t.stop(); t.release() }
    }

    fun setPaused(p: Boolean) {
        paused = p
        track?.let { if (p) runCatching { it.pause(); it.flush() } else runCatching { it.play() } }
    }

    fun close() {
        closed = true
        runCatching { socket?.close() }
    }
}
