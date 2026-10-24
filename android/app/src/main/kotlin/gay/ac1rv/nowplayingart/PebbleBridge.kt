package gay.ac1rv.nowplayingart

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

object PebbleBridge {
    private const val TAG = "PebbleBridge"
    val WATCH_UUID: UUID = UUID.fromString("59a19257-c680-4ca1-9608-f98ba0471d46")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var sender: DefaultPebbleSender? = null
    private val generation = AtomicInteger(0)
    @Volatile private var sending = false
    @Volatile private var lastArtId: String? = null
    @Volatile private var lastTitle: String = ""
    @Volatile private var lastArtist: String = ""
    @Volatile private var lastYear: Int = 0
    @Volatile private var workerStarted = false
    @Volatile private var pendingImage: Work.Image? = null
    @Volatile private var pendingPlayback: Work.Playback? = null

    private sealed class Work {
        data class Image(
            val id: String,
            val title: String,
            val artist: String,
            val year: Int,
            val png: ByteArray,
            val positionMs: Long,
            val durationMs: Long,
            val playing: Boolean,
        ) : Work()

        data class Playback(
            val positionMs: Long,
            val durationMs: Long,
            val playing: Boolean,
        ) : Work()
    }

    fun isTransferringArt(): Boolean = sending

    fun onImageComplete(seq: Int) {
        Log.i(TAG, "watch confirmed seq=$seq")
    }

    fun init(context: Context) {
        val app = context.applicationContext
        if (sender == null) {
            sender = DefaultPebbleSender(app)
            Log.i(TAG, "PebbleKit2 sender ready")
        }
        ClassicPebbleBridge.init(app)
        startWorker(app)
    }

