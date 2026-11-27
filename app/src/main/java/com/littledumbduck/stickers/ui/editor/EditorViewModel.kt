package com.littledumbduck.stickers.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.media.MediaPlayer
import com.littledumbduck.stickers.audio.AudioBed
import com.littledumbduck.stickers.audio.AudioCodec
import com.littledumbduck.stickers.audio.AudioKind
import com.littledumbduck.stickers.audio.SoundMixer
import com.littledumbduck.stickers.audio.SoundSpec
import com.littledumbduck.stickers.data.PackRepository
import com.littledumbduck.stickers.data.StickerAudio
import java.io.File
import com.littledumbduck.stickers.data.StickerBuilder
import com.littledumbduck.stickers.data.StickerImporter
import com.littledumbduck.stickers.image.ClipSource
import com.littledumbduck.stickers.image.EditImage
import com.littledumbduck.stickers.image.FramesClip
import com.littledumbduck.stickers.image.ImageLoader
import com.littledumbduck.stickers.image.SmartCutout
import com.littledumbduck.stickers.image.StickerProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

data class SeekRequest(val ms: Long, val precise: Boolean, val id: Int)

enum class EditorTool(val label: String) {
    TRIM("Duración"),
    CROP("Recortar"),
    LASSO("Lazo"),
    SMART("Fondo"),
    ERASE("Borrar"),
    BORDER("Borde"),
    AUDIO("Sonido"),
}

/**
 * Operación del editor. Se guardan para poder volver a aplicarlas cuando cambia el tramo de un
 * vídeo/GIF: los fotogramas nuevos tienen el mismo tamaño, así que las coordenadas siguen valiendo.
 */
private sealed interface EditOp {
    data class Crop(val l: Float, val t: Float, val r: Float, val b: Float) : EditOp
    class Lasso(val points: List<PointF>) : EditOp
    class Erase(val points: List<PointF>, val width: Float) : EditOp
    data object Rotate : EditOp
    data object Flip : EditOp
    data object Smart : EditOp
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
        private set

    /** null = sin borde. */
    var borderColor by mutableStateOf<Int?>(Color.WHITE)
    var borderWidth by mutableStateOf(14)
    var emojis by mutableStateOf(listOf(StickerImporter.DEFAULT_EMOJI))

    /** Tipo del paquete de destino. */
    var animatedPack by mutableStateOf(false)
        private set

    // ---------------- Clip (vídeo / GIF / WebP animado) ----------------

    /** Fuente completa de la animación, para poder elegir otro tramo. null si la imagen es estática. */
    var clip by mutableStateOf<ClipSource?>(null)
        private set

    /** Tramo elegido en la línea de tiempo (aún sin aplicar). */
    var trimStart by mutableLongStateOf(0L)
        private set
    var trimEnd by mutableLongStateOf(0L)
        private set

    /** Tramo con el que se generaron los fotogramas actuales. */
    var appliedStart by mutableLongStateOf(0L)
        private set
    var appliedEnd by mutableLongStateOf(0L)
        private set

    val trimDirty
        get() = clip != null && (
            stillMode != appliedStill ||
                if (stillMode) framePos != appliedStart else (trimStart != appliedStart || trimEnd != appliedEnd)
            )

    // ---- Fotograma fijo (elegir un instante de un vídeo/GIF) ----

    /**
     * true: el sticker es un único fotograma elegido del clip. Siempre en paquetes estáticos;
     * opcional en los animados (p. ej. una foto fija con el audio del vídeo).
     */
    var stillMode by mutableStateOf(false)
        private set

    /** Instante del fotograma elegido (aún sin aplicar si difiere de [appliedStart]). */
    var framePos by mutableLongStateOf(0L)
        private set
    private var appliedStill = false

    // ---------------- Sonido ----------------

