package app.kernelpanic.audio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM16 mono WAV reader used by debug tooling; it deliberately shares the production pipeline. */
class WavFileAudioSource(
    input: InputStream,
    private val chunkSamples: Int = 512,
) : AudioSource {
    private val bytes = input.use { it.readBytes() }
    private val dataOffset: Int
    private val dataSize: Int
    override val sampleRate: Int
    @Volatile private var stopped = false

    init {
        require(bytes.size >= 44 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "Not a WAV file" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var cursor = 12
        var foundRate = 0
        var foundChannels = 0
        var foundBits = 0
        var foundOffset = -1
        var foundSize = 0
        while (cursor + 8 <= bytes.size) {
            val id = String(bytes, cursor, 4)
            val size = buffer.getInt(cursor + 4)
            if (id == "fmt " && size >= 16) {
                require(buffer.getShort(cursor + 8).toInt() == 1) { "Only PCM WAV files are supported" }
                foundChannels = buffer.getShort(cursor + 10).toInt()
                foundRate = buffer.getInt(cursor + 12)
                foundBits = buffer.getShort(cursor + 22).toInt()
            } else if (id == "data") {
                foundOffset = cursor + 8
                foundSize = minOf(size, bytes.size - foundOffset)
                break
            }
            cursor += 8 + size + (size and 1)
        }
        require(foundChannels == 1 && foundBits == 16 && foundRate > 0 && foundOffset >= 0) { "WAV must be mono 16-bit PCM" }
        sampleRate = foundRate
        dataOffset = foundOffset
        dataSize = foundSize
    }

    override fun chunks(): Flow<AudioChunk> = flow {
        val buffer = ByteBuffer.wrap(bytes, dataOffset, dataSize).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.remaining() >= 2 && !stopped) {
            val count = minOf(chunkSamples, buffer.remaining() / 2)
            val chunk = ShortArray(count) { buffer.short }
            emit(AudioChunk(chunk, sampleRate))
        }
    }.flowOn(Dispatchers.Default)

    override fun stop() { stopped = true }
}
