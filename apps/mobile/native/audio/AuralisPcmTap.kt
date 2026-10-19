package expo.modules.audio

import android.content.Context
import androidx.annotation.Keep
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/** Own-player decoded PCM only. No microphone, global audio capture, or waveform synthesis. */
object AuralisPcmBus {
  @Volatile var enabled = false
  @Volatile var onFrame: ((Map<String, Any>) -> Unit)? = null
}

@UnstableApi
class AuralisRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
  override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
    DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(AuralisPcmTap())).build()
}

@UnstableApi
@Keep // Keep the PCM processor identifiable in the final APK verification.
class AuralisPcmTap : BaseAudioProcessor() {
  private val samples = DoubleArray(1024)
  private val real = DoubleArray(1024)
  private val imaginary = DoubleArray(1024)
  private val window = DoubleArray(1024) { 0.5 - 0.5 * cos(2.0 * PI * it / 1023.0) }
  private var cursor = 0
  private var channels = 2
  private var sampleRate = 44100
  private var encoding = C.ENCODING_PCM_16BIT
  private var lastEmit = 0L

  override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
    sampleRate = inputAudioFormat.sampleRate; channels = inputAudioFormat.channelCount; encoding = inputAudioFormat.encoding
    return if (encoding == C.ENCODING_PCM_16BIT || encoding == C.ENCODING_PCM_FLOAT) inputAudioFormat else AudioFormat.NOT_SET
  }
  override fun queueInput(inputBuffer: ByteBuffer) {
    val size = inputBuffer.remaining()
    // DefaultAudioSink uses EMPTY_BUFFER while priming/draining the processor.
    // BaseAudioProcessor shares that zero-capacity buffer: copying it into itself
    // throws on Android. An empty packet must also leave pending output intact.
    if (size == 0) return
    if (AuralisPcmBus.enabled) {
      val data = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
      val bytes = (if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2) * channels
      while (data.remaining() >= bytes) {
        var mono = 0.0
        repeat(channels) { mono += if (encoding == C.ENCODING_PCM_FLOAT) data.float.toDouble() else data.short / 32768.0 }
        samples[cursor++] = mono / channels
        if (cursor == 1024) { cursor = 0; analyze() }
      }
    }
    // Copy the original bytes unchanged into the output path.
    replaceOutputBuffer(size).put(inputBuffer).flip()
  }
  private fun analyze() {
    val now = System.nanoTime()
    if (now - lastEmit < 33_000_000L) return
    lastEmit = now
    var energy = 0.0; var peak = 0.0
    for (i in samples.indices) { energy += samples[i] * samples[i]; peak = max(peak, abs(samples[i])); real[i] = samples[i] * window[i]; imaginary[i] = 0.0 }
    var j = 0
    for (i in 1 until 1024) {
      var bit = 512
      while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
      j = j xor bit
      if (i < j) { val temp = real[i]; real[i] = real[j]; real[j] = temp }
    }
    var length = 2
    while (length <= 1024) {
      val angle = -2.0 * PI / length
      for (start in 0 until 1024 step length) {
        for (k in 0 until length / 2) {
          val a = start + k; val b = a + length / 2
          val wr = cos(angle * k); val wi = sin(angle * k)
          val tr = wr * real[b] - wi * imaginary[b]; val ti = wr * imaginary[b] + wi * real[b]
          real[b] = real[a] - tr; imaginary[b] = imaginary[a] - ti; real[a] += tr; imaginary[a] += ti
        }
      }
      length *= 2
    }
    val limit = min(20000.0, sampleRate / 2.0)
    val bands = (0 until 32).map { band ->
      val from = max(1, (20.0 * (limit / 20.0).pow(band / 32.0) * 1024.0 / sampleRate).toInt())
      val until = min(512, max(from + 1, (20.0 * (limit / 20.0).pow((band + 1) / 32.0) * 1024.0 / sampleRate).toInt()))
      var magnitude = 0.0
      for (bin in from until until) magnitude = max(magnitude, hypot(real[bin], imaginary[bin]) * 4.0 / 1024.0)
      ((20.0 * log10(max(1e-8, magnitude)) + 80.0) / 80.0).coerceIn(0.0, 1.0)
    }
    AuralisPcmBus.onFrame?.invoke(mapOf("bands" to bands, "rms" to sqrt(energy / 1024.0), "peak" to peak, "sampleRate" to sampleRate, "windowSize" to 1024))
  }
  override fun onFlush() { cursor = 0; lastEmit = 0 }
}
