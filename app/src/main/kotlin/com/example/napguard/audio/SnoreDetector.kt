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
        /** 鼾声频率高端（Hz）*/
        const val SNORE_FREQ_MAX = 400.0
        /** 低频能量占全频段的最低比例，超过才认为是鼾声 */
        const val LOW_FREQ_ENERGY_RATIO_THRESHOLD = 0.55

        // ── 振幅阈值参数（自适应噪底） ────────────────────────────
        /** 环境噪底校准窗口（前 N 帧用于估算安静环境分贝）*/
        const val CALIBRATION_FRAMES = 200      // 约 20 秒
        /** 鼾声需高于噪底多少 dB 才算有效 */
        const val NOISE_MARGIN_DB = 12.0
        /** 绝对最小阈值：即使在非常安静的环境中，也不会把细微呼吸声误判为鼾声 */
        const val MIN_SNORE_DB = 38.0
        /** 绝对最大阈值：防止噪底校准偏高导致灵敏度丢失 */
        const val MAX_SNORE_DB = 58.0

        // ── 滑动窗口参数（方案 A） ──────────────────────────────
        /** 滑动窗口大小（帧数），100ms 每帧，5分钟 = 3000 帧 */
        const val WINDOW_SIZE_FRAMES = 3000
        /** 窗口内鼾声帧占比超过此值才判定为入睡 */
        const val SNORE_RATIO_THRESHOLD = 0.60
        /** 最小有效窗口大小：至少需要积累 1 分钟数据后才开始判定 */
        const val MIN_VALID_WINDOW = 600
    }

    // ── 状态 ─────────────────────────────────────────────────────
    /** 环境噪底（动态校准后的有效阈值 dB） */
    private var calibratedThresholdDb = MIN_SNORE_DB
    private val calibrationBuffer = mutableListOf<Double>()
    private var isCalibrated = false

    /** 滑动窗口（循环队列），存每帧是否为鼾声 */
    private val snoreWindow = ArrayDeque<Boolean>(WINDOW_SIZE_FRAMES + 1)
    private var snoreFrameCount = 0

    // ── 公开 API ──────────────────────────────────────────────────

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

        val buffer = ShortArray(BUFFER_SIZE)
        audioRecord.startRecording()
        try { Log.d(TAG, "开始录音 (v2.0 增强算法)") } catch (e: Exception) {}

        try {
            while (coroutineContext.isActive) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    emit(analyzeFrame(buffer, read))
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
        val isAmplitudeDetected = db > calibratedThresholdDb

        // ─ 步骤 3：FFT 频率维度判断（只在振幅通过时才做 FFT，节省算力） ─
        var dominantFreq = 0.0
        var lowFreqRatio = 0.0
        var isFrequencyMatch = false

        if (isAmplitudeDetected) {
            val freqBins = SimpleFFT.computePowerSpectrum(buffer, readCount, SAMPLE_RATE)
            dominantFreq = freqBins.dominantFrequency()
            lowFreqRatio = freqBins.energyRatioInRange(SNORE_FREQ_MIN, SNORE_FREQ_MAX)
            isFrequencyMatch = lowFreqRatio >= LOW_FREQ_ENERGY_RATIO_THRESHOLD
        }

        // ─ 步骤 4：双维度综合判定 ─────────────────────────────────
        val isSnoreThisFrame = isAmplitudeDetected && isFrequencyMatch

        // ─ 步骤 5：更新滑动窗口 ──────────────────────────────────
        if (snoreWindow.size >= WINDOW_SIZE_FRAMES) {
            val evicted = snoreWindow.pollFirst()!!
            if (evicted) snoreFrameCount--
        }
        snoreWindow.addLast(isSnoreThisFrame)
        if (isSnoreThisFrame) snoreFrameCount++

        // 滑动窗口鼾声占比（只有窗口积累到最小有效大小后才计算）
        val snoreRatio = if (snoreWindow.size >= MIN_VALID_WINDOW) {
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
        if (snoreWindow.size < MIN_VALID_WINDOW) return 0.0
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
}
