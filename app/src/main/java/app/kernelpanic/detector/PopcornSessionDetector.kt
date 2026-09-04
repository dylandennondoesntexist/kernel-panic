package app.kernelpanic.detector

import java.util.ArrayDeque

/** Turns accepted event timing and long-duration signal evidence into the popcorn lifecycle. */
class PopcornSessionDetector(private val config: DetectorConfig) {
    private var phase = SessionPhase.CALIBRATING
    private val eventTimes = mutableListOf<Long>()
    private val peakEvidenceTimes = mutableListOf<Long>()
    private var smoothedMicrowaveDb: Double? = null
    private var operationBaselineDb: Double? = null
    private var operationObserved = false
    private var operationObservedAtMs: Long? = null
    private var operationCandidateSinceMs: Long? = null
    private val microwaveStopEvidence = ArrayDeque<Pair<Long, Boolean>>()
    private var microwaveStopped = false
    private var invalidSinceMs: Long? = null
    private var activeReached = false
    private var peakConfirmed = false
    private var activeCandidateSinceMs: Long? = null
    private var activeSinceMs: Long? = null
    private var declineCandidateSinceMs: Long? = null
    private var sparseSinceMs: Long? = null
    private var doneAtMs: Long? = null
    private var completionReason: CompletionReason? = null
    private var peakRate = 0.0
    private var firstPopMs: Long? = null
    private var lastHistorySecond = -1L
    private val rateHistory = ArrayDeque<Float>()
    private var lastEvent: PopEvent? = null

