package expo.modules.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/** Full-file analysis off the UI thread, never microphone/system playback capture. */
object AuralisLoudness {
  fun analyze(context: Context, uri: String): Map<String, Double> {
    val source = Uri.parse(uri)
    require(source.scheme == "file" || source.scheme == "content") { "Only a selected local file can be analyzed" }
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
      extractor.setDataSource(context, source, null)
      val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: error("No audio stream")
      extractor.selectTrack(index)
      val format = extractor.getTrackFormat(index)
      codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
      codec.configure(format, null, null, 0); codec.start()
      var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
      var meter = AuralisLoudnessMeter(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels)
      var frame = FloatArray(channels)
      var encoding = 2
      var inputDone = false
      var lastProgress = System.nanoTime()
      val info = MediaCodec.BufferInfo()
      while (true) {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Analysis cancelled")
        if (System.nanoTime() - lastProgress > 60_000_000_000L) error("Decoder stopped producing audio")
        if (!inputDone) {
          val inputIndex = codec.dequeueInputBuffer(10000)
          if (inputIndex >= 0) {
            val buffer = codec.getInputBuffer(inputIndex)!!
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) { codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
            else { codec.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0); extractor.advance() }
          }
        }
        val outputIndex = codec.dequeueOutputBuffer(info, 10000)
        if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
          val output = codec.outputFormat
          channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
          meter = AuralisLoudnessMeter(output.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels)
          frame = FloatArray(channels)
          encoding = if (output.containsKey("pcm-encoding")) output.getInteger("pcm-encoding") else 2
        } else if (outputIndex >= 0) {
          val data = codec.getOutputBuffer(outputIndex)!!.duplicate().order(ByteOrder.LITTLE_ENDIAN)
          data.position(info.offset); data.limit(info.offset + info.size)
          val bytes = when (encoding) { 4, 22 -> 4; 21 -> 3; else -> 2 }
          while (data.remaining() >= bytes * channels) {
            for (c in 0 until channels) frame[c] = when (encoding) {
              4 -> data.float
              22 -> (data.int / 2147483648.0).toFloat()
              21 -> { val a=data.get().toInt() and 255; val b=data.get().toInt() and 255; val d=data.get().toInt(); ((a or (b shl 8) or (d shl 16)) / 8388608f) }
              else -> data.short / 32768f
            }
            meter.accept(frame)
          }
          if (info.size > 0) lastProgress = System.nanoTime()
          codec.releaseOutputBuffer(outputIndex, false)
          if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
      }
      val result = meter.result()
      return mapOf("loudnessLufs" to result.integratedLufs(), "samplePeakDb" to result.samplePeakDb())
    } finally { try { codec?.stop() } catch (_: Exception) {} ; codec?.release(); extractor.release() }
  }
}
