package app.kernelpanic.audio

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileAudioSourceTest {
    @Test
    fun readsMonoPcm16InOrder() = runTest {
        val expected = shortArrayOf(-32768, -1, 0, 1, 32767)
        val source = WavFileAudioSource(ByteArrayInputStream(wav(expected, 16_000)), chunkSamples = 3)
        val chunks = source.chunks().toList()
        assertEquals(16_000, source.sampleRate)
        assertEquals(2, chunks.size)
        assertArrayEquals(expected, chunks.flatMap { it.samples.asList() }.toShortArray())
    }

    private fun wav(samples: ShortArray, sampleRate: Int): ByteArray {
        val dataSize = samples.size * 2
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
        buffer.putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(dataSize)
        samples.forEach(buffer::putShort)
        return buffer.array()
    }
}
