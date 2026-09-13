package com.booxin.launcher.ui.controller

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.sin

/**
 * Short rising success chime (no raw asset required).
 */
object PeripheralSuccessSound {
    fun play() {
        Thread {
            try {
                val sampleRate = 22050
                val durationMs = 520
                val n = sampleRate * durationMs / 1000
                val buf = ShortArray(n)
                // Two-note ascending chime.
                fillTone(buf, 0, n / 2, sampleRate, 523.25) // C5
                fillTone(buf, n / 2, n, sampleRate, 783.99) // G5
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buf, 0, buf.size)
                track.play()
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching {
                        track.stop()
                        track.release()
                    }
                }, (durationMs + 80).toLong())
            } catch (_: Throwable) {
                // Ignore audio failures.
            }
        }.start()
    }

    private fun fillTone(buf: ShortArray, from: Int, to: Int, sampleRate: Int, freq: Double) {
        val len = (to - from).coerceAtLeast(1)
        for (i in from until to) {
            val t = (i - from).toDouble() / sampleRate
            val env = when {
                i - from < len * 0.08 -> (i - from).toDouble() / (len * 0.08)
                i > to - len * 0.25 -> ((to - i).toDouble() / (len * 0.25)).coerceIn(0.0, 1.0)
                else -> 1.0
            }
            val sample = sin(2.0 * PI * freq * t) * env * 0.28
            buf[i] = (sample * Short.MAX_VALUE).toInt().toShort()
        }
    }
}
