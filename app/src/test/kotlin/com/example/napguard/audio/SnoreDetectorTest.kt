package com.example.napguard.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * SnoreDetector v2.0 单元测试
 *
 * 验证增强版算法的三个核心模块：
 * 1. FFT 频率分析（SimpleFFT）
 * 2. 振幅 + 频率双维度帧级判定
 * 3. 滑动窗口占比统计
 */
class SnoreDetectorTest {

    private lateinit var detector: SnoreDetector

    @Before
    fun setup() {
        detector = SnoreDetector()
        detector.reset()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 模块一：SimpleFFT 单元测试
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testFFT_sinWave_detectsDominantFrequency() {
        // 生成一个 200Hz 的纯正弦波（鼾声频率范围内）
        val sampleRate = 16000
        val freq = 200.0
        val samples = ShortArray(1024) { i ->
            (Short.MAX_VALUE * Math.sin(2 * Math.PI * freq * i / sampleRate)).toInt().toShort()
        }

        val bins = SimpleFFT.computePowerSpectrum(samples, 1024, sampleRate)
        val dominant = bins.dominantFrequency()

        println("FFT 200Hz 测试：主频 = ${String.format("%.1f", dominant)} Hz")
        // 允许 ±15Hz 的误差（FFT 分辨率 = 16000/1024 ≈ 15.6Hz）
        assertTrue("主频应在 185~215Hz 之间，实际 $dominant", dominant in 185.0..215.0)
    }

    @Test
    fun testFFT_lowFreqEnergyRatio_snoreRange() {
        // 生成 150Hz 正弦波，应在 50~400Hz 范围内
        val sampleRate = 16000
        val samples = ShortArray(1024) { i ->
            (Short.MAX_VALUE * 0.8 * Math.sin(2 * Math.PI * 150.0 * i / sampleRate)).toInt().toShort()
        }

        val bins = SimpleFFT.computePowerSpectrum(samples, 1024, sampleRate)
        val ratio = bins.energyRatioInRange(SnoreDetector.SNORE_FREQ_MIN, SnoreDetector.SNORE_FREQ_MAX)

        println("FFT 低频能量比（150Hz 信号）：$ratio")
        assertTrue("150Hz 信号应有 >90% 能量在 50~400Hz 内，实际 $ratio", ratio > 0.9)
    }

    @Test
    fun testFFT_highFreq_notInSnoreRange() {
        // 生成 4000Hz 正弦波（远超鼾声频率，应被过滤）
        val sampleRate = 16000
        val samples = ShortArray(1024) { i ->
            (Short.MAX_VALUE * 0.9 * Math.sin(2 * Math.PI * 4000.0 * i / sampleRate)).toInt().toShort()
        }

        val bins = SimpleFFT.computePowerSpectrum(samples, 1024, sampleRate)
        val ratio = bins.energyRatioInRange(SnoreDetector.SNORE_FREQ_MIN, SnoreDetector.SNORE_FREQ_MAX)

        println("FFT 低频能量比（4000Hz 信号）：$ratio")
        assertTrue("4000Hz 信号应有 <10% 能量在 50~400Hz 内，实际 $ratio", ratio < 0.1)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 模块二：滑动窗口统计测试
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testSlidingWindow_ratioAccumulation() {
        // 用合成的 200Hz 低频高振幅信号填满窗口
        val sampleRate = 16000
        val samples = ShortArray(4096) { i ->
            // 满幅 200Hz 正弦波，保证 dB 远超阈值
            (Short.MAX_VALUE * 0.9 * Math.sin(2 * Math.PI * 200.0 * i / sampleRate)).toInt().toShort()
        }

        // 将帧送入检测器，一直到窗口满足最小有效大小
        val requiredFrames = SnoreDetector.MIN_VALID_WINDOW + 10
        repeat(requiredFrames) { detector.analyzeFrame(samples, samples.size) }

        val ratio = detector.currentSnoreRatio()
        println("滑动窗口鼾声占比（连续低频合成音）：$ratio")
        // 注意：有 CALIBRATION_FRAMES 帧用于校准，校准期不算鼾声
        // 实际有效帧 = requiredFrames - CALIBRATION_FRAMES
        assertTrue("鼾声占比应大于 0，实际 $ratio", ratio > 0.0)
    }

    @Test
    fun testSlidingWindow_silence_zeroRatio() {
        // 完全静音数据，鼾声占比应为 0
        val silence = ShortArray(4096) { 0 }
        val requiredFrames = SnoreDetector.MIN_VALID_WINDOW + 10
        repeat(requiredFrames) { detector.analyzeFrame(silence, silence.size) }

        val ratio = detector.currentSnoreRatio()
        println("滑动窗口鼾声占比（静音）：$ratio")
        assertEquals("静音数据鼾声占比应为 0", 0.0, ratio, 0.001)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 模块三：真实录音端到端测试
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun testRealAudio_snoreBasic_detectedFrames() {
        val file = File("src/test/assets/snore_basic.wav")
        assertTrue("测试音频文件不存在: ${file.absolutePath}", file.exists())

        detector.reset()
        val frames = processWavFile(file)

        val snoreFrames = frames.count { it.isSnoreDetected }
        val amplitudeFrames = frames.count { it.isAmplitudeDetected }
        val freqMatchFrames = frames.count { it.isFrequencyMatch }
        val maxDb = frames.maxOfOrNull { it.decibels } ?: 0.0

        println("=== snore_basic.wav 分析结果 ===")
        println("总帧数: ${frames.size}")
        println("振幅通过帧: $amplitudeFrames (${(amplitudeFrames * 100 / frames.size.coerceAtLeast(1))}%)")
        println("频率匹配帧: $freqMatchFrames (${(freqMatchFrames * 100 / frames.size.coerceAtLeast(1))}%)")
        println("综合检测帧: $snoreFrames (${(snoreFrames * 100 / frames.size.coerceAtLeast(1))}%)")
        println("最高分贝: ${String.format("%.1f", maxDb)} dB")
        println("最终窗口占比: ${String.format("%.2f", frames.lastOrNull()?.snoreRatio ?: 0.0)}")

        // 该文件帧数 < CALIBRATION_FRAMES(200)，仍处校准期，双维度判定未激活
        // 故验证振幅维度是否检测到高音量帧即可
        assertTrue(
            "振幅应至少检测到一帧（最高 ${String.format("%.1f", maxDb)} dB）",
            amplitudeFrames > 0
        )
    }

    @Test
    fun testRealAudio_snoreMale_detectedFrames() {
        val file = File("src/test/assets/snore_male.wav")
        assertTrue("测试音频文件不存在: ${file.absolutePath}", file.exists())

        detector.reset()
        val frames = processWavFile(file)

        val snoreFrames = frames.count { it.isSnoreDetected }
        val totalFrames = frames.size
        val finalRatio = frames.lastOrNull()?.snoreRatio ?: 0.0

        println("=== snore_male.wav 分析结果 ===")
        println("总帧数: $totalFrames")
        println("鼾声帧数: $snoreFrames (${(snoreFrames * 100 / totalFrames.coerceAtLeast(1))}%)")
        println("最终窗口鼾声占比: ${String.format("%.2f", finalRatio)}")

        assertTrue("应检测到至少一帧鼾声", snoreFrames > 0)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 工具方法：读取 44.1kHz 立体声 WAV → 单声道 DoubleArray
    // ─────────────────────────────────────────────────────────────────────────

    private fun processWavFile(file: File): List<SnoreDetector.AudioFrame> {
        val bytes = file.readBytes()
        if (bytes.size < 44) return emptyList()
        val pcmData = bytes.sliceArray(44 until bytes.size)

        val shortBuffer = ByteBuffer.wrap(pcmData)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        // 立体声转单声道（左右声道取平均）
        val monoShorts = mutableListOf<Short>()
        while (shortBuffer.remaining() >= 2) {
            val left = shortBuffer.get()
            val right = shortBuffer.get()
            monoShorts.add(((left.toInt() + right.toInt()) / 2).toShort())
        }

        // 44100Hz，100ms/帧 = 4410 样本
        val frameSize = 4410
        val results = mutableListOf<SnoreDetector.AudioFrame>()
        var offset = 0
        while (offset + frameSize <= monoShorts.size) {
            val frame = ShortArray(frameSize) { monoShorts[offset + it] }
            results.add(detector.analyzeFrame(frame, frameSize))
            offset += frameSize
        }
        return results
    }
}
