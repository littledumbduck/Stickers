package com.littledumbduck.stickers.data

import android.content.Context
import android.net.Uri
import com.littledumbduck.stickers.image.ImageLoader

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
                if (pack.animated) {
                    // Si ya cumple los requisitos de WhatsApp se copia sin recomprimir (sin pérdida)
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null && StickerBuilder.isValidAnimatedSticker(bytes)) {
                        repo.putSticker(
                            packId = packId,
                            webp = bytes,
                            source = bytes,
                            sourceExtension = "webp",
                            emojis = listOf(DEFAULT_EMOJI),
                            borderColor = null,
                            borderWidth = 0,
                        )
                        return@runCatching
                    }
                }
                val img = ImageLoader.loadEditable(context, uri, allowAnimation = pack.animated)
                val out = StickerBuilder.build(img, pack.animated, null, 0, alreadyFinal = true)
                repo.putSticker(
                    packId = packId,
                    webp = out.webp,
                    source = out.source,
                    sourceExtension = out.sourceExtension,
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
