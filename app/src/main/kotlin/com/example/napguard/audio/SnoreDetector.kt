package com.example.napguard.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.log10
import java.util.ArrayDeque

/**
 * 鼾声检测器 v2.0 —— 增强版（方案 A + C）
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │                         检测流程                                 │
 * │                                                                 │
 * │  麦克风 PCM → [自适应噪底校正] → [分帧分析 100ms]               │
 * │                                                                 │
 * │  每帧双维度判断:                                                 │
 * │   ① 振幅维度：RMS dB > 噪底 + NoiseMargin(动态)                │
 * │   ② 频率维度：50~400Hz 低频能量比 > LowFreqRatio 阈值(60%)      │
 * │                                                                 │
 * │  帧标记结果 → [滑动窗口 5 分钟] → 鼾声帧占比 > 60% → 判为入睡   │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * 对比 v1.0 的改进:
 *   - 频率过滤：去除说话声、音乐、环境噪声等干扰
 *   - 滑动窗口：不再要求"连续无中断"，允许正常呼吸间隔
 *   - 自适应噪底：开机 30 秒校准环境噪声，阈值随环境动态调整
 *   - 防误报机制：单帧高 dB 不够，必须同时满足频率分布才算打鼾
 */
class SnoreDetector {

    companion object {
        private const val TAG = "SnoreDetector"

        const val SAMPLE_RATE = 16000
        const val FRAME_DURATION_MS = 100
        const val FRAME_SIZE = SAMPLE_RATE * FRAME_DURATION_MS / 1000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        val BUFFER_SIZE: Int = try {
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT).coerceAtLeast(4096)
        } catch (e: Exception) {
            4096
        }

        // ── 频率过滤参数（方案 C） ──────────────────────────────
        /** 鼾声频率低端（Hz）*/
        const val SNORE_FREQ_MIN = 50.0
        /** 鼾声频率高端（Hz） */
        const val SNORE_FREQ_MAX = 450.0
        /** 鼾声主峰通常位于更窄的低频带 */
        const val DOMINANT_FREQ_MIN = 80.0
        const val DOMINANT_FREQ_MAX = 280.0
        /** 低频能量占全频段的最低比例 */
        const val LOW_FREQ_ENERGY_RATIO_THRESHOLD = 0.42
        /** 中频（说话声常见频段）能量占比上限 */
        const val MID_FREQ_ENERGY_RATIO_MAX = 0.45
        /** 频谱平坦度上限，越低代表越有明显主峰 */
        const val SPECTRAL_FLATNESS_MAX = 0.55
        /** 过零率上限，说话/摩擦声通常更高 */
        const val ZERO_CROSSING_RATE_MAX = 0.18
        /** 短窗口模式下更严格的参数，优先压制误报 */
        const val SHORT_WINDOW_LOW_FREQ_RATIO_THRESHOLD = 0.60
        const val SHORT_WINDOW_MID_FREQ_RATIO_MAX = 0.22
        const val SHORT_WINDOW_SPECTRAL_FLATNESS_MAX = 0.32
        const val SHORT_WINDOW_ZERO_CROSSING_RATE_MAX = 0.12
        const val SHORT_WINDOW_DOMINANT_STABILITY_MAX_DEVIATION = 30.0
        const val SPEECH_COOLDOWN_FRAMES = 12

        // ── 振幅阈值参数（自适应噪底） ────────────────────────────
        /** 环境噪底校准窗口（前 N 帧用于估算安静环境分贝）*/
        const val CALIBRATION_FRAMES = 30       // 约 3 秒
        /** 鼾声需高于噪底多少 dB 才算有效 */
        const val NOISE_MARGIN_DB = 4.0
        /** 绝对最小阈值，即使在非常安静的环境中，也不会把细微呼吸声误判为鼾声 */
        const val MIN_SNORE_DB = 28.0
        /** 绝对最大阈值：防止噪底校准偏高导致灵敏度丢失 */
        const val MAX_SNORE_DB = 50.0
        /** 对外暴露默认最小有效窗口，供测试和 UI 文案参考 */
        const val MIN_VALID_WINDOW = 600

