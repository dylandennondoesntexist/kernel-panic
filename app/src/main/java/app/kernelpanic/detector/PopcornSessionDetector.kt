package app.kernelpanic.detector

import java.util.ArrayDeque

/** Turns accepted event timing and long-duration signal evidence into the popcorn lifecycle. */
class PopcornSessionDetector(private val config: DetectorConfig) {
    private var phase = SessionPhase.CALIBRATING
    private val eventTimes = mutableListOf<Long>()
    private val microwaveCalibration = mutableListOf<Double>()
    private var smoothedMicrowaveDb: Double? = null
    private var operationBaselineDb: Double? = null
    private var operationObserved = false
    private var microwaveDropSinceMs: Long? = null
    private var invalidSinceMs: Long? = null
    private var activeReached = false
    private var activeSinceMs: Long? = null
    private var decliningSinceMs: Long? = null
    private var sparseSinceMs: Long? = null
    private var doneAtMs: Long? = null
    private var completionReason: CompletionReason? = null
    private var peakRate = 0.0
    private var firstPopMs: Long? = null
    private var lastHistorySecond = -1L
    private val rateHistory = ArrayDeque<Float>()
    private var lastEvent: PopEvent? = null

    fun process(features: AudioFeatures, event: PopEvent?): DetectorSnapshot {
        val now = features.timestampMs
        updateSignalHealth(features, now)
        if (phase == SessionPhase.INTERRUPTED) return snapshot(features)

        val accepted = event?.takeIf { it.accepted }
        if (accepted != null) {
            eventTimes += accepted.timestampMs
            if (firstPopMs == null) firstPopMs = accepted.timestampMs
            lastEvent = accepted
        } else if (event != null) {
            lastEvent = event
        }

        updateMicrowaveState(features, now, accepted != null)
        val calibrationMs = (config.calibrationSeconds * 1000).toLong()
        if (phase == SessionPhase.CALIBRATING && now >= calibrationMs) {
            operationBaselineDb = microwaveCalibration.sorted().let { values ->
                if (values.isEmpty()) features.microwaveBandDb else values[values.size / 2]
            }
            operationObserved = (operationBaselineDb ?: -100.0) >= config.microwaveMinimumDb
            phase = SessionPhase.WAITING
        }

        if (phase !in setOf(SessionPhase.CALIBRATING, SessionPhase.STOPPED, SessionPhase.INTERRUPTED)) {
            updateLifecycle(now)
            updateStopDecision(now)
        }
        updateRateHistory(now)
        return snapshot(features)
    }

    fun stopManually(atMs: Long): DetectorSnapshot {
        completionReason = if (doneAtMs != null) CompletionReason.DONE_DETECTED else CompletionReason.STOPPED_MANUALLY
        phase = SessionPhase.STOPPED
        return DetectorSnapshot(
            phase = phase,
            elapsedMs = atMs,
            detectedPops = eventTimes.size,
            recentIntervalSeconds = medianRecentInterval(),
            currentGapSeconds = currentGap(atMs),
            shortPopRate = rate(atMs, config.shortRateWindowSeconds),
            peakPopRate = peakRate,
            firstPopMs = firstPopMs,
            activeWasReached = activeReached,
            doneAtMs = doneAtMs,
            completionReason = completionReason,
            rateHistory = rateHistory.toList(),
            lastEvent = lastEvent,
        )
    }

    private fun updateSignalHealth(features: AudioFeatures, now: Long) {
        if (features.digitalSilence) {
            if (invalidSinceMs == null) invalidSinceMs = now
            if (now - invalidSinceMs!! >= (config.invalidInputPersistenceSeconds * 1000).toLong()) {
                phase = SessionPhase.INTERRUPTED
                completionReason = CompletionReason.INTERRUPTED
            }
        } else {
            invalidSinceMs = null
        }
    }

    private fun updateMicrowaveState(features: AudioFeatures, now: Long, transientFrame: Boolean) {
        if (phase == SessionPhase.CALIBRATING && !features.digitalSilence && !transientFrame) {
            microwaveCalibration += features.microwaveBandDb
        }
        val previous = smoothedMicrowaveDb
        smoothedMicrowaveDb = if (previous == null) features.microwaveBandDb else previous * 0.96 + features.microwaveBandDb * 0.04
        val baseline = operationBaselineDb ?: return
        if (!operationObserved && smoothedMicrowaveDb!! >= config.microwaveMinimumDb) operationObserved = true
        val dropped = operationObserved && smoothedMicrowaveDb!! <= baseline - config.microwaveStopDropDb
        if (dropped) {
            if (microwaveDropSinceMs == null) microwaveDropSinceMs = now
        } else {
            microwaveDropSinceMs = null
        }
    }

