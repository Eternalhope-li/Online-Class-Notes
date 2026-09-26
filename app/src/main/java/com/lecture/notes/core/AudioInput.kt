package com.lecture.notes.core

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import kotlin.math.sqrt

/**
 * 16 kHz 单声道音频输入。两条路：
 *  - 麦克风
 *  - 内录系统声音（Android 10+），网课音质比外放录音干净得多
 * 内录时按设备原生 48 kHz 抓，再降采样到 16 kHz（3 点平均当低通），保证任何机型都能建流。
 */
class AudioInput private constructor(
    private val record: AudioRecord,
    private val captureRate: Int,
    private val factor: Int
) {
    private val raw = ShortArray(FRAME * factor)
    val buf = FloatArray(FRAME)

    @Volatile
    var lastLevel: Float = 0f
        private set

    val isInternal: Boolean get() = captureRate != SAMPLE_RATE

    @SuppressLint("MissingPermission")
    fun start() {
        record.startRecording()
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw IllegalStateException("录音设备启动失败")
        }
    }

    fun stop() {
        try {
            record.stop()
        } catch (_: Throwable) {
        }
    }

    fun release() {
        try {
            record.release()
        } catch (_: Throwable) {
        }
    }

    /** 读取一帧（buf.size 个 16 kHz 采样），返回实际写入的采样数。 */
    fun read(): Int {
        val n = record.read(raw, 0, buf.size * factor)
        if (n <= 0) return 0
        var i = 0
        var o = 0
        var energy = 0.0
        while (i + factor <= n) {
            var acc = 0
            for (k in 0 until factor) acc += raw[i + k]
            val v = acc / (factor * 32768f)
            buf[o++] = v
            energy += v.toDouble() * v
            i += factor
        }
        lastLevel = if (o == 0) 0f else sqrt(energy / o).toFloat()
        return o
    }

    companion object {
        const val SAMPLE_RATE = 16000
        private const val FRAME = 512
        private const val INTERNAL_RATE = 48000

        @SuppressLint("MissingPermission")
        fun microphone(): AudioInput {
            val min = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(FRAME * 2)
            val size = min * 2
            var rec = newRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, size)
            if (rec == null) rec = newRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, size)
            return AudioInput(rec!!, SAMPLE_RATE, 1)
        }

        @SuppressLint("MissingPermission")
        fun playback(projection: MediaProjection): AudioInput {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return microphone()
            }
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val min = AudioRecord.getMinBufferSize(
                INTERNAL_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(FRAME * 3 * 2)
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(INTERNAL_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(min * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return microphone()
            }
            return AudioInput(rec, INTERNAL_RATE, INTERNAL_RATE / SAMPLE_RATE)
        }

        @SuppressLint("MissingPermission")
        private fun newRecord(source: Int, rate: Int, size: Int): AudioRecord? {
            return try {
                val r = AudioRecord(
                    source, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size
                )
                if (r.state == AudioRecord.STATE_INITIALIZED) r else {
                    r.release()
                    null
                }
            } catch (t: Throwable) {
                null
            }
        }
    }
}