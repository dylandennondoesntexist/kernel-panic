package app.kernelpanic.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kernelpanic.audio.WavFileAudioSource
import app.kernelpanic.detector.DetectorConfig
import app.kernelpanic.detector.DetectorSnapshot
import app.kernelpanic.detector.PopcornDetector
import app.kernelpanic.detector.SessionPhase
import app.kernelpanic.testing.SyntheticAudioGenerator
import app.kernelpanic.testing.SyntheticScenario
import app.kernelpanic.ui.KernelPanicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DebugAudioLabActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KernelPanicTheme { DebugLab() } }
    }
}

@Composable
private fun DebugLab() {
    val context = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DetectorSnapshot?>(null) }
    var transitions by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun runSynthetic(scenario: SyntheticScenario) = scope.launch {
        running = true; error = null; transitions = emptyList(); result = null
        runCatching {
            withContext(Dispatchers.Default) {
                val fixture = SyntheticAudioGenerator.render(scenario)
                runDetector(fixture.samples, fixture.sampleRate) { snapshot, transitionList ->
                    result = snapshot
                    transitions = transitionList
                }
            }
        }.onFailure { error = it.message }
        running = false
    }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            running = true; error = null; transitions = emptyList()
            runCatching {
                withContext(Dispatchers.Default) {
                    val source = WavFileAudioSource(checkNotNull(context.contentResolver.openInputStream(uri)))
                    val config = configFor(source.sampleRate)
                    val detector = PopcornDetector(source.sampleRate, config)
                    val changes = mutableListOf<String>()
                    var prior: SessionPhase? = null
                    source.chunks().collect { chunk ->
                        detector.process(chunk.samples).lastOrNull()?.let { snapshot ->
                            if (snapshot.phase != prior) changes += "${snapshot.elapsedMs} ms: ${snapshot.phase}"
                            prior = snapshot.phase
                            result = snapshot
                        }
                    }
                    transitions = changes
                }
            }.onFailure { error = it.message }
            running = false
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Debug Audio Lab", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
        item { Text("Runs synthetic or mono PCM16 WAV audio through the exact production detector. This activity exists only in debug builds.") }
        item { Button(onClick = { documentPicker.launch("audio/*") }, enabled = !running, modifier = Modifier.fillMaxWidth()) { Text("Open WAV file") } }
        items(SyntheticScenario.entries) { scenario ->
            Button(onClick = { runSynthetic(scenario) }, enabled = !running, modifier = Modifier.fillMaxWidth()) { Text(scenario.title) }
        }
        item {
            if (running) Text("Processing…")
            error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
            result?.let { snapshot ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("State: ${snapshot.phase}", fontWeight = FontWeight.Bold)
                        Text("Events: ${snapshot.detectedPops}")
                        Text("Peak rate: ${"%.2f".format(snapshot.peakPopRate)}/s")
                        Text("Interval: ${snapshot.recentIntervalSeconds ?: "—"}")
                        Text("Active reached: ${snapshot.activeWasReached}")
                        Text("Done at: ${snapshot.doneAtMs ?: "—"} ms")
                        snapshot.lastEvent?.let { Text("Last transient score: ${"%.3f".format(it.score)} (${if (it.accepted) "accepted" else "rejected"})") }
                    }
                }
            }
        }
        item {
            Column { transitions.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
        }
    }
}

private fun runDetector(samples: ShortArray, sampleRate: Int, onUpdate: (DetectorSnapshot, List<String>) -> Unit) {
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
    onUpdate(last, transitions)
}

private fun configFor(sampleRate: Int) = if (sampleRate >= 40_000) DetectorConfig()
else DetectorConfig(frameSize = 512, hopSize = 256, popBandHighHz = sampleRate * 0.45)
