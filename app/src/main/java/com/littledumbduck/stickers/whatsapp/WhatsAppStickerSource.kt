package com.littledumbduck.stickers.whatsapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit

/** Sticker encontrado en la carpeta de WhatsApp. */
data class WaSticker(val uri: Uri, val name: String, val lastModified: Long)

/**
 * Lee los stickers que WhatsApp guarda en el almacenamiento compartido
 * (`Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers`). Desde Android 11 esa carpeta
 * solo es accesible a través del selector de carpetas del sistema (SAF), así que el usuario
 * concede acceso una vez y se guarda el permiso de forma persistente.
 */
object WhatsAppStickerSource {

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    private const val PREFS = "wa_source"
    private const val KEY_TREE = "tree_uri"

    private val KNOWN_FOLDERS = listOf(
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Stickers",
        "WhatsApp/Media/WhatsApp Stickers", // Android 10 o anterior
    )

    /** URI para abrir el selector directamente en la carpeta de stickers de WhatsApp. */
    fun initialFolderUri(business: Boolean = false): Uri {
        val path = if (business) KNOWN_FOLDERS[1] else KNOWN_FOLDERS[0]
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$path")
    }

    fun savedTree(context: Context): Uri? {
        val s = prefs(context).getString(KEY_TREE, null) ?: return null
        val uri = Uri.parse(s)
        val stillGranted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        return if (stillGranted) uri else null
    }

    fun saveTree(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs(context).edit { putString(KEY_TREE, uri.toString()) }
    }

    fun clearTree(context: Context) {
        savedTree(context)?.let { old ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        prefs(context).edit { remove(KEY_TREE) }
    }

    /**
     * Lista los .webp de la carpeta elegida. Si el usuario eligió una carpeta superior
     * (p. ej. "Media"), busca subcarpetas cuyo nombre contenga "Sticker".
     */
    fun listStickers(context: Context, treeUri: Uri): List<WaSticker> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val out = mutableListOf<WaSticker>()
        collect(context, treeUri, rootId, depth = 0, isStickerDir = true, out = out)
        return out.sortedByDescending { it.lastModified }
    }

    private fun collect(
        context: Context,
        treeUri: Uri,
        docId: String,
        depth: Int,
        isStickerDir: Boolean,
        out: MutableList<WaSticker>,
    ) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val subDirs = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1).orEmpty()
                val mime = c.getString(2).orEmpty()
                val modified = if (c.isNull(3)) 0L else c.getLong(3)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    subDirs += id to name
                } else if (isStickerDir && name.endsWith(".webp", ignoreCase = true)) {
                    out += WaSticker(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name, modified)
                }
            }
        }
        if (depth >= 5) return
        for ((id, name) in subDirs) {
            // Se baja por las carpetas hasta encontrar las de stickers, saltando las de medios
            // voluminosas que no interesan.
            if (name in SKIP_DIRS) continue
            val stickerDir = name.contains("sticker", ignoreCase = true)
            collect(context, treeUri, id, depth + 1, stickerDir, out)
        }
    }

    private val SKIP_DIRS = setOf(
        "WhatsApp Images", "WhatsApp Video", "WhatsApp Audio", "WhatsApp Documents",
        "WhatsApp Voice Notes", "WhatsApp Animated Gifs", "WhatsApp Profile Photos",
        "WhatsApp Video Notes", "Databases", "Backups", ".Statuses", ".Links", ".Shared",
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
