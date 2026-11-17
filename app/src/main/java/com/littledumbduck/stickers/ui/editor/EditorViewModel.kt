package com.littledumbduck.stickers.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.littledumbduck.stickers.data.PackRepository
import com.littledumbduck.stickers.data.StickerBuilder
import com.littledumbduck.stickers.data.StickerImporter
import com.littledumbduck.stickers.image.EditImage
import com.littledumbduck.stickers.image.ImageLoader
import com.littledumbduck.stickers.image.SmartCutout
import com.littledumbduck.stickers.image.StickerProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class EditorTool(val label: String) {
    CROP("Recortar"),
    LASSO("Lazo"),
    SMART("Inteligente"),
    ERASE("Borrador"),
    BORDER("Borde"),
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PackRepository.get(app)

    var image by mutableStateOf<EditImage?>(null)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var busyMessage by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
    var loadError by mutableStateOf<String?>(null)
        private set

    var tool by mutableStateOf(EditorTool.CROP)

    /** null = sin borde. */
    var borderColor by mutableStateOf<Int?>(Color.WHITE)
    var borderWidth by mutableStateOf(14)
    var emojis by mutableStateOf(listOf(StickerImporter.DEFAULT_EMOJI))

    /** Tipo del paquete de destino. */
    var animatedPack by mutableStateOf(false)
        private set

    private val history = ArrayDeque<EditImage>()
    private var loadedKey: String? = null
    private var packId: String = ""
    private var replaceFile: String? = null

    val isEditingExisting get() = replaceFile != null

    fun load(packId: String, source: Uri?, stickerFile: String?) {
        val key = "$packId|$source|$stickerFile"
        if (loadedKey == key) return
        loadedKey = key
        this.packId = packId
        this.replaceFile = stickerFile
        val ctx = getApplication<Application>()
        runBusy("Cargando…", onError = { loadError = it.message ?: "No se pudo abrir la imagen" }) {
            val pack = repo.getPack(packId) ?: error("El paquete ya no existe")
            animatedPack = pack.animated
            if (stickerFile != null) {
                val st = pack.stickers.firstOrNull { it.fileName == stickerFile } ?: error("El sticker ya no existe")
                emojis = st.emojis.ifEmpty { emojis }
                borderColor = st.borderColor
                if (st.borderColor != null) borderWidth = st.borderWidth
                val srcFile = st.sourceFile?.let { repo.file(packId, it) }?.takeIf { it.exists() }
                    ?: repo.file(packId, st.fileName)
                image = withContext(Dispatchers.IO) { ImageLoader.loadEditable(ctx, srcFile, pack.animated) }
                tool = EditorTool.BORDER
            } else {
                val uri = requireNotNull(source) { "No hay imagen" }
                val img = withContext(Dispatchers.IO) { ImageLoader.loadEditable(ctx, uri, pack.animated) }
                image = img
                // Si la imagen ya tiene transparencia (p. ej. otro sticker) se va directamente al borde.
                if (hasTransparentPixels(img.first)) tool = EditorTool.BORDER
            }
        }
    }

    // ---------------- Operaciones ----------------

    fun applyCrop(left: Float, top: Float, right: Float, bottom: Float) = transform("Recortando…") { img ->
        img.map { StickerProcessor.crop(it, left.toInt(), top.toInt(), right.toInt(), bottom.toInt()) }
    }

    fun applyLasso(points: List<PointF>) = transform("Recortando…") { StickerProcessor.lassoCut(it, points) }

    fun applyErase(points: List<PointF>, width: Float) =
        transform(if (image?.isAnimated == true) "Borrando…" else null) { img ->
            img.map { StickerProcessor.erase(it, points, width) }
        }

    fun rotate() = transform(null) { it.map(StickerProcessor::rotate90) }

    fun flip() = transform(null) { it.map(StickerProcessor::flipHorizontal) }

    fun applySmartCutout() {
        val src = image ?: return
        if (busyMessage != null) return
        runBusy("Detectando el sujeto…") {
            val result = withContext(Dispatchers.Default) {
                SmartCutout.removeBackground(getApplication(), src) { done, total ->
                    if (total > 1) viewModelScope.launch {
                        // Si ya terminó no se vuelve a mostrar el aviso
                        if (busyMessage != null) busyMessage = "Quitando el fondo… fotograma ${done + 1} de $total"
                    }
                }
            }
            push(result.image)
            message = if (result.usedFallback) {
                "Fondo eliminado con el modo sin conexión (solo detecta personas). Retoca con el borrador o el lazo si hace falta."
            } else {
                "Fondo eliminado. Puedes retocar con el borrador o el lazo."
            }
        }
    }

    fun undo() {
        val prev = history.removeLastOrNull() ?: return
        image = prev
        canUndo = history.isNotEmpty()
    }

    /** Vista previa final de 512x512 con el borde elegido (del fotograma indicado). */
    suspend fun renderPreview(frame: Int): Bitmap? {
        val img = image ?: return null
        val color = borderColor
        val width = borderWidth
        return withContext(Dispatchers.Default) {
            // Se usa el encuadre de toda la animación para que la vista previa sea fiel
            val bounds = StickerProcessor.opaqueBounds(img.frames)
            val f = img.frames[frame.coerceIn(0, img.frames.lastIndex)]
            if (!img.isAnimated || bounds == null) StickerProcessor.composeSticker(f, color, width)
            else StickerProcessor.composeSticker(
                Bitmap.createBitmap(f, bounds.left, bounds.top, bounds.width(), bounds.height()), color, width,
            )
        }
    }

    fun save(onDone: () -> Unit) {
        val img = image ?: return
        val color = borderColor
        val width = borderWidth
        val emo = emojis
        val animated = animatedPack
        runBusy(if (img.isAnimated && animated) "Generando sticker animado…" else "Guardando sticker…") {
            withContext(Dispatchers.Default) {
                val out = StickerBuilder.build(img, animated, color, width)
                withContext(Dispatchers.IO) {
                    repo.putSticker(
                        packId = packId,
                        webp = out.webp,
                        source = out.source,
                        sourceExtension = out.sourceExtension,
                        emojis = emo,
                        borderColor = color,
                        borderWidth = if (color != null) width else 0,
                        replaceFileName = replaceFile,
                    )
                }
            }
            onDone()
        }
    }

    // ---------------- Internos ----------------

    private fun transform(busy: String?, op: (EditImage) -> EditImage) {
        val src = image ?: return
        if (busyMessage != null) return
        val block: suspend () -> Unit = {
            val out = withContext(Dispatchers.Default) { op(src) }
            if (out !== src) push(out)
        }
        if (busy != null) {
            runBusy(busy, block = block)
        } else {
            viewModelScope.launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = e.message ?: "Error"
                }
            }
        }
    }

    /** Guarda el estado anterior para deshacer, sin superar un presupuesto de memoria. */
    private fun push(newImage: EditImage) {
        image?.let { history.addLast(it) }
        var total = history.sumOf { it.byteCount } + newImage.byteCount
        while (history.isNotEmpty() && (history.size > MAX_HISTORY || total > MAX_HISTORY_BYTES)) {
            total -= history.removeFirst().byteCount
        }
        image = newImage
        canUndo = history.isNotEmpty()
    }

    private fun runBusy(
        text: String,
        onError: (Throwable) -> Unit = { message = it.message ?: "Ha ocurrido un error" },
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            busyMessage = text
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            } catch (e: OutOfMemoryError) {
                onError(Exception("No hay memoria suficiente para esta imagen"))
            } finally {
                busyMessage = null
            }
        }
    }

    private fun hasTransparentPixels(bmp: Bitmap): Boolean {
        if (!bmp.hasAlpha()) return false
        val w = bmp.width
        val h = bmp.height
        // Basta con mirar las esquinas y los bordes
        val samples = listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1, w / 2 to 0, 0 to h / 2)
        return samples.any { (x, y) -> Color.alpha(bmp.getPixel(x, y)) < 250 }
    }

    companion object {
        private const val MAX_HISTORY = 8
        private const val MAX_HISTORY_BYTES = 160L * 1024 * 1024
    }
}
