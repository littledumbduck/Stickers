package com.littledumbduck.stickers.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.Closeable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Fuente animada (vídeo, GIF o WebP animado) de la que se puede ver cualquier instante y extraer
 * un tramo [inicio, fin) como fotogramas editables. Las llamadas son bloqueantes y seguras entre
 * hilos: úsalas desde Dispatchers.IO.
 */
abstract class ClipSource : Closeable {
    /** Duración total en ms. */
    abstract val durationMs: Long

    /** Separación mínima entre dos fotogramas distintos (ms). */
    abstract val frameStepMs: Long

    /** Proporción ancho/alto de los fotogramas. */
    abstract val aspect: Float

    /** Instante "canónico" del fotograma que se ve en [t] (sirve como clave de caché y para ajustar). */
    open fun snap(t: Long): Long {
        val c = t.coerceIn(0, max(0, durationMs - 1))
        return (c / frameStepMs) * frameStepMs
    }

    /** Fotograma visible en el instante [timeMs], con el lado mayor como mucho [maxDim]. */
    abstract fun frameAt(timeMs: Long, maxDim: Int): Bitmap

    /** Fotogramas del tramo [startMs, endMs) a tamaño de edición. */
    abstract fun extract(startMs: Long, endMs: Long): EditImage

    /** Si la fuente es un vídeo, su URI para reproducirlo entero con el reproductor del sistema. */
    open val videoUri: Uri? get() = null

    /** Tramo inicial propuesto al abrir el clip. */
    open fun defaultRange(): Pair<Long, Long> = 0L to min(durationMs, MAX_CLIP_MS)

    override fun close() {}

    companion object {
        /** WhatsApp no admite stickers animados de más de 10 s. */
        const val MAX_CLIP_MS = 10_000L
        const val MIN_CLIP_MS = 200L

        /** Devuelve una fuente animada si [uri] es un vídeo, un GIF animado o un WebP animado. */
        fun open(context: Context, uri: Uri): ClipSource? {
            val resolver = context.contentResolver
            if (isVideo(context, uri)) {
                return runCatching { VideoClip(context, uri) }.getOrNull()?.takeIf { it.durationMs > 0 }
            }
            val header = resolver.openInputStream(uri)?.use { s -> ByteArray(16).also { s.read(it) } } ?: return null
            val tag = String(header, 0, 4, Charsets.US_ASCII)
            val isWebp = tag == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
            val isGif = tag == "GIF8"
            if (!isWebp && !isGif) return null
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            return if (isWebp) {
                val dim = ImageLoader.MAX_ANIMATED_DIMENSION
                WebpCodec.decodeAnimated(bytes, dim, ImageLoader.maxAnimatedFrames(dim, dim))
                    ?.takeIf { it.isAnimated }?.let { FramesClip(it) }
            } else {
                GifClip.create(bytes)
            }
        }

        /**
         * ¿Es un vídeo? Por tipo MIME o, si no lo hay (archivos propios como las descargas de
         * TikTok), por la cabecera: caja "ftyp" de MP4/MOV o la firma de WebM/MKV.
         */
        fun isVideo(context: Context, uri: Uri): Boolean {
            val mime = context.contentResolver.getType(uri).orEmpty()
            if (mime.startsWith("video/")) return true
            if (mime.isNotEmpty()) return false
            val h = runCatching {
                context.contentResolver.openInputStream(uri)?.use { s -> ByteArray(12).also { s.read(it) } }
            }.getOrNull() ?: return false
            return String(h, 4, 4, Charsets.US_ASCII) == "ftyp" ||
                (h[0] == 0x1A.toByte() && h[1] == 0x45.toByte() && h[2] == 0xDF.toByte() && h[3] == 0xA3.toByte())
        }

        /**
         * Instantes que se extraen del tramo [startMs, endMs): uno por fotograma de la fuente (cada
         * [sourceStepMs]) hasta [ImageLoader.TARGET_FPS], repartidos por igual y sin pasar de
         * [maxFrames] (si no caben, se reparten menos a lo largo de todo el tramo).
         */
        internal fun sampleTimes(startMs: Long, endMs: Long, sourceStepMs: Double, maxFrames: Int): LongArray {
            val len = max(1L, endMs - startMs)
            val step = max(sourceStepMs, 1000.0 / ImageLoader.TARGET_FPS)
            // Pequeño margen para que 1000 ms a 33,3 ms por fotograma den 30 y no 31
            val n = ceil(len / step - 1e-3).toInt().coerceIn(1, max(1, maxFrames))
            return LongArray(n) { startMs + (it * len.toDouble() / n).roundToLong() }
        }

        /** Duración de cada fotograma: hasta el instante del siguiente (el último, hasta [endMs]). */
        internal fun durationsFor(times: LongArray, endMs: Long): List<Int> =
            times.indices.map { i -> max(1L, (if (i + 1 < times.size) times[i + 1] else endMs) - times[i]).toInt() }

        /** Une fotogramas consecutivos idénticos sumando su duración (p. ej. un GIF de pocos fps). */
        internal fun mergeRepeated(frames: List<Bitmap>, durations: List<Int>): EditImage {
            val fs = ArrayList<Bitmap>(frames.size)
            val ds = ArrayList<Int>(frames.size)
            for (i in frames.indices) {
                if (fs.isNotEmpty() && fs.last().sameAs(frames[i])) {
                    ds[ds.lastIndex] += durations[i]
                } else {
                    fs += frames[i]
                    ds += durations[i]
                }
            }
            return EditImage(fs, ds)
        }
    }
}

