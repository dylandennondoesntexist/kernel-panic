package app.kernelpanic.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean

class MicrophoneAudioSource private constructor(
    override val sampleRate: Int,
    private val recorder: AudioRecord,
    private val hopSize: Int,
) : AudioSource {
    private val stopped = AtomicBoolean(false)

    override fun chunks(): Flow<AudioChunk> = flow {
        if (recorder.state != AudioRecord.STATE_INITIALIZED) error("Microphone could not be initialized")
        recorder.startRecording()
        try {
            val buffer = ShortArray(hopSize)
            while (currentCoroutineContext().isActive && !stopped.get()) {
                val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                when {
                    read > 0 -> emit(AudioChunk(buffer.copyOf(read), sampleRate))
                    read == 0 -> Unit
                    else -> error("Microphone read failed ($read)")
                }
            }
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            recorder.release()
        }
    }.flowOn(Dispatchers.IO)

    override fun stop() {
        stopped.set(true)
        runCatching { recorder.stop() }
    }

    companion object {
        @SuppressLint("MissingPermission")
        fun create(context: Context, preferredRate: Int = 48_000, hopSize: Int = 512): MicrophoneAudioSource {
            val rates = listOf(preferredRate, 44_100, 32_000, 16_000).distinct()
            var lastError: Throwable? = null
            for (rate in rates) {
                try {
                    val minimum = AudioRecord.getMinBufferSize(
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                    if (minimum <= 0) continue
                    val format = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val supportsRaw = audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
                    val sources = if (supportsRaw) listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.DEFAULT)
                    else listOf(MediaRecorder.AudioSource.DEFAULT)
                    for (source in sources) {
                        val record = AudioRecord.Builder()
                            .setAudioSource(source)
                            .setAudioFormat(format)
                            .setBufferSizeInBytes(maxOf(minimum * 2, hopSize * 8))
                            .build()
                        if (record.state == AudioRecord.STATE_INITIALIZED) return MicrophoneAudioSource(rate, record, hopSize)
                        record.release()
                    }
                } catch (error: Throwable) {
                    lastError = error
                }
            }
            throw IllegalStateException("No supported microphone recording configuration", lastError)
        }
    }
}
