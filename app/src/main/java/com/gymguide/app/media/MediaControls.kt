package com.gymguide.app.media
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.KeyEvent
import androidx.core.app.NotificationManagerCompat

/**
 * Empty listener: its only job is to let Android grant GymCue access to active media sessions
 * (the same data Spotify shows in the notification shade / lock screen). No notifications are read or stored.
 */
class MediaListenerService : NotificationListenerService()

data class NowPlaying(val title: String = "", val artist: String = "", val album: String = "",
                      val art: Bitmap? = null, val playing: Boolean = false, val app: String = "")

/**
 * Controls Spotify (or whatever is playing) without leaving GymCue.
 * Uses Android MediaSession APIs, so no Spotify developer account/SDK is required.
 */
class MediaRemote(private val ctx: Context) {
  companion object { const val SPOTIFY = "com.spotify.music" }
  private val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
  private val component = ComponentName(ctx, MediaListenerService::class.java)
  private var controller: MediaController? = null
  private var onChange: ((NowPlaying?) -> Unit)? = null

  fun hasAccess() = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
  fun openAccessSettings() = ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  fun spotifyInstalled() = ctx.packageManager.getLaunchIntentForPackage(SPOTIFY) != null
  fun openSpotify() { ctx.packageManager.getLaunchIntentForPackage(SPOTIFY)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

  private val cb = object : MediaController.Callback() {
    override fun onMetadataChanged(metadata: MediaMetadata?) = emit()
    override fun onPlaybackStateChanged(state: PlaybackState?) = emit()
    override fun onSessionDestroyed() { pick(); emit() }
  }
  private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(); emit() }

  fun start(listener: (NowPlaying?) -> Unit) {
    onChange = listener
    if (!hasAccess()) { listener(null); return }
    runCatching { msm.addOnActiveSessionsChangedListener(sessionsListener, component) }
    pick(); emit()
  }
  fun stop() {
    runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
    controller?.unregisterCallback(cb); controller = null; onChange = null
  }

  private fun pick() {
    val list = runCatching { msm.getActiveSessions(component) }.getOrDefault(emptyList())
    val next = list.firstOrNull { it.packageName == SPOTIFY } ?: list.firstOrNull()
    if (next?.sessionToken != controller?.sessionToken) {
      controller?.unregisterCallback(cb); controller = next; next?.registerCallback(cb)
    }
  }
  private fun emit() {
    val c = controller ?: return onChange?.invoke(NowPlaying()) ?: Unit
    val m = c.metadata
    onChange?.invoke(NowPlaying(
      title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "",
      artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "",
      album = m?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "",
      art = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART),
      playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
      app = if (c.packageName == SPOTIFY) "Spotify" else c.packageName))
  }

  fun playPause() { val c = controller
    if (c == null) { mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY); return }
    if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play() }
  fun next() { controller?.transportControls?.skipToNext() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }
  fun previous() { controller?.transportControls?.skipToPrevious() ?: mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }

  /** Fallback when no session is visible yet (e.g. access not granted): send a hardware media key. */
  private fun mediaKey(code: Int) {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
  }
}
