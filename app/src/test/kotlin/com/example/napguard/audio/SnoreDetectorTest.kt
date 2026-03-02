package com.example.napguard.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SnoreDetectorTest {

    private val detector = SnoreDetector()

    @Test
    fun testSnoreDetection_Basic() {
        val file = File("src/test/assets/snore_basic.wav")
        assertTrue("测试音频文件不存在: ${file.absolutePath}", file.exists())

        val results = processWavFile(file)
        val snoreFrames = results.count { it.isSnoreDetected }
        val totalFrames = results.size
        val maxDb = results.maxOfOrNull { it.decibels } ?: 0.0

        println("Basic Snore Test: $snoreFrames / $totalFrames frames detected as snore. Max dB: $maxDb")
        
        // 基本要求：至少有部分帧被识别为打鼾（dB > 45）
        assertTrue("未能检测到任何打鼾帧 (录音最大分贝: $maxDb, 阈值: ${SnoreDetector.SNORE_DB_THRESHOLD})", snoreFrames > 0)
    }

    @Test
    fun testSnoreDetection_Male() {
        val file = File("src/test/assets/snore_male.wav")
        assertTrue("测试音频文件不存在: ${file.absolutePath}", file.exists())

        val results = processWavFile(file)
        val snoreFrames = results.count { it.isSnoreDetected }
        val totalFrames = results.size

        println("Male Snore Test: $snoreFrames / $totalFrames frames detected as snore")
        
        assertTrue("未能检测到任何打鼾帧", snoreFrames > 0)
    }

    /**
     * 模拟音频处理逻辑：读取 WAV 文件并分帧处理
     * 支持 44.1kHz 立体声转换为 16kHz 单声道（简化处理）
     */
    private fun processWavFile(file: File): List<SnoreDetector.AudioFrame> {
        val bytes = file.readBytes()
        // 跳过 WAV 文件头 (44 字节)
        if (bytes.size < 44) return emptyList()
        val pcmData = bytes.sliceArray(44 until bytes.size)
        
        val shortBuffer = ByteBuffer.wrap(pcmData)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
        
        // 转换为单声道 (假设文件是 16-bit 立体声，每两个 Short 取一个)
        val monoShorts = mutableListOf<Short>()
        var i = 0
        while (i + 1 < shortBuffer.remaining()) {
            val left = shortBuffer.get()
            val right = shortBuffer.get()
            // 简单取平均值作为单声道
            monoShorts.add(((left.toInt() + right.toInt()) / 2).toShort())
            i += 2
        }

        // 此时 monoShorts 是 44100Hz 的单声道数据
        // 每秒 44100 个采样，100ms 应该是 4410 个采样
        val frameSize = 4410 
        val results = mutableListOf<SnoreDetector.AudioFrame>()

        var offset = 0
        while (offset + frameSize <= monoShorts.size) {
            val frame = ShortArray(frameSize)
            for (j in 0 until frameSize) {
                frame[j] = monoShorts[offset + j]
            }
            
            val db = detector.processAudio(frame, frameSize)
            results.add(
                SnoreDetector.AudioFrame(
                    decibels = db,
                    isSnoreDetected = db > SnoreDetector.SNORE_DB_THRESHOLD
                )
            )
            offset += frameSize
        }
        return results
    }
}
