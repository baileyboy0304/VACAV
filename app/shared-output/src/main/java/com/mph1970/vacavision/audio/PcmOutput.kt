package com.mph1970.vacavision.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import com.sendspin.protocol.StreamFormat

internal data class PcmPosition(val frames: Long, val micros: Long)

/** Small platform boundary so the real renderer can be tested with a draining audio device. */
internal interface PcmOutput {
    val underruns: Int get() = 0
    val bufferFrames: Int
    val playedFrames: Long
    fun timestamp(): PcmPosition?
    fun play()
    fun pause()
    fun flush()
    fun release()
    fun setVolume(gain: Float)
    fun write(bytes: ByteArray, offset: Int, length: Int): Int
}

internal class AndroidPcmOutput(format: StreamFormat) : PcmOutput {
    private val track: AudioTrack
    private val position = AudioTimestamp()

    init {
        val mask = if (format.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minimum = AudioTrack.getMinBufferSize(format.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Audio output does not support negotiated format" }
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(format.sampleRate).setChannelMask(mask).build())
            .setBufferSizeInBytes(AudioBufferSizing.outputBytes(format.sampleRate, format.channels, minimum))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        check(track.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack failed to initialise" }
    }

    override val bufferFrames get() = track.bufferSizeInFrames
    override val underruns get() = track.underrunCount
    override val playedFrames get() = track.playbackHeadPosition.toLong() and 0xffffffffL
    override fun timestamp(): PcmPosition? =
        if (track.getTimestamp(position)) PcmPosition(position.framePosition, position.nanoTime / 1000) else null
    override fun play() = track.play()
    override fun pause() = track.pause()
    override fun flush() = track.flush()
    override fun release() = track.release()
    override fun setVolume(gain: Float) { track.setVolume(gain) }
    override fun write(bytes: ByteArray, offset: Int, length: Int) =
        track.write(bytes, offset, length, AudioTrack.WRITE_NON_BLOCKING)
}
