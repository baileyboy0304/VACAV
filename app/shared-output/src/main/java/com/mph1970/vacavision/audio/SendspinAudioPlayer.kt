package com.mph1970.vacavision.audio

import android.os.Process
import android.util.Log
import com.sendspin.protocol.AudioBuffer
import com.sendspin.protocol.AudioPlayer
import com.sendspin.protocol.ClockSync
import com.sendspin.protocol.StreamFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.min
import org.json.JSONObject

/**
 * PCM sink for the official Sendspin SDK. Uses Android DAC timestamps, as in
 * SendspinDroid, rather than treating arrival time or AudioTrack.write as playback.
 * The worker exclusively owns AudioTrack, including flush/release.
 */
class SendspinAudioPlayer internal constructor(
    private val buffer: AudioBuffer,
    private val clock: ClockSync,
    private val onPlaying: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val beforePlayback: () -> Unit = {},
    private val duckGain: () -> Float = { 1f },
    private val outputFactory: (StreamFormat) -> PcmOutput = { AndroidPcmOutput(it) },
) : AudioPlayer {
    @Volatile private var running = false
    @Volatile private var format: StreamFormat? = null
    @Volatile private var gain = 1f
    @Volatile private var worker: Thread? = null
    private val epoch = AtomicInteger()
    override val isPlaying: Boolean get() = running
    @Volatile override var droppedDecodeFrames = 0L
        private set
    override fun configure(format: StreamFormat) {
        require(format.codec == "pcm" && format.bitDepth == 16 && format.channels in 1..2 && format.sampleRate in listOf(44100, 48000)) { "Unsupported negotiated PCM format" }
        stop()
        buffer.flush()
        this.format = format
    }
    override fun start() = startOutput(preparePlayback = true)
    @Synchronized private fun startOutput(preparePlayback: Boolean) {
        if (running || format == null) return
        val f = format!!
        running = true
        worker = thread(name = "Sendspin-AudioTrack") { render(f, preparePlayback) }
    }
    override fun flush() { buffer.flush(); epoch.incrementAndGet() }
    @Synchronized override fun stop() {
        running = false
        worker?.interrupt()
        if (worker !== Thread.currentThread()) worker?.join(1000)
        worker = null
    }
    override fun transition(format: StreamFormat) { configure(format); startOutput(preparePlayback = false) }
    override fun setVolume(gain: Float) { this.gain = gain.coerceIn(0f, 1f) }

    private fun render(f: StreamFormat, preparePlayback: Boolean) {
        var track: PcmOutput? = null
        try {
            if (preparePlayback) beforePlayback()
            if (!running || Thread.currentThread().isInterrupted) return
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val frameBytes = f.channels * 2
            track = outputFactory(f)
            val silence = ByteArray(f.sampleRate / 100 * frameBytes) // bounded 10 ms writes
            var frames = 0L
            var localEpoch = epoch.get()
            var lastData = ClockSync.localMicros()
            var firstPcmFrame = -1L
            var reportedPlaying = false
            var blockedSince = 0L
            var discontinuity = true
            var insertedFrames = 0L
            var hardCorrections = 0L
            var zeroWrites = 0L
            var gapWrites = 0L
            var lastErrorUs = 0L
            var timestampValid = false
            var nextReportUs = 0L
            val startedUs = ClockSync.localMicros()
            fun report() {
                val sample = JSONObject()
                    .put("stream_started_us", startedUs).put("sample_rate", f.sampleRate).put("channels", f.channels)
                    .put("capacity_frames", track.bufferFrames).put("written_frames", frames)
                    .put("played_frames", track.playedFrames).put("audio_track_underruns", track.underruns)
                    .put("inserted_frames", insertedFrames).put("dropped_frames", droppedDecodeFrames)
                    .put("hard_corrections", hardCorrections).put("zero_writes", zeroWrites)
                    .put("gap_writes", gapWrites).put("last_error_us", lastErrorUs)
                    .put("timestamp_valid", timestampValid).put("network_late_chunks", buffer.lateChunks)
                    .put("network_dropped_chunks", buffer.droppedChunks).put("output_delay_us", buffer.outputDelayMicros)
                    .put("network_capacity_bytes", buffer.byteCapacity).put("network_queued_bytes", buffer.sizeBytes)
                AudioDiagnostics.record(sample)
                Log.i("VACAOverlay", "Sendspin measurements: $sample")
            }
            fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                val count = track.write(bytes, offset, length)
                check(count >= 0) { "AudioTrack write failed ($count)" }
                check(count % frameBytes == 0) { "AudioTrack returned an incomplete PCM frame ($count bytes)" }
                if (count == 0) {
                    zeroWrites++
                    val now = ClockSync.localMicros()
                    if (blockedSince == 0L) blockedSince = now
                    check(now - blockedSince < 3_000_000L) {
                        "AudioTrack stopped draining (written=$frames played=${track.playedFrames} capacity=${track.bufferFrames})"
                    }
                    Thread.sleep(2)
                } else blockedSince = 0L
                return count
            }
            track.play()
            Log.i("VACAOverlay", "Sendspin PCM opened: ${f.sampleRate} Hz, ${f.channels} ch, capacity=${track.bufferFrames} frames")
            while (running && !Thread.currentThread().isInterrupted) {
                if (localEpoch != epoch.get()) {
                    track.pause(); track.flush(); track.play()
                    frames = 0; localEpoch = epoch.get(); blockedSince = 0L
                    firstPcmFrame = -1L; reportedPlaying = false
                    discontinuity = true
                    onPlaying(false)
                }
                track.setVolume(gain * duckGain())
                val now = ClockSync.localMicros()
                if (now >= nextReportUs) { report(); nextReportUs = now + 5_000_000L }
                val played = track.playedFrames
                if (!reportedPlaying && firstPcmFrame >= 0 && played > firstPcmFrame) {
                    reportedPlaying = true
                    onPlaying(true)
                    Log.i("VACAOverlay", "Sendspin PCM advancing: played=$played firstPcm=$firstPcmFrame queued=${frames - played}")
                }
                // Let AudioTrack's actual capacity provide backpressure. A lower software
                // limit can prevent startup (or recovery after underrun) on Fire OS.
                val timestamp = track.timestamp()
                timestampValid = timestamp != null && timestamp.micros > 0 && now - timestamp.micros in 0..500_000L
                val presentation = if (timestampValid && timestamp != null) {
                    PcmTimeline.presentationMicros(frames, timestamp.frames, timestamp.micros, f.sampleRate)
                } else now + (frames - played).coerceAtLeast(0) * 1_000_000L / f.sampleRate
                // Schedule against the next output frame, including audio already queued.
                // A fixed wall-clock lookahead can fill a larger output with silence forever.
                val chunk = buffer.poll(maxOf(now, presentation) + 10_000L)
                if (chunk == null) {
                    gapWrites++
                    if (now - lastData > 500_000L) { buffer.signalUnderrun(); discontinuity = true }
                    frames += write(silence, 0, silence.size) / frameBytes
                    continue
                }
                if (chunk.data.size % frameBytes != 0) { droppedDecodeFrames++; continue }
                lastData = now
                val target = clock.toLocalMicros(chunk.serverTimestampMicros, now) - buffer.outputDelayMicros
                lastErrorUs = target - presentation
                val correction = PcmTimeline.correction(target, presentation, f.sampleRate, chunk.data.size / frameBytes, discontinuity)
                if (correction.hard && correction.frames != 0L) hardCorrections++
                val delta = correction.frames
                var padding = delta.coerceIn(0, f.sampleRate.toLong())
                while (padding > 0 && running && localEpoch == epoch.get()) {
                    // Steady drift repeats a boundary frame; silence is reserved for a
                    // discontinuity. All other PCM samples remain bit-exact.
                    val source = if (correction.hard) silence else chunk.data
                    val limit = if (correction.hard) silence.size else frameBytes
                    val bytes = min(padding * frameBytes, limit.toLong()).toInt()
                    val written = write(source, 0, bytes)
                    frames += written / frameBytes; padding -= written / frameBytes
                    insertedFrames += written / frameBytes
                }
                val skipFrames = (-delta).coerceIn(0, (chunk.data.size / frameBytes).toLong())
                droppedDecodeFrames += skipFrames
                var offset = skipFrames.toInt() * frameBytes
                while (offset < chunk.data.size && running && localEpoch == epoch.get()) {
                    val bytes = min(silence.size, chunk.data.size - offset)
                    val n = write(chunk.data, offset, bytes)
                    if (n > 0 && firstPcmFrame < 0) firstPcmFrame = frames
                    offset += n; frames += n / frameBytes
                }
                if (skipFrames < chunk.data.size / frameBytes) discontinuity = false
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) { onError("Sendspin audio: ${e.message}") }
        finally {
            runCatching { track?.pause() }; runCatching { track?.flush() }; runCatching { track?.release() }
            running = false; onPlaying(false)
        }
    }
}
