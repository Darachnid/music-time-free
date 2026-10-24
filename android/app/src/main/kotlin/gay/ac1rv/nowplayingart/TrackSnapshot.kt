package gay.ac1rv.nowplayingart

import android.graphics.Bitmap

data class TrackSnapshot(
    val id: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val playing: Boolean = false,
    val year: Int = 0,
)
