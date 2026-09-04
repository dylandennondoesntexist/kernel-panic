package app.kernelpanic.ui

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.core.content.edit
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kernelpanic.AlertController
import app.kernelpanic.AppScreen
import app.kernelpanic.KernelPanicViewModel
import app.kernelpanic.detector.SessionPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun KernelPanicApp(
    viewModel: KernelPanicViewModel,
    alertController: AlertController,
    hasMicrophonePermission: () -> Boolean,
    requestMicrophonePermission: (((Boolean, Boolean) -> Unit) -> Unit),
    openAppSettings: () -> Unit,
    openDebugAudioLab: () -> Unit,
) {
    KernelPanicTheme {
        val ui by viewModel.state.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val activity = context as? Activity
        val preferences = remember { context.getSharedPreferences("kernel-panic", Context.MODE_PRIVATE) }
        var showOnboarding by remember { mutableStateOf(!preferences.getBoolean("onboarding-complete", false)) }
        var showStopConfirmation by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }

        DisposableEffect(ui.listening) {
            if (ui.listening) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        BackHandler(ui.listening) { showStopConfirmation = true }
        LaunchedEffect(ui.message) {
            ui.message?.let {
                snackbar.showSnackbar(it)
                viewModel.dismissMessage()
            }
        }
        LaunchedEffect(ui.detector.phase) {
            when (ui.detector.phase) {
                SessionPhase.DONE -> {
                    viewModel.suppressSelfNoise()
                    alertController.alert("Popcorn is done")
                }
                SessionPhase.WARNING -> while (isActive) {
                    viewModel.suppressSelfNoise()
                    alertController.alert("Take it out")
                    delay(6_000)
                }
                SessionPhase.CRITICAL -> while (isActive) {
                    viewModel.suppressSelfNoise()
                    alertController.alert("Stop the microwave", critical = true)
                    delay(5_000)
                }
                SessionPhase.STOPPED, SessionPhase.INTERRUPTED, SessionPhase.IDLE -> alertController.stop()
                else -> Unit
            }
        }

        val stateColor = when (ui.detector.phase) {
            SessionPhase.DONE -> Color(0xFF1E7D4C)
            SessionPhase.WARNING -> Color(0xFFF2C14E)
            SessionPhase.CRITICAL -> Color(0xFFC9362C)
            else -> MaterialTheme.colorScheme.background
        }
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { insets ->
            Box(Modifier.fillMaxSize().background(stateColor).padding(insets)) {
                when (ui.screen) {
                    AppScreen.HOME -> HomeScreen(
                        state = ui,
                        hasMicrophonePermission = hasMicrophonePermission,
                        requestPermission = requestMicrophonePermission,
                        openSettings = openAppSettings,
                        onStart = viewModel::startListening,
                        onStop = viewModel::stopListening,
                        onNavigate = viewModel::navigate,
                        openDebugAudioLab = openDebugAudioLab,
                    )
                    AppScreen.SUMMARY -> SummaryScreen(ui.detector, onHome = { viewModel.navigate(AppScreen.HOME) }, onHistory = { viewModel.navigate(AppScreen.HISTORY) })
                    AppScreen.HISTORY -> HistoryScreen(ui.history, onBack = { viewModel.navigate(AppScreen.HOME) }, onSelect = viewModel::selectSession, onDelete = viewModel::deleteSession, onDeleteAll = viewModel::deleteAllHistory)
                    AppScreen.SESSION_DETAIL -> SessionDetailScreen(ui.selectedSession, onBack = { viewModel.navigate(AppScreen.HISTORY) }, onDelete = viewModel::deleteSession)
                    AppScreen.HOW_IT_WORKS -> InformationScreen("How It Works", HOW_IT_WORKS, { viewModel.navigate(AppScreen.HOME) })
                    AppScreen.PRIVACY -> InformationScreen("Privacy", PRIVACY, { viewModel.navigate(AppScreen.HOME) })
                    AppScreen.ABOUT -> InformationScreen("About", ABOUT, { viewModel.navigate(AppScreen.HOME) })
                }
            }
        }

        if (showOnboarding) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Welcome to Kernel Panic") },
                text = { Text("Place your phone on a nearby counter with its microphone clear. Start listening when you start the microwave. Stay nearby and always follow your popcorn package instructions. Never put your phone inside the microwave.") },
                confirmButton = {
                    Button(onClick = {
                        preferences.edit { putBoolean("onboarding-complete", true) }
                        showOnboarding = false
                    }) { Text("Got it") }
                },
            )
        }
        if (showStopConfirmation) {
            AlertDialog(
                onDismissRequest = { showStopConfirmation = false },
                title = { Text("Stop listening?") },
                text = { Text("The microphone will be released and this session will be saved as stopped manually.") },
                confirmButton = { Button(onClick = { showStopConfirmation = false; viewModel.stopListening() }) { Text("Stop") } },
                dismissButton = { TextButton(onClick = { showStopConfirmation = false }) { Text("Keep listening") } },
            )
        }
    }
}

const val HOW_IT_WORKS = "Kernel Panic does not simply run a timer. It listens for short, broadband sounds that resemble popcorn pops. A permissive rapid-pop detector powers the playful Pop Count and cumulative graph, while a separate conservative signal follows the rate curve. After a confirmed active peak, a sustained negative rate slope marks SLOWING; stricter sparse-interval rules decide DONE. The visible count can never make the app announce DONE.\n\nThe detector learns the microwave and room background over time and ignores the short setup period while keypad, door, package, or permission sounds are likely. Doneness still requires a confirmed popping peak; a lone sound or one long early gap cannot unlock the alert. Once sustained popping or slowing is confirmed, the visible cooking stages cannot move backward. The microwave stopping can end a session, but it can never produce or strengthen a DONE decision.\n\nPop Count is an estimate: simultaneous kernels can merge, and one noisy sound can occasionally look like more than one pop. The detector uses on-device digital signal processing and statistical rules—not machine learning. Kitchens, phones, microwaves, and popcorn all sound different, so the estimate can be wrong."
const val PRIVACY = "Microphone access is used only while the listening screen is active. Audio is analyzed on this device in real time. Release builds do not save recordings, and audio is never uploaded or transmitted.\n\nThere is no account. Only derived session statistics—such as duration, estimated Pop Count, and peak rate—are stored locally. You can delete one session or all history at any time."
const val ABOUT = "Kernel Panic is a playful, open-source cooking companion. It is a convenience aid, not a cooking safety system.\n\nStay near the microwave, follow the popcorn manufacturer's instructions, and use your own judgment. Microwave behavior varies. Do not put your phone inside the microwave; keep it nearby with the microphone unobstructed.\n\nVersion 1.0.0"
