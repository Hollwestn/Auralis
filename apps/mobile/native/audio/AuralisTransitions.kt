package expo.modules.audio
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlin.math.*

/** Native preloading/fades use player media positions, including while JS is suspended. */
@UnstableApi
class AuralisTransitions(private val context: Context, private val main: ExoPlayer, private val data: DataSource.Factory, private val item: (Int) -> MediaItem?, private val sleepActive: () -> Boolean) : Player.Listener {
  private val handler = Handler(Looper.getMainLooper())
  private var seconds = 0.0
  private var gains = emptyList<Double>()
  private var blocked = emptyList<Boolean>()
  private var secondary: ExoPlayer? = null
  private var target = -1
  private var start = -1L
  private var handover = -1L
  private var baseVolume = 1f
  private var closed = false
  private val tick = object : Runnable { override fun run() { update(); if (!closed && seconds > 0 && main.playWhenReady) handler.postDelayed(this, if(sleepActive()) 500 else 20) } }
  init { main.addListener(this) }
  fun configure(fade: Double, levels: List<Double>, disabled: List<Boolean>) {
    if (abs(seconds - fade) > 0.001) cancelFade()
    seconds = fade.coerceIn(0.0, 12.0); gains = levels.map { if (it.isFinite()) it.coerceIn(-24.0,12.0) else 0.0 }; blocked = disabled
    applyGain(); schedule()
  }
  private fun gain(index: Int) = 10.0.pow((gains.getOrNull(index) ?: 0.0) / 20.0)
  private fun applyGain() { AuralisPcmBus.gain = gain(main.currentMediaItemIndex) }
  private fun schedule() { handler.removeCallbacks(tick); if (!closed && seconds > 0 && main.playWhenReady) handler.post(tick) }
  override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!playWhenReady) cancelFade(); schedule() }
  override fun onTimelineChanged(timeline: Timeline, reason: Int) { if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) cancelFade(); applyGain() }
  override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
    applyGain()
    val next = secondary
    if (next != null && start >= 0 && main.currentMediaItemIndex == target) { main.volume = 0f; main.seekTo(target, next.currentPosition); handover = SystemClock.elapsedRealtime() }
    else cancelFade()
    schedule()
  }
  override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) { if (reason == Player.DISCONTINUITY_REASON_SEEK && handover < 0) cancelFade() }
  private fun update() {
    if (closed || !main.playWhenReady || sleepActive()) { cancelFade(); return }
    val shadow = secondary
    if (handover >= 0 && shadow != null) {
      if (main.playbackState != Player.STATE_READY) return
      val mix = ((SystemClock.elapsedRealtime() - handover) / 80f).coerceIn(0f,1f)
      main.volume = baseVolume * mix; shadow.volume = baseVolume * (1-mix)
      if (mix >= 1) cancelFade()
      return
    }
    if (start < 0) baseVolume = main.volume
    val index = main.currentMediaItemIndex
    if (main.repeatMode == Player.REPEAT_MODE_ONE || !main.hasNextMediaItem() || main.duration <= 0 || main.duration == androidx.media3.common.C.TIME_UNSET) { cancelFade(); return }
    val nextIndex = main.nextMediaItemIndex
    if (blocked.getOrNull(index) == true || blocked.getOrNull(nextIndex) == true) { cancelFade(); return }
    val fade = min(seconds * 1000, main.duration / 2.0).toLong()
    if (fade < 100) return
    val remaining = main.duration - main.currentPosition
    if (secondary == null && remaining <= fade + 3000) {
      val source = item(nextIndex) ?: return
      target = nextIndex
      secondary = ExoPlayer.Builder(context, AuralisRenderersFactory(context, { gain(target) }, false))
        .setLooper(context.mainLooper).setAudioAttributes(AudioAttributes.DEFAULT, false)
        .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(data)).build().also {
          it.volume = 0f; it.setMediaItem(source); it.prepare()
        }
    }
    val prepared = secondary ?: return
    if (prepared.playbackState != Player.STATE_READY) return
    if (start < 0 && remaining <= fade && remaining > 60) { start = main.currentPosition; prepared.setPlaybackSpeed(main.playbackParameters.speed); prepared.play() }
    if (start >= 0) { val progress=((main.currentPosition-start).toDouble()/max(1.0,(main.duration-start).toDouble())).coerceIn(0.0,1.0).toFloat();main.volume=baseVolume*(1-progress);prepared.volume=baseVolume*progress }
  }
  fun volume(value: Float) { baseVolume=value.coerceIn(0f,1f);if(start<0&&handover<0)main.volume=baseVolume }
  fun cancelFade() { val active=secondary!=null||start>=0||handover>=0;secondary?.release();secondary=null;target=-1;start=-1;handover=-1;if(active)main.volume=baseVolume }
  fun close() { closed=true;handler.removeCallbacks(tick);cancelFade();main.removeListener(this);AuralisPcmBus.gain=1.0 }
}
