package app.kernelpanic.detector

import app.kernelpanic.testing.SyntheticAudioGenerator
import app.kernelpanic.testing.SyntheticScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorScenarioTest {
    @Test
    fun microwaveOnly_neverBecomesActiveOrDone() {
        val run = runScenario(SyntheticScenario.MICROWAVE_ONLY)
        assertEquals(0, run.last.detectedPops)
        assertFalse(run.snapshots.any { it.activeWasReached })
        assertFalse(run.snapshots.any { it.doneAtMs != null })
    }

    @Test
    fun normalLifecycle_reachesActiveThenDoneOnlyInSparseTail() {
        val run = runScenario(SyntheticScenario.NORMAL)
        assertTrue("Expected enough accepted pops, got ${run.last.detectedPops}", run.last.detectedPops >= 25)
        assertTrue(run.snapshots.any { it.phase == SessionPhase.ACTIVE })
        assertNotNull(run.last.doneAtMs)
        assertTrue("Done was premature: ${run.last.doneAtMs}", run.last.doneAtMs!! >= 30_000)
    }

    @Test
    fun leadingDigitalSilence_doesNotPoisonAdaptiveNoiseFloor() {
        val fixture = SyntheticAudioGenerator.render(SyntheticScenario.NORMAL)
        val leadingSilence = ShortArray(fixture.sampleRate / 10)
        val samples = leadingSilence + fixture.samples
        val run = runFixture(samples, fixture.sampleRate)
        assertTrue("Startup silence prevented the active phase (${run.last.detectedPops} events)", run.last.activeWasReached)
        assertNotNull("Startup silence prevented completion", run.last.doneAtMs)
    }

    @Test
    fun oneEarlyLongGap_doesNotCausePrematureDone() {
        val run = runScenario(SyntheticScenario.EARLY_LONG_GAP)
        assertTrue(run.snapshots.any { it.phase == SessionPhase.ACTIVE })
        assertFalse(run.snapshots.any { it.doneAtMs != null && it.elapsedMs < 25_000 })
        assertNotNull(run.last.doneAtMs)
    }

    @Test
    fun isolatedEarlyPops_neverUnlockDone() {
        val run = runScenario(SyntheticScenario.ISOLATED_POPS)
        assertFalse(run.last.activeWasReached)
        assertEquals(null, run.last.doneAtMs)
    }

    @Test
    fun fastSuccessivePops_areDebouncedButRemainDistinct() {
        val fixture = SyntheticAudioGenerator.render(SyntheticScenario.FAST_POPS)
        val run = runFixture(fixture.samples, fixture.sampleRate)
        assertTrue("Too many real events were merged: ${run.last.detectedPops}", run.last.detectedPops >= fixture.expectedPopTimesSeconds.size * 0.55)
        assertTrue("One pop counted as multiple events", run.last.detectedPops <= fixture.expectedPopTimesSeconds.size + 2)
    }

    @Test
    fun selfNoiseSuppression_ignoresAlertWindowAndThenResumes() {
        val fixture = SyntheticAudioGenerator.render(SyntheticScenario.FAST_POPS)
        val config = DetectorConfig(frameSize = 512, hopSize = 256, popBandHighHz = fixture.sampleRate * 0.45)
        val detector = PopcornDetector(fixture.sampleRate, config)
        val firstEnd = (fixture.sampleRate * 4.8).toInt()
        detector.process(fixture.samples.copyOfRange(0, firstEnd))
        detector.suppressEventsFor(1_800)
        val suppressedEnd = (fixture.sampleRate * 6.6).toInt()
        val suppressed = detector.process(fixture.samples.copyOfRange(firstEnd, suppressedEnd)).last()
        assertEquals(0, suppressed.detectedPops)
        val resumedEnd = (fixture.sampleRate * 8.0).toInt()
        val resumed = detector.process(fixture.samples.copyOfRange(suppressedEnd, resumedEnd)).last()
        assertTrue("Detection did not resume after the app-noise guard", resumed.detectedPops > 5)
    }

    @Test
    fun loudKnocks_areRejectedAndCannotCauseDone() {
        val run = runScenario(SyntheticScenario.KNOCKS)
        assertTrue("Knocks accepted as pops: ${run.last.detectedPops}", run.last.detectedPops <= 1)
        assertFalse(run.last.activeWasReached)
        assertEquals(null, run.last.doneAtMs)
    }

    @Test
    fun speech_doesNotAccumulatePopEventsOrCauseDone() {
        val run = runScenario(SyntheticScenario.SPEECH)
        assertTrue("Speech accepted repeatedly: ${run.last.detectedPops}", run.last.detectedPops <= 2)
        assertFalse(run.last.activeWasReached)
        assertEquals(null, run.last.doneAtMs)
    }

    @Test
    fun mixedEnvironmentalDistractors_doNotEstablishActiveOrDone() {
        val run = runScenario(SyntheticScenario.MIXED_DISTRACTORS)
        assertTrue("Distractors accepted too often: ${run.last.detectedPops}", run.last.detectedPops <= 6)
        assertFalse(run.last.activeWasReached)
        assertEquals(null, run.last.doneAtMs)
    }

    @Test
    fun lowSignalToNoiseLifecycle_stillReachesActiveAndDone() {
        val run = runScenario(SyntheticScenario.LOW_SNR)
        assertTrue("Low-SNR cycle never became active (${run.last.detectedPops} events, last=${run.last.lastEvent})", run.last.activeWasReached)
        assertNotNull("Low-SNR cycle did not finish: ${run.last}", run.last.doneAtMs)
    }

    @Test
    fun inputFailure_interruptsWithoutDone() {
        val run = runScenario(SyntheticScenario.INPUT_FAILURE)
        assertTrue(run.snapshots.any { it.activeWasReached })
        assertEquals(SessionPhase.INTERRUPTED, run.last.phase)
        assertEquals(null, run.last.doneAtMs)
        assertEquals(CompletionReason.INTERRUPTED, run.last.completionReason)
    }

    @Test
    fun microwaveStoppingEarly_doesNotClaimDone() {
        val run = runScenario(SyntheticScenario.STOP_EARLY)
        assertEquals(SessionPhase.STOPPED, run.last.phase)
        assertEquals(CompletionReason.MICROWAVE_STOPPED, run.last.completionReason)
        assertEquals(null, run.last.doneAtMs)
    }

    @Test
    fun microwaveStoppingAfterDone_finalizesAsDone() {
        val run = runScenario(SyntheticScenario.STOP_AFTER_DONE)
        assertNotNull(run.last.doneAtMs)
        assertEquals(SessionPhase.STOPPED, run.last.phase)
        assertEquals(CompletionReason.DONE_DETECTED, run.last.completionReason)
    }

    @Test
    fun continuedOperationAfterDone_escalatesWarningThenCritical() {
        val run = runScenario(SyntheticScenario.CONTINUES_AFTER_DONE)
        assertTrue(run.snapshots.any { it.phase == SessionPhase.DONE })
        assertTrue(run.snapshots.any { it.phase == SessionPhase.WARNING })
        assertTrue(run.snapshots.any { it.phase == SessionPhase.CRITICAL })
    }

    private fun runScenario(scenario: SyntheticScenario): ScenarioRun {
        val fixture = SyntheticAudioGenerator.render(scenario)
        return runFixture(fixture.samples, fixture.sampleRate)
    }

    private fun runFixture(samples: ShortArray, sampleRate: Int): ScenarioRun {
        val config = DetectorConfig(frameSize = 512, hopSize = 256, popBandHighHz = sampleRate * 0.45)
        val detector = PopcornDetector(sampleRate, config)
        val snapshots = mutableListOf<DetectorSnapshot>()
        var cursor = 0
        while (cursor < samples.size) {
            val end = minOf(cursor + 512, samples.size)
            snapshots += detector.process(samples.copyOfRange(cursor, end))
            cursor = end
        }
        return ScenarioRun(snapshots, snapshots.last())
    }

    private data class ScenarioRun(val snapshots: List<DetectorSnapshot>, val last: DetectorSnapshot)
}