    fun process(
        features: AudioFeatures,
        event: PopEvent?,
        ignoreApplianceState: Boolean = false,
    ): DetectorSnapshot {
        val now = features.timestampMs
        updateSignalHealth(features, now)
        if (phase == SessionPhase.INTERRUPTED) return snapshot(features)

        if (event != null) lastEvent = event
        if (!ignoreApplianceState) {
            updateMicrowaveState(features, now)
        }

        // Button beeps, the door closing, and bag crinkles are common immediately after
        // listening starts. Keep them visible in diagnostics, but do not let them seed the
        // lifecycle until a steady microwave-running bed has actually been observed.
        val operationAgeMs = operationObservedAtMs?.let { now - it } ?: -1L
        val accepted = event?.takeIf {
            it.accepted && operationAgeMs >= (config.setupNoiseGuardSeconds * 1_000).toLong()
        }
        if (accepted != null) {
            eventTimes += accepted.timestampMs
            if (accepted.score >= config.peakEvidenceMinimumScore &&
                accepted.spectralFlatness >= config.peakEvidenceMinimumFlatness) {
                peakEvidenceTimes += accepted.timestampMs
            }
            if (firstPopMs == null) firstPopMs = accepted.timestampMs
        }
        val calibrationMs = (config.calibrationSeconds * 1000).toLong()
        if (phase == SessionPhase.CALIBRATING && now >= calibrationMs) {
            phase = SessionPhase.WAITING
        }

        if (phase !in setOf(SessionPhase.CALIBRATING, SessionPhase.STOPPED, SessionPhase.INTERRUPTED)) {
            updateLifecycle(now)
            updateStopDecision(now)
            updateTimeLimits(now)
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
            conservativeRateSlope = rateSlope(atMs),
            firstPopMs = firstPopMs,
            activeWasReached = activeReached,
            peakConfirmed = peakConfirmed,
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

    private fun updateMicrowaveState(features: AudioFeatures, now: Long) {
        val previous = smoothedMicrowaveDb
        smoothedMicrowaveDb = if (previous == null) features.microwaveBandDb else previous * 0.96 + features.microwaveBandDb * 0.04

        // The band level is already heavily smoothed. Requiring transient-free frames here
        // made real rotating fans look unstable and delayed "running" until the loud peak.
        if (!features.digitalSilence && smoothedMicrowaveDb!! >= config.microwaveMinimumDb) {
            if (operationCandidateSinceMs == null) operationCandidateSinceMs = now
            if (now - operationCandidateSinceMs!! >= (config.microwaveStartPersistenceSeconds * 1_000).toLong()) {
                if (!operationObserved) {
                    operationObserved = true
                    operationObservedAtMs = now
                }
                val currentBaseline = operationBaselineDb
                operationBaselineDb = if (currentBaseline == null) smoothedMicrowaveDb else {
                    // Continue moving upward during heating so a quiet room/startup baseline
                    // cannot prevent a later microwave-off decision. Downward drift is tiny.
                    val delta = (smoothedMicrowaveDb!! - currentBaseline).coerceIn(-1.0, 4.0)
                    currentBaseline + (if (delta > 0.0) 0.018 else 0.001) * delta
                }
            }
        } else if (!features.digitalSilence) {
            operationCandidateSinceMs = null
        }

        val baseline = operationBaselineDb ?: return
        if (!operationObserved || microwaveStopped) return
        val belowRunningLevel = smoothedMicrowaveDb!! <= baseline - config.microwaveStopDropDb
        microwaveStopEvidence.addLast(now to belowRunningLevel)
        val windowMs = (config.microwaveStopPersistenceSeconds * 1_000).toLong()
        while (microwaveStopEvidence.firstOrNull()?.first?.let { now - it > windowMs } == true) {
            microwaveStopEvidence.removeFirst()
        }
        val spansWindow = microwaveStopEvidence.firstOrNull()?.first?.let { now - it >= windowMs - 100 } == true
        val quietRatio = if (microwaveStopEvidence.isEmpty()) 0.0
            else microwaveStopEvidence.count { it.second }.toDouble() / microwaveStopEvidence.size
        if (spansWindow && quietRatio >= config.microwaveStopRequiredRatio) {
            microwaveStopped = true
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
                val sustainedCandidate = recentLong.size >= config.activeMinimumEvents &&
                    shortRate >= config.activeMinimumRate && span >= config.activeMinimumSpanSeconds
                if (sustainedCandidate) {
                    if (activeCandidateSinceMs == null) activeCandidateSinceMs = now
                    if (now - activeCandidateSinceMs!! >= (config.activeConfirmationSeconds * 1_000).toLong()) {
                        phase = SessionPhase.ACTIVE
                        activeReached = true
                        activeSinceMs = now
                        activeCandidateSinceMs = null
                    }
                } else {
                    activeCandidateSinceMs = null
                }
            }
            SessionPhase.ACTIVE -> {
                val activeLongEnough = now - (activeSinceMs ?: now) >= 2_000
                updatePeakConfirmation(now)
                val curveRate = rate(now, config.slowingRateWindowSeconds)
                val curveSlope = rateSlope(now)
                val convincingDecline = activeLongEnough && peakConfirmed && peakRate >= config.declineMinimumPeakRate &&
                    curveRate <= peakRate * config.slowingPeakRatio &&
                    curveSlope <= config.slowingMaximumRateSlope
                // The final interval rule remains independent from this earlier curve-stage
                // signal. SLOWING is informational; only evaluateDone can announce DONE.
                evaluateDone(now, shortRate)
                if (convincingDecline) {
                    if (declineCandidateSinceMs == null) declineCandidateSinceMs = now
                    if (doneAtMs == null &&
                        now - declineCandidateSinceMs!! >= (config.slowingConfirmationSeconds * 1_000).toLong()) {
                        phase = SessionPhase.DECLINING
                    }
                } else {
                    declineCandidateSinceMs = null
                }
            }
            SessionPhase.DECLINING -> {
                // Once a sustained decline is shown, the visible cooking lifecycle is
                // monotonic. A stray burst cannot bounce the UI back to Popping.
                evaluateDone(now, shortRate)
            }
            SessionPhase.DONE, SessionPhase.WARNING, SessionPhase.CRITICAL -> {
                val sinceDone = now - (doneAtMs ?: now)
                val desired = when {
                    sinceDone >= (config.criticalDelaySeconds * 1000).toLong() -> SessionPhase.CRITICAL
                    sinceDone >= (config.warningDelaySeconds * 1000).toLong() -> SessionPhase.WARNING
                    else -> SessionPhase.DONE
                }
                // Alert severity is monotonic. Beeps, the door, and the phone's own alert can
                // never move the UI from red/yellow back to green.
                if (desired.ordinal > phase.ordinal) {
                    phase = desired
                }
            }
            else -> Unit
        }
    }

    private fun evaluateDone(now: Long, shortRate: Double) {
        if (!activeReached || !peakConfirmed || doneAtMs != null) return
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
        if (!microwaveStopped) return
        completionReason = if (doneAtMs != null) CompletionReason.DONE_DETECTED else CompletionReason.MICROWAVE_STOPPED
        phase = SessionPhase.STOPPED
    }

    private fun updatePeakConfirmation(now: Long) {
        if (peakConfirmed) return
        val activeFor = now - (activeSinceMs ?: now)
        val cutoff = now - (config.peakEvidenceWindowSeconds * 1_000).toLong()
        val recentEvidence = peakEvidenceTimes.count { it >= cutoff }
        if (activeFor >= (config.peakConfirmationSeconds * 1_000).toLong() &&
            recentEvidence >= config.peakEvidenceMinimumEvents &&
            peakRate >= config.declineMinimumPeakRate) {
            peakConfirmed = true
        }
    }

    private fun updateTimeLimits(now: Long) {
        if (phase in setOf(SessionPhase.STOPPED, SessionPhase.INTERRUPTED)) return
        val doneTime = doneAtMs
        if (doneTime != null && now - doneTime >= (config.postDoneMaximumSeconds * 1_000).toLong()) {
            completionReason = CompletionReason.DONE_DETECTED
            phase = SessionPhase.STOPPED
        } else if (now >= (config.maximumSessionSeconds * 1_000).toLong()) {
            completionReason = if (doneTime != null) CompletionReason.DONE_DETECTED else CompletionReason.TIME_LIMIT
            phase = SessionPhase.STOPPED
        }
    }

    private fun microwaveOperating(): Boolean = operationObserved && !microwaveStopped

    private fun rate(now: Long, seconds: Double): Double {
        val cutoff = now - (seconds * 1000).toLong()
        return eventTimes.count { it >= cutoff }.toDouble() / seconds
    }

    /** First derivative of pop rate (the second derivative of cumulative Pop Count). */
    private fun rateSlope(now: Long): Double {
        val seconds = config.slowingRateWindowSeconds
        val windowMs = (seconds * 1_000).toLong()
        val recent = rateBetween(now - windowMs, now, seconds)
        val previous = rateBetween(now - 2 * windowMs, now - windowMs, seconds)
        return (recent - previous) / seconds
    }

    private fun rateBetween(startExclusiveMs: Long, endInclusiveMs: Long, seconds: Double): Double =
        eventTimes.count { it > startExclusiveMs && it <= endInclusiveMs }.toDouble() / seconds

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
            conservativeRateSlope = rateSlope(features.timestampMs),
            firstPopMs = firstPopMs,
            audioLevel = audioLevel,
            microwaveOperating = microwaveOperating(),
            activeWasReached = activeReached,
            peakConfirmed = peakConfirmed,
            doneAtMs = doneAtMs,
            completionReason = completionReason,
            signalHealthy = phase != SessionPhase.INTERRUPTED,
            rateHistory = rateHistory.toList(),
            lastEvent = lastEvent,
        )
    }
}
