package com.littledumbduck.stickers.data

import org.json.JSONArray
import org.json.JSONObject

/** Límites impuestos por WhatsApp a los paquetes de stickers estáticos. */
object StickerLimits {
    const val MIN_STICKERS = 3
    const val MAX_STICKERS = 30
    const val MAX_EMOJIS = 3
    const val STICKER_SIZE = 512
    const val STICKER_MAX_BYTES = 100 * 1024
    const val TRAY_SIZE = 96
    const val TRAY_MAX_BYTES = 50 * 1024
    const val MAX_TEXT = 128
}

data class Sticker(
    /** Archivo .webp de 512x512 que se entrega a WhatsApp. */
    val fileName: String,
    val emojis: List<String>,
    /** PNG con la imagen recortada sin borde, para poder volver a editar el sticker. */
    val sourceFile: String? = null,
    /** Color del borde (ARGB) o null si no tiene borde. */
    val borderColor: Int? = null,
    /** Grosor del borde en píxeles sobre el lienzo de 512. */
    val borderWidth: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("file", fileName)
        put("emojis", JSONArray(emojis))
        sourceFile?.let { put("source", it) }
        borderColor?.let { put("borderColor", it) }
        put("borderWidth", borderWidth)
    }

    companion object {
        fun fromJson(o: JSONObject): Sticker {
            val emojis = o.optJSONArray("emojis")?.let { arr ->
                List(arr.length()) { arr.getString(it) }
            } ?: emptyList()
            return Sticker(
                fileName = o.getString("file"),
                emojis = emojis,
                sourceFile = o.optString("source").ifEmpty { null },
                borderColor = if (o.has("borderColor")) o.getInt("borderColor") else null,
                borderWidth = o.optInt("borderWidth", 0),
            )
        }
    }
}

data class StickerPack(
    val identifier: String,
    val name: String,
    val publisher: String,
    val trayImageFile: String?,
    /** Si es true el icono de la bandeja se genera automáticamente con el primer sticker. */
    val trayAuto: Boolean = true,
    /** WhatsApp vuelve a leer las imágenes cuando cambia este número. */
    val imageDataVersion: Int = 1,
    val stickers: List<Sticker> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** Devuelve un mensaje con el motivo por el que no se puede enviar a WhatsApp, o null si es válido. */
    fun validationError(): String? = when {
        name.isBlank() -> "El paquete necesita un nombre"
        publisher.isBlank() -> "El paquete necesita un autor"
        stickers.size < StickerLimits.MIN_STICKERS ->
            "Añade al menos ${StickerLimits.MIN_STICKERS} stickers (tienes ${stickers.size})"
        stickers.size > StickerLimits.MAX_STICKERS ->
            "Máximo ${StickerLimits.MAX_STICKERS} stickers por paquete"
        trayImageFile == null -> "Falta el icono del paquete"
        else -> null
    }

    val isFull: Boolean get() = stickers.size >= StickerLimits.MAX_STICKERS

    fun toJson(): JSONObject = JSONObject().apply {
        put("identifier", identifier)
        put("name", name)
        put("publisher", publisher)
        trayImageFile?.let { put("tray", it) }
        put("trayAuto", trayAuto)
        put("imageDataVersion", imageDataVersion)
        put("createdAt", createdAt)
        put("stickers", JSONArray().apply { stickers.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): StickerPack {
            val arr = o.optJSONArray("stickers") ?: JSONArray()
            return StickerPack(
                identifier = o.getString("identifier"),
                name = o.optString("name"),
                publisher = o.optString("publisher"),
                trayImageFile = o.optString("tray").ifEmpty { null },
                trayAuto = o.optBoolean("trayAuto", true),
                imageDataVersion = o.optInt("imageDataVersion", 1),
                createdAt = o.optLong("createdAt", 0L),
                stickers = List(arr.length()) { Sticker.fromJson(arr.getJSONObject(it)) },
            )
        }
    }
}
