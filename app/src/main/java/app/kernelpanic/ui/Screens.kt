package app.kernelpanic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kernelpanic.AppScreen
import app.kernelpanic.AppUiState
import app.kernelpanic.BuildConfig
import app.kernelpanic.data.SessionEntity
import app.kernelpanic.detector.CompletionReason
import app.kernelpanic.detector.DetectorSnapshot
import app.kernelpanic.detector.SessionPhase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    state: AppUiState,
    hasMicrophonePermission: () -> Boolean,
    requestPermission: (((Boolean, Boolean) -> Unit) -> Unit),
    openSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onNavigate: (AppScreen) -> Unit,
    openDebugAudioLab: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var permissionPermanentlyDenied by remember { mutableStateOf(false) }
    val listening = state.listening
    val phase = state.detector.phase
    val highContrast = phase in setOf(SessionPhase.DONE, SessionPhase.CRITICAL)
    val foreground = if (highContrast) Color.White else MaterialTheme.colorScheme.onBackground

    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Kernel Panic", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = foreground, modifier = Modifier.semantics { heading() })
            Box {
                IconButton(
                    enabled = !listening,
                    onClick = { menuOpen = true },
                    modifier = Modifier.semantics { contentDescription = "Open menu" },
                ) { Text("⋮", fontSize = 28.sp, color = foreground) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    listOf(
                        "History" to AppScreen.HISTORY,
                        "How It Works" to AppScreen.HOW_IT_WORKS,
                        "Privacy" to AppScreen.PRIVACY,
                        "About" to AppScreen.ABOUT,
                    ).forEach { (label, screen) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false; onNavigate(screen) })
                    }
                    if (BuildConfig.DEBUG) {
                        DropdownMenuItem(text = { Text("Debug Audio Lab") }, onClick = { menuOpen = false; openDebugAudioLab() })
                    }
                }
            }
        }

        Spacer(Modifier.weight(0.15f))
        PopcornMascot(phase, Modifier.size(190.dp))
        Text(
            statusTitle(phase),
            style = if (phase in setOf(SessionPhase.DONE, SessionPhase.WARNING, SessionPhase.CRITICAL)) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            color = foreground,
            modifier = Modifier.semantics { heading() },
        )
        Text(statusSubtitle(phase), color = foreground.copy(alpha = 0.82f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))

        if (listening) {
            Spacer(Modifier.height(20.dp))
            AudioBars(state.detector.audioLevel, Modifier.fillMaxWidth().height(54.dp))
            RateGraph(state.detector.rateHistory, Modifier.padding(top = 8.dp).standardGraphSize())
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                val currentGap = state.detector.currentGapSeconds
                val recentMedian = state.detector.recentIntervalSeconds
                val showGap = phase in setOf(SessionPhase.DECLINING, SessionPhase.DONE, SessionPhase.WARNING, SessionPhase.CRITICAL) &&
                    currentGap != null && currentGap > (recentMedian ?: 0.0)
                val intervalValue = if (showGap) currentGap else recentMedian
                LiveStat("Time", formatDuration(state.detector.elapsedMs), foreground)
                LiveStat("Detected Pops", state.detector.detectedPops.toString(), foreground)
                LiveStat(if (showGap) "Current gap" else "Recent median", intervalValue?.let { String.format(Locale.US, "%.1f s", it) } ?: "—", foreground)
            }
            OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("STOP") }
        } else {
            Spacer(Modifier.height(34.dp))
            Surface(
                onClick = {
                    permissionDenied = false
                    if (hasMicrophonePermission()) onStart()
                    else requestPermission { granted, permanent ->
                        if (granted) onStart() else {
                            permissionDenied = true
                            permissionPermanentlyDenied = permanent
                        }
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                shadowElevation = 8.dp,
                modifier = Modifier.size(140.dp).semantics { role = Role.Button; contentDescription = "Start listening with microphone" },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(shape = CircleShape, color = Color(0xFFD94A3A), modifier = Modifier.size(76.dp)) {}
                }
            }
            Text(
                "START LISTENING",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text("Start when you start the microwave", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 7.dp))
            if (permissionDenied) {
                Card(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Microphone access is required to hear and analyze popcorn while listening.", textAlign = TextAlign.Center)
                        TextButton(onClick = if (permissionPermanentlyDenied) openSettings else {
                            { requestPermission { granted, permanent ->
                                permissionPermanentlyDenied = permanent
                                permissionDenied = !granted
                                if (granted) onStart()
                            } }
                        }) { Text(if (permissionPermanentlyDenied) "Open app settings" else "Try again") }
                    }
                }
            }
            Spacer(Modifier.weight(0.15f))
            Text("Stay nearby • Follow package directions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f))
        }
    }
}

@Composable
private fun LiveStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium, color = color.copy(alpha = 0.75f), textAlign = TextAlign.Center)
    }
}

@Composable
fun SummaryScreen(snapshot: DetectorSnapshot, onHome: () -> Unit, onHistory: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("Session complete", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 28.dp).semantics { heading() })
            Text(snapshot.completionReason?.label ?: "Interrupted", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            PopcornMascot(if (snapshot.doneAtMs != null) SessionPhase.DONE else SessionPhase.IDLE, Modifier.size(140.dp))
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SummaryRow("Total time", formatDuration(snapshot.elapsedMs))
                    SummaryRow("Detected pop events", snapshot.detectedPops.toString())
                    SummaryRow("First detected pop", snapshot.firstPopMs?.let(::formatDuration) ?: "—")
                    SummaryRow("Peak popping rate", String.format(Locale.US, "%.1f / sec", snapshot.peakPopRate))
                    SummaryRow("Final recent median", snapshot.recentIntervalSeconds?.let { String.format(Locale.US, "%.1f sec", it) } ?: "—")
                }
            }
        }
        item { Button(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text("Done") } }
        item { TextButton(onClick = onHistory) { Text("View history") } }
        item { Text("Pop events are sound estimates, not an exact kernel count.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
    }
}

