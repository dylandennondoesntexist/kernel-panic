package app.kernelpanic.testing

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

enum class SyntheticScenario(val title: String) {
    MICROWAVE_ONLY("Microwave only (3 min)"),
    NORMAL("Normal lifecycle"),
    EARLY_LONG_GAP("Early long gap"),
    ISOLATED_POPS("Early isolated pops"),
    FAST_POPS("Fast successive pops"),
    KNOCKS("Loud external knocks"),
    SPEECH("Speech over microwave"),
    MIXED_DISTRACTORS("Crinkling, beeps, traffic, and room noise"),
    LOW_SNR("Low signal-to-noise lifecycle"),
    INPUT_FAILURE("Input failure"),
    STOP_EARLY("Microwave stops early"),
    STOP_AFTER_DONE("Microwave stops after done"),
    CONTINUES_AFTER_DONE("Microwave continues after done"),
}

data class SyntheticFixture(
    val samples: ShortArray,
    val sampleRate: Int,
    val expectedPopTimesSeconds: List<Double>,
)

/** Deterministic kitchen-like fixtures. They are test/debug data and are never used for production decisions. */
object SyntheticAudioGenerator {
    const val SAMPLE_RATE = 16_000

    fun render(scenario: SyntheticScenario): SyntheticFixture {
        val duration = when (scenario) {
            SyntheticScenario.MICROWAVE_ONLY -> 180.0
            SyntheticScenario.ISOLATED_POPS -> 90.0
            SyntheticScenario.FAST_POPS -> 22.0
            SyntheticScenario.INPUT_FAILURE -> 35.0
            SyntheticScenario.STOP_EARLY -> 25.0
            SyntheticScenario.CONTINUES_AFTER_DONE, SyntheticScenario.MIXED_DISTRACTORS -> 60.0
            else -> 48.0
        }
        val normal = normalPopTimes()
        val popTimes = when (scenario) {
            SyntheticScenario.NORMAL, SyntheticScenario.STOP_AFTER_DONE, SyntheticScenario.CONTINUES_AFTER_DONE -> normal
            SyntheticScenario.EARLY_LONG_GAP -> earlyGapPopTimes()
            SyntheticScenario.ISOLATED_POPS -> listOf(8.0, 27.0, 58.0)
            SyntheticScenario.FAST_POPS -> generateTimes(5.0, 15.0, 0.105)
            SyntheticScenario.LOW_SNR -> normal
            SyntheticScenario.INPUT_FAILURE -> activePopTimes(until = 21.5)
            SyntheticScenario.STOP_EARLY -> listOf(5.5, 9.0)
            else -> emptyList()
        }
        val microwaveStopsAt = when (scenario) {
            SyntheticScenario.STOP_EARLY -> 12.0
            SyntheticScenario.STOP_AFTER_DONE -> 41.0
            else -> duration + 1.0
        }
        val failureAt = if (scenario == SyntheticScenario.INPUT_FAILURE) 23.0 else duration + 1.0
        val samples = ShortArray((duration * SAMPLE_RATE).toInt())
        var random = 0x1234ABCD
        fun noise(): Double {
            random = random * 1664525 + 1013904223
            return ((random ushr 8) and 0xFFFF) / 32767.5 - 1.0
        }
        for (index in samples.indices) {
            val time = index.toDouble() / SAMPLE_RATE
            if (time >= failureAt) {
                samples[index] = 0
                continue
            }
            var value = if (time < microwaveStopsAt) {
                0.030 * sin(2.0 * PI * 118.0 * time) +
                    0.018 * sin(2.0 * PI * 356.0 * time) +
                    0.013 * noise() + 0.005 * sin(2.0 * PI * 780.0 * time)
            } else {
                0.0015 * noise()
            }

            val popAmplitude = if (scenario == SyntheticScenario.LOW_SNR) 0.115 else 0.34
            for ((eventIndex, eventTime) in popTimes.withIndex()) {
                val dt = time - eventTime
                if (dt in 0.0..0.075) {
                    val envelope = exp(-dt * (50.0 + eventIndex % 5 * 5.0))
                    val color = noise() * 0.72 + sin(2.0 * PI * (2200.0 + eventIndex % 7 * 310.0) * dt) * 0.28
                    value += popAmplitude * (0.82 + (eventIndex % 4) * 0.06) * envelope * color
                }
            }
            if (scenario == SyntheticScenario.KNOCKS) {
                listOf(8.0, 17.0, 29.0, 42.0).forEach { eventTime ->
                    val dt = time - eventTime
                    if (dt in 0.0..0.22) value += 0.72 * exp(-dt * 17.0) * sin(2.0 * PI * 240.0 * dt)
                }
            }
            if (scenario == SyntheticScenario.SPEECH) {
                val speaking = (time in 7.0..14.0) || (time in 23.0..31.0) || (time in 38.0..44.0)
                if (speaking) {
                    val syllables = (0.35 + 0.65 * sin(2.0 * PI * 3.8 * time).coerceAtLeast(0.0))
                    value += syllables * (0.065 * sin(2.0 * PI * 185.0 * time) +
                        0.035 * sin(2.0 * PI * 510.0 * time) + 0.018 * sin(2.0 * PI * 920.0 * time))
                }
            }
            if (scenario == SyntheticScenario.MIXED_DISTRACTORS) {
                val beepPhase = time % 0.55
                if (time in 8.0..10.5 && beepPhase < 0.12) {
                    value += 0.13 * sin(2.0 * PI * 2_100.0 * time)
                }
                if (time in 19.0..20.1) {
                    value += 0.11 * noise() * (0.7 + 0.3 * sin(2.0 * PI * 17.0 * time))
                }
                if (time in 29.0..40.0) {
                    value += 0.045 * sin(2.0 * PI * 82.0 * time) + 0.018 * noise()
                }
                if (time in 46.0..46.4) {
                    value += 0.14 * noise()
                }
            }
            samples[index] = (value.coerceIn(-0.98, 0.98) * Short.MAX_VALUE).toInt().toShort()
        }
        return SyntheticFixture(samples, SAMPLE_RATE, popTimes)
    }

    private fun normalPopTimes(): List<Double> = buildList {
        addAll(listOf(5.0, 7.2, 9.0))
        addAll(generateTimes(10.0, 16.0, 0.68))
        addAll(generateTimes(16.1, 25.0, 0.23))
        addAll(listOf(25.4, 25.9, 26.5, 27.2, 28.0, 29.0, 30.2, 31.7, 33.5, 35.5))
    }

    private fun activePopTimes(until: Double): List<Double> = buildList {
        addAll(listOf(4.5, 5.3, 6.0))
        addAll(generateTimes(6.5, 12.0, 0.55))
        addAll(generateTimes(12.0, until, 0.24))
    }

    private fun earlyGapPopTimes(): List<Double> = buildList {
        addAll(listOf(4.5, 5.2, 5.9))
        addAll(generateTimes(6.2, 11.8, 0.31))
        addAll(generateTimes(14.5, 25.0, 0.25))
        addAll(listOf(25.5, 26.1, 26.9, 27.9, 29.1, 30.6, 32.4, 34.4))
    }

    private fun generateTimes(start: Double, endInclusive: Double, interval: Double): List<Double> {
        val values = mutableListOf<Double>()
        var value = start
        while (value <= endInclusive + 1e-6) {
            values += value
            value += interval
        }
        return values
    }
}
