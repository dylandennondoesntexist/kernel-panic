package app.kernelpanic.detector

enum class SessionPhase {
    IDLE, CALIBRATING, WAITING, RAMPING_UP, ACTIVE, DECLINING,
    DONE, WARNING, CRITICAL, STOPPED, INTERRUPTED
}

enum class CompletionReason(val label: String) {
    DONE_DETECTED("Done detected"),
    MICROWAVE_STOPPED("Microwave stopped"),
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
)

data class PopEvent(
    val timestampMs: Long,
    val score: Double,
    val rmsDb: Double,
    val noiseFloorDb: Double,
    val spectralFlux: Double,
    val highFrequencyRatio: Double,
    val accepted: Boolean,
)

data class DetectorSnapshot(
    val phase: SessionPhase = SessionPhase.IDLE,
    val elapsedMs: Long = 0,
    val detectedPops: Int = 0,
    val recentIntervalSeconds: Double? = null,
    val shortPopRate: Double = 0.0,
    val peakPopRate: Double = 0.0,
    val firstPopMs: Long? = null,
    val audioLevel: Float = 0f,
    val microwaveOperating: Boolean = false,
    val activeWasReached: Boolean = false,
    val doneAtMs: Long? = null,
    val completionReason: CompletionReason? = null,
    val signalHealthy: Boolean = true,
    val rateHistory: List<Float> = emptyList(),
    val lastEvent: PopEvent? = null,
)
