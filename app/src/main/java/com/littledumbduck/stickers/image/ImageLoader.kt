package com.littledumbduck.stickers.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.max

object ImageLoader {

    /** Tamaño máximo de trabajo en el editor: suficiente para un sticker de 512 y ligero en memoria. */
    const val MAX_EDIT_DIMENSION = 1280

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
