package com.example.napguard.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * 简单的基 2 Cooley-Tukey FFT 实现（纯 Kotlin，无外部依赖）
 *
 * 用于对 PCM 音频数据进行频域分析，以检测主要频率成分。
 */
object SimpleFFT {

    /**
     * 对短整型 PCM 数据执行 FFT，返回每个频率箱的功率谱密度（线性）。
     *
     * @param samples PCM 样本数组
     * @param count 参与计算的样本数，必须是 2 的幂（内部会自动调整到最近 2 的幂）
     * @param sampleRate 音频采样率（Hz）
     * @return FrequencyBins 包含各频率箱的中心频率和功率
     */
    fun computePowerSpectrum(samples: ShortArray, count: Int, sampleRate: Int): FrequencyBins {
        // 取不超过 count 的最大 2 的幂
        val n = nearestPow2(count.coerceAtMost(samples.size))

        // 转换为浮点复数数组（实部 = 样本值，虚部 = 0）
        // 应用汉宁窗（Hanning Window）减少频谱泄漏
        var mean = 0.0
        for (i in 0 until n) {
            mean += samples[i].toDouble()
        }
        mean /= n

        val real = DoubleArray(n) { i ->
            val window = 0.5 * (1.0 - cos(2.0 * PI * i / (n - 1)))
            (samples[i].toDouble() - mean) * window
        }
        val imag = DoubleArray(n) { 0.0 }

        // 原地 FFT
        fftInPlace(real, imag, n)

        // 计算功率谱（只取前半段，因为结果关于奈奎斯特频率对称）
        val halfN = n / 2
        val freqResolution = sampleRate.toDouble() / n
        val powers = DoubleArray(halfN) { i ->
            val r = real[i]
            val im = imag[i]
            // 归一化后的功率：|X[k]|^2 / N
            (r * r + im * im) / n
        }
        return FrequencyBins(powers, freqResolution)
    }

    /**
     * 原地基 2 FFT（Cooley-Tukey 蝶形算法）
     */
    private fun fftInPlace(real: DoubleArray, imag: DoubleArray, n: Int) {
        // 位逆序排列
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                real[i] = real[j].also { real[j] = real[i] }
                imag[i] = imag[j].also { imag[j] = imag[i] }
            }
        }

        // 蝶形计算
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val angle = -2.0 * PI / len
            val wReal = cos(angle)
            val wImag = sin(angle)

            var start = 0
            while (start < n) {
                var curReal = 1.0
                var curImag = 0.0
                for (k in 0 until halfLen) {
                    val uR = real[start + k]
                    val uI = imag[start + k]
                    val vR = real[start + k + halfLen] * curReal - imag[start + k + halfLen] * curImag
                    val vI = real[start + k + halfLen] * curImag + imag[start + k + halfLen] * curReal
                    real[start + k] = uR + vR
                    imag[start + k] = uI + vI
                    real[start + k + halfLen] = uR - vR
                    imag[start + k + halfLen] = uI - vI
                    val newCurReal = curReal * wReal - curImag * wImag
                    curImag = curReal * wImag + curImag * wReal
                    curReal = newCurReal
                }
                start += len
            }
            len = len shl 1
        }
    }

    private fun nearestPow2(n: Int): Int {
        var p = 1
        while (p < n) p = p shl 1
        return if (p == n) n else p shr 1
    }

    /**
     * FFT 结果容器
     */
    data class FrequencyBins(
        val powers: DoubleArray,      // 每个频率箱的功率
        val freqResolution: Double,   // 每个频率箱的宽度（Hz）
    ) {
        fun energyInRange(minHz: Double, maxHz: Double): Double {
            if (powers.isEmpty()) return 0.0
            val minBin = (minHz / freqResolution).toInt().coerceAtLeast(0)
            val maxBin = (maxHz / freqResolution).toInt().coerceAtMost(powers.size - 1)
            if (maxBin < minBin) return 0.0
            var sum = 0.0
            for (i in minBin..maxBin) {
                sum += powers[i]
            }
            return sum
        }

        /**
         * 计算指定频率范围内的总能量占比（相对于全频段总能量）
         */
        fun energyRatioInRange(minHz: Double, maxHz: Double): Double {
            val totalEnergy = powers.sum()
            if (totalEnergy <= 0.0) return 0.0

            val rangeEnergy = energyInRange(minHz, maxHz)
            return rangeEnergy / totalEnergy
        }

        /**
         * 频谱平坦度：越接近 1 越像宽频噪声，越接近 0 越像有明显峰值的信号。
         */
        fun spectralFlatnessInRange(minHz: Double, maxHz: Double): Double {
            if (powers.isEmpty()) return 1.0
            val minBin = (minHz / freqResolution).toInt().coerceAtLeast(0)
            val maxBin = (maxHz / freqResolution).toInt().coerceAtMost(powers.size - 1)
            if (maxBin < minBin) return 1.0

            var logSum = 0.0
            var linearSum = 0.0
            var count = 0
            for (i in minBin..maxBin) {
                val power = powers[i].coerceAtLeast(1e-12)
                logSum += ln(power)
                linearSum += power
                count++
            }
            if (count == 0 || linearSum <= 0.0) return 1.0

            val geometricMean = kotlin.math.exp(logSum / count)
            val arithmeticMean = linearSum / count
            return (geometricMean / arithmeticMean).coerceIn(0.0, 1.0)
        }

        /**
         * 返回能量最大的频率所在箱的中心频率（Hz）
         */
        fun dominantFrequency(): Double {
            val maxIdx = powers.indices.maxByOrNull { powers[it] } ?: 0
            return maxIdx * freqResolution
        }
    }
}
