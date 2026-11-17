package com.littledumbduck.stickers.data

import android.content.Context
import com.littledumbduck.stickers.image.StickerProcessor
import com.littledumbduck.stickers.image.WebpCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Almacena los paquetes en `filesDir/packs/<id>/` con un `pack.json`, los .webp de cada sticker,
 * los PNG originales (para reeditar) y el icono de la bandeja.
 *
 * Todas las operaciones son bloqueantes: llámalas desde Dispatchers.IO.
 */
class PackRepository private constructor(context: Context) {

    private val root = File(context.filesDir, "packs").apply { mkdirs() }
    private val lock = Any()
    private val _packs = MutableStateFlow(loadAll())
    val packs: StateFlow<List<StickerPack>> = _packs.asStateFlow()

    fun getPack(id: String): StickerPack? = _packs.value.firstOrNull { it.identifier == id }

    fun packDir(id: String): File = File(root, id)

    fun file(packId: String, fileName: String): File = File(packDir(packId), fileName)

    // ---------- Paquetes ----------

    fun createPack(name: String, publisher: String, animated: Boolean = false): StickerPack = synchronized(lock) {
        val id = "pack_" + System.currentTimeMillis()
        packDir(id).mkdirs()
        val pack = StickerPack(
            identifier = id,
            name = name.trim().take(StickerLimits.MAX_TEXT),
            publisher = publisher.trim().take(StickerLimits.MAX_TEXT),
            trayImageFile = null,
            animated = animated,
        )
        save(pack)
        pack
    }

    fun updatePackInfo(id: String, name: String, publisher: String) = mutate(id) {
        it.copy(
            name = name.trim().take(StickerLimits.MAX_TEXT),
            publisher = publisher.trim().take(StickerLimits.MAX_TEXT),
        )
    }

    fun deletePack(id: String) = synchronized(lock) {
        packDir(id).deleteRecursively()
        _packs.value = _packs.value.filterNot { it.identifier == id }
    }

    // ---------- Stickers ----------

    /**
     * Añade un sticker o, si [replaceFileName] no es null, sustituye a uno existente conservando su posición.
     */
    fun putSticker(
        packId: String,
        webp: ByteArray,
        source: ByteArray?,
        sourceExtension: String = "png",
        emojis: List<String>,
        borderColor: Int?,
        borderWidth: Int,
        replaceFileName: String? = null,
    ): Sticker = synchronized(lock) {
        val pack = requireNotNull(getPack(packId)) { "El paquete no existe" }
        if (replaceFileName == null && pack.isFull) {
            error("El paquete ya tiene ${StickerLimits.MAX_STICKERS} stickers")
        }
        val key = UUID.randomUUID().toString().take(8)
        val dir = packDir(packId).apply { mkdirs() }
        val webpName = "sticker_$key.webp"
        File(dir, webpName).writeBytes(webp)
        val srcName = source?.let { bytes ->
            "source_$key.$sourceExtension".also { File(dir, it).writeBytes(bytes) }
        }
        val sticker = Sticker(
            fileName = webpName,
            emojis = emojis.take(StickerLimits.MAX_EMOJIS),
            sourceFile = srcName,
            borderColor = borderColor,
            borderWidth = borderWidth,
        )
        val newList = if (replaceFileName != null) {
            val old = pack.stickers.firstOrNull { it.fileName == replaceFileName }
            old?.let { deleteStickerFiles(packId, it) }
            if (old == null) pack.stickers + sticker
            else pack.stickers.map { if (it.fileName == replaceFileName) sticker else it }
        } else {
            pack.stickers + sticker
        }
        val updated = refreshTray(pack.copy(stickers = newList, imageDataVersion = pack.imageDataVersion + 1))
        save(updated)
        sticker
    }

    fun deleteSticker(packId: String, fileName: String) = mutate(packId) { pack ->
        val st = pack.stickers.firstOrNull { it.fileName == fileName } ?: return@mutate pack
        deleteStickerFiles(packId, st)
        refreshTray(
            pack.copy(
                stickers = pack.stickers - st,
                imageDataVersion = pack.imageDataVersion + 1,
            )
        )
    }

