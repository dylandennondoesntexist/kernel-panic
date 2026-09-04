package app.kernelpanic.detector

/** All detector tuning lives here so recordings can be compared against one configuration. */
data class DetectorConfig(
    /** Audio analysis window. 1024 samples is 21.3 ms at the preferred 48 kHz input rate. */
    val frameSize: Int = 1024,
    /** New samples per window. A half-window hop produces 50% overlap. */
    val hopSize: Int = 512,
    val highPassHz: Double = 180.0,
    val microwaveBandLowHz: Double = 80.0,
    val microwaveBandHighHz: Double = 1_200.0,
    val popBandLowHz: Double = 1_200.0,
    val popBandHighHz: Double = 12_000.0,
    /** Seconds spent learning the appliance and room background. */
    val calibrationSeconds: Double = 2.5,
    /** Per-frequency background continues learning slowly while the microwave heats. */
    val backgroundLearningSeconds: Double = 60.0,
    /** Minimum broadband rise above the adaptive background for a transient candidate. */
    val energyRiseDb: Double = 3.0,
    val onsetRiseDb: Double = 2.2,
    val minimumSpectralFlux: Double = 0.025,
    val candidateFluxFloorMultiplier: Double = 1.30,
    val scoreFluxFloorMultiplier: Double = 1.35,
    val minimumAttackRatio: Double = 1.20,
    val minimumHighFrequencyRatio: Double = 0.18,
    val minimumCrestFactor: Double = 2.0,
    val transientScoreThreshold: Double = 0.46,
    /** Candidate frames separated by this much quiet are treated as separate acoustic events. */
    val eventReleaseMs: Long = 32,
    /** Suppresses ringing from one pop while preserving rapid double-pops. */
    val minimumEventSeparationMs: Long = 72,
    val maximumEventDurationMs: Long = 180,
    /** More permissive thresholds for the fun, user-facing pop estimate. */
    val countEnergyRiseDb: Double = 1.4,
    val countMinimumSpectralFlux: Double = 0.014,
    val countFluxFloorMultiplier: Double = 1.08,
    val countMinimumHighFrequencyRatio: Double = 0.10,
    val countMinimumCrestFactor: Double = 1.45,
    val countScoreThreshold: Double = 0.28,
    val countMinimumSeparationMs: Long = 32,
    val countRearmMs: Long = 16,
    /** Reveal buffered permissive candidates once the first high-confidence pop arrives. */
    val countBackfillSeconds: Double = 5.0,
    /** Fallback for compressed recordings whose rapid pop burst never clears the strict gate. */
    val countFallbackStartSeconds: Double = 45.0,
    val countFallbackMinimumCandidates: Int = 18,
    val shortRateWindowSeconds: Double = 5.0,
    val longRateWindowSeconds: Double = 10.0,
    val activeMinimumEvents: Int = 8,
    val activeMinimumRate: Double = 1.2,
    val activeMinimumSpanSeconds: Double = 2.0,
    val activeConfirmationSeconds: Double = 3.0,
    /** A real broadband peak must be established before any decline can unlock DONE. */
    val peakEvidenceMinimumScore: Double = 0.52,
    val peakEvidenceMinimumFlatness: Double = 0.12,
    val peakEvidenceWindowSeconds: Double = 20.0,
    val peakEvidenceMinimumEvents: Int = 10,
    val peakConfirmationSeconds: Double = 4.0,
    /** Adjacent windows estimate the slope (acceleration) of the cumulative pop curve. */
    val slowingRateWindowSeconds: Double = 4.0,
    /** Enter SLOWING on the descending shoulder, not only near the final sparse tail. */
    val slowingPeakRatio: Double = 0.82,
    val slowingMaximumRateSlope: Double = -0.15,
    /** Final doneness remains more conservative than the earlier SLOWING signal. */
    val declinePeakRatio: Double = 0.55,
    val declineMinimumPeakRate: Double = 1.2,
    /** A negative rate slope must persist before the one-way SLOWING state is shown. */
    val slowingConfirmationSeconds: Double = 3.0,
    /** Shorter statistic shown to the user so the display responds to the latest few pops. */
    val displayIntervalWindowSize: Int = 3,
    /** More conservative statistic used by the doneness decision. */
    val decisionIntervalWindowSize: Int = 5,
    val sparseIntervalSeconds: Double = 1.65,
    val sparseIndividualIntervalSeconds: Double = 1.45,
    /** Four of the latest five intervals must be sparse; no absolute cook time is used. */
    val sparseRequiredIntervals: Int = 4,
    /** Briefly confirms the cadence without waiting for the microwave to stop. */
    val sparsePersistenceSeconds: Double = 0.3,
    val noPopCompletionSeconds: Double = 4.6,
    val microwaveMinimumDb: Double = -58.0,
    val microwaveStartPersistenceSeconds: Double = 1.5,
    /** Ignores keypad, door, package, and permission/UI sounds immediately after startup. */
    val setupNoiseGuardSeconds: Double = 10.0,
    val microwaveStopDropDb: Double = 13.0,
    val microwaveStopPersistenceSeconds: Double = 2.5,
    /** A few beeps/impacts may violate the drop; most of the window must still be quiet. */
    val microwaveStopRequiredRatio: Double = 0.72,
    val invalidInputPersistenceSeconds: Double = 1.5,
    val warningDelaySeconds: Double = 5.0,
    val criticalDelaySeconds: Double = 12.0,
    val postDoneMaximumSeconds: Double = 60.0,
    val maximumSessionSeconds: Double = 300.0,
)