    /** Origen del sonido, o null si el sticker es mudo. */
    var audioKind by mutableStateOf<AudioKind?>(null)
        private set
    var audioUri by mutableStateOf<Uri?>(null)
        private set
    /** Nombre que se muestra de la fuente (archivo, vídeo…). */
    var audioName by mutableStateOf<String?>(null)
        private set
    /** Duración total de la fuente de sonido. */
    var audioSourceMs by mutableLongStateOf(0L)
        private set
    var audioStart by mutableLongStateOf(0L)
        private set
    var audioEnd by mutableLongStateOf(0L)
        private set
    /** Forma de onda de la fuente completa. */
    var audioPeaks by mutableStateOf<FloatArray?>(null)
        private set
    /** Con el audio del propio vídeo: el sonido sigue al tramo elegido en "Duración". */
    var audioSync by mutableStateOf(true)
    var audioVolume by mutableFloatStateOf(1f)
    var fadeIn by mutableStateOf(false)
    var fadeOut by mutableStateOf(false)
    var normalizeAudio by mutableStateOf(false)
    /** Volumen del audio original del vídeo mezclado por debajo (0 = sin mezclar). */
    var bedVolume by mutableFloatStateOf(0f)
    /** El vídeo de origen tiene pista de sonido. */
    var videoHasAudio by mutableStateOf(false)
        private set
    var audioPlaying by mutableStateOf(false)
        private set
    /** Cambia cada vez que empieza a sonar la vista previa (para sincronizar la animación). */
    var audioPlayToken by mutableIntStateOf(0)
        private set
    private var player: MediaPlayer? = null
    private var previewKey: SoundSpec? = null
    private val previewFile by lazy { File(getApplication<Application>().cacheDir, "sound_preview.m4a") }
    /** Copia del sonido del sticker que se está reeditando (el original se borra al guardar). */
    private var existingAudio: File? = null
    /** Origen con el que se creó el sonido guardado. */
    private var existingKind: String? = null

    /** El sticker que se reedita ya tenía sonido (se puede recuperar tras quitarlo o cambiarlo). */
    val hasSavedAudio get() = existingAudio != null

    // ---- Vista previa de la fuente completa en "Duración" ----

    /** Posición (ms) de la vista previa del vídeo/GIF completo. */
    var previewPos by mutableLongStateOf(0L)
        private set
    var previewPlaying by mutableStateOf(true)

    /** true: se ve el vídeo entero; false: se repite solo el tramo elegido. */
    var previewWhole by mutableStateOf(true)
        private set

    /** Petición de salto en el reproductor (el contador permite repetir el mismo instante). */
    var seekRequest by mutableStateOf<SeekRequest?>(null)
        private set
    private var seekCounter = 0
    private var lastScrubT: Long? = null

    /** Se está arrastrando un tirador de la línea de tiempo. */
    var scrubbing by mutableStateOf(false)
        private set

    /** Fotograma que se previsualiza mientras se arrastra en la línea de tiempo. */
    var scrubFrame by mutableStateOf<Bitmap?>(null)
        private set
    private val scrubRequests = MutableStateFlow<Long?>(null)

    // ---------------- Historial ----------------

    /** Fotogramas del tramo aplicado, antes de cualquier edición. */
    private var baseImage: EditImage? = null
    private val ops = ArrayList<EditOp>()

    /** Imagen previa a cada operación (puede faltar si se liberó por memoria). */
    private val history = ArrayDeque<Pair<Int, EditImage>>()

    private var loadedKey: String? = null
    private var packId: String = ""
    private var replaceFile: String? = null

    val isEditingExisting get() = replaceFile != null

    /** Hay ediciones o un sonido nuevo que se perderían al salir. */
    val hasUnsavedChanges get() = canUndo || (audioKind != null && audioKind != AudioKind.EXISTING)

    init {
        // Vista previa al arrastrar: siempre se atiende la última posición pedida
        viewModelScope.launch {
            scrubRequests.collectLatest { t ->
                val c = clip
                scrubFrame = if (t == null || c == null) null
                else runCatching { withContext(Dispatchers.IO) { c.frameAt(t, ImageLoader.MAX_ANIMATED_DIMENSION) } }
                    .getOrNull() ?: scrubFrame
            }
        }
    }