    fun updateEmojis(packId: String, fileName: String, emojis: List<String>) = mutate(packId) { pack ->
        pack.copy(
            stickers = pack.stickers.map {
                if (it.fileName == fileName) it.copy(emojis = emojis.take(StickerLimits.MAX_EMOJIS)) else it
            },
            imageDataVersion = pack.imageDataVersion + 1,
        )
    }

    fun moveSticker(packId: String, fileName: String, delta: Int) = mutate(packId) { pack ->
        val list = pack.stickers.toMutableList()
        val from = list.indexOfFirst { it.fileName == fileName }
        if (from < 0) return@mutate pack
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to == from) return@mutate pack
        list.add(to, list.removeAt(from))
        refreshTray(pack.copy(stickers = list, imageDataVersion = pack.imageDataVersion + 1))
    }

    /** Usa el sticker indicado como icono del paquete (desactiva el icono automático). */
    fun setTrayFromSticker(packId: String, fileName: String) = mutate(packId) { pack ->
        val st = pack.stickers.firstOrNull { it.fileName == fileName } ?: return@mutate pack
        writeTray(pack.copy(trayAuto = false, imageDataVersion = pack.imageDataVersion + 1), st)
    }

    // ---------- Internos ----------

    private fun deleteStickerFiles(packId: String, st: Sticker) {
        file(packId, st.fileName).delete()
        st.sourceFile?.let { file(packId, it).delete() }
    }

    /** Regenera el icono si es automático o si ya no existe. */
    private fun refreshTray(pack: StickerPack): StickerPack {
        val first = pack.stickers.firstOrNull()
        if (first == null) {
            pack.trayImageFile?.let { file(pack.identifier, it).delete() }
            return pack.copy(trayImageFile = null, trayAuto = true)
        }
        val trayMissing = pack.trayImageFile == null || !file(pack.identifier, pack.trayImageFile).exists()
        return if (pack.trayAuto || trayMissing) writeTray(pack.copy(trayAuto = true), first) else pack
    }

    private fun writeTray(pack: StickerPack, from: Sticker): StickerPack {
        // El icono siempre es estático: primer fotograma si el sticker es animado
        val bytes = file(pack.identifier, from.fileName).takeIf { it.exists() }?.readBytes() ?: return pack
        val bmp = WebpCodec.decodeFirstFrame(bytes) ?: return pack
        val png = StickerProcessor.makeTrayIcon(bmp)
        pack.trayImageFile?.let { file(pack.identifier, it).delete() }
        val name = "tray_${pack.imageDataVersion}_${UUID.randomUUID().toString().take(4)}.png"
        file(pack.identifier, name).writeBytes(png)
        return pack.copy(trayImageFile = name)
    }

    private fun mutate(id: String, block: (StickerPack) -> StickerPack) = synchronized(lock) {
        val pack = getPack(id) ?: return@synchronized
        save(block(pack))
    }

    private fun save(pack: StickerPack) {
        val dir = packDir(pack.identifier).apply { mkdirs() }
        val tmp = File(dir, "pack.json.tmp")
        tmp.writeText(pack.toJson().toString())
        tmp.renameTo(File(dir, "pack.json"))
        val list = _packs.value.toMutableList()
        val idx = list.indexOfFirst { it.identifier == pack.identifier }
        if (idx >= 0) list[idx] = pack else list.add(pack)
        _packs.value = list.sortedByDescending { it.createdAt }
    }

    private fun loadAll(): List<StickerPack> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir ->
                runCatching {
                    StickerPack.fromJson(JSONObject(File(dir, "pack.json").readText()))
                }.getOrNull()
            }
            .sortedByDescending { it.createdAt }

    companion object {
        @Volatile
        private var instance: PackRepository? = null

        fun get(context: Context): PackRepository =
            instance ?: synchronized(this) {
                instance ?: PackRepository(context.applicationContext).also { instance = it }
            }
    }
}
