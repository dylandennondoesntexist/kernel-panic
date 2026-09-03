package app.kernelpanic.debug

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.kernelpanic.audio.AudioSource
import app.kernelpanic.audio.MicrophoneAudioSource
import app.kernelpanic.audio.WavFileAudioSource
import app.kernelpanic.detector.DetectorConfig
import app.kernelpanic.detector.DetectorSnapshot
import app.kernelpanic.detector.PopcornDetector
import app.kernelpanic.detector.SessionPhase
import app.kernelpanic.testing.SyntheticAudioGenerator
import app.kernelpanic.testing.SyntheticScenario
import app.kernelpanic.ui.KernelPanicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class DebugAudioLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KernelPanicTheme { DebugLab() } }
    }
}

@Composable
private fun DebugLab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var processing by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DetectorSnapshot?>(null) }
    var transitions by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var activeSource by remember { mutableStateOf<AudioSource?>(null) }
    var activeWriter by remember { mutableStateOf<WavPcmWriter?>(null) }
    var recordingJob by remember { mutableStateOf<Job?>(null) }
    val recordingsDirectory = remember {
        File(checkNotNull(context.getExternalFilesDir(null)), "recordings").apply { mkdirs() }
    }
    var latestRecording by remember {
        mutableStateOf(recordingsDirectory.listFiles { file -> file.extension.equals("wav", true) }?.maxByOrNull { it.lastModified() })
    }

    fun applyRun(run: DetectorRun) {
        result = run.snapshot
        transitions = run.transitions
    }

    fun analyzeFile(file: File) = scope.launch {
        processing = true
        error = null
        status = "Analyzing ${file.name}…"
        runCatching {
            withContext(Dispatchers.Default) {
                FileInputStream(file).use { analyzeSource(WavFileAudioSource(it)) }
            }
        }.onSuccess {
            applyRun(it)
            status = "Analyzed ${file.name}"
        }.onFailure { error = it.message }
        processing = false
    }

    fun startRecording() {
        if (recording || processing) return
        error = null
        result = null
        transitions = emptyList()
        val job = scope.launch(Dispatchers.Default) {
            var source: MicrophoneAudioSource? = null
            var writer: WavPcmWriter? = null
            try {
                val microphone = MicrophoneAudioSource.create(context)
                source = microphone
                val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                val file = File(recordingsDirectory, "kernel-panic-$stamp.wav")
                val wavWriter = WavPcmWriter(file, microphone.sampleRate)
                writer = wavWriter
                val detector = PopcornDetector(microphone.sampleRate, configFor(microphone.sampleRate))
                val changes = mutableListOf<String>()
                var prior: SessionPhase? = null
                var publishAfterMs = 0L
                withContext(Dispatchers.Main) {
                    activeSource = source
                    activeWriter = writer
                    latestRecording = file
                    recording = true
                    status = "Recording ${file.name}. Cook normally, then tap Stop recording."
                }
                microphone.chunks().collect { chunk ->
                    wavWriter.write(chunk.samples)
                    detector.process(chunk.samples).lastOrNull()?.let { snapshot ->
                        if (snapshot.phase != prior) changes += "${snapshot.elapsedMs} ms: ${snapshot.phase}"
                        prior = snapshot.phase
                        if (snapshot.elapsedMs >= publishAfterMs) {
                            publishAfterMs = snapshot.elapsedMs + 250
                            withContext(Dispatchers.Main) {
                                result = snapshot
                                transitions = changes.toList()
                            }
                        }
                    }
                }
            } catch (throwable: Throwable) {
                withContext(Dispatchers.Main) { error = throwable.message ?: "Recording failed" }
            } finally {
                source?.stop()
                writer?.close()
                withContext(NonCancellable + Dispatchers.Main) {
                    activeSource = null
                    activeWriter = null
                    recording = false
                    recordingJob = null
                    latestRecording?.let { status = "Saved ${it.absolutePath}" }
                }
            }
        }
        recordingJob = job
    }

    fun stopRecording() = scope.launch {
        activeSource?.stop()
        activeWriter?.close()
        recordingJob?.cancelAndJoin()
        recordingJob = null
        recording = false
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else error = "Microphone permission is required to make a real-session debug recording."
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            processing = true
            error = null
            status = "Analyzing selected WAV…"
            runCatching {
                withContext(Dispatchers.Default) {
                    val input = checkNotNull(context.contentResolver.openInputStream(uri))
                    analyzeSource(WavFileAudioSource(input))
                }
            }.onSuccess { applyRun(it); status = "Selected WAV analyzed" }
                .onFailure { error = it.message }
            processing = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (recordingJob != null) {
                activeSource?.stop()
                activeWriter?.close()
                recordingJob?.cancel()
            }
        }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Text("Debug Audio Lab", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text("This debug-only screen records or imports mono PCM16 WAV audio and runs it through the exact production detector. Raw recordings never exist in release builds.") }
        item {
            Button(
                onClick = {
                    if (recording) stopRecording()
                    else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                    else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = !processing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (recording) "Stop and save recording" else "Record a real microwave session") }
        }
        item { Button(onClick = { documentPicker.launch("audio/*") }, enabled = !processing && !recording, modifier = Modifier.fillMaxWidth()) { Text("Open WAV file") } }
        latestRecording?.let { file ->
            item { OutlinedButton(onClick = { analyzeFile(file) }, enabled = !processing && !recording, modifier = Modifier.fillMaxWidth()) { Text("Analyze last recording") } }
        }
        item { Text("Synthetic regression scenarios", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
        items(SyntheticScenario.entries) { scenario ->
            Button(
                onClick = {
                    scope.launch {
                        processing = true; error = null; transitions = emptyList(); result = null
                        runCatching {
                            withContext(Dispatchers.Default) {
                                val fixture = SyntheticAudioGenerator.render(scenario)
                                runDetector(fixture.samples, fixture.sampleRate)
                            }
                        }.onSuccess(::applyRun).onFailure { error = it.message }
                        processing = false
                    }
                },
                enabled = !processing && !recording,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(scenario.title) }
        }
        item {
            if (processing) Text("Processing…")
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
            result?.let { snapshot -> DetectorResultCard(snapshot) }
        }
        item { Column { transitions.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } } }
    }
}