    fun load(packId: String, source: Uri?, stickerFile: String?, initialTool: EditorTool? = null) {
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
                val img = withContext(Dispatchers.IO) { ImageLoader.loadEditable(ctx, srcFile, pack.animated) }
                // Un sticker animado ya guardado también se puede acortar
                if (img.isAnimated) openClip(FramesClip(img), wholeClip = true) else setBase(img)
                tool = EditorTool.BORDER
                st.audioFile?.let { repo.file(packId, it) }?.takeIf { it.exists() }?.let { f ->
                    val copy = File(ctx.cacheDir, "sound_existing.m4a")
                    withContext(Dispatchers.IO) { f.copyTo(copy, overwrite = true) }
                    existingAudio = copy
                    existingKind = st.audioKind
                    loadAudioSource(Uri.fromFile(copy), AudioKind.EXISTING, "Sonido guardado", wholeSource = true)
                }
            } else {
                val uri = requireNotNull(source) { "No hay imagen" }
                // También en paquetes estáticos: de un vídeo o GIF se elige el fotograma
                val newClip = withContext(Dispatchers.IO) { ClipSource.open(ctx, uri) }
                if (newClip != null) {
                    stillMode = !pack.animated
                    openClip(newClip, wholeClip = false)
                    videoHasAudio = newClip.videoUri?.let { v ->
                        withContext(Dispatchers.IO) { AudioCodec.hasAudio(ctx, v) }
                    } ?: false
                    tool = EditorTool.TRIM
                } else {
                    val img = withContext(Dispatchers.IO) { ImageLoader.loadEditable(ctx, uri, pack.animated) }
                    setBase(img)
                    // Si la imagen ya tiene transparencia (p. ej. otro sticker) se va directamente al borde.
                    if (hasTransparentPixels(img.first)) tool = EditorTool.BORDER
                }
            }
            if (initialTool != null && (initialTool != EditorTool.TRIM || clip != null)) tool = initialTool
        }
    }

    private suspend fun openClip(c: ClipSource, wholeClip: Boolean) {
        clip = c
        val (s, e) = if (wholeClip) 0L to c.durationMs else c.defaultRange()
        trimStart = s
        trimEnd = e
        framePos = 0L
        val base = withContext(Dispatchers.IO) { if (stillMode) stillFrame(c, 0L) else c.extract(s, e) }
        appliedStart = if (stillMode) 0L else s
        appliedEnd = if (stillMode) 0L else e
        appliedStill = stillMode
        setBase(base)
    }

    private fun setBase(img: EditImage) {
        baseImage = img
        image = img
        ops.clear()
        history.clear()
        canUndo = false
    }

    // ---------------- Herramientas ----------------

    fun selectTool(t: EditorTool) {
        // Al salir de "Duración" con un tramo nuevo sin aplicar, se aplica automáticamente
        if (tool == EditorTool.TRIM && t != EditorTool.TRIM && trimDirty) applyTrim()
        tool = t
    }

    /** Paquetes animados: alterna entre animación y fotograma fijo. */
    fun chooseStill(still: Boolean) {
        val c = clip ?: return
        if (!animatedPack || still == stillMode) return
        stillMode = still
        if (still) {
            framePos = c.snap(previewPos)
            previewWhole = true
        }
    }

    /** Elige como fotograma el instante que se está viendo y lo aplica. */
    fun useCurrentFrame() {
        val c = clip ?: return
        framePos = c.snap(previewPos)
        // Con el audio del vídeo, el sonido empieza en el fotograma elegido
        if (audioKind == AudioKind.VIDEO_ORIGINAL) {
            val len = (audioEnd - audioStart).coerceAtLeast(SoundMixer.MIN_MS)
            audioStart = framePos.coerceAtMost(max(0L, audioSourceMs - SoundMixer.MIN_MS))
            audioEnd = min(audioSourceMs, audioStart + len)
        }
        applyTrim()
    }

    /** Avanza o retrocede [delta] fotogramas en la vista previa. */
    fun stepFrame(delta: Int) {
        val c = clip ?: return
        previewPlaying = false
        seekPreview(c.snap(previewPos) + delta * c.frameStepMs + if (delta > 0) 1 else 0)
    }

    private fun stillFrame(c: ClipSource, t: Long): EditImage = EditImage.single(
        c.frameAt(t, if (animatedPack) ImageLoader.MAX_ANIMATED_DIMENSION else ImageLoader.MAX_EDIT_DIMENSION)
    )

    fun setTrim(start: Long, end: Long) {
        trimStart = start
        trimEnd = end
    }

    fun resetTrim() {
        if (appliedStill) {
            stillMode = true
            framePos = appliedStart
        } else {
            stillMode = false
            setTrim(appliedStart, appliedEnd)
        }
    }

    /** Pide (de forma asíncrona) el fotograma del instante [t] para la vista previa; null la quita. */
    fun scrubTo(t: Long?) {
        // Al soltar un tirador, la vista previa salta a ese instante
        if (t == null) lastScrubT?.let { seekPreview(it) }
        lastScrubT = t
        scrubbing = t != null
        scrubRequests.value = t
    }

    /** Salta a [ms] en la vista previa. [precise] = buscar el fotograma exacto (más lento en vídeo). */
    fun seekPreview(ms: Long, precise: Boolean = true) {
        val dur = clip?.durationMs ?: return
        val t = ms.coerceIn(0L, max(0L, dur - 1))
        previewPos = t
        seekRequest = SeekRequest(t, precise, ++seekCounter)
    }

    fun choosePreviewWhole(whole: Boolean) {
        previewWhole = whole
        if (!whole && (previewPos < trimStart || previewPos >= trimEnd)) seekPreview(trimStart)
    }

    /** Posición informada por el reproductor de vídeo. */
    fun onVideoPosition(ms: Long) {
        previewPos = ms
        if (!previewWhole && previewPlaying && ms >= trimEnd) seekPreview(trimStart)
    }

    /** Avance de la vista previa de GIF/animaciones (sin reproductor del sistema). */
    fun advancePreview(dtMs: Long) {
        val dur = clip?.durationMs ?: return
        val (from, to) = if (previewWhole) 0L to dur else trimStart to trimEnd
        var p = previewPos + dtMs
        if (p >= to || p < from) p = from
        previewPos = p
    }

    /** Usa la posición actual como inicio del tramo, conservando su duración si cabe. */
    fun markStart() {
        val c = clip ?: return
        val dur = c.durationMs
        val minLen = min(ClipSource.MIN_CLIP_MS, dur)
        val len = (trimEnd - trimStart).coerceIn(minLen, ClipSource.MAX_CLIP_MS)
        val s = c.snap(previewPos).coerceIn(0L, dur - minLen)
        var e = trimEnd
        if (e < s + minLen || e - s > ClipSource.MAX_CLIP_MS) e = min(dur, s + len)
        setTrim(s, e)
    }

    /** Usa la posición actual como final del tramo, conservando su duración si cabe. */
    fun markEnd() {
        val c = clip ?: return
        val dur = c.durationMs
        val minLen = min(ClipSource.MIN_CLIP_MS, dur)
        val len = (trimEnd - trimStart).coerceIn(minLen, ClipSource.MAX_CLIP_MS)
        val e = if (previewPos >= dur - c.frameStepMs) dur else max(previewPos, minLen).coerceAtMost(dur)
        var s = trimStart
        if (e < s + minLen || e - s > ClipSource.MAX_CLIP_MS) s = max(0L, e - len)
        setTrim(s, e)
    }

    suspend fun thumbnail(t: Long, maxDim: Int): Bitmap? {
        val c = clip ?: return null
        return runCatching { withContext(Dispatchers.IO) { c.frameAt(t, maxDim) } }.getOrNull()
    }

    /** Extrae los fotogramas del tramo elegido y vuelve a aplicar todas las ediciones. */
    fun applyTrim() {
        if (!trimDirty || busyMessage != null) return
        runBusy("Extrayendo fotogramas…") { applyTrimNow() }
    }

    private suspend fun applyTrimNow() {
        val c = clip ?: return
        val still = stillMode
        val s = if (still) framePos else trimStart
        val e = if (still) framePos else trimEnd
        val base = withContext(Dispatchers.IO) { if (still) stillFrame(c, s) else c.extract(s, e) }
        val edited = replay(base, ops)
        baseImage = base
        appliedStart = s
        appliedEnd = e
        appliedStill = still
        history.clear() // las imágenes guardadas eran de otro tramo; se rehacen si se deshace
        image = edited
    }

    fun applyCrop(left: Float, top: Float, right: Float, bottom: Float) =
        perform(EditOp.Crop(left, top, right, bottom), "Recortando…")

    fun applyLasso(points: List<PointF>) = perform(EditOp.Lasso(points), "Recortando…")

    fun applyErase(points: List<PointF>, width: Float) =
        perform(EditOp.Erase(points, width), if (image?.isAnimated == true) "Borrando…" else null)

    fun rotate() = perform(EditOp.Rotate, null)

    fun flip() = perform(EditOp.Flip, null)

    fun applySmartCutout() {
        if (image == null || busyMessage != null) return
        runBusy("Detectando el sujeto…") {
            val before = image ?: return@runBusy
            var fallback = false
            val out = applyOp(before, EditOp.Smart) { fallback = it }
            commit(EditOp.Smart, before, out)
            message = if (fallback) {
                "Fondo eliminado con el modo sin conexión (solo detecta personas). Retoca con el borrador o el lazo si hace falta."
            } else {
                "Fondo eliminado. Puedes retocar con el borrador o el lazo."
            }
        }
    }

    fun undo() {
        if (ops.isEmpty() || busyMessage != null) return
        ops.removeAt(ops.lastIndex)
        val cached = history.lastOrNull()?.takeIf { it.first == ops.size }
        if (cached != null) {
            history.removeLast()
            image = cached.second
            canUndo = ops.isNotEmpty()
        } else {
            // No queda copia en memoria: se rehace desde los fotogramas originales
            runBusy("Deshaciendo…") {
                image = replay(requireNotNull(baseImage), ops)
                canUndo = ops.isNotEmpty()
            }
        }
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
        if (image == null) return
        val color = borderColor
        val width = borderWidth
        val emo = emojis
        val animated = animatedPack
        stopAudio()
        runBusy(if (animated && clip != null) "Generando sticker animado…" else "Guardando sticker…") {
            if (trimDirty) applyTrimNow()
            val img = image ?: return@runBusy
            val sound = soundSpec()?.let { spec ->
                busyMessage = "Preparando el sonido…"
                withContext(Dispatchers.IO) { renderSound(spec) }
            }
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
                        audio = sound,
                    )
                }
            }
            onDone()
        }
    }

    override fun onCleared() {
        clip?.close()
        player?.release()
        player = null
    }

    // ---------------- Sonido ----------------

    /** Tramo efectivo de la fuente de sonido. */
    val audioRange: Pair<Long, Long>
        get() = if (audioKind == AudioKind.VIDEO_ORIGINAL && audioSync && !stillMode && clip != null) {
            trimStart to min(trimEnd, trimStart + SoundMixer.MAX_MS)
        } else audioStart to audioEnd

    /** ¿Se puede mezclar por debajo el audio original del vídeo? */
    val canMixVideoAudio
        get() = videoHasAudio && clip?.videoUri != null && audioKind != null && audioKind != AudioKind.VIDEO_ORIGINAL

    /** Duración del sticker animado (para igualar el sonido), o null si es estático. */
    val stickerLengthMs: Long?
        get() = when {
            !animatedPack -> null
            clip != null && !stillMode -> trimEnd - trimStart
            image?.isAnimated == true -> image!!.durations.sum().toLong()
            else -> null
        }

    /**
     * Elige una fuente de sonido: un archivo de audio, otro vídeo, una nota de voz o el propio
     * vídeo ([uri] null con [AudioKind.VIDEO_ORIGINAL]).
     */
    fun setAudioSource(uri: Uri?, kind: AudioKind, name: String? = null) {
        if (busyMessage != null) return
        val src = if (kind == AudioKind.VIDEO_ORIGINAL) clip?.videoUri else uri
        if (src == null) return
        stopAudio()
        runBusy("Leyendo el sonido…") { loadAudioSource(src, kind, name, wholeSource = false) }
    }

    private suspend fun loadAudioSource(uri: Uri, kind: AudioKind, name: String?, wholeSource: Boolean) {
        val ctx = getApplication<Application>()
        val dur = withContext(Dispatchers.IO) {
            require(AudioCodec.hasAudio(ctx, uri)) {
                if (kind == AudioKind.OTHER_VIDEO) "Ese vídeo no tiene sonido" else "No se encontró sonido en el archivo"
            }
            AudioCodec.durationMs(ctx, uri)
        }
        require(dur > 0) { "No se pudo leer la duración del sonido" }
        audioKind = kind
        audioUri = uri
        audioName = name ?: kind.label
        audioSourceMs = dur
        audioPeaks = null
        bedVolume = 0f
        val wanted = stickerLengthMs ?: DEFAULT_SOUND_MS
        when {
            wholeSource -> { audioStart = 0; audioEnd = min(dur, SoundMixer.MAX_MS) }
            kind == AudioKind.VIDEO_ORIGINAL -> {
                audioSync = !stillMode
                audioStart = (if (stillMode) framePos else trimStart).coerceIn(0, max(0, dur - SoundMixer.MIN_MS))
                audioEnd = min(dur, audioStart + if (stillMode) DEFAULT_SOUND_MS else trimEnd - trimStart)
            }
            kind == AudioKind.RECORDING -> { audioStart = 0; audioEnd = min(dur, SoundMixer.MAX_MS) }
            else -> { audioStart = 0; audioEnd = min(dur, min(wanted, SoundMixer.MAX_MS)) }
        }
        viewModelScope.launch {
            audioPeaks = runCatching {
                withContext(Dispatchers.IO) { AudioCodec.peaks(ctx, uri, dur, PEAK_BUCKETS) }
            }.getOrNull()
        }
    }

    fun restoreSavedAudio() {
        val f = existingAudio ?: return
        if (busyMessage != null) return
        stopAudio()
        runBusy("Leyendo el sonido…") {
            loadAudioSource(Uri.fromFile(f), AudioKind.EXISTING, "Sonido guardado", wholeSource = true)
        }
    }

    fun removeAudio() {
        stopAudio()
        audioKind = null
        audioUri = null
        audioPeaks = null
        bedVolume = 0f
    }

    /** Cambia el tramo del sonido respetando el mínimo y el máximo. */
    fun setAudioRange(start: Long, end: Long) {
        val dur = audioSourceMs
        var s = start.coerceIn(0, max(0, dur - SoundMixer.MIN_MS))
        var e = end.coerceIn(s + min(SoundMixer.MIN_MS, dur), dur)
        if (e - s > SoundMixer.MAX_MS) {
            // Se mueve el extremo que no se ha tocado
            if (s != audioStart) e = s + SoundMixer.MAX_MS else s = e - SoundMixer.MAX_MS
        }
        audioSync = false
        audioStart = s
        audioEnd = e
    }

    /** Desplaza el tramo del sonido conservando su duración. */
    fun shiftAudio(deltaMs: Long) {
        val len = audioEnd - audioStart
        val s = (audioStart + deltaMs).coerceIn(0, max(0, audioSourceMs - len))
        audioSync = false
        audioStart = s
        audioEnd = s + len
    }

    /** Ajusta la duración del sonido a la de la animación. */
    fun matchStickerLength() {
        val len = stickerLengthMs ?: return
        val s = audioStart.coerceAtMost(max(0, audioSourceMs - len))
        audioSync = false
        audioStart = s
        audioEnd = min(audioSourceMs, s + len)
    }

    fun soundSpec(): SoundSpec? {
        val kind = audioKind ?: return null
        val uri = audioUri ?: return null
        val (s, e) = audioRange
        val bed = if (canMixVideoAudio && bedVolume > 0f) {
            AudioBed(clip!!.videoUri!!, if (stillMode) framePos else trimStart, bedVolume)
        } else null
        return SoundSpec(
            uri = uri,
            kind = kind,
            startMs = s,
            endMs = e,
            volume = audioVolume,
            fadeInMs = if (fadeIn) FADE_MS else 0,
            fadeOutMs = if (fadeOut) FADE_MS else 0,
            normalize = normalizeAudio,
            bed = bed,
        )
    }

    /** Genera el .m4a final. El sonido guardado sin cambios se copia tal cual (sin recomprimir). */
    private fun renderSound(spec: SoundSpec): StickerAudio {
        val ctx = getApplication<Application>()
        val untouched = spec.kind == AudioKind.EXISTING && spec.startMs == 0L && spec.endMs >= audioSourceMs &&
            spec.volume == 1f && spec.fadeInMs == 0 && spec.fadeOutMs == 0 && !spec.normalize && spec.bed == null
        val existing = existingAudio
        if (untouched && existing != null && existing.exists()) {
            return StickerAudio(existing.readBytes(), existingKind ?: AudioKind.EXISTING.name, audioSourceMs)
        }
        val out = File(ctx.cacheDir, "sound_render.m4a")
        val ms = SoundMixer.renderToFile(ctx, spec, out)
        // Si se reeditó el sonido guardado se conserva su origen
        val kind = if (spec.kind == AudioKind.EXISTING) existingKind ?: spec.kind.name else spec.kind.name
        return StickerAudio(out.readBytes(), kind, ms)
    }

    /** Escucha el sonido tal como quedará (con volumen, mezcla y fundidos). */
    fun toggleAudioPreview() {
        if (audioPlaying) {
            stopAudio()
            return
        }
        val spec = soundSpec() ?: return
        if (busyMessage != null) return
        runBusy("Preparando el sonido…") {
            val ctx = getApplication<Application>()
            if (spec != previewKey || !previewFile.exists()) {
                withContext(Dispatchers.IO) { SoundMixer.renderToFile(ctx, spec, previewFile) }
                previewKey = spec
            }
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(previewFile.absolutePath)
                setOnCompletionListener { audioPlaying = false }
                prepare()
                start()
            }
            audioPlaying = true
            audioPlayToken++
        }
    }

    fun stopAudio() {
        runCatching { player?.stop() }
        player?.release()
        player = null
        audioPlaying = false
    }

    // ---------------- Internos ----------------

    private fun perform(op: EditOp, busy: String?) {
        val src = image ?: return
        if (busyMessage != null) return
        val block: suspend () -> Unit = { commit(op, src, applyOp(src, op)) }
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

    private suspend fun applyOp(img: EditImage, op: EditOp, onFallback: (Boolean) -> Unit = {}): EditImage =
        when (op) {
            is EditOp.Crop -> withContext(Dispatchers.Default) {
                img.map { StickerProcessor.crop(it, op.l.toInt(), op.t.toInt(), op.r.toInt(), op.b.toInt()) }
            }
            is EditOp.Lasso -> withContext(Dispatchers.Default) { StickerProcessor.lassoCut(img, op.points) }
            is EditOp.Erase -> withContext(Dispatchers.Default) {
                img.map { StickerProcessor.erase(it, op.points, op.width) }
            }
            EditOp.Rotate -> withContext(Dispatchers.Default) { img.map(StickerProcessor::rotate90) }
            EditOp.Flip -> withContext(Dispatchers.Default) { img.map(StickerProcessor::flipHorizontal) }
            EditOp.Smart -> withContext(Dispatchers.Default) {
                // Con clip no se recorta al sujeto, para poder repetir la operación en otro tramo
                SmartCutout.removeBackground(getApplication(), img, trim = clip == null) { done, total ->
                    if (total > 1) viewModelScope.launch {
                        // Si ya terminó no se vuelve a mostrar el aviso
                        if (busyMessage != null) busyMessage = "Quitando el fondo… fotograma ${done + 1} de $total"
                    }
                }
            }.also { onFallback(it.usedFallback) }.image
        }

    private suspend fun replay(base: EditImage, list: List<EditOp>): EditImage {
        var img = base
        for (op in list) img = applyOp(img, op)
        return img
    }

    /** Registra la operación y guarda el estado anterior para deshacer, con un presupuesto de memoria. */
    private fun commit(op: EditOp, before: EditImage, after: EditImage) {
        if (after === before) return
        history.addLast(ops.size to before)
        ops += op
        var total = history.sumOf { it.second.byteCount } + after.byteCount
        while (history.isNotEmpty() && (history.size > MAX_HISTORY || total > MAX_HISTORY_BYTES)) {
            total -= history.removeFirst().second.byteCount
        }
        image = after
        canUndo = true
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
        private const val DEFAULT_SOUND_MS = 5_000L
        private const val FADE_MS = 300
        const val PEAK_BUCKETS = 160
        private const val MAX_HISTORY_BYTES = 160L * 1024 * 1024
    }
}
