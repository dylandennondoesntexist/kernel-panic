package app.kernelpanic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.kernelpanic.audio.AudioSource
import app.kernelpanic.audio.MicrophoneAudioSource
import app.kernelpanic.data.SessionEntity
import app.kernelpanic.detector.CompletionReason
import app.kernelpanic.detector.DetectorConfig
import app.kernelpanic.detector.DetectorSnapshot
import app.kernelpanic.detector.PopcornDetector
import app.kernelpanic.detector.SessionPhase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

enum class AppScreen { HOME, SUMMARY, HISTORY, SESSION_DETAIL, HOW_IT_WORKS, PRIVACY, ABOUT }

data class AppUiState(
    val screen: AppScreen = AppScreen.HOME,
    val detector: DetectorSnapshot = DetectorSnapshot(),
    val history: List<SessionEntity> = emptyList(),
    val selectedSession: SessionEntity? = null,
    val listening: Boolean = false,
    val message: String? = null,
)

class KernelPanicViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as KernelPanicApplication).sessions
    private val mutableState = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private var source: AudioSource? = null
    private var detector: PopcornDetector? = null
    private var captureJob: Job? = null
    private var finalized = false

    fun startListening() {
        if (mutableState.value.listening) return
        finalized = false
        try {
            val audioSource = MicrophoneAudioSource.create(getApplication())
            val detectorConfig = configFor(audioSource.sampleRate)
            val engine = PopcornDetector(audioSource.sampleRate, detectorConfig)
            source = audioSource
            detector = engine
            mutableState.value = mutableState.value.copy(
                screen = AppScreen.HOME,
                detector = DetectorSnapshot(phase = SessionPhase.CALIBRATING),
                listening = true,
                message = null,
            )
            captureJob = viewModelScope.launch {
                audioSource.chunks()
                    .catch { error -> interruptInternal("Microphone stopped: ${error.message ?: "unknown error"}") }
                    .collect { chunk ->
                        engine.process(chunk.samples).lastOrNull()?.let { snapshot ->
                            mutableState.value = mutableState.value.copy(detector = snapshot)
                            if (snapshot.phase == SessionPhase.STOPPED || snapshot.phase == SessionPhase.INTERRUPTED) {
                                finalize(snapshot)
                            }
                        }
                    }
            }
        } catch (error: Throwable) {
            mutableState.value = mutableState.value.copy(
                listening = false,
                message = "Unable to start the microphone. ${error.message.orEmpty()}".trim(),
            )
        }
    }

    fun stopListening() {
        if (!mutableState.value.listening) return
        val snapshot = detector?.stopManually() ?: mutableState.value.detector.copy(
            phase = SessionPhase.STOPPED,
            completionReason = CompletionReason.STOPPED_MANUALLY,
        )
        source?.stop()
        captureJob?.cancel()
        finalize(snapshot)
    }

    fun suppressSelfNoise(durationMs: Long = 1_800) {
        detector?.suppressEventsFor(durationMs)
    }

    fun interruptListening(message: String = "Listening was interrupted") {
        if (!mutableState.value.listening) return
        interruptInternal(message)
    }

    private fun interruptInternal(message: String) {
        val current = mutableState.value.detector
        val snapshot = current.copy(
            phase = SessionPhase.INTERRUPTED,
            completionReason = CompletionReason.INTERRUPTED,
            signalHealthy = false,
        )
        source?.stop()
        captureJob?.cancel()
        mutableState.value = mutableState.value.copy(message = message)
        finalize(snapshot)
    }

    private fun finalize(snapshot: DetectorSnapshot) {
        if (finalized) return
        finalized = true
        source?.stop()
        source = null
        detector = null
        mutableState.value = mutableState.value.copy(
            screen = AppScreen.SUMMARY,
            detector = snapshot,
            listening = false,
        )
        viewModelScope.launch {
            runCatching { repository.save(snapshot) }
                .onFailure { mutableState.value = mutableState.value.copy(message = "Session ended, but history could not be saved.") }
            loadHistory()
        }
    }

    fun navigate(screen: AppScreen) {
        if (mutableState.value.listening) return
        mutableState.value = mutableState.value.copy(
            screen = screen,
            selectedSession = null,
            message = null,
            detector = if (screen == AppScreen.HOME) DetectorSnapshot() else mutableState.value.detector,
        )
        if (screen == AppScreen.HISTORY) loadHistory()
    }

    fun selectSession(session: SessionEntity) {
        mutableState.value = mutableState.value.copy(screen = AppScreen.SESSION_DETAIL, selectedSession = session)
    }

    fun deleteSession(session: SessionEntity) = viewModelScope.launch {
        runCatching { repository.delete(session) }
            .onFailure { mutableState.value = mutableState.value.copy(message = "Could not delete that session.") }
        loadHistory()
        if (mutableState.value.selectedSession?.id == session.id) navigate(AppScreen.HISTORY)
    }

    fun deleteAllHistory() = viewModelScope.launch {
        runCatching { repository.deleteAll() }
            .onFailure { mutableState.value = mutableState.value.copy(message = "Could not delete history.") }
        loadHistory()
    }

    fun dismissMessage() {
        mutableState.value = mutableState.value.copy(message = null)
    }

    private fun loadHistory() = viewModelScope.launch {
        runCatching { repository.all() }
            .onSuccess { mutableState.value = mutableState.value.copy(history = it) }
            .onFailure { mutableState.value = mutableState.value.copy(message = "History is unavailable.") }
    }

    override fun onCleared() {
        source?.stop()
        captureJob?.cancel()
        super.onCleared()
    }

    private fun configFor(sampleRate: Int): DetectorConfig = when {
        sampleRate >= 40_000 -> DetectorConfig(frameSize = 1024, hopSize = 512)
        sampleRate >= 24_000 -> DetectorConfig(frameSize = 512, hopSize = 256)
        else -> DetectorConfig(frameSize = 512, hopSize = 256, popBandHighHz = sampleRate * 0.45)
    }
}
