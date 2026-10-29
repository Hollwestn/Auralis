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
  @After fun resetBus() { AuralisPcmBus.enabled = false; AuralisPcmBus.onFrame = null; AuralisPcmBus.gain = 1.0 }

  @Test fun normalizationAttenuatesPcmAndLimitsBoostInsteadOfWrapping() {
    var gain=0.5
    val tap=AuralisPcmTap({gain},false)
    tap.configure(AudioFormat(48000,1,C.ENCODING_PCM_16BIT));tap.flush()
    val input=ByteBuffer.allocateDirect(4096).order(ByteOrder.nativeOrder())
    repeat(2048){input.putShort(20000.toShort())};input.flip();tap.queueInput(input)
    val attenuated=tap.output.order(ByteOrder.nativeOrder())
    while(attenuated.hasRemaining())assertEquals(10000,attenuated.short.toInt())
    gain=3.98
    val boosted=ByteBuffer.allocateDirect(48000).order(ByteOrder.nativeOrder())
    repeat(24000){boosted.putShort(30000.toShort())};boosted.flip();tap.queueInput(boosted)
    val output=tap.output.order(ByteOrder.nativeOrder());var last=0
    while(output.hasRemaining()){last=output.short.toInt();assertTrue(last in 15000..32767)}
    assertTrue(last>=32760)
  }

  @Test fun floatingPointStereoGainWorksWhenVisualizationIsOff() {
    val tap=AuralisPcmTap({0.25},false)
    tap.configure(AudioFormat(48000,2,C.ENCODING_PCM_FLOAT));tap.flush()
    val input=ByteBuffer.allocateDirect(8192).order(ByteOrder.nativeOrder())
    repeat(1024){input.putFloat(.8f);input.putFloat(-.4f)};input.flip();tap.queueInput(input)
    val output=tap.output.order(ByteOrder.nativeOrder())
    repeat(1024){assertEquals(.2f,output.float,.00001f);assertEquals(-.1f,output.float,.00001f)}
  }

  @Test fun nativeLoudnessMeterMeasuresGatedMonoAndStereoWithKnownReference() {
    val mono=AuralisLoudnessMeter(48000,1);val stereo=AuralisLoudnessMeter(48000,2)
    for(i in 0 until 144000){val value=(.1*sin(2*PI*1000*i/48000)).toFloat();mono.accept(floatArrayOf(value));stereo.accept(floatArrayOf(value,value))}
    assertEquals(-23.0,mono.result().integratedLufs(),.2)
    assertEquals(3.0103,stereo.result().integratedLufs()-mono.result().integratedLufs(),.01)
    assertEquals(-20.0,mono.result().samplePeakDb(),.01)
  }

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

