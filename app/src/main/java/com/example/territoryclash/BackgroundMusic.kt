package com.example.territoryclash

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

class BackgroundMusic {
    private val sampleRate = 22050
    private var track: AudioTrack? = null
    var muted: Boolean = false
        private set

    fun resume() {
        ensureTrack()
        track?.play()
    }

    fun pause() {
        track?.pause()
    }

    fun toggle(): Boolean {
        muted = !muted
        track?.setVolume(if (muted) 0f else 0.16f)
        return !muted
    }

    fun release() {
        track?.stop()
        track?.release()
        track = null
    }

    private fun ensureTrack() {
        if (track != null) return
        val pcm = generateLoop()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val audio = AudioTrack(
            attrs,
            format,
            pcm.size * 2,
            AudioTrack.MODE_STATIC,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        audio.write(pcm, 0, pcm.size)
        audio.setLoopPoints(0, pcm.size, -1)
        audio.setVolume(if (muted) 0f else 0.16f)
        track = audio
    }

    private fun generateLoop(): ShortArray {
        val beatSeconds = 0.48
        val beats = 32
        val total = (sampleRate * beatSeconds * beats).toInt()
        val out = ShortArray(total)

        val progression = arrayOf(
            intArrayOf(45, 52, 57),
            intArrayOf(41, 48, 53),
            intArrayOf(48, 55, 60),
            intArrayOf(43, 50, 55),
            intArrayOf(45, 52, 57),
            intArrayOf(41, 48, 53),
            intArrayOf(50, 57, 62),
            intArrayOf(43, 50, 55)
        )

        fun hz(midi: Int): Double = 440.0 * Math.pow(2.0, (midi - 69) / 12.0)

        for (i in 0 until total) {
            val t = i.toDouble() / sampleRate
            val beat = (t / beatSeconds).toInt().coerceIn(0, beats - 1)
            val beatT = (t % beatSeconds) / beatSeconds
            val bar = (beat / 4) % progression.size
            val chord = progression[bar]

            val arpMidi = chord[beat % 3] + 12
            val bassMidi = chord[0] - 12
            val arp = sin(2.0 * PI * hz(arpMidi) * t) * (1.0 - beatT) * 0.42
            val bass = sin(2.0 * PI * hz(bassMidi) * t) * 0.30
            val pad = (
                sin(2.0 * PI * hz(chord[0]) * t) +
                sin(2.0 * PI * hz(chord[1]) * t) +
                sin(2.0 * PI * hz(chord[2]) * t)
            ) * 0.075
            val kickEnv = if (beatT < 0.23) (1.0 - beatT / 0.23) else 0.0
            val kick = sin(2.0 * PI * (65.0 - beatT * 22.0) * t) * kickEnv * 0.24

            val sample = ((arp + bass + pad + kick).coerceIn(-1.0, 1.0) * 14500.0).toInt()
            out[i] = sample.toShort()
        }
        return out
    }
}
