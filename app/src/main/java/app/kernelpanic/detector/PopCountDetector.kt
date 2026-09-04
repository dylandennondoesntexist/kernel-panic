package app.kernelpanic.detector

import kotlin.math.max

/**
 * Permissive onset detector that supplies extra candidates to the user-facing estimate.
 *
 * False positives here are deliberately tolerated. Its output is isolated from the lifecycle
 * detector so a lively counter can never make the app announce DONE.
 */
class PopCountDetector(private val config: DetectorConfig) {
    private var noiseFloorDb = -60.0
    private var fluxFloor = 0.012
    private var initialized = false
    private var armed = true
    private var quietSinceMs: Long? = null
    private var lastCountedMs = Long.MIN_VALUE / 2

    fun process(features: AudioFeatures, acceptingPops: Boolean): PopEvent? {
        if (!initialized && features.digitalSilence) return null
        if (!initialized) {
            noiseFloorDb = features.rmsDb
            fluxFloor = max(features.spectralFlux, 0.006)
            initialized = true
            return null
        }
        if (!acceptingPops) {
            discardCandidate()
            updateBackground(features, fast = true)
            return null
        }

        val energyExcess = features.rmsDb - noiseFloorDb
        val fluxThreshold = max(config.countMinimumSpectralFlux, fluxFloor * config.countFluxFloorMultiplier)
        val energyScore = ((energyExcess - config.countEnergyRiseDb) / 8.0).coerceIn(0.0, 1.0)
        val fluxScore = ((features.spectralFlux - fluxThreshold) / 0.18).coerceIn(0.0, 1.0)
        val highScore = ((features.highFrequencyRatio - config.countMinimumHighFrequencyRatio) / 0.55).coerceIn(0.0, 1.0)
        val crestScore = ((features.crestFactor - config.countMinimumCrestFactor) / 5.0).coerceIn(0.0, 1.0)
        val attackScore = ((features.attackRatio - 1.05) / 3.5).coerceIn(0.0, 1.0)
        val excessScore = (features.spectralExcess / 5.0).coerceIn(0.0, 1.0)
        val score = (0.22 * energyScore + 0.24 * fluxScore + 0.15 * highScore +
            0.14 * crestScore + 0.13 * attackScore + 0.12 * excessScore).coerceIn(0.0, 1.0)
        val qualifies = energyExcess >= config.countEnergyRiseDb &&
            features.spectralFlux >= fluxThreshold &&
            features.highFrequencyRatio >= config.countMinimumHighFrequencyRatio &&
            features.crestFactor >= config.countMinimumCrestFactor &&
            features.attackRatio >= 1.05 &&
            score >= config.countScoreThreshold

        if (!qualifies) {
            if (quietSinceMs == null) quietSinceMs = features.timestampMs
            if (features.timestampMs - quietSinceMs!! >= config.countRearmMs) armed = true
            updateBackground(features)
            return null
        }

        quietSinceMs = null
        val separated = features.timestampMs - lastCountedMs >= config.countMinimumSeparationMs
        // A fresh, unusually sharp onset may re-arm inside a continuous burst. This is what
        // lets two kernels close together appear as two pops instead of one merged excursion.
        val strongNewOnset = features.attackRatio >= 1.55 && features.spectralFlux >= fluxThreshold * 1.65
        if (!separated || (!armed && !strongNewOnset)) return null

        armed = false
        lastCountedMs = features.timestampMs
        return PopEvent(
            timestampMs = features.timestampMs,
            score = score,
            rmsDb = features.rmsDb,
            noiseFloorDb = noiseFloorDb,
            spectralFlux = features.spectralFlux,
            highFrequencyRatio = features.highFrequencyRatio,
            accepted = true,
            crestFactor = features.crestFactor,
            spectralFlatness = features.spectralFlatness,
            attackRatio = features.attackRatio,
            spectralExcess = features.spectralExcess,
        )
    }

    fun discardCandidate() {
        armed = true
        quietSinceMs = null
    }

    private fun updateBackground(features: AudioFeatures, fast: Boolean = false) {
        if (features.digitalSilence) return
        val energyAlpha = if (fast) 0.06 else if (features.rmsDb < noiseFloorDb) 0.03 else 0.002
        val fluxAlpha = if (fast) 0.08 else 0.008
        noiseFloorDb += energyAlpha * (features.rmsDb - noiseFloorDb).coerceIn(-12.0, 2.5)
        fluxFloor += fluxAlpha * (features.spectralFlux.coerceAtMost(fluxFloor * 2.2 + 0.01) - fluxFloor)
    }
}
