package com.littledumbduck.stickers.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Movie
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max

object ImageLoader {

    /** Tamaño máximo de trabajo en el editor: suficiente para un sticker de 512 y ligero en memoria. */
    const val MAX_EDIT_DIMENSION = 1280

    /** Las animaciones se editan directamente a tamaño de sticker para no agotar la memoria. */
    const val MAX_ANIMATED_DIMENSION = 512
    const val MAX_ANIMATED_FRAMES = 60
    private const val MAX_VIDEO_MS = 8_000L

    /**
     * Carga una imagen para el editor. Si [allowAnimation] es true, los WebP animados, GIF y
     * vídeos se cargan con todos sus fotogramas; si no, solo el primero.
     */
    fun loadEditable(context: Context, uri: Uri, allowAnimation: Boolean): EditImage {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        if (mime.startsWith("video/")) {
            return if (allowAnimation) loadVideo(context, uri)
            else EditImage.single(videoFrameAt(context, uri, 0L))
        }
        if (allowAnimation) {
            val header = resolver.openInputStream(uri)?.use { s -> ByteArray(32).also { s.read(it) } }
            if (header != null) {
                val isWebp = String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
                val isGif = String(header, 0, 4, Charsets.US_ASCII) == "GIF8"
                if (isWebp || isGif) {
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: error("No se pudo leer")
                    val anim = if (isWebp) {
                        WebpCodec.decodeAnimated(bytes, MAX_ANIMATED_DIMENSION, MAX_ANIMATED_FRAMES)
                    } else {
                        decodeGif(bytes)
                    }
                    if (anim != null && anim.isAnimated) return anim
                }
            }
        }
        return EditImage.single(load(context, uri))
    }

    /** GIF animado mediante [Movie] (la única API del sistema que permite elegir el instante). */
    @Suppress("DEPRECATION")
    private fun decodeGif(bytes: ByteArray): EditImage? {
        val movie = Movie.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val duration = movie.duration()
        if (duration <= 0 || movie.width() <= 0) return null
        val scale = minOf(1f, MAX_ANIMATED_DIMENSION.toFloat() / max(movie.width(), movie.height()))
        val w = max(1, (movie.width() * scale).toInt())
        val h = max(1, (movie.height() * scale).toInt())
        val length = minOf(duration, 10_000)
        val step = max(60, length / MAX_ANIMATED_FRAMES)
        val frames = mutableListOf<Bitmap>()
        val durations = mutableListOf<Int>()
        var t = 0
        while (t < length) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.scale(scale, scale)
            movie.setTime(t)
            movie.draw(c, 0f, 0f)
            frames += bmp
            durations += minOf(step, length - t)
            t += step
        }
        return EditImage(frames, durations)
    }

    /** Extrae como mucho los primeros 8 s de un vídeo, a unos 8–10 fotogramas por segundo. */
    private fun loadVideo(context: Context, uri: Uri): EditImage {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val length = minOf(duration, MAX_VIDEO_MS)
            if (length <= 0) return EditImage.single(videoFrame(r, 0L))
            val step = max(100L, length / MAX_ANIMATED_FRAMES)
            val frames = mutableListOf<Bitmap>()
            val durations = mutableListOf<Int>()
            var t = 0L
            while (t < length) {
                frames += videoFrame(r, t)
                durations += minOf(step, length - t).toInt()
                t += step
            }
            return if (frames.size > 1) EditImage(frames, durations) else EditImage.single(frames[0])
        } finally {
            r.release()
        }
    }

    private fun videoFrameAt(context: Context, uri: Uri, ms: Long): Bitmap {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            return videoFrame(r, ms, MAX_EDIT_DIMENSION)
        } finally {
            r.release()
        }
    }

    private fun videoFrame(r: MediaMetadataRetriever, ms: Long, maxDim: Int = MAX_ANIMATED_DIMENSION): Bitmap {
        val us = ms * 1000
        val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST, maxDim, maxDim)
        } else {
            r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)?.let { full ->
                val s = minOf(1f, maxDim.toFloat() / max(full.width, full.height))
                if (s < 1f) Bitmap.createScaledBitmap(full, (full.width * s).toInt(), (full.height * s).toInt(), true)
                else full
            }
        } ?: error("No se pudo leer el vídeo")
        return ensureArgb(bmp)
    }

    /** Carga un archivo de sticker guardado (WebP animado, WebP o PNG). */
    fun loadEditable(context: Context, file: File, allowAnimation: Boolean): EditImage =
        loadEditable(context, Uri.fromFile(file), allowAnimation)

    /** Decodifica una imagen (también WebP animado: se toma el primer fotograma) en ARGB_8888 mutable. */
    fun load(context: Context, uri: Uri, maxDim: Int = MAX_EDIT_DIMENSION): Bitmap {
        val bmp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                val largest = max(info.size.width, info.size.height)
                if (largest > maxDim) {
                    val s = maxDim.toFloat() / largest
                    decoder.setTargetSize(
                        (info.size.width * s).toInt().coerceAtLeast(1),
                        (info.size.height * s).toInt().coerceAtLeast(1),
                    )
                }
            }
        } else {
            loadLegacy(context, uri, maxDim)
        }
        return ensureArgb(bmp)
    }

    fun load(context: Context, file: File, maxDim: Int = MAX_EDIT_DIMENSION): Bitmap =
        load(context, Uri.fromFile(file), maxDim)

    private fun loadLegacy(context: Context, uri: Uri, maxDim: Int): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("No se pudo leer la imagen")
        // Orientación EXIF (las fotos de cámara suelen venir giradas)
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees != 0f) {
            val m = Matrix().apply { postRotate(degrees) }
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        }
        if (max(bmp.width, bmp.height) > maxDim) {
            val s = maxDim.toFloat() / max(bmp.width, bmp.height)
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
        }
        return bmp
    }

    private fun ensureArgb(bmp: Bitmap): Bitmap =
        if (bmp.config == Bitmap.Config.ARGB_8888 && bmp.isMutable) bmp
        else bmp.copy(Bitmap.Config.ARGB_8888, true)
}
