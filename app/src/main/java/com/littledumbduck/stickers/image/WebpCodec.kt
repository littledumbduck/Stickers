package com.littledumbduck.stickers.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lectura y escritura de WebP animado. Android sabe codificar y decodificar WebP de una sola
 * imagen, pero no da acceso a los fotogramas de uno animado ni sabe crearlo. Aquí se hace a mano
 * el contenedor RIFF (chunks VP8X / ANIM / ANMF) reutilizando el códec de Android para cada
 * fotograma.
 *
 * Formato: https://developers.google.com/speed/webp/docs/riff_container
 */
object WebpCodec {

    data class Info(val width: Int, val height: Int, val durations: List<Int>) {
        val frameCount get() = durations.size
        val totalDuration get() = durations.sum()
    }

    private class Chunk(val fourcc: String, val start: Int, val dataStart: Int, val size: Int) {
        /** Fin del chunk incluido el byte de relleno. */
        val end get() = dataStart + size + (size and 1)
    }

    // ---------------- Detección / información ----------------

    fun isWebp(bytes: ByteArray) = bytes.size >= 12 &&
        ascii(bytes, 0) == "RIFF" && ascii(bytes, 8) == "WEBP"

    fun isAnimated(bytes: ByteArray): Boolean {
        if (!isWebp(bytes)) return false
        val vp8x = chunks(bytes, 12, riffEnd(bytes)).firstOrNull { it.fourcc == "VP8X" } ?: return false
        return (bytes[vp8x.dataStart].toInt() and 0x02) != 0
    }

    /** Dimensiones y duraciones de un WebP animado (null si no lo es). */
    fun info(bytes: ByteArray): Info? {
        if (!isAnimated(bytes)) return null
        val top = chunks(bytes, 12, riffEnd(bytes))
        val vp8x = top.first { it.fourcc == "VP8X" }
        val w = u24(bytes, vp8x.dataStart + 4) + 1
        val h = u24(bytes, vp8x.dataStart + 7) + 1
        val durations = top.filter { it.fourcc == "ANMF" }.map { u24(bytes, it.dataStart + 12) }
        return Info(w, h, durations)
    }

    // ---------------- Decodificación ----------------

    /**
     * Decodifica todos los fotogramas ya compuestos (cada uno es la imagen completa del lienzo).
     * Si hay más de [maxFrames] se agrupan fotogramas sumando sus duraciones.
     */
    fun decodeAnimated(bytes: ByteArray, maxDim: Int, maxFrames: Int): EditImage? {
        if (!isAnimated(bytes)) return null
        val top = chunks(bytes, 12, riffEnd(bytes))
        val vp8x = top.first { it.fourcc == "VP8X" }
        val cw = u24(bytes, vp8x.dataStart + 4) + 1
        val ch = u24(bytes, vp8x.dataStart + 7) + 1
        val anmfs = top.filter { it.fourcc == "ANMF" }
        if (anmfs.isEmpty()) return null

        // Qué fotogramas conservar
        val step = max(1, (anmfs.size + maxFrames - 1) / maxFrames)
        val scale = min(1f, maxDim.toFloat() / max(cw, ch))
        val outW = max(1, (cw * scale).roundToInt())
        val outH = max(1, (ch * scale).roundToInt())

        val canvasBmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val frames = mutableListOf<Bitmap>()
        val durations = mutableListOf<Int>()

        anmfs.forEachIndexed { i, a ->
            val d = a.dataStart
            val x = u24(bytes, d) * 2
            val y = u24(bytes, d + 3) * 2
            val fw = u24(bytes, d + 6) + 1
            val fh = u24(bytes, d + 9) + 1
            val duration = u24(bytes, d + 12)
            val flags = bytes[d + 15].toInt()
            val noBlend = (flags and 0x02) != 0
            val dispose = (flags and 0x01) != 0

            val frameBmp = decodeFrame(bytes, d + 16, a.dataStart + a.size, fw, fh)
            if (frameBmp != null) {
                if (noBlend) canvas.drawRect(x.toFloat(), y.toFloat(), (x + fw).toFloat(), (y + fh).toFloat(), clear)
                canvas.drawBitmap(frameBmp, x.toFloat(), y.toFloat(), null)
            }
            if (i % step == 0) {
                val snap = if (scale < 1f) Bitmap.createScaledBitmap(canvasBmp, outW, outH, true)
                else canvasBmp.copy(Bitmap.Config.ARGB_8888, true)
                frames += snap
                durations += normalizeDuration(duration)
            } else {
                durations[durations.lastIndex] += normalizeDuration(duration)
            }
            if (dispose) canvas.drawRect(x.toFloat(), y.toFloat(), (x + fw).toFloat(), (y + fh).toFloat(), clear)
        }
        return EditImage(frames, durations)
    }

    /** Primer fotograma de cualquier WebP (animado o no). */
    fun decodeFirstFrame(bytes: ByteArray): Bitmap? =
        if (isAnimated(bytes)) decodeAnimated(bytes, Int.MAX_VALUE, 1)?.frames?.firstOrNull()
        else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    /** Reconstruye un WebP independiente con los sub-chunks de un ANMF y lo decodifica. */
    private fun decodeFrame(bytes: ByteArray, start: Int, end: Int, w: Int, h: Int): Bitmap? {
        val sub = chunks(bytes, start, end)
        val alph = sub.firstOrNull { it.fourcc == "ALPH" }
        val img = sub.firstOrNull { it.fourcc == "VP8 " || it.fourcc == "VP8L" } ?: return null
        val out = ByteArrayOutputStream()
        if (alph != null && img.fourcc == "VP8 ") {
            out.write(vp8xChunk(w, h, animated = false, alpha = true))
            out.write(bytes, alph.start, alph.end - alph.start)
        }
        out.write(bytes, img.start, img.end - img.start)
        val webp = wrapRiff(out.toByteArray())
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(webp, 0, webp.size, opts)
    }