@Composable
private fun DetectorResultCard(snapshot: DetectorSnapshot) {
    val event = snapshot.lastEvent
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("State: ${snapshot.phase}", fontWeight = FontWeight.Bold)
            Text("Events: ${snapshot.detectedPops}")
            Text("Peak rate: ${"%.2f".format(snapshot.peakPopRate)}/s")
            Text("Recent median: ${snapshot.recentIntervalSeconds ?: "—"}")
            Text("Current gap: ${snapshot.currentGapSeconds?.let { "%.2f s".format(it) } ?: "—"}")
            Text("Active reached: ${snapshot.activeWasReached}")
            Text("Done at: ${snapshot.doneAtMs ?: "—"} ms")
            if (event != null) {
                Text("Last transient: ${"%.3f".format(event.score)} (${if (event.accepted) "accepted" else "rejected"})", fontWeight = FontWeight.Bold)
                Text("RMS ${"%.1f".format(event.rmsDb)} dB • noise ${"%.1f".format(event.noiseFloorDb)} dB")
                Text("Flux ${"%.3f".format(event.spectralFlux)} • high ratio ${"%.2f".format(event.highFrequencyRatio)}")
                Text("Crest ${"%.2f".format(event.crestFactor)} • flatness ${"%.2f".format(event.spectralFlatness)} • attack ${"%.2f".format(event.attackRatio)}")
            }
        }
    }
}

private suspend fun analyzeSource(source: AudioSource): DetectorRun {
    val detector = PopcornDetector(source.sampleRate, configFor(source.sampleRate))
    val transitions = mutableListOf<String>()
    var prior: SessionPhase? = null
    var last = DetectorSnapshot()
    source.chunks().collect { chunk ->
        detector.process(chunk.samples).lastOrNull()?.let { snapshot ->
            if (snapshot.phase != prior) transitions += "${snapshot.elapsedMs} ms: ${snapshot.phase}"
            prior = snapshot.phase
            last = snapshot
        }
    }
    return DetectorRun(last, transitions)
}

private fun runDetector(samples: ShortArray, sampleRate: Int): DetectorRun {
    val detector = PopcornDetector(sampleRate, configFor(sampleRate))
    val transitions = mutableListOf<String>()
    var prior: SessionPhase? = null
    var last = DetectorSnapshot()
    var cursor = 0
    while (cursor < samples.size) {
        val end = minOf(cursor + 512, samples.size)
        detector.process(samples.copyOfRange(cursor, end)).lastOrNull()?.let { snapshot ->
            if (snapshot.phase != prior) transitions += "${snapshot.elapsedMs} ms: ${snapshot.phase}"
            prior = snapshot.phase
            last = snapshot
        }
        cursor = end
    }
    return DetectorRun(last, transitions)
}

private data class DetectorRun(val snapshot: DetectorSnapshot, val transitions: List<String>)

private fun configFor(sampleRate: Int) = if (sampleRate >= 40_000) DetectorConfig()
else DetectorConfig(frameSize = 512, hopSize = 256, popBandHighHz = sampleRate * 0.45)

/** Small debug-only PCM16 WAV writer. Header sizes are finalized on close. */
private class WavPcmWriter(file: File, sampleRate: Int) {
    private val output = RandomAccessFile(file, "rw")
    private var dataBytes = 0
    private var closed = false

    init {
        output.setLength(0)
        output.writeBytes("RIFF")
        writeIntLe(0)
        output.writeBytes("WAVEfmt ")
        writeIntLe(16)
        writeShortLe(1)
        writeShortLe(1)
        writeIntLe(sampleRate)
        writeIntLe(sampleRate * 2)
        writeShortLe(2)
        writeShortLe(16)
        output.writeBytes("data")
        writeIntLe(0)
    }

    @Synchronized
    fun write(samples: ShortArray) {
        if (closed) return
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            bytes[index * 2] = (sample.toInt() and 0xFF).toByte()
            bytes[index * 2 + 1] = ((sample.toInt() ushr 8) and 0xFF).toByte()
        }
        output.write(bytes)
        dataBytes += bytes.size
    }

    @Synchronized
    fun close() {
        if (closed) return
        output.seek(4)
        writeIntLe(36 + dataBytes)
        output.seek(40)
        writeIntLe(dataBytes)
        output.close()
        closed = true
    }

    private fun writeIntLe(value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 24) and 0xFF)
    }

    private fun writeShortLe(value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
    }
}
