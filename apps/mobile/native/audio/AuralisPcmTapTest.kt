package expo.modules.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

class AuralisPcmTapTest {
  @After fun resetBus() { AuralisPcmBus.enabled = false; AuralisPcmBus.onFrame = null }

  @Test fun emptyDrainInputNeverAliasesTheSharedEmptyBufferOrErasesPendingPcm() {
    val tap = AuralisPcmTap()
    tap.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT)); tap.flush()
    // DefaultAudioSink can queue EMPTY_BUFFER before its first decoded packet.
    tap.queueInput(AudioProcessor.EMPTY_BUFFER)
    assertEquals(0, tap.output.remaining())
    val expected = byteArrayOf(1, 2, 3, 4)
    val input = ByteBuffer.allocateDirect(4).put(expected)
    input.flip(); tap.queueInput(input)
    tap.queueInput(AudioProcessor.EMPTY_BUFFER)
    tap.queueInput(ByteBuffer.allocateDirect(0))
    val actual = ByteArray(4); tap.output.get(actual)
    assertArrayEquals(expected, actual)
    assertEquals(0, input.remaining())
    tap.queueEndOfStream()
    assertTrue(tap.isEnded)
    tap.flush(); tap.queueInput(AudioProcessor.EMPTY_BUFFER)
    assertEquals(0, tap.output.remaining())
  }

  @Test fun sineFftMeasuresOneKilohertzWithoutChangingPcmBytes() {
    val tap = AuralisPcmTap()
    tap.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT)); tap.flush()
    var frame: Map<String, Any>? = null
    AuralisPcmBus.enabled = true; AuralisPcmBus.onFrame = { frame = it }
    val buffer = ByteBuffer.allocateDirect(2048).order(ByteOrder.nativeOrder())
    for (index in 0 until 1024) buffer.putShort((sin(2 * PI * 1000 * index / 48000.0) * 0.8 * 32767).toInt().toShort())
    buffer.flip(); val expected = ByteArray(2048); buffer.duplicate().get(expected)
    tap.queueInput(buffer)
    val actual = ByteArray(2048); tap.output.get(actual); assertArrayEquals(expected, actual)
    assertNotNull(frame)
    @Suppress("UNCHECKED_CAST") val bands = frame!!["bands"] as List<Double>
    assertEquals(32, bands.size)
    val strongest = bands.indices.maxBy { bands[it] }
    assertTrue("FFT peak must follow the real 1 kHz PCM fixture", strongest in 17..19)
    assertEquals(0.8 / sqrt(2.0), frame!!["rms"] as Double, 0.02)
    assertEquals(0.8, frame!!["peak"] as Double, 0.01)
  }

  @Test fun silenceProducesZeroSpectrumAndDisabledTapEmitsNothing() {
    val tap = AuralisPcmTap()
    tap.configure(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT)); tap.flush()
    var emissions = 0
    AuralisPcmBus.onFrame = { frame ->
      emissions++
      @Suppress("UNCHECKED_CAST") val bands = frame["bands"] as List<Double>
      assertTrue(bands.all { it == 0.0 }); assertEquals(0.0, frame["rms"] as Double, 0.0)
    }
    AuralisPcmBus.enabled = false
    tap.queueInput(ByteBuffer.allocateDirect(4096)); assertEquals(0, emissions)
    AuralisPcmBus.enabled = true
    tap.queueInput(ByteBuffer.allocateDirect(4096)); assertEquals(1, emissions)
  }
}