    // ---------------- Codificación ----------------

    /**
     * Crea un WebP animado (bucle infinito) con fotogramas del mismo tamaño. Cada fotograma ocupa
     * todo el lienzo y sustituye al anterior (sin mezcla), así no hay restos entre fotogramas.
     */
    fun encodeAnimated(frames: List<Bitmap>, durations: List<Int>, lossless: Boolean, quality: Int = 80): ByteArray {
        require(frames.isNotEmpty() && frames.size == durations.size)
        val w = frames[0].width
        val h = frames[0].height
        val body = ByteArrayOutputStream()
        body.write(vp8xChunk(w, h, animated = true, alpha = true))
        // ANIM: color de fondo transparente + bucle infinito
        body.write(chunkBytes("ANIM", byteArrayOf(0, 0, 0, 0, 0, 0)))
        frames.forEachIndexed { i, frame ->
            val single = compressSingle(frame, lossless, quality)
            val sub = chunks(single, 12, riffEnd(single))
            val payload = ByteArrayOutputStream()
            payload.write(u24Bytes(0))
            payload.write(u24Bytes(0))
            payload.write(u24Bytes(frame.width - 1))
            payload.write(u24Bytes(frame.height - 1))
            payload.write(u24Bytes(durations[i].coerceIn(0, 0xFFFFFF)))
            payload.write(0x02) // sin mezcla, sin "dispose"
            sub.filter { it.fourcc == "ALPH" || it.fourcc == "VP8 " || it.fourcc == "VP8L" }
                .forEach { payload.write(single, it.start, it.end - it.start) }
            body.write(chunkBytes("ANMF", payload.toByteArray()))
        }
        return wrapRiff(body.toByteArray())
    }

    /**
     * Codifica respetando el límite de WhatsApp para stickers animados: baja la calidad y, si no
     * basta, elimina fotogramas alternos (sumando su duración al anterior).
     */
    fun encodeAnimatedUnder(frames: List<Bitmap>, durations: List<Int>, maxBytes: Int): ByteArray {
        var fs = frames
        var ds = durations
        while (true) {
            var last = ByteArray(0)
            for (q in intArrayOf(80, 65, 50, 35, 20, 10)) {
                last = encodeAnimated(fs, ds, lossless = false, quality = q)
                if (last.size <= maxBytes) return last
            }
            if (fs.size <= 2) return last
            val nf = mutableListOf<Bitmap>()
            val nd = mutableListOf<Int>()
            for (i in fs.indices step 2) {
                nf += fs[i]
                nd += ds[i] + (if (i + 1 < ds.size) ds[i + 1] else 0)
            }
            fs = nf
            ds = nd
        }
    }

    /**
     * Ajusta las duraciones a las reglas de WhatsApp: cada fotograma ≥ 8 ms (0 se interpreta como
     * 100 ms, como hacen los navegadores) y como mucho 10 s en total (se recortan los últimos).
     */
    fun fitDurations(frames: List<Bitmap>, durations: List<Int>, maxTotalMs: Int = 10_000): Pair<List<Bitmap>, List<Int>> {
        val ds = durations.map { normalizeDuration(it) }
        val keepF = mutableListOf<Bitmap>()
        val keepD = mutableListOf<Int>()
        var total = 0
        for (i in frames.indices) {
            if (total + ds[i] > maxTotalMs) {
                val rest = maxTotalMs - total
                if (rest >= 8) {
                    keepF += frames[i]
                    keepD += rest
                }
                break
            }
            keepF += frames[i]
            keepD += ds[i]
            total += ds[i]
        }
        return keepF to keepD
    }

    private fun normalizeDuration(d: Int) = when {
        d <= 0 -> 100
        d < 8 -> 8
        else -> d
    }

    private fun compressSingle(bmp: Bitmap, lossless: Boolean, quality: Int): ByteArray {
        val format = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
            else -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        val q = if (lossless) 100 else quality.coerceAtMost(99)
        return ByteArrayOutputStream().also { bmp.compress(format, q, it) }.toByteArray()
    }

    // ---------------- Utilidades RIFF ----------------

    private fun riffEnd(bytes: ByteArray) = min(bytes.size, 8 + u32(bytes, 4))

    private fun chunks(bytes: ByteArray, from: Int, to: Int): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var p = from
        while (p + 8 <= to) {
            val size = u32(bytes, p + 4)
            if (size < 0 || p + 8 + size > to) break
            val c = Chunk(ascii(bytes, p), p, p + 8, size)
            out += c
            p = c.end
        }
        return out
    }

    private fun vp8xChunk(w: Int, h: Int, animated: Boolean, alpha: Boolean): ByteArray {
        var flags = 0
        if (alpha) flags = flags or 0x10
        if (animated) flags = flags or 0x02
        val data = byteArrayOf(flags.toByte(), 0, 0, 0) + u24Bytes(w - 1) + u24Bytes(h - 1)
        return chunkBytes("VP8X", data)
    }

    private fun chunkBytes(fourcc: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fourcc.toByteArray(Charsets.US_ASCII))
        out.write(u32Bytes(data.size))
        out.write(data)
        if (data.size and 1 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun wrapRiff(body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 12)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(u32Bytes(body.size + 4))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(body)
        return out.toByteArray()
    }

    private fun ascii(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)

    private fun u24(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16)

    private fun u32(b: ByteArray, at: Int) = u24(b, at) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun u24Bytes(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(), (v shr 16 and 0xFF).toByte())

    private fun u32Bytes(v: Int) = u24Bytes(v) + byteArrayOf((v ushr 24 and 0xFF).toByte())
}
