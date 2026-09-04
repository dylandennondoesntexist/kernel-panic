package app.kernelpanic.detector

enum class SessionPhase {
    IDLE, CALIBRATING, WAITING, RAMPING_UP, ACTIVE, DECLINING,
    DONE, WARNING, CRITICAL, STOPPED, INTERRUPTED
}

enum class CompletionReason(val label: String) {
    DONE_DETECTED("Done detected"),
    MICROWAVE_STOPPED("Microwave stopped"),
    TIME_LIMIT("Listening time limit reached"),
    STOPPED_MANUALLY("Stopped manually"),
    INTERRUPTED("Interrupted")
}

data class AudioFeatures(
    val timestampMs: Long,
    val rms: Double,
    val rmsDb: Double,
    val peak: Double,
    val crestFactor: Double,
    val spectralFlux: Double,
    val spectralFlatness: Double,
    val highFrequencyRatio: Double,
    val microwaveBandDb: Double,
    val attackRatio: Double,
    val decayRatio: Double,
    val digitalSilence: Boolean,
    /** Pop-band energy above a slowly learned per-frequency background spectrum. */
    val spectralExcess: Double = 0.0,
)

data class PopEvent(
    val timestampMs: Long,
    val score: Double,
    val rmsDb: Double,
    val noiseFloorDb: Double,
    val spectralFlux: Double,
    val highFrequencyRatio: Double,
    val accepted: Boolean,
    val crestFactor: Double = 0.0,
    val spectralFlatness: Double = 0.0,
    val attackRatio: Double = 0.0,
    val spectralExcess: Double = 0.0,
)

data class DetectorSnapshot(
    val phase: SessionPhase = SessionPhase.IDLE,
    val elapsedMs: Long = 0,
    /** User-facing rapid-pop estimate; intentionally independent from doneness. */
    val estimatedPopCount: Int = 0,
    val estimatedCurrentRate: Double = 0.0,
    val estimatedPeakRate: Double = 0.0,
    val estimatedFirstPopMs: Long? = null,
    val popHistory: List<Float> = emptyList(),
    /** Conservative acoustic events used only by the lifecycle detector and debug tools. */
    val detectedPops: Int = 0,
    val recentIntervalSeconds: Double? = null,
    val currentGapSeconds: Double? = null,
    val shortPopRate: Double = 0.0,
    val peakPopRate: Double = 0.0,
    /** Change in conservative pops/second per second; negative means the curve is slowing. */
    val conservativeRateSlope: Double = 0.0,
    val firstPopMs: Long? = null,
    val audioLevel: Float = 0f,
    val microwaveOperating: Boolean = false,
    val activeWasReached: Boolean = false,
    val peakConfirmed: Boolean = false,
    val doneAtMs: Long? = null,
    val completionReason: CompletionReason? = null,
    val signalHealthy: Boolean = true,
    val rateHistory: List<Float> = emptyList(),
    val lastEvent: PopEvent? = null,
)
