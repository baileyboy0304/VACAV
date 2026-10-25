package com.mph1970.vacavision.audio

/** Byte capacities include all channels; AudioTrack positions are interleaved frames. */
internal object AudioBufferSizing {
    const val PREPARATION_TIMEOUT_MS = 2500L
    const val LEAD_TIME_MS = 500
    const val OUTPUT_MS = 200
    // 5.46 s at our largest format (48 kHz, stereo PCM16): covers the bounded
    // remote-pause wait + 500 ms send-ahead + 200 ms output + scheduling margin.
    const val NETWORK_BYTES = 1_048_576

    fun outputBytes(rate: Int, channels: Int, platformMinimum: Int): Int {
        require(rate > 0 && channels in 1..2 && platformMinimum > 0)
        val frameBytes = channels * 2
        val bytes = maxOf(platformMinimum, rate * frameBytes * OUTPUT_MS / 1000)
        return ((bytes + frameBytes - 1) / frameBytes) * frameBytes
    }
}
