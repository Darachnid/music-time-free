package gay.ac1rv.nowplayingart

import android.content.Context
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Polls the active MediaController and pushes playback position to the watch. */
object PlaybackHub {
    private const val TAG = "PlaybackHub"
    private const val TICK_MS = 2_000L

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var controller: MediaController? = null
    private var callback: MediaController.Callback? = null

    private val tick = object : Runnable {
        override fun run() {
            val ctx = appContext ?: return
            pushNow(ctx)
            val playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
            if (playing) {
                main.postDelayed(this, TICK_MS)
            }
        }
    }

    fun watch(context: Context, mediaController: MediaController) {
        appContext = context.applicationContext
        if (controller != mediaController) {
            detachCallback()
            controller = mediaController
            attachCallback(mediaController)
        }
        pushNow(context.applicationContext)
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun attachCallback(c: MediaController) {
        val cb = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                appContext?.let { pushNow(it) }
                if (state?.state == PlaybackState.STATE_PLAYING) {
                    main.removeCallbacks(tick)
                    main.post(tick)
                }
            }

            override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
                val ctx = appContext ?: return
                MediaNotificationListener.snapshotFromControllerStatic(c)?.let { snap ->
                    NowPlayingArtApp.updateTrack(snap)
                    PebbleBridge.sendTrack(ctx, snap)
                }
                pushNow(ctx)
            }
        }
        callback = cb
        c.registerCallback(cb)
    }

    private fun detachCallback() {
        val c = controller
        val cb = callback
        if (c != null && cb != null) {
            c.unregisterCallback(cb)
        }
        callback = null
    }

    private fun pushNow(context: Context) {
        if (PebbleBridge.isTransferringArt()) {
            return
        }
        val c = controller ?: return
        val meta = c.metadata
        val state = c.playbackState
        val duration = meta?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val position = state?.position ?: 0L
        val playing = state?.state == PlaybackState.STATE_PLAYING
        PebbleBridge.sendPlayback(context, position.coerceAtLeast(0L), duration.coerceAtLeast(0L), playing)
    }
}
