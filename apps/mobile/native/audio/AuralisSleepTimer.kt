package expo.modules.audio

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer

/** Native deadline keeps working while the React Native thread is suspended. */
class AuralisSleepTimer(private val player: ExoPlayer) {
  private val handler = Handler(Looper.getMainLooper())
  private var deadline = 0L
  val active get() = deadline > 0L
  var onStart: (() -> Unit)? = null
  private var originalVolume: Float? = null
  private val tick = object : Runnable {
    override fun run() {
      val remaining = deadline - SystemClock.elapsedRealtime()
      if (remaining <= 0) { player.pause(); cancel(); return }
      if (remaining <= 10000) {
        if (originalVolume == null) originalVolume = player.volume
        player.volume = originalVolume!! * (remaining / 10000f).coerceIn(0f, 1f)
      }
      handler.postDelayed(this, 200)
    }
  }
  fun set(seconds: Double) {
    require(seconds.isFinite() && seconds >= 0 && seconds <= 86400)
    if(seconds > 0) onStart?.invoke()
    cancel()
    if (seconds > 0) { deadline = SystemClock.elapsedRealtime() + (seconds * 1000).toLong(); handler.post(tick) }
  }
  fun cancel() { handler.removeCallbacks(tick); originalVolume?.let { player.volume = it }; originalVolume = null; deadline = 0 }
}
