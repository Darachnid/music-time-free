package gay.ac1rv.nowplayingart

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import android.media.session.MediaSessionManager

/** Push the current media session to the watch (used on app open, session changes, etc.). */
object MediaRefresh {
    private const val TAG = "MediaRefresh"

    fun pushNow(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val msm = app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(app, MediaNotificationListener::class.java)
        try {
            val controllers = msm.getActiveSessions(component)
            if (controllers.isEmpty()) {
                Log.d(TAG, "no active sessions")
                NowPlayingArtApp.lastTrack?.let { PebbleBridge.sendTrack(app, it, force) }
                return
            }
            controllers.forEach { controller ->
                MediaNotificationListener.snapshotFromControllerStatic(controller)?.let { snap ->
                    NowPlayingArtApp.updateTrack(snap)
                    PebbleBridge.sendTrack(app, snap, force)
                }
                PlaybackHub.watch(app, controller)
            }
        } catch (e: Exception) {
            Log.w(TAG, "pushNow", e)
            NowPlayingArtApp.lastTrack?.let { PebbleBridge.sendTrack(app, it, force) }
        }
    }
}