    private fun startWorker(appContext: Context) {
        if (workerStarted) return
        workerStarted = true
        scope.launch {
            for (ignored in wake) {
                try {
                    while (true) {
                        val img = pendingImage ?: break
                        pendingImage = null
                        runImage(appContext, img)
                    }
                    val pb = pendingPlayback
                    pendingPlayback = null
                    if (pb != null) {
                        sendPlaybackNow(
                            appContext,
                            pb.positionMs,
                            pb.durationMs,
                            pb.playing,
                            lastTitle,
                            lastArtist,
                            lastYear,
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "worker", e)
                    sending = false
                }
            }
        }
    }

    fun sendPlayback(context: Context, positionMs: Long, durationMs: Long, playing: Boolean) {
        if (sending) return
        init(context)
        pendingPlayback = Work.Playback(positionMs, durationMs, playing)
        wake.trySend(Unit)
    }

    fun sendTrack(context: Context, track: TrackSnapshot, force: Boolean = false) {
        init(context)
        if (track.id == lastArtId && sending) {
            Log.d(TAG, "already sending ${track.title}")
            return
        }
        if (!force && track.id == lastArtId) return
        lastTitle = track.title
        lastArtist = track.artist
        lastYear = track.year
        val art = track.art
        if (art == null || art.isRecycled) {
            Log.w(TAG, "no art for \"${track.title}\"")
            sendPlayback(context, track.positionMs, track.durationMs, track.playing)
            return
        }
        val copy: Bitmap = try {
            art.copy(Bitmap.Config.ARGB_8888, false) ?: art
        } catch (e: Exception) {
            Log.w(TAG, "art copy", e)
            return
        }
        scope.launch {
            val png = try {
                ArtEncoder.encodeCover(copy)
            } catch (e: Exception) {
                Log.w(TAG, "png encode", e)
                null
            }
            if (copy !== art && !copy.isRecycled) copy.recycle()
            if (png == null) {
                Log.w(TAG, "png encode failed for \"${track.title}\"")
                return@launch
            }
            Log.i(TAG, "png ${png.size}B for \"${track.title}\" (${ArtEncoder.chunkCount(png.size)} chunks)")
            pendingImage = Work.Image(
                id = track.id,
                title = track.title,
                artist = track.artist,
                year = track.year,
                png = png,
                positionMs = track.positionMs,
                durationMs = track.durationMs,
                playing = track.playing,
            )
            wake.trySend(Unit)
        }
    }

    private suspend fun runImage(appContext: Context, item: Work.Image) {
        sending = true
        val seq = generation.incrementAndGet()
        Log.i(TAG, "send png \"${item.title}\" seq=$seq ${item.png.size}B")
        var ok = sendPngKit2(item.png, seq)
        if (!ok) {
            Log.i(TAG, "kit2 png failed; classic fallback")
            ok = ClassicPebbleBridge.sendImage(appContext, item.png, seq)
        }
        sending = false
        if (ok) {
            lastArtId = item.id
            lastTitle = item.title
            lastArtist = item.artist
            lastYear = item.year
            sendPlaybackNow(
                appContext,
                item.positionMs,
                item.durationMs,
                item.playing,
                item.title,
                item.artist,
                item.year,
            )
        }
    }

    private suspend fun sendPlaybackNow(
        appContext: Context,
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
        title: String = lastTitle,
        artist: String = lastArtist,
        year: Int = lastYear,
    ) {
        val pos = positionMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val dur = durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val s = sender
        var kit2Ok = false
        if (s != null) {
            val dict: PebbleDictionary = mapOf(
                AppKeys.TRACK_POSITION_MS.toUInt() to PebbleDictionaryItem.Int32(pos),
                AppKeys.TRACK_DURATION_MS.toUInt() to PebbleDictionaryItem.Int32(dur),
                AppKeys.TRACK_PLAYING.toUInt() to PebbleDictionaryItem.Int32(if (playing) 1 else 0),
                AppKeys.TRACK_YEAR.toUInt() to PebbleDictionaryItem.Int32(year),
                AppKeys.TRACK_TITLE.toUInt() to PebbleDictionaryItem.Text(title.take(48)),
                AppKeys.TRACK_ARTIST.toUInt() to PebbleDictionaryItem.Text(artist.take(48)),
            )
            kit2Ok = s.sendDataToPebble(WATCH_UUID, dict).toString()
                .contains("Success", ignoreCase = true)
        }
        if (!kit2Ok) {
            ClassicPebbleBridge.sendPlayback(appContext, pos, dur, playing, title, artist, year)
        }
    }

    private suspend fun sendPngKit2(
        png: ByteArray,
        seq: Int,
        sendHeader: Boolean = true,
    ): Boolean {
        val s = sender ?: return false
        val chunks = ArtEncoder.chunkCount(png.size)
        if (sendHeader) {
            val header: PebbleDictionary = mapOf(
                AppKeys.IMAGE_DATA_SIZE.toUInt() to PebbleDictionaryItem.Int32(png.size),
                AppKeys.IMAGE_CHUNKS_TOTAL.toUInt() to PebbleDictionaryItem.Int32(chunks),
                AppKeys.IMAGE_SEQ.toUInt() to PebbleDictionaryItem.Int32(seq),
            )
            val result = s.sendDataToPebble(WATCH_UUID, header)
            Log.i(TAG, "kit2 png header seq=$seq chunks=$chunks: $result")
            if (!result.toString().contains("Success", ignoreCase = true)) {
                return false
            }
            delay(80)
        }
        for (i in 0 until chunks) {
            val chunk = ArtEncoder.chunkAt(png, i)
            val dict: PebbleDictionary = mapOf(
                AppKeys.IMAGE_CHUNK_INDEX.toUInt() to PebbleDictionaryItem.Int32(i),
                AppKeys.IMAGE_CHUNK_DATA.toUInt() to PebbleDictionaryItem.Bytes(chunk),
                AppKeys.IMAGE_SEQ.toUInt() to PebbleDictionaryItem.Int32(seq),
            )
            val result = s.sendDataToPebble(WATCH_UUID, dict)
            if (!result.toString().contains("Success", ignoreCase = true)) {
                Log.w(TAG, "kit2 png chunk $i/$chunks failed: $result")
                return false
            }
            delay(60)
        }
        return true
    }
}