@Composable
fun HistoryScreen(
    sessions: List<SessionEntity>,
    onBack: () -> Unit,
    onSelect: (SessionEntity) -> Unit,
    onDelete: (SessionEntity) -> Unit,
    onDeleteAll: () -> Unit,
) {
    var confirmDeleteAll by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        SimpleHeader("History", onBack)
        if (sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("No sessions yet. Your completed listening sessions will appear here.", textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    val maxPops = sessions.maxOf { it.detectedPopEvents }
                    Text("Personal best: $maxPops detected pop events", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                }
                items(sessions, key = { it.id }) { session ->
                    Card(
                        Modifier.fillMaxWidth().clickable { onSelect(session) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(reasonLabel(session.completionReason), fontWeight = FontWeight.Bold)
                                Text(formatDate(session.timestampEpochMs), style = MaterialTheme.typography.bodySmall)
                                Text("${formatDuration(session.durationMs)} • ${session.detectedPopEvents} events", style = MaterialTheme.typography.bodyMedium)
                            }
                            IconButton(onClick = { onDelete(session) }, modifier = Modifier.semantics { contentDescription = "Delete session" }) { Text("×", fontSize = 26.sp) }
                        }
                    }
                }
                item { TextButton(onClick = { confirmDeleteAll = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete all history") } }
            }
        }
    }
    if (confirmDeleteAll) AlertDialog(
        onDismissRequest = { confirmDeleteAll = false },
        title = { Text("Delete all history?") },
        text = { Text("This permanently removes every saved session from this device.") },
        confirmButton = { Button(onClick = { confirmDeleteAll = false; onDeleteAll() }) { Text("Delete all") } },
        dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("Cancel") } },
    )
}

@Composable
fun SessionDetailScreen(session: SessionEntity?, onBack: () -> Unit, onDelete: (SessionEntity) -> Unit) {
    if (session == null) { InformationScreen("Session", "This session is no longer available.", onBack); return }
    Column(Modifier.fillMaxSize()) {
        SimpleHeader("Session details", onBack)
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(reasonLabel(session.completionReason), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(formatDate(session.timestampEpochMs), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SummaryRow("Total time", formatDuration(session.durationMs))
                    SummaryRow("Detected pop events", session.detectedPopEvents.toString())
                    SummaryRow("First detected pop", session.firstPopMs?.let(::formatDuration) ?: "—")
                    SummaryRow("Peak popping rate", String.format(Locale.US, "%.1f / sec", session.peakPopRate))
                    SummaryRow("Final recent median", session.finalIntervalSeconds?.let { String.format(Locale.US, "%.1f sec", it) } ?: "—")
                }
            }
            OutlinedButton(onClick = { onDelete(session) }, modifier = Modifier.fillMaxWidth()) { Text("Delete session") }
        }
    }
}

@Composable
fun InformationScreen(title: String, body: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        SimpleHeader(title, onBack)
        LazyColumn(contentPadding = PaddingValues(24.dp)) {
            item { Text(body, style = MaterialTheme.typography.bodyLarge, lineHeight = 26.sp) }
        }
    }
}

@Composable
private fun SimpleHeader(title: String, onBack: () -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "Back" }) { Text("‹", fontSize = 34.sp) }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f))
        Text(value, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

private fun statusTitle(phase: SessionPhase): String = when (phase) {
    SessionPhase.IDLE -> "Ready when you are"
    SessionPhase.CALIBRATING -> "Listening…"
    SessionPhase.WAITING -> "Heating up…"
    SessionPhase.RAMPING_UP -> "Popping is starting"
    SessionPhase.ACTIVE -> "Popping!"
    SessionPhase.DECLINING -> "Almost done…"
    SessionPhase.DONE -> "POPCORN IS DONE!"
    SessionPhase.WARNING -> "TAKE IT OUT!"
    SessionPhase.CRITICAL -> "STOP THE MICROWAVE!"
    SessionPhase.STOPPED -> "Microwave stopped"
    SessionPhase.INTERRUPTED -> "Having trouble hearing the microwave"
}

private fun statusSubtitle(phase: SessionPhase): String = when (phase) {
    SessionPhase.IDLE -> "Place your phone nearby with the microphone clear"
    SessionPhase.CALIBRATING -> "Learning the sound of your microwave"
    SessionPhase.WAITING -> "Waiting for a convincing popping pattern"
    SessionPhase.RAMPING_UP -> "The popping rate is increasing"
    SessionPhase.ACTIVE -> "A sustained active cycle has been detected"
    SessionPhase.DECLINING -> "The popping rate is slowing consistently"
    SessionPhase.DONE -> "Stay nearby and stop the microwave"
    SessionPhase.WARNING -> "The microwave still sounds like it is running"
    SessionPhase.CRITICAL -> "Stop the microwave now"
    SessionPhase.STOPPED -> "The appliance sound has stopped"
    SessionPhase.INTERRUPTED -> "Check microphone access and try again"
}

fun formatDuration(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(Locale.US, totalSeconds / 60, totalSeconds % 60)
}

private fun formatDate(epochMs: Long): String = DateTimeFormatter.ofPattern("MMM d, yyyy • h:mm a")
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(epochMs))

private fun reasonLabel(value: String): String = runCatching { CompletionReason.valueOf(value).label }.getOrDefault("Interrupted")