/** Vídeo leído con [MediaMetadataRetriever]. */
class VideoClip(context: Context, private val uri: Uri) : ClipSource() {
    private val retriever = MediaMetadataRetriever().apply { setDataSource(context, uri) }

    override val videoUri: Uri get() = uri

    override val durationMs: Long =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

    /** Número de fotogramas (solo Android 9+; null si el vídeo no lo indica). */
    private val frameCount: Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
                ?.takeIf { it > 0 }
        } else null

    /** Duración exacta de un fotograma (33,3 ms a 30 fps). Sin dato, se asume 30 fps. */
    private val exactStepMs: Double =
        frameCount?.let { (durationMs.toDouble() / it).coerceIn(4.0, 200.0) } ?: (1000.0 / 30)

    override val frameStepMs: Long = exactStepMs.toLong().coerceIn(10, 200)

    private val codedWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1
    private val codedHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1
    private val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0

    override val aspect: Float =
        if (rotation % 180 != 0) codedHeight.toFloat() / codedWidth else codedWidth.toFloat() / codedHeight

    /** Tamaño (ya girado) de los fotogramas extraídos para editar. */
    private val editSize: Pair<Int, Int> = run {
        val (dw, dh) = if (rotation % 180 != 0) codedHeight to codedWidth else codedWidth to codedHeight
        val s = min(1f, ImageLoader.MAX_ANIMATED_DIMENSION.toFloat() / max(dw, dh))
        max(1, (dw * s).roundToInt()) to max(1, (dh * s).roundToInt())
    }

    /** Los vídeos suelen ser largos: se propone empezar con 5 s. */
    override fun defaultRange() = 0L to min(durationMs, 5_000L)

    @Synchronized
    override fun frameAt(timeMs: Long, maxDim: Int): Bitmap {
        val us = timeMs.coerceIn(0, max(0, durationMs - 1)) * 1000
        val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, maxDim, maxDim)
        } else {
            retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)?.let { scaleDown(it, maxDim) }
        } ?: error("No se pudo leer el vídeo")
        return argb(bmp)
    }

    @Synchronized
    override fun extract(startMs: Long, endMs: Long): EditImage {
        val (w, h) = editSize
        val times = sampleTimes(startMs, endMs, exactStepMs, ImageLoader.maxAnimatedFrames(w, h))
        val frames = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { extractSequential(times) }.getOrNull()
        } else null
        return EditImage(frames ?: times.map { frameAt(it, ImageLoader.MAX_ANIMATED_DIMENSION) }, durationsFor(times, endMs))
    }

    /**
     * Decodifica los fotogramas seguidos, por tandas, con getFramesAtIndex. Pedirlos uno a uno por
     * tiempo obliga a buscar el fotograma clave anterior y decodificar desde ahí cada vez, lo que
     * con decenas de fotogramas por segundo es muy lento. Devuelve null si no se puede (y se usa el
     * método lento).
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun extractSequential(times: LongArray): List<Bitmap>? {
        val count = frameCount ?: return null
        if (count < 2 || times.isEmpty()) return null
        val indices = IntArray(times.size) { (times[it] / exactStepMs).toInt().coerceIn(0, count - 1) }
        // Tandas limitadas por memoria: cada fotograma llega a resolución completa
        val rawBytes = max(1L, codedWidth.toLong() * codedHeight * 4)
        val budget = min(64L shl 20, Runtime.getRuntime().maxMemory() / 8)
        val batchMax = (budget / rawBytes).toInt().coerceIn(1, 16)
        val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
        val (w, h) = editSize
        val out = ArrayList<Bitmap>(times.size)
        var turn: Int? = null // giro que hay que aplicar (se comprueba con el primer fotograma)
        var p = 0
        while (p < indices.size) {
            val first = indices[p]
            var q = p
            while (q + 1 < indices.size && indices[q + 1] - first < batchMax) q++
            val n = indices[q] - first + 1
            val batch = retriever.getFramesAtIndex(first, n, params)
            try {
                if (batch.size != n) return null
                if (turn == null) turn = detectTurn(batch[0], times[0]) ?: return null
                for (k in p..q) out += fitFrame(batch[indices[k] - first], turn, w, h)
            } finally {
                batch.forEach { it.recycle() }
            }
            p = q + 1
        }
        return out
    }

    /**
     * Giro (0/90/180/270) que necesitan los fotogramas de getFramesAtIndex para verse como los de
     * getFrameAtTime, comparando miniaturas del primero. null si no se parecen con ningún giro
     * (el índice no corresponde al instante, p. ej. vídeos de velocidad variable).
     */
    private fun detectTurn(raw: Bitmap, timeMs: Long): Int? {
        val ref = frameAt(timeMs, 32)
        val rw = ref.width
        val rh = ref.height
        val refPx = IntArray(rw * rh).also { ref.getPixels(it, 0, rw, 0, 0, rw, rh) }
        var best: Int? = null
        var bestDiff = Double.MAX_VALUE
        for (deg in intArrayOf(0, 90, 180, 270)) {
            val sideways = deg % 180 != 0
            val small = Bitmap.createScaledBitmap(raw, if (sideways) rh else rw, if (sideways) rw else rh, true)
            val cand = rotate(small, deg)
            if (cand.width != rw || cand.height != rh) continue
            val px = IntArray(rw * rh).also { cand.getPixels(it, 0, rw, 0, 0, rw, rh) }
            var sum = 0L
            for (i in px.indices) {
                val a = px[i]
                val b = refPx[i]
                sum += abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) +
                    abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))
            }
            val diff = sum / (3.0 * px.size)
            if (diff < bestDiff) {
                bestDiff = diff
                best = deg
            }
        }
        return best?.takeIf { bestDiff < 24 }
    }

    /** Reduce (por mitades, para que no salga pixelado) y gira un fotograma a [w]x[h]. */
    private fun fitFrame(raw: Bitmap, turn: Int, w: Int, h: Int): Bitmap {
        val sideways = turn % 180 != 0
        val tw = if (sideways) h else w
        val th = if (sideways) w else h
        var b = raw
        while (b.width / 2 >= tw && b.height / 2 >= th) {
            val half = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
            if (b !== raw) b.recycle()
            b = half
        }
        if (b.width != tw || b.height != th) {
            val exact = Bitmap.createScaledBitmap(b, tw, th, true)
            if (b !== raw) b.recycle()
            b = exact
        }
        val turned = rotate(b, turn)
        // El original de la tanda se libera después: nunca devolverlo tal cual
        return if (turned === raw) raw.copy(Bitmap.Config.ARGB_8888, true) else argb(turned)
    }

    private fun rotate(b: Bitmap, deg: Int): Bitmap =
        if (deg == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg.toFloat()) }, true)

    override fun close() = retriever.release()
}

