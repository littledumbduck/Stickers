package com.littledumbduck.stickers.whatsapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.littledumbduck.stickers.BuildConfig
import com.littledumbduck.stickers.data.PackRepository
import com.littledumbduck.stickers.data.StickerPack

/**
 * Proveedor de contenido con el contrato que espera WhatsApp (ver
 * github.com/WhatsApp/stickers). WhatsApp lo consulta para leer los metadatos de los paquetes,
 * la lista de stickers y los propios archivos de imagen.
 */
class StickerContentProvider : ContentProvider() {

    private val authority = BuildConfig.CONTENT_PROVIDER_AUTHORITY

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(authority, METADATA, CODE_METADATA)
        addURI(authority, "$METADATA/*", CODE_METADATA_SINGLE)
        addURI(authority, "$STICKERS/*", CODE_STICKERS)
        addURI(authority, "$STICKERS_ASSET/*/*", CODE_ASSET)
    }

    private val repo get() = PackRepository.get(requireNotNull(context))

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = when (matcher.match(uri)) {
            CODE_METADATA -> packsCursor(repo.packs.value.filter { it.validationError() == null })
            CODE_METADATA_SINGLE -> packsCursor(listOfNotNull(repo.getPack(uri.lastPathSegment.orEmpty())))
            CODE_STICKERS -> stickersCursor(repo.getPack(uri.lastPathSegment.orEmpty()))
            else -> throw IllegalArgumentException("URI desconocida: $uri")
        }
        cursor.setNotificationUri(requireNotNull(context).contentResolver, uri)
        return cursor
    }

    private fun packsCursor(packs: List<StickerPack>): Cursor {
        val c = MatrixCursor(
            arrayOf(
                STICKER_PACK_IDENTIFIER_IN_QUERY,
                STICKER_PACK_NAME_IN_QUERY,
                STICKER_PACK_PUBLISHER_IN_QUERY,
                STICKER_PACK_ICON_IN_QUERY,
                ANDROID_APP_DOWNLOAD_LINK_IN_QUERY,
                IOS_APP_DOWNLOAD_LINK_IN_QUERY,
                PUBLISHER_EMAIL,
                PUBLISHER_WEBSITE,
                PRIVACY_POLICY_WEBSITE,
                LICENSE_AGREEMENT_WEBSITE,
                IMAGE_DATA_VERSION,
                AVOID_CACHE,
                ANIMATED_STICKER_PACK,
            )
        )
        for (p in packs) {
            c.newRow()
                .add(p.identifier)
                .add(p.name)
                .add(p.publisher)
                .add(p.trayImageFile.orEmpty())
                .add("")
                .add("")
                .add("")
                .add("")
                .add("")
                .add("")
                .add(p.imageDataVersion.toString())
                .add(0)
                .add(if (p.animated) 1 else 0)
        }
        return c
    }

    private fun stickersCursor(pack: StickerPack?): Cursor {
        val c = MatrixCursor(
            arrayOf(
                STICKER_FILE_NAME_IN_QUERY,
                STICKER_FILE_EMOJI_IN_QUERY,
                STICKER_FILE_ACCESSIBILITY_TEXT_IN_QUERY,
            )
        )
        pack?.stickers?.forEach { s ->
            c.addRow(arrayOf<Any>(s.fileName, s.emojis.joinToString(","), ""))
        }
        return c
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? {
        if (matcher.match(uri) != CODE_ASSET) return null
        val segments = uri.pathSegments
        if (segments.size != 3) return null
        val packId = segments[1]
        val fileName = segments[2]
        val pack = repo.getPack(packId) ?: return null
        // Solo se sirven archivos que pertenecen al paquete (evita recorrer rutas arbitrarias).
        val allowed = fileName == pack.trayImageFile || pack.stickers.any { it.fileName == fileName }
        if (!allowed) return null
        val file = repo.file(packId, fileName)
        if (!file.exists()) return null
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return AssetFileDescriptor(pfd, 0, file.length())
    }

    override fun getType(uri: Uri): String = when (matcher.match(uri)) {
        CODE_METADATA -> "vnd.android.cursor.dir/vnd.$authority.$METADATA"
        CODE_METADATA_SINGLE -> "vnd.android.cursor.item/vnd.$authority.$METADATA"
        CODE_STICKERS -> "vnd.android.cursor.dir/vnd.$authority.$STICKERS"
        CODE_ASSET -> if (uri.lastPathSegment.orEmpty().endsWith(".png")) "image/png" else "image/webp"
        else -> throw IllegalArgumentException("URI desconocida: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("No soportado")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("No soportado")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("No soportado")

    companion object {
        const val METADATA = "metadata"
        const val STICKERS = "stickers"
        const val STICKERS_ASSET = "stickers_asset"

        private const val CODE_METADATA = 1
        private const val CODE_METADATA_SINGLE = 2
        private const val CODE_STICKERS = 3
        private const val CODE_ASSET = 4

        const val STICKER_PACK_IDENTIFIER_IN_QUERY = "sticker_pack_identifier"
        const val STICKER_PACK_NAME_IN_QUERY = "sticker_pack_name"
        const val STICKER_PACK_PUBLISHER_IN_QUERY = "sticker_pack_publisher"
        const val STICKER_PACK_ICON_IN_QUERY = "sticker_pack_icon"
        const val ANDROID_APP_DOWNLOAD_LINK_IN_QUERY = "android_play_store_link"
        const val IOS_APP_DOWNLOAD_LINK_IN_QUERY = "ios_app_download_link"
        const val PUBLISHER_EMAIL = "sticker_pack_publisher_email"
        const val PUBLISHER_WEBSITE = "sticker_pack_publisher_website"
        const val PRIVACY_POLICY_WEBSITE = "sticker_pack_privacy_policy_website"
        const val LICENSE_AGREEMENT_WEBSITE = "sticker_pack_license_agreement_website"
        const val IMAGE_DATA_VERSION = "image_data_version"
        const val AVOID_CACHE = "whatsapp_will_not_cache_stickers"
        const val ANIMATED_STICKER_PACK = "animated_sticker_pack"

        const val STICKER_FILE_NAME_IN_QUERY = "sticker_file_name"
        const val STICKER_FILE_EMOJI_IN_QUERY = "sticker_emoji"
        const val STICKER_FILE_ACCESSIBILITY_TEXT_IN_QUERY = "sticker_accessibility_text"
    }
}
