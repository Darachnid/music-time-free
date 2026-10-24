package gay.ac1rv.nowplayingart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.getpebble.android.kit.PebbleKit
import com.getpebble.android.kit.util.PebbleDictionary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Fallback for the official Core Pebble app and other classic PebbleKit hosts. */
object ClassicPebbleBridge {
    private const val TAG = "ClassicPebble"
    private const val ACTION_RECEIVE = "com.getpebble.action.app.RECEIVE"
    private const val ACTION_ACK = "com.getpebble.action.app.RECEIVE_ACK"
    private const val ACTION_NACK = "com.getpebble.action.app.RECEIVE_NACK"
    private const val EXTRA_TRANSACTION_ID = "transaction_id"

    private var receiverRegistered = false
    private var ackRegistered = false
    private val nextTid = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()

    fun init(context: Context) {
        try {
            ensureAckHandlers(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "init", e)
        }
    }

    fun ensureWatchReceiver(context: Context, onRefresh: () -> Unit) {
        if (receiverRegistered) return
        try {
            ensureAckHandlers(context.applicationContext)
            val receiver = object : PebbleKit.PebbleDataReceiver(PebbleBridge.WATCH_UUID) {
                override fun receiveData(ctx: Context, transactionId: Int, data: PebbleDictionary) {
                    PebbleKit.sendAckToPebble(ctx, transactionId)
                    if (data.contains(AppKeys.IMAGE_COMPLETE)) {
                        val seq = try {
                            data.getInteger(AppKeys.IMAGE_COMPLETE).toInt()
                        } catch (_: Exception) {
                            -1
                        }
                        PebbleBridge.onImageComplete(seq)
                    }
                    if (data.contains(AppKeys.REQUEST_REFRESH)) {
                        onRefresh()
                    }
                }
            }
            ContextCompat.registerReceiver(
                context.applicationContext,
                receiver,
                IntentFilter(ACTION_RECEIVE),
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true
            Log.i(TAG, "classic watch→phone receiver registered")
        } catch (e: Exception) {
            receiverRegistered = false
            Log.e(TAG, "ensureWatchReceiver", e)
        }
    }

    private fun ensureAckHandlers(context: Context) {
        if (ackRegistered) return
        val app = context.applicationContext
        val ack = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val tid = intent.getIntExtra(EXTRA_TRANSACTION_ID, -1)
                pending.remove(tid)?.complete(true)
            }
        }
        val nack = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val tid = intent.getIntExtra(EXTRA_TRANSACTION_ID, -1)
                pending.remove(tid)?.complete(false)
            }
        }
        ContextCompat.registerReceiver(app, ack, IntentFilter(ACTION_ACK), ContextCompat.RECEIVER_EXPORTED)
        ContextCompat.registerReceiver(app, nack, IntentFilter(ACTION_NACK), ContextCompat.RECEIVER_EXPORTED)
        ackRegistered = true
    }

    private fun nextTransactionId(): Int {
        var id: Int
        do {
            id = nextTid.updateAndGet { prev -> if (prev >= 31) 1 else prev + 1 }
        } while (pending.containsKey(id))
        return id
    }

    private suspend fun sendDict(context: Context, dict: PebbleDictionary): Boolean {
        val tid = nextTransactionId()
        val deferred = CompletableDeferred<Boolean>()
        pending[tid] = deferred
        try {
            PebbleKit.sendDataToPebbleWithTransactionId(
                context.applicationContext,
                PebbleBridge.WATCH_UUID,
                dict,
                tid,
            )
        } catch (e: Exception) {
            pending.remove(tid)
            Log.e(TAG, "send failed", e)
            return false
        }
        // Core Pebble often never ACKs third-party apps. Treat timeout as delivered.
        val ok = withTimeoutOrNull(250) { deferred.await() }
        pending.remove(tid)
        return ok != false
    }

    suspend fun sendImage(
        context: Context,
        artBytes: ByteArray,
        seq: Int,
    ): Boolean {
        val appContext = context.applicationContext
        val chunks = ArtEncoder.chunkCount(artBytes.size)
        val header = PebbleDictionary().apply {
            addInt32(AppKeys.IMAGE_WIDTH, ArtEncoder.TARGET_W)
            addInt32(AppKeys.IMAGE_HEIGHT, ArtEncoder.TARGET_H)
            addInt32(AppKeys.IMAGE_DATA_SIZE, artBytes.size)
            addInt32(AppKeys.IMAGE_CHUNKS_TOTAL, chunks)
            addInt32(AppKeys.IMAGE_SEQ, seq)
        }
        if (!sendDict(appContext, header)) {
            Log.e(TAG, "classic header nack seq=$seq")
            return false
        }
        delay(80)
        for (i in 0 until chunks) {
            val chunk = ArtEncoder.chunkAt(artBytes, i)
            val dict = PebbleDictionary().apply {
                addInt32(AppKeys.IMAGE_CHUNK_INDEX, i)
                addBytes(AppKeys.IMAGE_CHUNK_DATA, chunk)
                addInt32(AppKeys.IMAGE_SEQ, seq)
            }
            if (!sendDict(appContext, dict)) {
                Log.e(TAG, "classic chunk $i nack")
                return false
            }
            delay(40)
        }
        Log.i(TAG, "classic transfer complete seq=$seq")
        return true
    }

    suspend fun sendPlayback(
        context: Context,
        positionMs: Int,
        durationMs: Int,
        playing: Boolean,
        title: String = "",
        artist: String = "",
        year: Int = 0,
    ) {
        val dict = PebbleDictionary().apply {
            addInt32(AppKeys.TRACK_POSITION_MS, positionMs)
            addInt32(AppKeys.TRACK_DURATION_MS, durationMs)
            addInt32(AppKeys.TRACK_PLAYING, if (playing) 1 else 0)
            addInt32(AppKeys.TRACK_YEAR, year)
            if (title.isNotBlank()) addString(AppKeys.TRACK_TITLE, title.take(48))
            if (artist.isNotBlank()) addString(AppKeys.TRACK_ARTIST, artist.take(48))
        }
        sendDict(context.applicationContext, dict)
    }
}
