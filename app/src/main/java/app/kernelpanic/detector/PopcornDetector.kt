package app.kernelpanic.detector

/** Production pipeline shared by AudioRecord, WAV playback, and deterministic fixtures. */
class PopcornDetector(
    val sampleRate: Int,
    val config: DetectorConfig = DetectorConfig(),
) {
    private val extractor = AudioFeatureExtractor(sampleRate, config)
    private val eventDetector = PopEventDetector(config)
    private val sessionDetector = PopcornSessionDetector(config)
    private val ring = ShortArray(config.frameSize)
    private var ringWrite = 0
    private var samplesSeen = 0L
    private var samplesSinceAnalysis = 0

    fun process(samples: ShortArray): List<DetectorSnapshot> {
        val results = ArrayList<DetectorSnapshot>(samples.size / config.hopSize + 1)
        for (sample in samples) {
            ring[ringWrite] = sample
            ringWrite = (ringWrite + 1) % ring.size
            samplesSeen++
            samplesSinceAnalysis++
            if (samplesSeen >= config.frameSize && samplesSinceAnalysis >= config.hopSize) {
                samplesSinceAnalysis = 0
                val frame = ShortArray(config.frameSize)
                for (i in frame.indices) frame[i] = ring[(ringWrite + i) % ring.size]
                val timestampMs = samplesSeen * 1000L / sampleRate
                val features = extractor.extract(frame, timestampMs)
                val calibrating = timestampMs < (config.calibrationSeconds * 1000).toLong()
                val event = eventDetector.process(features, acceptingEvents = !calibrating)
                results += sessionDetector.process(features, event)
            }
        }
        return results
    }

    fun stopManually(): DetectorSnapshot = sessionDetector.stopManually(samplesSeen * 1000L / sampleRate)
}