/** GIF animado mediante [Movie] (la única API del sistema que permite elegir el instante). */
@Suppress("DEPRECATION")
class GifClip private constructor(private val movie: Movie) : ClipSource() {
    override val durationMs = movie.duration().toLong()
    override val frameStepMs = 20L
    override val aspect = movie.width().toFloat() / movie.height()

    @Synchronized
    override fun frameAt(timeMs: Long, maxDim: Int): Bitmap {
        val scale = min(1f, maxDim.toFloat() / max(movie.width(), movie.height()))
        val w = max(1, (movie.width() * scale).roundToInt())
        val h = max(1, (movie.height() * scale).roundToInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(w.toFloat() / movie.width(), h.toFloat() / movie.height())
        movie.setTime(timeMs.coerceIn(0, max(0, durationMs - 1)).toInt())
        movie.draw(c, 0f, 0f)
        return bmp
    }

    override fun extract(startMs: Long, endMs: Long): EditImage {
        val dim = ImageLoader.MAX_ANIMATED_DIMENSION
        val s = min(1f, dim.toFloat() / max(movie.width(), movie.height()))
        val maxFrames = ImageLoader.maxAnimatedFrames((movie.width() * s).roundToInt(), (movie.height() * s).roundToInt())
        // Movie no dice cuándo cambia cada fotograma: se muestrea a 30 fps y se unen los repetidos
        val times = sampleTimes(startMs, endMs, frameStepMs.toDouble(), maxFrames)
        return mergeRepeated(times.map { frameAt(it, dim) }, durationsFor(times, endMs))
    }

    companion object {
        fun create(bytes: ByteArray): GifClip? {
            val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
            if (movie.duration() <= 0 || movie.width() <= 0 || movie.height() <= 0) return null
            return GifClip(movie)
        }
    }
}

/** Animación ya decodificada (WebP animado o un sticker guardado). Ajusta a sus fotogramas reales. */
class FramesClip(private val image: EditImage) : ClipSource() {
    private val starts: LongArray = LongArray(image.frames.size).also { arr ->
        var acc = 0L
        image.durations.forEachIndexed { i, d -> arr[i] = acc; acc += max(1, d) }
    }
    override val durationMs = image.durations.sumOf { max(1, it).toLong() }
    override val frameStepMs = max(1L, image.durations.minOf { max(1, it) }.toLong())
    override val aspect = image.width.toFloat() / image.height

