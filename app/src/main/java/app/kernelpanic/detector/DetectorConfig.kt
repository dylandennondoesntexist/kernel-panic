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
    /** Minimum broadband rise above the adaptive background for a transient candidate. */
    val energyRiseDb: Double = 3.4,
    val onsetRiseDb: Double = 2.2,
    val minimumSpectralFlux: Double = 0.025,
    val minimumHighFrequencyRatio: Double = 0.22,
    val minimumCrestFactor: Double = 2.4,
    val transientScoreThreshold: Double = 0.48,
    /** Candidate frames separated by this much quiet are treated as separate acoustic events. */
    val eventReleaseMs: Long = 32,
    /** Suppresses ringing from one pop while preserving rapid double-pops. */
    val minimumEventSeparationMs: Long = 72,
    val maximumEventDurationMs: Long = 180,
    val shortRateWindowSeconds: Double = 5.0,
    val longRateWindowSeconds: Double = 10.0,
    val activeMinimumEvents: Int = 8,
    val activeMinimumRate: Double = 1.2,
    val activeMinimumSpanSeconds: Double = 2.0,
    val declinePeakRatio: Double = 0.55,
    val declineMinimumPeakRate: Double = 1.2,
    val intervalWindowSize: Int = 5,
    val sparseIntervalSeconds: Double = 1.65,
    val sparseIndividualIntervalSeconds: Double = 1.45,
    val sparseRequiredIntervals: Int = 3,
    val sparsePersistenceSeconds: Double = 2.2,
    val noPopCompletionSeconds: Double = 4.6,
    val microwaveMinimumDb: Double = -58.0,
    val microwaveStopDropDb: Double = 13.0,
    val microwaveStopPersistenceSeconds: Double = 2.5,
    val invalidInputPersistenceSeconds: Double = 1.5,
    val warningDelaySeconds: Double = 5.0,
    val criticalDelaySeconds: Double = 12.0,
)