        // ── 滑动窗口参数（方案 A） ──────────────────────────────
        /** 窗口内鼾声帧占比超过此值才判定为入睡
         *  真实鼾声是阵发性的（打一声、停一停、继续），10秒窗口内通常只有 10%~20% 的帧有鼾声 */
        const val SNORE_RATIO_THRESHOLD = 0.18
    }

    // ── 状态 ─────────────────────────────────────────────────────
    /** 环境噪底（动态校准后的有效阈值 dB） */
    private var calibratedThresholdDb = MIN_SNORE_DB
    private val calibrationBuffer = mutableListOf<Double>()
    private var isCalibrated = false

    /** 滑动窗口大小（帧数），100ms 每帧 */
    private var windowSizeFrames = 3000 // 默认 5 分钟
    /** 最小有效窗口大小 */
    private var minValidWindow = MIN_VALID_WINDOW

    /** 滑动窗口（循环队列），存每帧是否为鼾声 */
    private val snoreWindow = ArrayDeque<Boolean>()
    private var snoreFrameCount = 0
    /** 帧级证据积分，避免一两帧语音尖峰直接误报 */
    private var snoreEvidenceScore = 0
    private var speechCooldownFrames = 0
    private var consecutiveCandidateFrames = 0
    private val recentCandidateFreqs = ArrayDeque<Double>()

    /**
     * 更新滑动窗口参数
     * @param durationSec 期待的入睡判定时长（秒）
     */
    fun updateWindowSize(durationSec: Int) {
        val newWindowSize = (durationSec * 10).coerceAtLeast(10) // 每秒 10 帧
        windowSizeFrames = newWindowSize
        minValidWindow = (newWindowSize * 0.8).toInt().coerceAtLeast(1) // 至少需要 80% 的数据量
        reset()
    }

    data class AudioFrame(
        val decibels: Double,
        val dominantFrequency: Double,
        val lowFreqEnergyRatio: Double,
        val isAmplitudeDetected: Boolean,   // 振幅维度
        val isFrequencyMatch: Boolean,      // 频率维度
        val isSnoreDetected: Boolean,       // 双维度综合判断
        val snoreRatio: Double,             // 当前窗口鼾声占比
        val isCalibrated: Boolean,          // 噪底是否已校准
    )

    /**
     * 重置所有状态（重新开始一次监控时调用）
     */
    fun reset() {
        calibrationBuffer.clear()
        isCalibrated = false
        calibratedThresholdDb = MIN_SNORE_DB
        snoreWindow.clear()
        snoreFrameCount = 0
        snoreEvidenceScore = 0
        speechCooldownFrames = 0
        consecutiveCandidateFrames = 0
        recentCandidateFreqs.clear()
    }

    /**
     * 开始录音并持续发出音频帧数据（Flow）
     */
    fun audioFrameFlow(): Flow<AudioFrame> = flow {
        reset()

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            BUFFER_SIZE,
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            try { Log.e(TAG, "AudioRecord 初始化失败") } catch (e: Exception) { println("AudioRecord 初始化失败") }
            return@flow
        }

        val readBuffer = ShortArray(BUFFER_SIZE)
        val frameBuffer = ShortArray(FRAME_SIZE)
        var frameOffset = 0
        audioRecord.startRecording()
        try { Log.d(TAG, "开始录音 (v2.0 增强算法)") } catch (e: Exception) {}

        try {
            while (coroutineContext.isActive) {
                val read = audioRecord.read(readBuffer, 0, readBuffer.size)
                if (read > 0) {
                    var sourceOffset = 0
                    while (sourceOffset < read) {
                        val copyCount = minOf(FRAME_SIZE - frameOffset, read - sourceOffset)
                        readBuffer.copyInto(frameBuffer, destinationOffset = frameOffset, startIndex = sourceOffset, endIndex = sourceOffset + copyCount)
                        frameOffset += copyCount
                        sourceOffset += copyCount

                        if (frameOffset == FRAME_SIZE) {
                            emit(analyzeFrame(frameBuffer, FRAME_SIZE))
                            frameOffset = 0
                        }
                    }
                }
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 分析一帧音频数据，输出 AudioFrame。
     * 对外暴露 internal 方便单元测试。
     */
    internal fun analyzeFrame(buffer: ShortArray, readCount: Int): AudioFrame {
        val db = calculateDecibels(buffer, readCount)

        // ─ 步骤 1：校准期（前 N 帧估算噪底） ──────────────────────
        if (!isCalibrated) {
            calibrationBuffer.add(db)
            if (calibrationBuffer.size >= CALIBRATION_FRAMES) {
                // 取前 60% 的低分贝帧的均值作为噪底基准，过滤掉偶发噪声
                val sorted = calibrationBuffer.sorted()
                val noiseSamples = sorted.take((sorted.size * 0.6).toInt())
                val noiseFloor = noiseSamples.average()
                calibratedThresholdDb = (noiseFloor + NOISE_MARGIN_DB)
                    .coerceIn(MIN_SNORE_DB, MAX_SNORE_DB)
                isCalibrated = true
                try { Log.d(TAG, "噪底校准完成: 噪底=${String.format("%.1f", noiseFloor)}dB, 阈值=${String.format("%.1f", calibratedThresholdDb)}dB") } catch (e: Exception) {}
            }
        }

        // ─ 步骤 2：振幅维度判断 ──────────────────────────────────
        val isAmplitudeDetected = db >= calibratedThresholdDb

        // ─ 步骤 3：FFT 频率维度判断（只在振幅通过时才做 FFT，节省算力） ─
        var dominantFreq = 0.0
        var lowFreqRatio = 0.0
        var isFrequencyMatch = false
        var midFreqRatio = 0.0
        var spectralFlatness = 1.0
        var zeroCrossingRate = 1.0
        var isSpeechLike = false
        val isShortWindowMode = windowSizeFrames <= 200

        if (isAmplitudeDetected) {
            val freqBins = SimpleFFT.computePowerSpectrum(buffer, readCount, SAMPLE_RATE)
            dominantFreq = freqBins.dominantFrequency()
            lowFreqRatio = freqBins.energyRatioInRange(SNORE_FREQ_MIN, SNORE_FREQ_MAX)
            midFreqRatio = freqBins.energyRatioInRange(700.0, 2000.0)
            spectralFlatness = freqBins.spectralFlatnessInRange(SNORE_FREQ_MIN, 1200.0)
            zeroCrossingRate = calculateZeroCrossingRate(buffer, readCount)

            isSpeechLike = when {
                midFreqRatio >= 0.35 -> true
                zeroCrossingRate >= 0.22 -> true
                spectralFlatness >= 0.38 && midFreqRatio >= 0.20 -> true
                else -> false
            }

            val dominantFreqMatch = dominantFreq in DOMINANT_FREQ_MIN..DOMINANT_FREQ_MAX
            val lowFreqStrong = lowFreqRatio >= if (isShortWindowMode) SHORT_WINDOW_LOW_FREQ_RATIO_THRESHOLD else LOW_FREQ_ENERGY_RATIO_THRESHOLD
            val midFreqSuppressed = midFreqRatio <= if (isShortWindowMode) SHORT_WINDOW_MID_FREQ_RATIO_MAX else MID_FREQ_ENERGY_RATIO_MAX
            val harmonicEnough = spectralFlatness <= if (isShortWindowMode) SHORT_WINDOW_SPECTRAL_FLATNESS_MAX else SPECTRAL_FLATNESS_MAX
            val waveformStable = zeroCrossingRate <= if (isShortWindowMode) SHORT_WINDOW_ZERO_CROSSING_RATE_MAX else ZERO_CROSSING_RATE_MAX

            isFrequencyMatch = dominantFreqMatch && lowFreqStrong && midFreqSuppressed && harmonicEnough && waveformStable && !isSpeechLike
        }

        if (isSpeechLike) {
            speechCooldownFrames = SPEECH_COOLDOWN_FRAMES
        } else if (speechCooldownFrames > 0) {
            speechCooldownFrames--
        }

        val baseCandidateSnore = isAmplitudeDetected && isFrequencyMatch && speechCooldownFrames == 0
        consecutiveCandidateFrames = if (baseCandidateSnore) consecutiveCandidateFrames + 1 else 0
        updateRecentCandidateFrequencies(dominantFreq, baseCandidateSnore)
        val frequencyStable = isRecentCandidateFrequencyStable(
            maxDeviationHz = if (isShortWindowMode) SHORT_WINDOW_DOMINANT_STABILITY_MAX_DEVIATION else 45.0,
        )

        val requiredBurstFrames = if (isShortWindowMode) 3 else 2
        val isCandidateSnore = baseCandidateSnore && consecutiveCandidateFrames >= requiredBurstFrames && frequencyStable
        snoreEvidenceScore = when {
            isCandidateSnore -> (snoreEvidenceScore + 2).coerceAtMost(6)
            isSpeechLike -> (snoreEvidenceScore - 2).coerceAtLeast(0)
            else -> (snoreEvidenceScore - 1).coerceAtLeast(0)
        }

        // ─ 步骤 4：双维度综合判定 + 稳态确认 ───────────────────────
        val isSnoreThisFrame = isCandidateSnore && snoreEvidenceScore >= 3

        if (isCalibrated && !isCandidateSnore) {
            updateAdaptiveThreshold(db)
        }

        if (isSnoreThisFrame) {
            try {
                val ratio = if (snoreWindow.size >= minValidWindow) snoreFrameCount.toDouble() / snoreWindow.size else 0.0
                Log.d(TAG, "检测分析: dB=${String.format("%.1f", db)}, freq=${String.format("%.1f", dominantFreq)}Hz, low=${String.format("%.2f", lowFreqRatio)}, mid=${String.format("%.2f", midFreqRatio)}, flat=${String.format("%.2f", spectralFlatness)}, zcr=${String.format("%.2f", zeroCrossingRate)}, speech=$isSpeechLike, cooldown=$speechCooldownFrames, burst=$consecutiveCandidateFrames, stable=$frequencyStable, score=$snoreEvidenceScore, 识别结果=$isSnoreThisFrame, 窗口占比=${String.format("%.3f", ratio)}(${snoreWindow.size}/${minValidWindow})")
            } catch (e: Exception) {}
        } else if (isAmplitudeDetected) {
            try {
                Log.d(TAG, "检测分析: dB=${String.format("%.1f", db)}, freq=${String.format("%.1f", dominantFreq)}Hz, low=${String.format("%.2f", lowFreqRatio)}, mid=${String.format("%.2f", midFreqRatio)}, flat=${String.format("%.2f", spectralFlatness)}, zcr=${String.format("%.2f", zeroCrossingRate)}, speech=$isSpeechLike, cooldown=$speechCooldownFrames, burst=$consecutiveCandidateFrames, stable=$frequencyStable, score=$snoreEvidenceScore, 识别结果=false")
            } catch (e: Exception) {}
        }

        // ─ 步骤 5：更新滑动窗口 ──────────────────────────────────
        if (snoreWindow.size >= windowSizeFrames) {
            val evicted = snoreWindow.pollFirst()!!
            if (evicted) snoreFrameCount--
        }
        snoreWindow.addLast(isSnoreThisFrame)
        if (isSnoreThisFrame) snoreFrameCount++

        // 滑动窗口鼾声占比（只有窗口积累到最小有效大小后才计算）
        val snoreRatio = if (snoreWindow.size >= minValidWindow) {
            snoreFrameCount.toDouble() / snoreWindow.size
        } else {
            0.0 // 数据不足，不做判断
        }

        return AudioFrame(
            decibels = db,
            dominantFrequency = dominantFreq,
            lowFreqEnergyRatio = lowFreqRatio,
            isAmplitudeDetected = isAmplitudeDetected,
            isFrequencyMatch = isFrequencyMatch,
            isSnoreDetected = isSnoreThisFrame,
            snoreRatio = snoreRatio,
            isCalibrated = isCalibrated,
        )
    }

    /**
     * 返回当前滑动窗口的鼾声占比
     */
    fun currentSnoreRatio(): Double {
        if (snoreWindow.size < minValidWindow) return 0.0
        return snoreFrameCount.toDouble() / snoreWindow.size
    }

    // ── 私有辅助 ─────────────────────────────────────────────────

    private fun calculateDecibels(buffer: ShortArray, readCount: Int): Double {
        if (readCount == 0) return 0.0
        var sum = 0.0
        for (i in 0 until readCount) {
            sum += buffer[i].toLong() * buffer[i].toLong()
        }
        val rms = kotlin.math.sqrt(sum / readCount)
        return if (rms > 0) 20 * log10(rms) else 0.0
    }

    private fun calculateZeroCrossingRate(buffer: ShortArray, readCount: Int): Double {
        if (readCount <= 1) return 0.0
        var zeroCrossings = 0
        var previous = buffer[0].toInt()
        for (i in 1 until readCount) {
            val current = buffer[i].toInt()
            if ((previous >= 0 && current < 0) || (previous < 0 && current >= 0)) {
                zeroCrossings++
            }
            previous = current
        }
        return zeroCrossings.toDouble() / (readCount - 1)
    }

    private fun updateAdaptiveThreshold(db: Double) {
        if (!db.isFinite()) return
        val delta = db - calibratedThresholdDb
        if (abs(delta) < 8.0) {
            val target = (db + NOISE_MARGIN_DB).coerceIn(MIN_SNORE_DB, MAX_SNORE_DB)
            calibratedThresholdDb = calibratedThresholdDb * 0.98 + target * 0.02
        }
    }

    private fun updateRecentCandidateFrequencies(dominantFreq: Double, accepted: Boolean) {
        if (!accepted || dominantFreq <= 0.0) {
            recentCandidateFreqs.clear()
            return
        }
        if (recentCandidateFreqs.size >= 4) {
            recentCandidateFreqs.removeFirst()
        }
        recentCandidateFreqs.addLast(dominantFreq)
    }

    private fun isRecentCandidateFrequencyStable(maxDeviationHz: Double): Boolean {
        if (recentCandidateFreqs.size < 3) return true
        val minFreq = recentCandidateFreqs.minOrNull() ?: return true
        val maxFreq = recentCandidateFreqs.maxOrNull() ?: return true
        return (maxFreq - minFreq) <= maxDeviationHz
    }
}
