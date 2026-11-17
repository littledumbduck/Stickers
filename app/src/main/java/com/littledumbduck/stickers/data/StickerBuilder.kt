package com.littledumbduck.stickers.data

import com.littledumbduck.stickers.image.EditImage
import com.littledumbduck.stickers.image.StickerProcessor
import com.littledumbduck.stickers.image.WebpCodec

/** Genera el archivo final para WhatsApp (y la copia editable) a partir de una imagen del editor. */
object StickerBuilder {

    class Output(val webp: ByteArray, val source: ByteArray, val sourceExtension: String)

    /**
     * @param animatedPack tipo del paquete de destino. Una imagen animada en un paquete estático
     *   usa su primer fotograma; una estática en un paquete animado se convierte en una animación
     *   de dos fotogramas iguales (WhatsApp exige que todos los stickers del paquete sean animados).
     * @param alreadyFinal la imagen ya es un sticker terminado: solo se ajusta a 512x512.
     */
    fun build(
        img: EditImage,
        animatedPack: Boolean,
        borderColor: Int?,
        borderWidth: Int,
        alreadyFinal: Boolean = false,
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
        val composed = if (alreadyFinal) frames.map(StickerProcessor::fitTo512)
        else StickerProcessor.composeFrames(frames, borderColor, borderWidth)
        val webp = WebpCodec.encodeAnimatedUnder(composed, durations, StickerLimits.ANIMATED_STICKER_MAX_BYTES)
        return if (img.isAnimated) {
            Output(webp, WebpCodec.encodeAnimated(img.frames, img.durations, lossless = true), "webp")
        } else {
            Output(webp, StickerProcessor.encodePng(img.first), "png")
        }
    }

    /** ¿Se puede usar este archivo tal cual como sticker animado de WhatsApp? */
    fun isValidAnimatedSticker(bytes: ByteArray): Boolean {
        val info = WebpCodec.info(bytes) ?: return false
        return info.width == StickerLimits.STICKER_SIZE && info.height == StickerLimits.STICKER_SIZE &&
            info.frameCount > 1 && bytes.size <= StickerLimits.ANIMATED_STICKER_MAX_BYTES &&
            info.durations.all { it >= 8 } && info.totalDuration <= StickerLimits.ANIMATED_MAX_DURATION_MS
    }
}
