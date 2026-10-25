package com.mph1970.vacavision.audio

import kotlin.math.abs
import kotlin.math.roundToLong

/** Signed frame correction against the predicted DAC time for the next write. */
object PcmTimeline {
    data class Correction(val frames: Long, val hard: Boolean)

    // SendspinDroid's Android sink tolerates 10 ms and reserves hard resync for
    // 200 ms. The Cube's DAC timestamps fluctuate by several ms: a 1 ms hard
    // threshold repeatedly cut/inserted PCM despite a healthy network/output.
    private const val DEADBAND_US = 10_000L
    private const val HARD_RESYNC_US = 200_000L

    /** Dead band for DAC jitter, bounded drift correction, explicit discontinuity recovery. */
    fun correction(targetMicros: Long, presentationMicros: Long, sampleRate: Int,
                   chunkFrames: Int, discontinuity: Boolean): Correction {
        val error = targetMicros - presentationMicros
        if (discontinuity || abs(error) >= HARD_RESYNC_US) {
            return Correction(correctionFrames(targetMicros, presentationMicros, sampleRate), true)
        }
        if (abs(error) < DEADBAND_US) return Correction(0, false)
        val step = minOf(maxOf(1L, (21.0 * sampleRate / 1_000_000).roundToLong()), chunkFrames / 200L)
        return Correction(if (error > 0) step else -step, false)
    }

    fun correctionFrames(targetMicros: Long, presentationMicros: Long, sampleRate: Int): Long =
        ((targetMicros - presentationMicros) * sampleRate) / 1_000_000L
    fun presentationMicros(frame: Long, timestampFrame: Long, timestampMicros: Long, sampleRate: Int): Long =
        timestampMicros + (frame - timestampFrame) * 1_000_000L / sampleRate
}
