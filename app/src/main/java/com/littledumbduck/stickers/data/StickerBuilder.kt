package com.littledumbduck.stickers.data

import android.graphics.Bitmap
import com.littledumbduck.stickers.image.EditImage
import com.littledumbduck.stickers.image.StickerProcessor
import com.littledumbduck.stickers.image.WebpCodec
import kotlin.math.max
import kotlin.math.roundToInt

/** Genera el archivo final para WhatsApp (y la copia editable) a partir de una imagen del editor. */
object StickerBuilder {

    /**
     * @param report solo en stickers animados: fluidez y calidad con que ha cabido en el límite.
     */
    class Output(
        val webp: ByteArray,
        val source: ByteArray,
        val sourceExtension: String,
        val report: AnimatedReport? = null,
    )

    /** Cómo ha quedado un sticker animado tras ajustarlo a los 500 KB de WhatsApp. */
    class AnimatedReport(
        val fps: Float,
        val sourceFps: Float,
        val frames: Int,
        val sourceFrames: Int,
        val quality: Int,
        val bytes: Int,
        /** Calidad mínima usada en la búsqueda (para saber si aún se puede priorizar más la fluidez). */
        val minQuality: Int,
    ) {
        /** Se han quitado fotogramas para caber (va menos fluido que el original). */
        val lostFluidity get() = frames < sourceFrames

        /** Calidad baja: se nota borroso o con bloques. */
        val lowQuality get() = quality < GOOD_QUALITY

        val needsWarning get() = lostFluidity || lowQuality

        /** Bajando la calidad mínima se podrían recuperar fotogramas. */
        val canTradeQualityForFps get() = lostFluidity && minQuality > SMOOTH_MIN_QUALITY

        fun summary(): String = buildString {
            append("${fps.roundToInt()} fps")
            if (lostFluidity) append(" (el original tiene ${sourceFps.roundToInt()} fps)")
            append(" · calidad $quality % · ${bytes / 1024} KB")
        }
    }

    /** Por debajo de esta calidad WebP se avisa de que el sticker puede verse peor. */
    const val GOOD_QUALITY = 60

    /** Calidad mínima cuando el usuario pide priorizar la fluidez. */
    const val SMOOTH_MIN_QUALITY = 20

    /**
     * @param animatedPack tipo del paquete de destino. Una imagen animada en un paquete estático
     *   usa su primer fotograma; una estática en un paquete animado se convierte en una animación
     *   de dos fotogramas iguales (WhatsApp exige que todos los stickers del paquete sean animados).
     * @param alreadyFinal la imagen ya es un sticker terminado: solo se ajusta a 512x512.
     * @param minQuality calidad por debajo de la cual se prefiere quitar fotogramas (animados).
     * @param onProgress fps que se están probando al ajustar el tamaño (animados).
     */
    fun build(
        img: EditImage,
        animatedPack: Boolean,
        borderColor: Int?,
        borderWidth: Int,
        alreadyFinal: Boolean = false,
        minQuality: Int = WebpCodec.DEFAULT_MIN_QUALITY,
        onProgress: ((fps: Float) -> Unit)? = null,
    ): Output {
        if (!animatedPack) {
            val src = img.first
            val final = if (alreadyFinal) StickerProcessor.fitTo512(src)
            else StickerProcessor.composeSticker(src, borderColor, borderWidth)
            return Output(StickerProcessor.encodeWebp(final), StickerProcessor.encodePng(src), "png")
        }

        var (frames, durations) = if (img.isAnimated) {
            WebpCodec.fitDurations(img.frames, img.durations, StickerLimits.ANIMATED_MAX_DURATION_MS)
        } else {
            listOf(img.first, img.first) to listOf(1000, 1000)
        }
        if (frames.size == 1) {
            frames = listOf(frames[0], frames[0])
            durations = listOf(durations[0], durations[0]).map { maxOf(it / 2, 8) }
        }
        val sourceFrames = frames.size
        // Si no hay memoria para componer todos los fotogramas, se reparten menos desde el principio
        val fitting = framesThatFitInMemory(frames)
        if (fitting < frames.size) {
            val sel = WebpCodec.selectFrames(frames, durations, fitting)
            frames = sel.first
            durations = sel.second
        }

        val composed = if (alreadyFinal) {
            StickerProcessor.Composed(frames.map(StickerProcessor::fitTo512), WebpCodec.Placement(512, 512, 0, 0))
        } else {
            StickerProcessor.composeAnimation(frames, borderColor, borderWidth)
        }
        val result = WebpCodec.encodeAnimatedUnder(
            composed.frames, durations, StickerLimits.ANIMATED_STICKER_MAX_BYTES,
            placement = composed.placement, minQuality = minQuality, onProgress = onProgress,
        )
        val report = if (img.isAnimated) {
            val total = max(1, durations.sum())
            AnimatedReport(
                fps = result.frames * 1000f / total,
                sourceFps = sourceFrames * 1000f / total,
                frames = result.frames,
                sourceFrames = sourceFrames,
                quality = result.quality,
                bytes = result.bytes.size,
                minQuality = minQuality,
            )
        } else null
        return if (img.isAnimated) {
            Output(result.bytes, WebpCodec.encodeAnimated(img.frames, img.durations, lossless = true), "webp", report)
        } else {
            Output(result.bytes, StickerProcessor.encodePng(img.first), "png", report)
        }
    }

    /** Cuántos fotogramas compuestos (como mucho 512x512) caben en la memoria libre. */
    private fun framesThatFitInMemory(frames: List<Bitmap>): Int {
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        val perFrame = 512L * 512 * 4
        return (free * 6 / 10 / perFrame).toInt().coerceIn(2, frames.size)
    }

    /** ¿Se puede usar este archivo tal cual como sticker animado de WhatsApp? */
    fun isValidAnimatedSticker(bytes: ByteArray): Boolean {
        val info = WebpCodec.info(bytes) ?: return false
        return info.width == StickerLimits.STICKER_SIZE && info.height == StickerLimits.STICKER_SIZE &&
            info.frameCount > 1 && bytes.size <= StickerLimits.ANIMATED_STICKER_MAX_BYTES &&
            info.durations.all { it >= 8 } && info.totalDuration <= StickerLimits.ANIMATED_MAX_DURATION_MS
    }
}
