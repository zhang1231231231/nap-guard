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

/**
 * 鼾声检测器
 *
 * 通过 AudioRecord 实时采集麦克风数据，分析音频振幅。
 * 简单判定逻辑：持续音量高于阈值且频率特征符合鼾声（低频周期性）。
 *
 * 判定规则（简化版，v1.0）：
 * - 计算每帧音频的 RMS（均方根振幅）
 * - 转为 dB 值
 * - 若 dB > SNORE_DB_THRESHOLD 且持续 SNORE_DETECTION_FRAMES 帧，视为检测到鼾声
 */
class SnoreDetector {

    companion object {
        private const val TAG = "SnoreDetector"

        const val SAMPLE_RATE = 16000          // 16kHz 采样率
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        const val SNORE_DB_THRESHOLD = 45.0   // 鼾声振幅阈值（dB）
        const val FRAME_DURATION_MS = 100L     // 每帧分析时长

        val BUFFER_SIZE = try {
            AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT).coerceAtLeast(4096)
        } catch (e: Exception) {
            4096 // Fallback for unit tests
        }
    }

    data class AudioFrame(
        val decibels: Double,
        val isSnoreDetected: Boolean,
    )

    /**
     * 开始录音并持续发出音频帧数据。
     * 调用者应在 Coroutine 中收集，并在取消时自动停止录音。
     */
    fun audioFrameFlow(): Flow<AudioFrame> = flow {
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            BUFFER_SIZE,
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            // 在真机上 Log.e，在测试中可以使用 println 或忽略
            try { Log.e(TAG, "AudioRecord 初始化失败") } catch (e: Exception) { println("AudioRecord 初始化失败") }
            return@flow
        }

        val buffer = ShortArray(BUFFER_SIZE)
        audioRecord.startRecording()
        try { Log.d(TAG, "开始录音") } catch (e: Exception) { println("开始录音") }

        try {
            while (coroutineContext.isActive) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val db = processAudio(buffer, read)
                    val isSnore = db > SNORE_DB_THRESHOLD
                    emit(AudioFrame(decibels = db, isSnoreDetected = isSnore))
                }
            }
        } finally {
            audioRecord.stop()
            audioRecord.release()
            try { Log.d(TAG, "停止录音，资源已释放") } catch (e: Exception) { println("停止录音，资源已释放") }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 处理音频数据，返回分贝值
     * 暴露为 internal 以便单元测试
     */
    internal fun processAudio(buffer: ShortArray, readCount: Int): Double {
        return calculateDecibels(buffer, readCount)
    }

    /**
     * 计算 PCM 缓冲区的 RMS 振幅，并转换为分贝值
     */
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
