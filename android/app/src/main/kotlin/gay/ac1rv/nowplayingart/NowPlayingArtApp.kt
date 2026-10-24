package gay.ac1rv.nowplayingart

import android.app.Application
import android.util.Log

class NowPlayingArtApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            PebbleBridge.init(this)
            PebbleAppSetup.bootstrap(this)
        } catch (e: Exception) {
            Log.e(TAG, "startup init", e)
        }
    }

    companion object {
        private const val TAG = "NowPlayingArt"
        @Volatile var instance: NowPlayingArtApp? = null
        @Volatile var lastTrack: TrackSnapshot? = null

        fun updateTrack(track: TrackSnapshot) {
            val prev = lastTrack
            lastTrack = track
            val prevArt = prev?.art
            if (prevArt != null && prevArt !== track.art && !prevArt.isRecycled) {
                try {
                    prevArt.recycle()
                } catch (_: Exception) {
                }
            }
        }
    }
}
