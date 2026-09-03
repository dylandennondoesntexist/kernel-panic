package app.kernelpanic.detector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test
    fun displayMedianUsesThreeLatestIntervalsAndCurrentGapKeepsGrowing() {
        val detector = PopcornSessionDetector(DetectorConfig())
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
        val detector = PopcornSessionDetector(DetectorConfig())
        val events = (4_000L..10_000L step 400L).toMutableSet()
        events += setOf(12_000L, 14_000L, 16_000L, 16_500L, 17_000L)
        var snapshot = DetectorSnapshot()
        for (time in 0L..19_500L step 50L) {
            snapshot = detector.process(features(time), if (time in events) pop(time) else null)
        }
        assertTrue(snapshot.activeWasReached)
        assertNull("Recent rapid pops must invalidate older sparse intervals", snapshot.doneAtMs)
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
