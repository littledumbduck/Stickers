package com.littledumbduck.stickers.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
            val mime = resolver.getType(uri).orEmpty()
            if (mime.startsWith("video/")) {
                return runCatching { VideoClip(context, uri) }.getOrNull()?.takeIf { it.durationMs > 0 }
            }
            val header = resolver.openInputStream(uri)?.use { s -> ByteArray(16).also { s.read(it) } } ?: return null
            val tag = String(header, 0, 4, Charsets.US_ASCII)
            val isWebp = tag == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
            val isGif = tag == "GIF8"
            if (!isWebp && !isGif) return null
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            return if (isWebp) {
                WebpCodec.decodeAnimated(bytes, ImageLoader.MAX_ANIMATED_DIMENSION, ImageLoader.MAX_ANIMATED_FRAMES)
                    ?.takeIf { it.isAnimated }?.let { FramesClip(it) }
            } else {
                GifClip.create(bytes)
            }
        }

        /** Paso entre fotogramas al extraer un tramo: denso en clips cortos, sin pasar del máximo. */
        internal fun extractStep(lengthMs: Long, minStepMs: Long) =
            max(minStepMs, (lengthMs + ImageLoader.MAX_ANIMATED_FRAMES - 1) / ImageLoader.MAX_ANIMATED_FRAMES)
    }
}

/** Vídeo leído con [MediaMetadataRetriever]. */
class VideoClip(context: Context, uri: Uri) : ClipSource() {
    private val retriever = MediaMetadataRetriever().apply { setDataSource(context, uri) }

    override val durationMs: Long =
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

    override val frameStepMs: Long = run {
        val count = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
        } else null
        if (count != null && count > 0) (durationMs / count).coerceIn(10, 200) else 33L
    }

    override val aspect: Float = run {
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 1f
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 1f
        val rot = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (rot % 180 != 0) h / w else w / h
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

    override fun extract(startMs: Long, endMs: Long): EditImage {
        val len = max(1, endMs - startMs)
        val step = extractStep(len, 100)
        val frames = mutableListOf<Bitmap>()
        val durations = mutableListOf<Int>()
        var t = startMs
        while (t < endMs) {
            frames += frameAt(t, ImageLoader.MAX_ANIMATED_DIMENSION)
            durations += min(step, endMs - t).toInt()
            t += step
        }
        return EditImage(frames, durations)
    }

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
        val len = max(1, endMs - startMs)
        val step = extractStep(len, 60)
        val frames = mutableListOf<Bitmap>()
        val durations = mutableListOf<Int>()
        var t = startMs
        while (t < endMs) {
            frames += frameAt(t, ImageLoader.MAX_ANIMATED_DIMENSION)
            durations += min(step, endMs - t).toInt()
            t += step
        }
        return EditImage(frames, durations)
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
