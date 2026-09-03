package app.kernelpanic.audio

import kotlinx.coroutines.flow.Flow

data class AudioChunk(val samples: ShortArray, val sampleRate: Int)

interface AudioSource {
    val sampleRate: Int
    fun chunks(): Flow<AudioChunk>
    fun stop()
}
