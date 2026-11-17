package com.littledumbduck.stickers.data

import android.content.Context
import android.net.Uri
import com.littledumbduck.stickers.image.ImageLoader
import com.littledumbduck.stickers.image.StickerProcessor

/** Importa imágenes que ya son stickers (p. ej. los de WhatsApp) sin pasar por el editor. */
object StickerImporter {

    data class Result(val imported: Int, val skipped: Int)

    /** Bloqueante: llamar desde Dispatchers.IO. */
    fun import(context: Context, packId: String, uris: List<Uri>): Result {
        val repo = PackRepository.get(context)
        var ok = 0
        var skipped = 0
        for (uri in uris) {
            val pack = repo.getPack(packId) ?: break
            if (pack.isFull) {
                skipped++
                continue
            }
            runCatching {
                val bmp = ImageLoader.load(context, uri, StickerLimits.STICKER_SIZE)
                val sticker = StickerProcessor.fitTo512(bmp)
                repo.putSticker(
                    packId = packId,
                    webp = StickerProcessor.encodeWebp(sticker),
                    sourcePng = StickerProcessor.encodePng(bmp),
                    emojis = listOf(DEFAULT_EMOJI),
                    borderColor = null,
                    borderWidth = 0,
                )
            }.onSuccess { ok++ }.onFailure { skipped++ }
        }
        return Result(ok, skipped)
    }

    const val DEFAULT_EMOJI = "😀"
}
