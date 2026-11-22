package com.example.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class AudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "AudioEngine"
        const val MIC_SAMPLE_RATE = 16_000
        const val SPEAKER_SAMPLE_RATE = 24_000
        const val CHUNK_SIZE = 1024
        private const val SPEAKING_DRAIN_GRACE_MS = 350L
    }

    var onAudioCaptured: ((ByteArray) -> Unit)? = null
    var onAmplitudeChanged: ((Float) -> Unit)? = null
    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingStopped: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()

    private val isRecording = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)
    private val isSpeaking = AtomicBoolean(false)
    private val isCallMode = AtomicBoolean(false)

    @Volatile
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var audioTrack: AudioTrack? = null

    private var recordJob: Job? = null
    private var playJob: Job? = null

    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
        if (muted && !isSpeaking.get()) {
            onAmplitudeChanged?.invoke(0f)
        }
    }

    fun isMuted(): Boolean = isMuted.get()

    fun setCallMode(inCall: Boolean) {
        isCallMode.set(inCall)
    }

    fun isSpeakingNow(): Boolean = isSpeaking.get()

    fun startRecording() {
        if (isRecording.get()) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            MIC_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "Invalid AudioRecord buffer size: $minBuf")
            return
        }

        val bufferSize = maxOf(minBuf * 2, CHUNK_SIZE * 4)
        try {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                recorder.release()
                return
            }

            audioRecord = recorder
            recorder.startRecording()
            isRecording.set(true)

            recordJob?.cancel()
            recordJob = scope.launch {
                val buffer = ByteArray(CHUNK_SIZE)
                while (isActive && isRecording.get()) {
                    val read = try {
                        recorder.read(buffer, 0, buffer.size)
                    } catch (e: Exception) {
                        Log.e(TAG, "AudioRecord read error", e)
                        break
                    }
                    if (read > 0) {
                        // Suppress mic while MYRA is speaking (echo suppression), muted, or in call STT mode
                        if (!isMuted.get() && !isSpeaking.get() && !isCallMode.get()) {
                            val chunk = buffer.copyOf(read)
                            onAudioCaptured?.invoke(chunk)
                            onAmplitudeChanged?.invoke(calculateRms(chunk, read))
                        }
                    }
                }
            }
            Log.d(TAG, "AudioRecord started at ${MIC_SAMPLE_RATE}Hz")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting AudioRecord", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioRecord", e)
        }
    }

    fun stopRecording() {
        isRecording.set(false)
        recordJob?.cancel()
        recordJob = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    fun startPlayback() {
        if (isPlaying.get()) return

        val minBuf = AudioTrack.getMinBufferSize(
            SPEAKER_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.e(TAG, "Invalid AudioTrack buffer size: $minBuf")
            return
        }

        val bufferSize = maxOf(minBuf * 2, CHUNK_SIZE * 8)
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SPEAKER_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack = track
            track.play()
            isPlaying.set(true)

            playJob?.cancel()
            playJob = scope.launch {
                while (isActive && isPlaying.get()) {
                    val chunk = playbackQueue.poll(SPEAKING_DRAIN_GRACE_MS, TimeUnit.MILLISECONDS)
                    if (chunk != null) {
                        if (isSpeaking.compareAndSet(false, true)) {
                            onSpeakingStarted?.invoke()
                        }
                        onAmplitudeChanged?.invoke(calculateRms(chunk, chunk.size))
                        try {
                            track.write(chunk, 0, chunk.size)
                        } catch (e: Exception) {
                            Log.e(TAG, "AudioTrack write error", e)
                        }
                    } else {
                        if (isSpeaking.compareAndSet(true, false)) {
                            onAmplitudeChanged?.invoke(0f)
                            onSpeakingStopped?.invoke()
                        }
                    }
                }
            }
            Log.d(TAG, "AudioTrack started at ${SPEAKER_SAMPLE_RATE}Hz")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting AudioTrack", e)
        }
    }

    fun queueAudio(pcmBytes: ByteArray) {
        if (!isPlaying.get()) {
            startPlayback()
        }
        playbackQueue.offer(pcmBytes)
    }

    fun clearPlaybackQueue() {
        playbackQueue.clear()
        try {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.play()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing AudioTrack", e)
        }
        if (isSpeaking.compareAndSet(true, false)) {
            onAmplitudeChanged?.invoke(0f)
            onSpeakingStopped?.invoke()
        }
    }

    fun stopPlayback() {
        isPlaying.set(false)
        playbackQueue.clear()
        playJob?.cancel()
        playJob = null
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }
        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
        if (isSpeaking.compareAndSet(true, false)) {
            onSpeakingStopped?.invoke()
        }
    }

    private fun calculateRms(pcmBytes: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        var sumSquares = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < length) {
            val lo = pcmBytes[i].toInt() and 0xFF
            val hi = pcmBytes[i + 1].toInt() shl 8
            val sample = (hi or lo).toShort().toDouble() / 32768.0
            sumSquares += sample * sample
            samples++
            i += 2
        }
        if (samples == 0) return 0f
        val rms = sqrt(sumSquares / samples).toFloat()
        return (rms * 3.5f).coerceIn(0f, 1f)
    }

    fun release() {
        stopRecording()
        stopPlayback()
        scope.cancel()
    }
}
