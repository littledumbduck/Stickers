package com.littledumbduck.stickers.image

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * Recorte inteligente en el dispositivo.
 *
 * 1. Con servicios de Google Play: segmentación de sujetos de ML Kit (personas, animales,
 *    objetos). El modelo lo descarga Google Play la primera vez.
 * 2. Sin servicios de Google Play, sin conexión o si falla la descarga: segmentación de personas
 *    de ML Kit, cuyo modelo va incluido en la app y funciona siempre sin conexión.
 */
object SmartCutout {

    class NoSubjectException : Exception("No se encontró ningún sujeto en la imagen")

    data class Result(val image: EditImage, val usedFallback: Boolean)

    suspend fun removeBackground(
        context: Context,
        image: EditImage,
        /**
         * Recortar el resultado al sujeto. Con false se conserva el tamaño original, necesario
         * cuando la operación se vuelve a aplicar sobre otros fotogramas (cambio de duración).
         */
        trim: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result {
        val frames = if (hasPlayServices(context)) {
            try {
                return Result(finish(withSubjectSegmentation(context, image, onProgress), trim), usedFallback = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoSubjectException) {
                throw e
            } catch (_: Exception) {
                withSelfieSegmentation(image, onProgress) // el módulo no está disponible: alternativa
            }
        } else {
            withSelfieSegmentation(image, onProgress)
        }
        return Result(finish(frames, trim), usedFallback = true)
    }

    private fun finish(image: EditImage, trim: Boolean): EditImage {
        if (StickerProcessor.opaqueBounds(image.frames) == null) throw NoSubjectException()
        return if (trim) StickerProcessor.trimTransparent(image) else image
    }

    private fun hasPlayServices(context: Context) =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    private suspend fun withSubjectSegmentation(
        context: Context,
        image: EditImage,
        onProgress: (Int, Int) -> Unit,
    ): EditImage {
        val segmenter: SubjectSegmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundBitmap().build()
        )
        try {
            val installer = ModuleInstall.getClient(context)
            if (!installer.areModulesAvailable(segmenter).await().areModulesAvailable()) {
                withTimeout(90_000) {
                    installer.installModules(ModuleInstallRequest.newBuilder().addApi(segmenter).build()).await()
                }
            }
            val out = image.frames.mapIndexed { i, frame ->
                onProgress(i, image.frames.size)
                val fg = segmenter.process(InputImage.fromBitmap(frame, 0)).await().foregroundBitmap
                    ?: Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
                // Garantiza el mismo tamaño en todos los fotogramas
                if (fg.width == frame.width && fg.height == frame.height) fg.copy(Bitmap.Config.ARGB_8888, true)
                else Bitmap.createScaledBitmap(fg, frame.width, frame.height, true)
            }
            return EditImage(out, image.durations)
        } finally {
            segmenter.close()
        }
    }

    private suspend fun withSelfieSegmentation(image: EditImage, onProgress: (Int, Int) -> Unit): EditImage {
        val segmenter = Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(
                    if (image.isAnimated) SelfieSegmenterOptions.STREAM_MODE else SelfieSegmenterOptions.SINGLE_IMAGE_MODE
                )
                .build()
        )
        try {
            val out = image.frames.mapIndexed { i, frame ->
                onProgress(i, image.frames.size)
                val mask = segmenter.process(InputImage.fromBitmap(frame, 0)).await()
                val buf = mask.buffer.apply { rewind() }.asFloatBuffer()
                applyMask(frame, FloatArray(mask.width * mask.height).also { buf.get(it) }, mask.width, mask.height)
            }
            return EditImage(out, image.durations)
        } finally {
            segmenter.close()
        }
    }

    /** Multiplica la transparencia por la confianza de la máscara (suavizada en los bordes). */
    private fun applyMask(src: Bitmap, conf: FloatArray, mw: Int, mh: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            val my = (y * mh / h).coerceAtMost(mh - 1)
            for (x in 0 until w) {
                val mx = (x * mw / w).coerceAtMost(mw - 1)
                val c = ((conf[my * mw + mx] - 0.35f) / 0.3f).coerceIn(0f, 1f)
                val i = y * w + x
                val a = ((px[i] ushr 24) * c).toInt()
                px[i] = (a shl 24) or (px[i] and 0x00FFFFFF)
            }
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }
}
