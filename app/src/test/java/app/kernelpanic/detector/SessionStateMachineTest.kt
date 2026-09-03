package app.kernelpanic.detector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStateMachineTest {
    @Test
    fun silenceCanNeverCauseDoneBeforeActive() {
        val detector = PopcornSessionDetector(DetectorConfig())
        var snapshot = DetectorSnapshot()
        for (time in 0L..90_000L step 100L) snapshot = detector.process(features(time), null)
        assertFalse(snapshot.activeWasReached)
        assertNull(snapshot.doneAtMs)
    }

    @Test
    fun oneRandomPopAndLongGapCannotCauseDone() {
        val detector = PopcornSessionDetector(DetectorConfig())
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
        val detector = PopcornSessionDetector(DetectorConfig())
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
        val detector = PopcornSessionDetector(DetectorConfig())
        val eventTimes = (4_000L..12_000L step 400L).toSet()
        var snapshot = DetectorSnapshot()
        for (time in 0L..15_000L step 50L) {
            snapshot = detector.process(features(time, digitalSilence = time >= 13_000L), if (time in eventTimes) pop(time) else null)
        }
        assertTrue(snapshot.activeWasReached)
        assertTrue(snapshot.phase == SessionPhase.INTERRUPTED)
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

    private fun pop(time: Long) = PopEvent(time, 0.9, -12.0, -30.0, 0.4, 0.6, true)
}
