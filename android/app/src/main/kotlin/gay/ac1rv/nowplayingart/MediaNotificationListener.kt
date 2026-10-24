package gay.ac1rv.nowplayingart

import android.app.Notification
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.zip.CRC32

class MediaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "notification listener connected")
        registerSessionListener()
        refreshActiveSessions()
        activeNotifications?.forEach { handleNotification(it) }
    }

    private fun refreshActiveSessions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, MediaNotificationListener::class.java)
        try {
            val controllers = msm.getActiveSessions(component)
            Log.i(TAG, "active media sessions: ${controllers.size}")
            controllers.forEach { publishFromController(it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "getActiveSessions denied", e)
        }
    }

    private fun publishFromController(controller: MediaController) {
        snapshotFromController(controller)?.let { pushTrack(it) }
        PlaybackHub.watch(this, controller)
    }

    private fun snapshotFromController(controller: MediaController): TrackSnapshot? =
        snapshotFromControllerStatic(controller)

    private fun registerSessionListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(this, MediaNotificationListener::class.java)
        try {
            msm.addOnActiveSessionsChangedListener({ controllers ->
                controllers?.forEach { publishFromController(it) }
            }, component)
        } catch (e: SecurityException) {
            Log.w(TAG, "session listener denied", e)
        }
    }

    private fun pushTrack(snap: TrackSnapshot) {
        NowPlayingArtApp.updateTrack(snap)
        try {
            PebbleBridge.sendTrack(this, snap)
        } catch (e: Exception) {
            Log.e(TAG, "sendTrack", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { handleNotification(it) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // keep last art on watch when playback stops
    }

    private fun handleNotification(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (n.category != Notification.CATEGORY_TRANSPORT) {
            return
        }

        val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
        }

        var title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        var artist = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        var art: Bitmap? = bitmapFromNotification(n)
        var controller: MediaController? = null

        if (token != null) {
            try {
                controller = MediaController(this, token)
                snapshotFromController(controller)?.let { snap ->
                    pushTrack(snap)
                    PlaybackHub.watch(this, controller)
                    return
                }
                val meta = controller.metadata
                if (meta != null) {
                    title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: title
                    artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                        ?: artist
                    art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                        ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: art
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaController", e)
            }
        }

        if (title.isBlank()) return
        val id = crcId("$title\u0000$artist")
        pushTrack(TrackSnapshot(id, title, artist, copyBitmap(art)))
        controller?.let { PlaybackHub.watch(this, it) }
    }

    private fun bitmapFromNotification(n: Notification): Bitmap? {
        val picture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            n.extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        } else {
            @Suppress("DEPRECATION")
            n.extras.getParcelable(Notification.EXTRA_PICTURE)
        }
        if (picture != null) return picture

        val large = n.getLargeIcon()
        if (large != null) return iconToBitmap(large)

        @Suppress("DEPRECATION")
        val bmp = n.largeIcon
        if (bmp != null) return bmp

        return null
    }

    private fun iconToBitmap(icon: Icon): Bitmap? {
        return try {
            val d = icon.loadDrawable(this) ?: return null
            if (d is BitmapDrawable) return d.bitmap
            val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 256
            val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 256
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            d.setBounds(0, 0, w, h)
            d.draw(canvas)
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "iconToBitmap", e)
            null
        }
    }

    companion object {
        private const val TAG = "NowPlayingArt"

        fun snapshotFromControllerStatic(controller: MediaController): TrackSnapshot? {
            val meta = controller.metadata ?: return null
            val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            if (title.isBlank()) return null
            val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
            val art = copyBitmap(
                meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
            )
            val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L)
            val state = controller.playbackState
            val position = state?.position?.coerceAtLeast(0L) ?: 0L
            val playing = state?.state == PlaybackState.STATE_PLAYING
            val year = yearFromMetadata(meta)
            val id = crcId("$title\u0000$artist")
            return TrackSnapshot(id, title, artist, art, duration, position, playing, year)
        }

        private fun yearFromMetadata(meta: MediaMetadata): Int {
            val y = meta.getLong(MediaMetadata.METADATA_KEY_YEAR).toInt()
            if (y in 1000..3000) return y
            val date = meta.getString(MediaMetadata.METADATA_KEY_DATE).orEmpty()
            val m = Regex("(19|20)\\d{2}").find(date)
            return m?.value?.toIntOrNull() ?: 0
        }

        private fun crcId(raw: String): String {
            val crc = CRC32()
            crc.update(raw.toByteArray())
            return crc.value.toString(16)
        }

        fun copyBitmap(src: Bitmap?): Bitmap? {
            if (src == null || src.isRecycled) return null
            return try {
                src.copy(Bitmap.Config.ARGB_8888, false)
            } catch (e: Exception) {
                Log.w(TAG, "copyBitmap", e)
                null
            }
        }
    }
}
