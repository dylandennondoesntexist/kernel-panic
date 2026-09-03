package app.kernelpanic.detector

import kotlin.math.max

/** Adaptive, multi-feature transient classifier with excursion merging and short debouncing. */
class PopEventDetector(private val config: DetectorConfig) {
    private var noiseFloorDb = -60.0
    private var fluxFloor = 0.015
    private var initialized = false
    private var candidate: Candidate? = null
    private var candidateQuietSinceMs: Long? = null
    private var lastAcceptedMs = Long.MIN_VALUE / 2

    private data class Candidate(
        val startedMs: Long,
        var lastCandidateMs: Long,
        var best: PopEvent,
    )

    @Synchronized
    fun process(features: AudioFeatures, acceptingEvents: Boolean): PopEvent? {
        // AudioRecord and WAV playback can begin with a short zero-filled buffer. It is not
        // a meaningful room baseline and must not initialize the adaptive noise floor.
        if (!initialized && features.digitalSilence) return null
        if (!initialized) {
            noiseFloorDb = features.rmsDb
            fluxFloor = max(features.spectralFlux, 0.008)
            initialized = true
            return null
        }

        // Calibration frames establish the appliance/room baseline but can never open an
        // excursion. Otherwise a startup edge can merge the entire session into one event.
        if (!acceptingEvents) {
            discardCandidate()
            updateBackground(features, fast = true)
            return null
        }

        val energyExcess = features.rmsDb - noiseFloorDb
        val energyScore = ((energyExcess - config.energyRiseDb) / 10.0).coerceIn(0.0, 1.0)
        val fluxScore = ((features.spectralFlux - max(config.minimumSpectralFlux, fluxFloor * 1.7)) / 0.22).coerceIn(0.0, 1.0)
        val highScore = ((features.highFrequencyRatio - config.minimumHighFrequencyRatio) / 0.50).coerceIn(0.0, 1.0)
        val crestScore = ((features.crestFactor - config.minimumCrestFactor) / 5.0).coerceIn(0.0, 1.0)
        val onsetScore = ((features.attackRatio - 1.35) / 4.0).coerceIn(0.0, 1.0)
        val flatnessScore = ((features.spectralFlatness - 0.08) / 0.45).coerceIn(0.0, 1.0)
        val score = (0.25 * energyScore + 0.23 * fluxScore + 0.18 * highScore +
            0.15 * crestScore + 0.12 * onsetScore + 0.07 * flatnessScore).coerceIn(0.0, 1.0)

        // Start an inspectable excursion from energy/flux/onset. Spectral shape and crest
        // remain acceptance gates, so debug builds can explain rejected speaker/room sounds.
        val looksTransient = energyExcess >= config.energyRiseDb &&
            features.spectralFlux >= max(config.minimumSpectralFlux, fluxFloor * 1.45) &&
            (features.attackRatio >= 1.25 || candidate != null)

        val frameEvent = PopEvent(
            timestampMs = features.timestampMs,
            score = score,
            rmsDb = features.rmsDb,
            noiseFloorDb = noiseFloorDb,
            spectralFlux = features.spectralFlux,
            highFrequencyRatio = features.highFrequencyRatio,
            accepted = false,
            crestFactor = features.crestFactor,
            spectralFlatness = features.spectralFlatness,
            attackRatio = features.attackRatio,
        )

        if (looksTransient) {
            val active = candidate
            if (active == null) {
                candidate = Candidate(features.timestampMs, features.timestampMs, frameEvent)
            } else {
                active.lastCandidateMs = features.timestampMs
                if (frameEvent.score > active.best.score) active.best = frameEvent
            }
            candidateQuietSinceMs = null
        } else if (candidate != null) {
            if (candidateQuietSinceMs == null) candidateQuietSinceMs = features.timestampMs
            if (features.timestampMs - candidateQuietSinceMs!! >= config.eventReleaseMs) {
                val completed = candidate!!
                candidate = null
                candidateQuietSinceMs = null
                val duration = completed.lastCandidateMs - completed.startedMs
                val separated = completed.best.timestampMs - lastAcceptedMs >= config.minimumEventSeparationMs
                val accepted = acceptingEvents && completed.best.score >= config.transientScoreThreshold &&
                    completed.best.highFrequencyRatio >= config.minimumHighFrequencyRatio &&
                    completed.best.crestFactor >= config.minimumCrestFactor &&
                    duration <= config.maximumEventDurationMs && separated
                if (accepted) lastAcceptedMs = completed.best.timestampMs
                updateBackground(features)
                return completed.best.copy(accepted = accepted)
            }
        } else {
            updateBackground(features)
        }
        return null
    }

    /** Drops an excursion that began before or during an app-generated alert. */
    @Synchronized
    fun discardCandidate() {
        candidate = null
        candidateQuietSinceMs = null
    }

    private fun updateBackground(features: AudioFeatures, fast: Boolean = false) {
        if (features.digitalSilence) return
        val energyAlpha = if (fast) 0.06 else if (features.rmsDb < noiseFloorDb) 0.025 else 0.0025
        val fluxAlpha = if (fast) 0.08 else 0.01
        noiseFloorDb += energyAlpha * (features.rmsDb - noiseFloorDb).coerceIn(-12.0, 3.0)
        fluxFloor += fluxAlpha * (features.spectralFlux.coerceAtMost(fluxFloor * 2.0 + 0.01) - fluxFloor)
    }
}