    private fun updateLifecycle(now: Long) {
        val shortRate = rate(now, config.shortRateWindowSeconds)
        peakRate = maxOf(peakRate, shortRate)
        val recentLong = eventTimes.filter { now - it <= (config.longRateWindowSeconds * 1000).toLong() }

        when (phase) {
            SessionPhase.WAITING -> if (recentLong.size >= 3) phase = SessionPhase.RAMPING_UP
            SessionPhase.RAMPING_UP -> {
                val span = if (recentLong.size > 1) (recentLong.last() - recentLong.first()) / 1000.0 else 0.0
                if (recentLong.size >= config.activeMinimumEvents &&
                    shortRate >= config.activeMinimumRate && span >= config.activeMinimumSpanSeconds) {
                    phase = SessionPhase.ACTIVE
                    activeReached = true
                    activeSinceMs = now
                }
            }
            SessionPhase.ACTIVE -> {
                val activeLongEnough = now - (activeSinceMs ?: now) >= 2_000
                val interval = medianRecentInterval()
                if (activeLongEnough && peakRate >= config.declineMinimumPeakRate &&
                    shortRate <= peakRate * config.declinePeakRatio &&
                    (interval == null || interval >= 0.85 || now - (eventTimes.lastOrNull() ?: now) >= 1_500)) {
                    phase = SessionPhase.DECLINING
                    decliningSinceMs = now
                }
            }
            SessionPhase.DECLINING -> {
                if (shortRate >= peakRate * 0.78 && now - (decliningSinceMs ?: now) >= 1_500) {
                    phase = SessionPhase.ACTIVE
                    decliningSinceMs = null
                    sparseSinceMs = null
                } else {
                    evaluateDone(now, shortRate)
                }
            }
            SessionPhase.DONE, SessionPhase.WARNING, SessionPhase.CRITICAL -> {
                val sinceDone = now - (doneAtMs ?: now)
                phase = when {
                    sinceDone >= (config.criticalDelaySeconds * 1000).toLong() && microwaveOperating() -> SessionPhase.CRITICAL
                    sinceDone >= (config.warningDelaySeconds * 1000).toLong() && microwaveOperating() -> SessionPhase.WARNING
                    else -> SessionPhase.DONE
                }
            }
            else -> Unit
        }
    }

    private fun evaluateDone(now: Long, shortRate: Double) {
        if (!activeReached || doneAtMs != null) return
        val intervals = recentIntervals(config.decisionIntervalWindowSize)
        val median = median(intervals)
        val sufficientlySparse = intervals.size >= config.sparseRequiredIntervals &&
            median != null && median >= config.sparseIntervalSeconds &&
            intervals.count { it >= config.sparseIndividualIntervalSeconds } >= config.sparseRequiredIntervals &&
            intervals.last() >= config.sparseIndividualIntervalSeconds &&
            shortRate <= peakRate * config.declinePeakRatio
        val noPopLongEnough = eventTimes.lastOrNull()?.let {
            now - it >= (config.noPopCompletionSeconds * 1000).toLong() && shortRate <= peakRate * config.declinePeakRatio
        } ?: false

        if (sufficientlySparse || noPopLongEnough) {
            if (sparseSinceMs == null) sparseSinceMs = now
            if (noPopLongEnough || now - sparseSinceMs!! >= (config.sparsePersistenceSeconds * 1000).toLong()) {
                doneAtMs = now
                completionReason = CompletionReason.DONE_DETECTED
                phase = SessionPhase.DONE
            }
        } else {
            sparseSinceMs = null
        }
    }

    private fun updateStopDecision(now: Long) {
        val dropStart = microwaveDropSinceMs ?: return
        if (now - dropStart < (config.microwaveStopPersistenceSeconds * 1000).toLong()) return
        completionReason = if (doneAtMs != null) CompletionReason.DONE_DETECTED else CompletionReason.MICROWAVE_STOPPED
        phase = SessionPhase.STOPPED
    }

    private fun microwaveOperating(): Boolean = operationObserved && microwaveDropSinceMs == null

    private fun rate(now: Long, seconds: Double): Double {
        val cutoff = now - (seconds * 1000).toLong()
        return eventTimes.count { it >= cutoff }.toDouble() / seconds
    }

    private fun recentIntervals(count: Int): List<Double> = eventTimes.zipWithNext { a, b -> (b - a) / 1000.0 }.takeLast(count)

    private fun medianRecentInterval(): Double? = median(recentIntervals(config.displayIntervalWindowSize))

    private fun currentGap(now: Long): Double? = eventTimes.lastOrNull()?.let { (now - it).coerceAtLeast(0) / 1000.0 }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }

    private fun updateRateHistory(now: Long) {
        val second = now / 1000
        if (second == lastHistorySecond) return
        lastHistorySecond = second
        rateHistory.addLast(rate(now, config.shortRateWindowSeconds).toFloat())
        while (rateHistory.size > 240) rateHistory.removeFirst()
    }

    private fun snapshot(features: AudioFeatures): DetectorSnapshot {
        val audioLevel = ((features.rmsDb + 60.0) / 48.0).coerceIn(0.0, 1.0).toFloat()
        return DetectorSnapshot(
            phase = phase,
            elapsedMs = features.timestampMs,
            detectedPops = eventTimes.size,
            recentIntervalSeconds = medianRecentInterval(),
            currentGapSeconds = currentGap(features.timestampMs),
            shortPopRate = rate(features.timestampMs, config.shortRateWindowSeconds),
            peakPopRate = peakRate,
            firstPopMs = firstPopMs,
            audioLevel = audioLevel,
            microwaveOperating = microwaveOperating(),
            activeWasReached = activeReached,
            doneAtMs = doneAtMs,
            completionReason = completionReason,
            signalHealthy = phase != SessionPhase.INTERRUPTED,
            rateHistory = rateHistory.toList(),
            lastEvent = lastEvent,
        )
    }
}
