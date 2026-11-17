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
import com.littledumbduck.stickers.data.StickerImporter
import com.littledumbduck.stickers.image.ImageLoader
import com.littledumbduck.stickers.image.SmartCutout
import com.littledumbduck.stickers.image.StickerProcessor
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

    var bitmap by mutableStateOf<Bitmap?>(null)
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

    private val history = ArrayDeque<Bitmap>()
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
        runBusy("Cargando imagen…", onError = { loadError = it.message ?: "No se pudo abrir la imagen" }) {
            if (stickerFile != null) {
                val st = repo.getPack(packId)?.stickers?.firstOrNull { it.fileName == stickerFile }
                    ?: error("El sticker ya no existe")
                emojis = st.emojis.ifEmpty { emojis }
                borderColor = st.borderColor
                if (st.borderColor != null) borderWidth = st.borderWidth
                val srcFile = st.sourceFile?.let { repo.file(packId, it) }?.takeIf { it.exists() }
                    ?: repo.file(packId, st.fileName)
                bitmap = withContext(Dispatchers.IO) { ImageLoader.load(ctx, srcFile) }
                tool = EditorTool.BORDER
            } else {
                val uri = requireNotNull(source) { "No hay imagen" }
                val bmp = withContext(Dispatchers.IO) { ImageLoader.load(ctx, uri) }
                bitmap = bmp
                // Si la imagen ya tiene transparencia (p. ej. otro sticker) se va directamente al borde.
                if (bmp.hasAlpha() && hasTransparentPixels(bmp)) tool = EditorTool.BORDER
            }
        }
    }

    // ---------------- Operaciones ----------------

    fun applyCrop(left: Float, top: Float, right: Float, bottom: Float) = transform("Recortando…") {
        StickerProcessor.crop(it, left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
    }

    fun applyLasso(points: List<PointF>) = transform("Recortando…") { StickerProcessor.lassoCut(it, points) }

    fun applyErase(points: List<PointF>, width: Float) = transform(null) { StickerProcessor.erase(it, points, width) }

    fun rotate() = transform(null) { StickerProcessor.rotate90(it) }

    fun flip() = transform(null) { StickerProcessor.flipHorizontal(it) }

    fun applySmartCutout() {
        val src = bitmap ?: return
        runBusy("Detectando el sujeto… (la primera vez se descarga el modelo)") {
            val out = SmartCutout.removeBackground(getApplication(), src)
            push(out)
            message = "Fondo eliminado. Puedes retocar con el borrador o el lazo."
        }
    }

    fun undo() {
        val prev = history.removeLastOrNull() ?: return
        bitmap = prev
        canUndo = history.isNotEmpty()
    }

    /** Genera la vista previa final de 512x512 con el borde elegido. */
    suspend fun renderPreview(): Bitmap? {
        val src = bitmap ?: return null
        val color = borderColor
        val width = borderWidth
        return withContext(Dispatchers.Default) { StickerProcessor.composeSticker(src, color, width) }
    }

    fun save(onDone: () -> Unit) {
        val src = bitmap ?: return
        val color = borderColor
        val width = borderWidth
        val emo = emojis
        runBusy("Guardando sticker…") {
            withContext(Dispatchers.Default) {
                val final = StickerProcessor.composeSticker(src, color, width)
                val webp = StickerProcessor.encodeWebp(final)
                val png = StickerProcessor.encodePng(src)
                withContext(Dispatchers.IO) {
                    repo.putSticker(
                        packId = packId,
                        webp = webp,
                        sourcePng = png,
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

    private fun transform(busy: String?, op: (Bitmap) -> Bitmap) {
        val src = bitmap ?: return
        if (busyMessage != null) return
        val job: suspend () -> Unit = {
            val out = withContext(Dispatchers.Default) { op(src) }
            if (out !== src) push(out)
        }
        if (busy != null) runBusy(busy) { job() } else viewModelScope.launch {
            runCatching { job() }.onFailure { message = it.message ?: "Error" }
        }
    }

    private fun push(newBitmap: Bitmap) {
        bitmap?.let {
            history.addLast(it)
            while (history.size > MAX_HISTORY) history.removeFirst()
        }
        bitmap = newBitmap
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            } finally {
                busyMessage = null
            }
        }
    }

    private fun hasTransparentPixels(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        // Basta con mirar las esquinas y los bordes
        val samples = listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1, w / 2 to 0, 0 to h / 2)
        return samples.any { (x, y) -> Color.alpha(bmp.getPixel(x, y)) < 250 }
    }

    companion object {
        private const val MAX_HISTORY = 8
    }
}
