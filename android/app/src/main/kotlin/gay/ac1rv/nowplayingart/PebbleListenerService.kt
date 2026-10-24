package gay.ac1rv.nowplayingart

import android.util.Log
import io.rebble.pebblekit2.client.BasePebbleListenerService
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import java.util.UUID

class PebbleListenerService : BasePebbleListenerService() {

    override suspend fun onMessageReceived(
        watchappUUID: UUID,
        data: PebbleDictionary,
        watch: WatchIdentifier,
    ): ReceiveResult {
        if (watchappUUID != PebbleBridge.WATCH_UUID) {
            return ReceiveResult.Nack
        }
        if (data.containsKey(AppKeys.IMAGE_COMPLETE.toUInt())) {
            PebbleBridge.onImageComplete(-1)
        }
        if (data.containsKey(AppKeys.REQUEST_REFRESH.toUInt())) {
            Log.i(TAG, "watch requested refresh")
            MediaRefresh.pushNow(applicationContext, force = true)
        }
        return ReceiveResult.Ack
    }

    override fun onAppOpened(watchappUUID: UUID, watch: WatchIdentifier) {
        if (watchappUUID != PebbleBridge.WATCH_UUID) return
        Log.i(TAG, "watchface opened — pushing art")
        MediaRefresh.pushNow(applicationContext, force = true)
    }

    companion object {
        private const val TAG = "NowPlayingArt"
    }
}
