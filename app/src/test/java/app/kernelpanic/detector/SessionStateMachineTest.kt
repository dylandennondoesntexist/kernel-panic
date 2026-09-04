package app.kernelpanic.detector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {
    @Test
    fun silenceCanNeverCauseDoneBeforeActive() {
        val detector = PopcornSessionDetector(DetectorConfig(setupNoiseGuardSeconds = 0.0))
        var snapshot = DetectorSnapshot()
        for (time in 0L..90_000L step 100L) snapshot = detector.process(features(time), null)
        assertFalse(snapshot.activeWasReached)
        assertNull(snapshot.doneAtMs)
    }

    @Test
    fun oneRandomPopAndLongGapCannotCauseDone() {
        val detector = PopcornSessionDetector(DetectorConfig(setupNoiseGuardSeconds = 0.0))
        var snapshot = DetectorSnapshot()
        for (time in 0L..40_000L step 100L) {
            val event = if (time == 7_000L) pop(time) else null
            snapshot = detector.process(features(time), event)
        }
        assertFalse(snapshot.activeWasReached)
        assertNull(snapshot.doneAtMs)
    }

    @Test
    fun activeCycleFollowedByNoPopsCanCompleteWithoutAnotherPop() {
        val detector = PopcornSessionDetector(DetectorConfig(setupNoiseGuardSeconds = 0.0))
        val eventTimes = (4_000L..14_000L step 350L).toSet()
        var snapshot = DetectorSnapshot()
        for (time in 0L..22_000L step 50L) {
            snapshot = detector.process(features(time), if (time in eventTimes) pop(time) else null)
        }
        assertTrue(snapshot.activeWasReached)
        assertTrue(snapshot.doneAtMs != null)
    }

    @Test
    fun exactZeroInputInterruptsInsteadOfCompleting() {
        val detector = PopcornSessionDetector(DetectorConfig(setupNoiseGuardSeconds = 0.0))
        val eventTimes = (4_000L..12_000L step 400L).toSet()
        var snapshot = DetectorSnapshot()
        for (time in 0L..15_000L step 50L) {
            snapshot = detector.process(features(time, digitalSilence = time >= 13_000L), if (time in eventTimes) pop(time) else null)
        }
        assertTrue(snapshot.activeWasReached)
        assertTrue(snapshot.phase == SessionPhase.INTERRUPTED)
        assertNull(snapshot.doneAtMs)
    }

    @Test
    fun displayMedianUsesThreeLatestIntervalsAndCurrentGapKeepsGrowing() {
        val detector = PopcornSessionDetector(DetectorConfig(setupNoiseGuardSeconds = 0.0))
        val active = (4_000L..7_000L step 300L).toMutableSet()
        active += setOf(7_200L, 7_500L, 7_900L, 9_400L, 11_400L)
        var snapshot = DetectorSnapshot()
        for (time in 0L..14_000L step 100L) {
            snapshot = detector.process(features(time), if (time in active) pop(time) else null)
            if (time == 11_400L) assertEquals(1.5, snapshot.recentIntervalSeconds!!, 0.001)
        }
        assertEquals(2.6, snapshot.currentGapSeconds!!, 0.001)
    }

    @Test
    fun rapidPopsAfterSparseIntervals_cancelPendingCompletion() {
        val detector = PopcornSessionDetector(DetectorConfig(setupNoiseGuardSeconds = 0.0))
        val events = (4_000L..10_000L step 400L).toMutableSet()
        events += setOf(12_000L, 14_000L, 16_000L, 16_500L, 17_000L)
        var snapshot = DetectorSnapshot()
        for (time in 0L..19_500L step 50L) {
            snapshot = detector.process(features(time), if (time in events) pop(time) else null)
        }
        assertTrue(snapshot.activeWasReached)
        assertNull("Recent rapid pops must invalidate older sparse intervals", snapshot.doneAtMs)
    }

    @Test
    fun repeatedSparseCadenceCompletesWhileMicrowaveIsStillOperating() {
        val detector = PopcornSessionDetector(DetectorConfig(setupNoiseGuardSeconds = 0.0))
        val events = (4_000L..10_000L step 400L).toMutableSet()
        events += setOf(11_800L, 13_600L, 15_400L, 17_200L)
        var snapshot = DetectorSnapshot()
        for (time in 0L..18_000L step 50L) {
            snapshot = detector.process(features(time), if (time in events) pop(time) else null)
        }
        assertTrue(snapshot.activeWasReached)
        assertTrue("Sparse cadence should decide doneness without a stop signal", snapshot.doneAtMs != null)
        assertTrue("The microwave must still be operating when doneness is decided", snapshot.microwaveOperating)
        assertEquals(CompletionReason.DONE_DETECTED, snapshot.completionReason)
    }

    @Test
    fun setupTransientsAreIgnoredUntilMicrowaveOperationIsEstablished() {
        val detector = PopcornSessionDetector(DetectorConfig())
        var snapshot = DetectorSnapshot()
        val setupNoises = setOf(500L, 1_000L, 1_400L, 4_000L, 7_000L, 10_000L)
        for (time in 0L..12_000L step 100L) {
            snapshot = detector.process(features(time), if (time in setupNoises) pop(time) else null)
        }
        assertTrue(snapshot.microwaveOperating)
        assertEquals("Keypad, door, and bag sounds must not seed the lifecycle", 0, snapshot.detectedPops)
    }

    @Test
    fun lowQualityEventBurstCannotCompleteWithoutPeakEvidence() {
        val detector = PopcornSessionDetector(DetectorConfig(setupNoiseGuardSeconds = 0.0))
        val eventTimes = (4_000L..14_000L step 350L).toSet()
        var snapshot = DetectorSnapshot()
        for (time in 0L..24_000L step 50L) {
            val event = if (time in eventTimes) pop(time, spectralFlatness = 0.05) else null
            snapshot = detector.process(features(time), event)
        }
        assertTrue("The cadence should still be observable", snapshot.activeWasReached)
        assertFalse("Compressed/tonal events must not establish a real peak", snapshot.peakConfirmed)
        assertNull("No decline from a confirmed peak means no DONE", snapshot.doneAtMs)
    }

    @Test
    fun doneWarningAndCriticalNeverRegress() {
        val config = DetectorConfig(warningDelaySeconds = 1.0, criticalDelaySeconds = 2.0, setupNoiseGuardSeconds = 0.0)
        val detector = PopcornSessionDetector(config)
        val eventTimes = (4_000L..11_000L step 350L).toSet()
        val alertPhases = mutableListOf<SessionPhase>()
        var snapshot = DetectorSnapshot()
        for (time in 0L..22_000L step 50L) {
            snapshot = detector.process(
                features(time),
                if (time in eventTimes) pop(time) else null,
                ignoreApplianceState = snapshot.doneAtMs != null,
            )
            if (snapshot.phase in setOf(SessionPhase.DONE, SessionPhase.WARNING, SessionPhase.CRITICAL)) {
                alertPhases += snapshot.phase
            }
        }
        assertTrue(alertPhases.contains(SessionPhase.DONE))
        assertTrue(alertPhases.contains(SessionPhase.WARNING))
        assertTrue(alertPhases.contains(SessionPhase.CRITICAL))
        assertTrue(alertPhases.zipWithNext().all { (before, after) -> after.ordinal >= before.ordinal })
    }

    @Test
    fun doneSessionStopsListeningAfterPostDoneLimit() {
        val config = DetectorConfig(
            warningDelaySeconds = 0.5,
            criticalDelaySeconds = 1.0,
            postDoneMaximumSeconds = 2.0,
            setupNoiseGuardSeconds = 0.0,
        )
        val detector = PopcornSessionDetector(config)
        val eventTimes = (4_000L..11_000L step 350L).toSet()
        var snapshot = DetectorSnapshot()
        for (time in 0L..24_000L step 50L) {
            snapshot = detector.process(features(time), if (time in eventTimes) pop(time) else null)
        }
        assertTrue(snapshot.doneAtMs != null)
        assertEquals(SessionPhase.STOPPED, snapshot.phase)
        assertEquals(CompletionReason.DONE_DETECTED, snapshot.completionReason)
    }

    @Test
    fun absoluteSessionLimitStopsAnUnfinishedSession() {
        val detector = PopcornSessionDetector(DetectorConfig(maximumSessionSeconds = 5.0))
        var snapshot = DetectorSnapshot()
        for (time in 0L..6_000L step 100L) snapshot = detector.process(features(time), null)
        assertEquals(SessionPhase.STOPPED, snapshot.phase)
        assertEquals(CompletionReason.TIME_LIMIT, snapshot.completionReason)
        assertNull(snapshot.doneAtMs)
    }

    private fun features(time: Long, digitalSilence: Boolean = false) = AudioFeatures(
        timestampMs = time,
        rms = if (digitalSilence) 0.0 else 0.03,
        rmsDb = if (digitalSilence) -180.0 else -30.0,
        peak = if (digitalSilence) 0.0 else 0.05,
        crestFactor = 2.0,
        spectralFlux = 0.01,
        spectralFlatness = 0.2,
        highFrequencyRatio = 0.1,
        microwaveBandDb = if (digitalSilence) -180.0 else -25.0,
        attackRatio = 1.0,
        decayRatio = 1.0,
        digitalSilence = digitalSilence,
    )

    private fun pop(time: Long, spectralFlatness: Double = 0.25) = PopEvent(
        timestampMs = time,
        score = 0.9,
        rmsDb = -12.0,
        noiseFloorDb = -30.0,
        spectralFlux = 0.4,
        highFrequencyRatio = 0.6,
        accepted = true,
        crestFactor = 4.0,
        spectralFlatness = spectralFlatness,
        attackRatio = 3.0,
    )
}