    private fun indexAt(t: Long): Int {
        var i = starts.binarySearch(t.coerceIn(0, max(0, durationMs - 1)))
        if (i < 0) i = -i - 2
        return i.coerceIn(0, starts.lastIndex)
    }

    override fun snap(t: Long) = starts[indexAt(t)]

    override fun frameAt(timeMs: Long, maxDim: Int): Bitmap = scaleDown(image.frames[indexAt(timeMs)], maxDim)

    override fun extract(startMs: Long, endMs: Long): EditImage {
        val frames = mutableListOf<Bitmap>()
        val durations = mutableListOf<Int>()
        for (i in image.frames.indices) {
            val s = starts[i]
            val e = s + max(1, image.durations[i])
            val overlap = min(e, endMs) - max(s, startMs)
            if (overlap > 0) {
                frames += image.frames[i]
                durations += overlap.toInt()
            }
        }
        if (frames.isEmpty()) {
            frames += image.frames[indexAt(startMs)]
            durations += max(1, (endMs - startMs).toInt())
        }
        return EditImage(frames, durations)
    }
}

private fun scaleDown(bmp: Bitmap, maxDim: Int): Bitmap {
    val s = min(1f, maxDim.toFloat() / max(bmp.width, bmp.height))
    return if (s < 1f) Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true)
    else bmp
}

private fun argb(bmp: Bitmap): Bitmap =
    if (bmp.config == Bitmap.Config.ARGB_8888 && bmp.isMutable) bmp else bmp.copy(Bitmap.Config.ARGB_8888, true)
