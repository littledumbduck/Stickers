package com.littledumbduck.stickers.audio

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.littledumbduck.stickers.BuildConfig
import com.littledumbduck.stickers.data.PackRepository
import com.littledumbduck.stickers.data.Sticker
import com.littledumbduck.stickers.data.StickerPack
import com.littledumbduck.stickers.image.WebpCodec
import java.io.File

/**
 * Lleva los stickers con sonido a WhatsApp. WhatsApp aún no deja que otras apps incluyan el
 * sonido en un paquete (github.com/WhatsApp/stickers/issues/1163), así que se generan:
 *  - un vídeo MP4 (sticker + sonido) para crear el sticker en WhatsApp con "Crear" →
 *    "Mantener audio original";
 *  - el audio .m4a suelto, para la opción "Importar archivo" del editor de WhatsApp.
 * Bloqueante salvo los intents: llamar desde Dispatchers.IO.
 */
object SoundStickerExport {

    const val ALBUM = "Stickers con sonido"

    /** Fondos para el vídeo (no tiene transparencia). */
    val BACKGROUNDS = listOf(
        0xFFFFFFFF.toInt() to "Blanco",
        0xFF000000.toInt() to "Negro",
        0xFF00B140.toInt() to "Verde croma",
        0xFF0047BB.toInt() to "Azul croma",
    )

    private fun exportDir(context: Context) = File(context.cacheDir, "exports").apply { mkdirs() }

    private fun baseName(pack: StickerPack, st: Sticker): String {
        val clean = pack.name.replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_').take(30).ifEmpty { "sticker" }
        return "${clean}_${st.fileName.removePrefix("sticker_").removeSuffix(".webp")}"
    }

    /** Genera el MP4 del sticker (con su sonido si lo tiene) en la caché y lo devuelve. */
    fun exportVideo(context: Context, pack: StickerPack, st: Sticker, background: Int): File {
        val repo = PackRepository.get(context)
        val bytes = repo.file(pack.identifier, st.fileName).readBytes()
        val anim = WebpCodec.decodeAnimated(bytes, StickerVideoExporter.SIZE, 400)
        val (frames, durations) = if (anim != null && anim.isAnimated) anim.frames to anim.durations
        else listOf(requireNotNull(WebpCodec.decodeFirstFrame(bytes)) { "No se pudo leer el sticker" }) to listOf(1000)
        val audio = st.audioFile?.let { repo.file(pack.identifier, it) }?.takeIf { it.exists() }
        val out = File(exportDir(context), baseName(pack, st) + ".mp4")
        StickerVideoExporter.export(frames, durations, audio, background, out)
        return out
    }

    /** Copia el audio del sticker a la caché con un nombre legible (para compartirlo). */
    fun exportAudio(context: Context, pack: StickerPack, st: Sticker): File {
        val src = PackRepository.get(context).file(pack.identifier, requireNotNull(st.audioFile) { "El sticker no tiene sonido" })
        val out = File(exportDir(context), baseName(pack, st) + ".m4a")
        src.copyTo(out, overwrite = true)
        return out
    }

    /** ¿Hace falta pedir permiso de almacenamiento para guardar en la galería? (solo Android 9 o anterior) */
    val needsStoragePermission get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Guarda un vídeo en la galería (Movies/Stickers con sonido) y devuelve su URI. */
    fun saveVideoToGallery(context: Context, file: File): Uri =
        saveToMediaStore(context, file, "video/mp4", Environment.DIRECTORY_MOVIES, video = true)

    /** Guarda el audio en Música/Stickers con sonido, donde lo encuentra "Importar archivo". */
    fun saveAudioToMusic(context: Context, file: File): Uri =
        saveToMediaStore(context, file, "audio/mp4", Environment.DIRECTORY_MUSIC, video = false)

    private fun saveToMediaStore(context: Context, file: File, mime: String, dir: String, video: Boolean): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/$ALBUM")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = requireNotNull(resolver.insert(collection, values)) { "No se pudo guardar en la galería" }
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        }
        @Suppress("DEPRECATION")
        val target = File(Environment.getExternalStoragePublicDirectory(dir), ALBUM).apply { mkdirs() }
        val dest = File(target, file.name)
        file.copyTo(dest, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
        return Uri.fromFile(dest)
    }

    fun contentUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)

    /** Intent para compartir un archivo (vídeo o audio) con cualquier app. */
    fun shareIntent(context: Context, file: File, mime: String, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, contentUri(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title)
    }

    /** Abre WhatsApp (o WhatsApp Business) para crear allí el sticker. */
    fun openWhatsAppIntent(context: Context, pkg: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(pkg)
}
