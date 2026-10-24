package gay.ac1rv.nowplayingart

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.min

/**
 * Encodes album art as an indexed PNG (64-color Pebble palette).
 * Typical size is 2–8KB instead of 38KB of raw pixels.
 */
object ArtEncoder {
    const val TARGET_W = 200
    const val TARGET_H = 206
    const val CHUNK_BYTES = 400
    const val MAX_PNG_BYTES = 12_000

    fun encodeCover(source: Bitmap?): ByteArray? {
        if (source == null || source.isRecycled || source.width < 2 || source.height < 2) {
            return null
        }
        val full = encodeScaled(source, TARGET_W, TARGET_H) ?: return null
        if (full.size <= MAX_PNG_BYTES) return full
        val smaller = encodeScaled(source, 140, 160)
        return when {
            smaller != null && smaller.size <= MAX_PNG_BYTES -> smaller
            smaller != null && smaller.size < full.size -> smaller
            else -> full
        }
    }

    private fun encodeScaled(source: Bitmap, w: Int, h: Int): ByteArray? {
        val scaled = scaleCover(source, w, h)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== source) scaled.recycle()
        val index = ByteArray(w * h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            index[i] = (((r shr 6) shl 4) or ((g shr 6) shl 2) or (b shr 6)).toByte()
        }
        return IndexedPng.encode(w, h, index)
    }

    private fun scaleCover(src: Bitmap, dw: Int, dh: Int): Bitmap {
        val scale = min(dw.toFloat() / src.width, dh.toFloat() / src.height)
        val sw = src.width * scale
        val sh = src.height * scale
        val dx = (dw - sw) / 2f
        val dy = (dh - sh) / 2f
        val out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(0xFF000000.toInt())
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
        canvas.drawBitmap(src, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun chunkCount(dataSize: Int): Int =
        (dataSize + CHUNK_BYTES - 1) / CHUNK_BYTES

    fun chunkAt(data: ByteArray, index: Int): ByteArray {
        val start = index * CHUNK_BYTES
        val end = minOf(start + CHUNK_BYTES, data.size)
        return data.copyOfRange(start, end)
    }
}

/** Minimal PNG8 encoder using the 64-color Pebble RGB palette. */
private object IndexedPng {
    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    fun encode(width: Int, height: Int, indices: ByteArray): ByteArray? {
        if (indices.size != width * height) return null
        val plte = ByteArray(64 * 3)
        for (i in 0 until 64) {
            plte[i * 3] = (((i shr 4) and 3) * 85).toByte()
            plte[i * 3 + 1] = (((i shr 2) and 3) * 85).toByte()
            plte[i * 3 + 2] = ((i and 3) * 85).toByte()
        }
        val raw = ByteArray((width + 1) * height)
        for (y in 0 until height) {
            raw[y * (width + 1)] = 0
            System.arraycopy(indices, y * width, raw, y * (width + 1) + 1, width)
        }
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw)
        deflater.finish()
        val zipped = ByteArrayOutputStream()
        val tmp = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(tmp)
            if (n > 0) zipped.write(tmp, 0, n)
        }
        deflater.end()

        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        writeChunk(out, "IHDR", ihdr(width, height))
        writeChunk(out, "PLTE", plte)
        writeChunk(out, "IDAT", zipped.toByteArray())
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun ihdr(width: Int, height: Int): ByteArray {
        val d = ByteArray(13)
        putInt(d, 0, width)
        putInt(d, 4, height)
        d[8] = 8
        d[9] = 3
        return d
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val len = ByteArray(4)
        putInt(len, 0, data.size)
        out.write(len)
        out.write(typeBytes)
        out.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        val crcBytes = ByteArray(4)
        putInt(crcBytes, 0, crc.value.toInt())
        out.write(crcBytes)
    }

    private fun putInt(dest: ByteArray, offset: Int, value: Int) {
        dest[offset] = (value ushr 24).toByte()
        dest[offset + 1] = (value ushr 16).toByte()
        dest[offset + 2] = (value ushr 8).toByte()
        dest[offset + 3] = value.toByte()
    }
}
