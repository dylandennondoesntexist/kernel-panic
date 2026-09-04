package app.kernelpanic.detector

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Stateful FFT feature extractor with a slowly learned per-frequency background profile. */
class AudioFeatureExtractor(
    private val sampleRate: Int,
    private val config: DetectorConfig,
) {
    private var previousRms = 1e-6
    private var previousSpectrum: DoubleArray? = null
    private var backgroundSpectrum: DoubleArray? = null

    fun extract(samples: DoubleArray, timestampMs: Long, digitalSilence: Boolean): AudioFeatures {
        require(samples.size == config.frameSize) { "Expected ${config.frameSize} samples" }
        val n = samples.size
        val windowedSamples = DoubleArray(n)
        var sumSquares = 0.0
        var peak = 0.0
        var firstEnergy = 0.0
        var lastEnergy = 0.0

        for (i in samples.indices) {
            val hp = samples[i]
            val windowed = hp * (0.5 - 0.5 * cos(2.0 * PI * i / (n - 1)))
            windowedSamples[i] = windowed
            val square = hp * hp
            sumSquares += square
            peak = max(peak, kotlin.math.abs(hp))
            if (i < n / 4) firstEnergy += square
            if (i >= n * 3 / 4) lastEnergy += square
        }

        val rms = sqrt(sumSquares / n).coerceAtLeast(1e-9)
        val rmsDb = 20.0 * log10(rms)
        val magnitudes = fftMagnitudes(windowedSamples)
        val totalPower = magnitudes.sumOf { it * it }.coerceAtLeast(1e-15)
        var microwavePower = 0.0
        var highPower = 0.0
        var logMagnitudeSum = 0.0
        var arithmeticMagnitude = 0.0
        var includedBins = 0
        var popExcessSquares = 0.0
        var popExcessBins = 0
        val learnedBackground = backgroundSpectrum

        for (bin in 1 until magnitudes.size) {
            val frequency = bin.toDouble() * sampleRate / n
            val power = magnitudes[bin] * magnitudes[bin]
            if (frequency in config.microwaveBandLowHz..config.microwaveBandHighHz) microwavePower += power
            if (frequency in config.popBandLowHz..minOf(config.popBandHighHz, sampleRate / 2.0)) {
                highPower += power
                learnedBackground?.let { background ->
                    val reference = background[bin].coerceAtLeast(1e-9)
                    val excess = ((magnitudes[bin] - reference) / reference).coerceIn(0.0, 25.0)
                    popExcessSquares += excess * excess
                    popExcessBins++
                }
            }
            if (frequency >= config.highPassHz) {
                val magnitude = magnitudes[bin].coerceAtLeast(1e-12)
                logMagnitudeSum += ln(magnitude)
                arithmeticMagnitude += magnitude
                includedBins++
            }
        }
        updateBackgroundSpectrum(magnitudes, timestampMs)

        val normalized = DoubleArray(magnitudes.size) { magnitudes[it] / sqrt(totalPower) }
        val prior = previousSpectrum
        var flux = 0.0
        if (prior != null) {
            for (i in normalized.indices) {
                val rise = normalized[i] - prior[i]
                if (rise > 0.0) flux += rise * rise
            }
            flux = sqrt(flux)
        }
        previousSpectrum = normalized

        val flatness = if (includedBins == 0 || arithmeticMagnitude == 0.0) 0.0 else {
            val geometricMean = kotlin.math.exp(logMagnitudeSum / includedBins)
            geometricMean / (arithmeticMagnitude / includedBins)
        }
        val microwaveRms = sqrt(microwavePower / n).coerceAtLeast(1e-9)
        val attack = sqrt((firstEnergy / (n / 4)).coerceAtLeast(1e-15))
        val decay = sqrt((lastEnergy / (n / 4)).coerceAtLeast(1e-15))

        val result = AudioFeatures(
            timestampMs = timestampMs,
            rms = rms,
            rmsDb = rmsDb,
            peak = peak,
            crestFactor = peak / rms,
            spectralFlux = flux,
            spectralFlatness = flatness,
            highFrequencyRatio = (highPower / totalPower).coerceIn(0.0, 1.0),
            microwaveBandDb = 20.0 * log10(microwaveRms),
            attackRatio = (rms / previousRms).coerceAtMost(20.0),
            decayRatio = (attack / decay.coerceAtLeast(1e-9)).coerceAtMost(20.0),
            digitalSilence = digitalSilence,
            spectralExcess = if (popExcessBins == 0) 0.0 else sqrt(popExcessSquares / popExcessBins),
        )
        previousRms = rms
        return result
    }

    private fun updateBackgroundSpectrum(magnitudes: DoubleArray, timestampMs: Long) {
        val background = backgroundSpectrum
        if (background == null) {
            backgroundSpectrum = magnitudes.copyOf()
            return
        }
        val calibrating = timestampMs < (config.calibrationSeconds * 1_000).toLong()
        val learning = timestampMs < (config.backgroundLearningSeconds * 1_000).toLong()
        for (bin in magnitudes.indices) {
            val alpha = when {
                calibrating -> if (magnitudes[bin] < background[bin]) 0.10 else 0.035
                magnitudes[bin] < background[bin] -> 0.018
                learning -> 0.0015
                else -> 0.00015
            }
            background[bin] += alpha * (magnitudes[bin] - background[bin])
        }
    }

    private fun fftMagnitudes(realInput: DoubleArray): DoubleArray {
        val n = realInput.size
        require(n > 0 && n and (n - 1) == 0) { "FFT size must be a power of two" }
        val real = realInput.copyOf()
        val imaginary = DoubleArray(n)
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val temp = real[i]
                real[i] = real[j]
                real[j] = temp
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wLenReal = cos(angle)
            val wLenImag = sin(angle)
            var start = 0
            while (start < n) {
                var wReal = 1.0
                var wImag = 0.0
                for (offset in 0 until length / 2) {
                    val even = start + offset
                    val odd = even + length / 2
                    val oddReal = real[odd] * wReal - imaginary[odd] * wImag
                    val oddImag = real[odd] * wImag + imaginary[odd] * wReal
                    real[odd] = real[even] - oddReal
                    imaginary[odd] = imaginary[even] - oddImag
                    real[even] += oddReal
                    imaginary[even] += oddImag
                    val nextReal = wReal * wLenReal - wImag * wLenImag
                    wImag = wReal * wLenImag + wImag * wLenReal
                    wReal = nextReal
                }
                start += length
            }
            length = length shl 1
        }
        return DoubleArray(n / 2 + 1) { sqrt(real[it] * real[it] + imaginary[it] * imaginary[it]) }
    }
}
