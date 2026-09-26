package com.lecture.notes.core

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeechSegment
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * 离线语音识别引擎：Silero VAD 负责把连续音频切成一句一句，SenseVoice 负责把每句变成中文。
 *
 * 模型随安装包内置，默认直接从 assets 读取；万一设备内存吃紧读不动，
 * 会自动退回“把模型解包到内部存储再用文件方式加载”的路径。
 */
class AsrEngine private constructor(
    private val assets: AssetManager?,
    private val dir: File?,
    numThreads: Int
) {
    private val vad: Vad
    private val recognizer: OfflineRecognizer
    private var decodedSeconds = 0.0
    private var decodedCostMs = 0.0

    init {
        val t0 = System.currentTimeMillis()

        val vadConfig = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = path(VAD_MODEL),
                threshold = 0.45f,
                minSilenceDuration = 0.6f,
                minSpeechDuration = 0.30f,
                windowSize = 512,
                maxSpeechDuration = 12.0f
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false
        )
        vad = Vad(assets, vadConfig)

        val asrConfig = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = path(ASR_MODEL),
                    language = "zh",
                    useInverseTextNormalization = true
                ),
                tokens = path(ASR_TOKENS),
                numThreads = numThreads.coerceIn(1, 4),
                provider = "cpu",
                debug = false
            ),
            decodingMethod = "greedy_search"
        )
        recognizer = OfflineRecognizer(assets, asrConfig)

        // 先用 0.6 秒静音跑一次，把算子的内存提前分配好，避免第一句明显变慢
        try {
            decode(FloatArray(SAMPLE_RATE * 6 / 10))
        } catch (t: Throwable) {
            Log.w(TAG, "warmup failed: ${t.message}")
        }
        Log.i(TAG, "engine ready in ${System.currentTimeMillis() - t0} ms, threads=$numThreads")
    }

    private fun path(rel: String): String = if (assets != null) rel else File(dir, rel).absolutePath

    fun accept(samples: FloatArray) = vad.acceptWaveform(samples)

    fun hasSegment(): Boolean = !vad.empty()

    fun nextSegment(): SpeechSegment = vad.front().also { vad.pop() }

    fun isSpeech(): Boolean = vad.isSpeechDetected()

    fun flush() = vad.flush()

    fun reset() = vad.reset()

    fun decode(samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val stream = recognizer.createStream()
        val started = android.os.SystemClock.elapsedRealtime()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text
        } finally {
            stream.release()
            val cost = android.os.SystemClock.elapsedRealtime() - started
            decodedSeconds += samples.size.toDouble() / SAMPLE_RATE
            decodedCostMs += cost
            if (decodedSeconds >= 60.0) {
                Log.i(
                    TAG,
                    "performance: 累计识别 %.1f 秒音频耗时 %.1f 秒，实时率 %.2f"
                        .format(decodedSeconds, decodedCostMs / 1000.0, decodedCostMs / 1000.0 / decodedSeconds)
                )
                decodedSeconds = 0.0
                decodedCostMs = 0.0
            }
        }
    }

    fun release() {
        try {
            vad.release()
        } catch (_: Throwable) {
        }
        try {
            recognizer.release()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val TAG = "AsrEngine"
        const val SAMPLE_RATE = 16000
        private const val VAD_MODEL = "silero_vad.onnx"
        private const val ASR_MODEL = "sense-voice/model.int8.onnx"
        private const val ASR_TOKENS = "sense-voice/tokens.txt"

        /** 优先用 assets；失败（内存不足等）则解包到内部存储再加载。 */
        fun create(ctx: Context, numThreads: Int): AsrEngine {
            return try {
                AsrEngine(ctx.assets, null, numThreads)
            } catch (t: Throwable) {
                Log.w(TAG, "load from assets failed, falling back to files", t)
                val d = extract(ctx)
                AsrEngine(null, d, numThreads)
            }
        }

        fun extract(ctx: Context): File {
            val d = File(ctx.filesDir, "models")
            d.mkdirs()
            copyIfNeeded(ctx, "silero_vad.onnx", File(d, "silero_vad.onnx"))
            val sv = File(d, "sense-voice")
            sv.mkdirs()
            copyIfNeeded(ctx, "sense-voice/model.int8.onnx", File(sv, "model.int8.onnx"))
            copyIfNeeded(ctx, "sense-voice/tokens.txt", File(sv, "tokens.txt"))
            return d
        }

        private fun copyIfNeeded(ctx: Context, asset: String, dst: File) {
            val expected = try {
                ctx.assets.openFd(asset).use { it.length }
            } catch (t: Throwable) {
                -1L
            }
            if (dst.exists() && dst.length() > 0 && (expected <= 0 || dst.length() == expected)) return
            ctx.assets.open(asset).use { input ->
                dst.outputStream().use { out -> input.copyTo(out, 1 shl 16) }
            }
        }
    }
}
