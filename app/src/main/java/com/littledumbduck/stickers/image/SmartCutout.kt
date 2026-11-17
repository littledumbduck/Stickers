package com.littledumbduck.stickers.image

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await

/**
 * Recorte inteligente: separa automáticamente el sujeto principal (personas, animales, objetos)
 * del fondo usando la segmentación de sujetos de ML Kit, que se ejecuta en el propio dispositivo.
 */
object SmartCutout {

    suspend fun removeBackground(context: Context, bitmap: Bitmap): Bitmap {
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundBitmap()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        try {
            // El modelo lo descargan los servicios de Google Play la primera vez.
            val installer = ModuleInstall.getClient(context)
            val available = installer.areModulesAvailable(segmenter).await().areModulesAvailable()
            if (!available) {
                installer.installModules(
                    ModuleInstallRequest.newBuilder().addApi(segmenter).build()
                ).await()
            }
            val result = segmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
            val fg = result.foregroundBitmap
                ?: throw IllegalStateException("No se encontró ningún sujeto en la imagen")
            val trimmed = StickerProcessor.trimTransparent(fg)
            if (trimmed === fg && isFullyTransparent(fg)) {
                throw IllegalStateException("No se encontró ningún sujeto en la imagen")
            }
            return trimmed.copy(Bitmap.Config.ARGB_8888, true)
        } finally {
            segmenter.close()
        }
    }

    private fun isFullyTransparent(bmp: Bitmap): Boolean {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return px.none { (it ushr 24) > 8 }
    }
}
