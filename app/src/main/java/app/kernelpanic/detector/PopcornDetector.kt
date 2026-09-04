package app.kernelpanic.detector

import java.util.ArrayDeque
import kotlin.math.PI

/** Production pipeline shared by AudioRecord, WAV playback, and deterministic fixtures. */
class PopcornDetector(
    val sampleRate: Int,
    val config: DetectorConfig = DetectorConfig(),
) {
    private val extractor = AudioFeatureExtractor(sampleRate, config)
    private val eventDetector = PopEventDetector(config)
    private val countDetector = PopCountDetector(config)
    private val sessionDetector = PopcornSessionDetector(config)
    private val rawRing = ShortArray(config.frameSize)
    private val filteredRing = DoubleArray(config.frameSize)
    private var ringWrite = 0
    private var nonZeroSamples = 0
    private var samplesSeen = 0L
    private var samplesSinceAnalysis = 0
    private var previousInput = 0.0
    private var previousHighPassed = 0.0
    private val highPassAlpha = run {
        val rc = 1.0 / (2.0 * PI * config.highPassHz)
        val dt = 1.0 / sampleRate
        rc / (rc + dt)
    }
    private val estimatedPopTimes = mutableListOf<Long>()
    private val pendingEstimatedPopTimes = ArrayDeque<Long>()
    private val estimatedPopsPerSecond = mutableMapOf<Long, Int>()
    private var countingStarted = false
    private var cachedHistorySecond = -1L
    private var popHistoryDirty = true
    private var cachedPopHistory = emptyList<Float>()
    private var estimatedPeakRate = 0.0
    private var estimatedFirstPopMs: Long? = null
    @Volatile private var suppressUntilSample = 0L

    fun process(samples: ShortArray): List<DetectorSnapshot> {
        val results = ArrayList<DetectorSnapshot>(samples.size / config.hopSize + 1)
        for (sample in samples) {
            if (rawRing[ringWrite].toInt() != 0) nonZeroSamples--
            rawRing[ringWrite] = sample
            if (sample.toInt() != 0) nonZeroSamples++
            val input = sample / 32768.0
            val highPassed = highPassAlpha * (previousHighPassed + input - previousInput)
            previousInput = input
            previousHighPassed = highPassed
            filteredRing[ringWrite] = highPassed
            ringWrite = (ringWrite + 1) % rawRing.size
            samplesSeen++
            samplesSinceAnalysis++
            if (samplesSeen >= config.frameSize && samplesSinceAnalysis >= config.hopSize) {
                samplesSinceAnalysis = 0
                val frame = DoubleArray(config.frameSize)
                for (i in frame.indices) frame[i] = filteredRing[(ringWrite + i) % filteredRing.size]
                val timestampMs = samplesSeen * 1000L / sampleRate
                val features = extractor.extract(frame, timestampMs, digitalSilence = nonZeroSamples == 0)
                val calibrating = timestampMs < (config.calibrationSeconds * 1000).toLong()
                val suppressed = samplesSeen <= suppressUntilSample
                val trustedEvent = if (suppressed) {
                    eventDetector.discardCandidate()
                    null
                } else {
                    eventDetector.process(features, acceptingEvents = !calibrating)
                }
                val countedEvent = if (suppressed) {
                    countDetector.discardCandidate()
                    null
                } else {
                    countDetector.process(features, acceptingPops = !calibrating)
                }
                val sessionSnapshot = sessionDetector.process(
                    features,
                    trustedEvent,
                    ignoreApplianceState = suppressed,
                )
                // The estimate gets extra recall from a permissive candidate stream, but
                // setup noises before the microwave settles should not look like kernels.
                // Candidates wait in a short rolling buffer until one conservative event
                // confirms that popping has actually begun; the buffer is then backfilled.
                val countSetupComplete = timestampMs >=
                    ((config.calibrationSeconds + config.setupNoiseGuardSeconds) * 1_000).toLong()
                if (!countSetupComplete) pendingEstimatedPopTimes.clear()
                if (countedEvent != null && sessionSnapshot.microwaveOperating && countSetupComplete) {
                    if (countingStarted) {
                        addEstimatedPop(countedEvent.timestampMs)
                    } else {
                        pendingEstimatedPopTimes.addLast(countedEvent.timestampMs)
                    }
                }
                val backfillCutoff = timestampMs - (config.countBackfillSeconds * 1_000).toLong()
                while (pendingEstimatedPopTimes.firstOrNull()?.let { it < backfillCutoff } == true) {
                    pendingEstimatedPopTimes.removeFirst()
                }
                val sustainedPermissiveBurst = timestampMs >= (config.countFallbackStartSeconds * 1_000).toLong() &&
                    pendingEstimatedPopTimes.size >= config.countFallbackMinimumCandidates
                if (!countingStarted && (sessionSnapshot.detectedPops > 0 || sustainedPermissiveBurst)) {
                    countingStarted = true
                    while (pendingEstimatedPopTimes.isNotEmpty()) {
                        addEstimatedPop(pendingEstimatedPopTimes.removeFirst())
                    }
                }
                val currentRate = estimatedRate(timestampMs)
                estimatedPeakRate = maxOf(estimatedPeakRate, currentRate)
                results += sessionSnapshot.withPopEstimate(currentRate)
            }
        }
        return results
    }

    fun stopManually(): DetectorSnapshot {
        val now = samplesSeen * 1000L / sampleRate
        return sessionDetector.stopManually(now).withPopEstimate(estimatedRate(now))
    }

    /** Prevents the phone's own speech, tone, or vibration from being counted as a pop. */
    fun suppressEventsFor(durationMs: Long) {
        val extraSamples = durationMs.coerceAtLeast(0) * sampleRate / 1000L
        suppressUntilSample = maxOf(suppressUntilSample, samplesSeen + extraSamples)
        eventDetector.discardCandidate()
        countDetector.discardCandidate()
    }

    private fun addEstimatedPop(timestampMs: Long) {
        estimatedPopTimes += timestampMs
        rememberFirstPop(timestampMs)
        val second = timestampMs / 1_000
        estimatedPopsPerSecond[second] = (estimatedPopsPerSecond[second] ?: 0) + 1
        popHistoryDirty = true
    }

    private fun rememberFirstPop(timestampMs: Long) {
        if (estimatedFirstPopMs == null || timestampMs < estimatedFirstPopMs!!) {
            estimatedFirstPopMs = timestampMs
        }
    }

    private fun estimatedRate(now: Long): Double {
        val cutoff = now - 1_000
        return estimatedPopTimes.count { it >= cutoff }.toDouble()
    }

    private fun popHistory(now: Long): List<Float> {
        val currentSecond = now / 1_000
        if (!popHistoryDirty && currentSecond == cachedHistorySecond) return cachedPopHistory
        val firstSecond = (currentSecond - 299).coerceAtLeast(0)
        var total = estimatedPopsPerSecond.filterKeys { it < firstSecond }.values.sum()
        cachedPopHistory = (firstSecond..currentSecond).map { second ->
            total += estimatedPopsPerSecond[second] ?: 0
            total.toFloat()
        }
        cachedHistorySecond = currentSecond
        popHistoryDirty = false
        return cachedPopHistory
    }

    private fun DetectorSnapshot.withPopEstimate(currentRate: Double): DetectorSnapshot = copy(
        estimatedPopCount = estimatedPopTimes.size,
        estimatedCurrentRate = currentRate,
        estimatedPeakRate = this@PopcornDetector.estimatedPeakRate,
        estimatedFirstPopMs = this@PopcornDetector.estimatedFirstPopMs,
        popHistory = popHistory(elapsedMs),
    )
}
