package com.littledumbduck.stickers.ui.editor

import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixOff
import androidx.compose.material.icons.filled.BorderOuter
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FirstPage
import androidx.compose.material.icons.filled.LastPage
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.FilterChip
import kotlin.math.max
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.littledumbduck.stickers.image.ClipSource
import com.littledumbduck.stickers.image.ImageLoader
import androidx.compose.runtime.withFrameMillis
import com.littledumbduck.stickers.ui.common.parseEmojis
import kotlinx.coroutines.delay
import android.graphics.Color as AColor

private val BORDER_COLORS = listOf(
    AColor.WHITE, AColor.BLACK, 0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFCC00.toInt(),
    0xFF34C759.toInt(), 0xFF25D366.toInt(), 0xFF00C7BE.toInt(), 0xFF007AFF.toInt(),
    0xFF5856D6.toInt(), 0xFFAF52DE.toInt(), 0xFFFF2D55.toInt(), 0xFFA2845E.toInt(),
)

private fun EditorTool.icon(): ImageVector = when (this) {
    EditorTool.TRIM -> Icons.Default.ContentCut
    EditorTool.CROP -> Icons.Default.Crop
    EditorTool.LASSO -> Icons.Default.Gesture
    EditorTool.SMART -> Icons.Default.AutoAwesome
    EditorTool.ERASE -> Icons.Default.AutoFixOff
    EditorTool.BORDER -> Icons.Default.BorderOuter
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    packId: String,
    sourceUri: Uri?,
    stickerFile: String?,
    onClose: () -> Unit,
    onSaved: () -> Unit,
    vm: EditorViewModel = viewModel(),
) {
    val context = LocalContext.current
    LaunchedEffect(packId, sourceUri, stickerFile) { vm.load(packId, sourceUri, stickerFile) }

    val img = vm.image
    val snackbar = remember { SnackbarHostState() }
    var showSave by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var eraserDp by remember { mutableStateOf(28f) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val view = remember { ViewTransform() }

    // Reproducción de animaciones (se pausa en la pestaña Borde para mostrar la vista previa)
    var frameIndex by remember(img) { mutableIntStateOf(0) }
    var userPlaying by remember { mutableStateOf(true) }
    val playing = img != null && img.isAnimated && userPlaying && vm.tool != EditorTool.BORDER
    LaunchedEffect(img, playing) {
        val anim = img ?: return@LaunchedEffect
        while (playing) {
            delay(anim.durations[frameIndex].coerceAtLeast(20).toLong())
            frameIndex = (frameIndex + 1) % anim.frames.size
        }
    }

    // ---- "Duración": vista previa de la fuente completa (vídeo, GIF o animación) ----
    val clip = vm.clip
    val trimMode = vm.tool == EditorTool.TRIM && clip != null
    var muted by remember { mutableStateOf(false) }
    var sourceFrame by remember(clip) { mutableStateOf<Bitmap?>(null) }
    val framePlayback = trimMode && clip?.videoUri == null
    // GIF / animación: se avanza con el reloj de pantalla y se pide el fotograma de cada instante
    LaunchedEffect(framePlayback, vm.previewPlaying, vm.scrubbing) {
        if (!framePlayback || !vm.previewPlaying || vm.scrubbing) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            vm.advancePreview(now - last)
            last = now
        }
    }
    val frameKey = if (framePlayback) clip?.snap(vm.previewPos) else null
    LaunchedEffect(frameKey) {
        if (frameKey != null) vm.thumbnail(frameKey, ImageLoader.MAX_ANIMATED_DIMENSION)?.let { sourceFrame = it }
    }

    // Estado de las herramientas (se reinicia cuando cambia la imagen)
    val cropRect = remember(img) {
        mutableStateOf(Rect(0f, 0f, (img?.width ?: 1).toFloat(), (img?.height ?: 1).toFloat()))
    }
    val lasso = remember(img) { mutableStateListOf<Offset>() }

    LaunchedEffect(vm.message) {
        vm.message?.let {
            snackbar.showSnackbar(it)
            vm.message = null
        }
    }
    LaunchedEffect(vm.loadError) {
        vm.loadError?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            onClose()
        }
    }
    // Vista previa final (con borde) cuando se está en la pestaña Borde
    LaunchedEffect(img, vm.borderColor, vm.borderWidth, vm.tool, frameIndex) {
        if (vm.tool != EditorTool.BORDER) return@LaunchedEffect
        delay(60) // pequeño "debounce" mientras se arrastra el control deslizante
        preview = vm.renderPreview(frameIndex)
    }

    BackHandler { if (vm.canUndo) confirmExit = true else onClose() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (vm.isEditingExisting) "Editar" else "Nuevo",
                        maxLines = 1,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (vm.canUndo) confirmExit = true else onClose() }) {
                        Icon(Icons.Default.Close, "Cerrar")
                    }
                },
                actions = {
                    if (img?.isAnimated == true || trimMode) {
                        val isPlaying = if (trimMode) vm.previewPlaying else userPlaying
                        IconButton(onClick = {
                            if (trimMode) vm.previewPlaying = !vm.previewPlaying else userPlaying = !userPlaying
                        }) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (isPlaying) "Pausar" else "Reproducir",
                            )
                        }
                    }
                    IconButton(onClick = vm::undo, enabled = vm.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Deshacer")
                    }
                    IconButton(onClick = vm::rotate, enabled = img != null) {
                        Icon(Icons.Default.RotateRight, "Girar")
                    }
                    IconButton(onClick = vm::flip, enabled = img != null) {
                        Icon(Icons.Default.Flip, "Voltear")
                    }
                    IconButton(onClick = { showSave = true }, enabled = img != null && vm.busyMessage == null) {
                        Icon(Icons.Default.Check, "Guardar")
                    }
                },
            )
        },
        bottomBar = {
            Column {
                Surface(tonalElevation = 2.dp) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 112.dp).padding(horizontal = 16.dp, vertical = 10.dp)) {
                        ToolPanel(
                            vm = vm,
                            muted = muted,
                            onToggleMute = { muted = !muted },
                            cropRect = cropRect.value,
                            imageSize = img?.let { it.width to it.height },
                            lassoPoints = lasso.size,
                            eraserDp = eraserDp,
                            onEraserDp = { eraserDp = it },
                            onResetCrop = {
                                img?.let { cropRect.value = Rect(0f, 0f, it.width.toFloat(), it.height.toFloat()) }
                            },
                            onSquareCrop = {
                                img?.let {
                                    val r = cropRect.value
                                    val side = minOf(r.width, r.height)
                                    val c = r.center
                                    val left = (c.x - side / 2).coerceIn(0f, it.width - side)
                                    val top = (c.y - side / 2).coerceIn(0f, it.height - side)
                                    cropRect.value = Rect(left, top, left + side, top + side)
                                }
                            },
                            onApplyCrop = {
                                val r = cropRect.value
                                vm.applyCrop(r.left, r.top, r.right, r.bottom)
                                view.reset()
                            },
                            onClearLasso = { lasso.clear() },
                            onApplyLasso = {
                                vm.applyLasso(lasso.map { PointF(it.x, it.y) })
                                lasso.clear()
                                view.reset()
                            },
                        )
                    }
                }
                NavigationBar {
                    // "Duración" solo existe para vídeos, GIF y animaciones
                    EditorTool.entries.filter { it != EditorTool.TRIM || vm.clip != null }.forEach { t ->
                        NavigationBarItem(
                            selected = vm.tool == t,
                            onClick = { vm.selectTool(t) },
                            icon = { Icon(t.icon(), null) },
                            label = { Text(t.label, maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier.padding(padding).fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest),
            contentAlignment = Alignment.Center,
        ) {
            val density = LocalDensity.current
            val showPreview = vm.tool == EditorTool.BORDER && preview != null
            // En "Duración", mientras se arrastra se ve en grande el fotograma exacto del tirador
            val scrub = vm.scrubFrame.takeIf { trimMode && vm.scrubbing }
            val videoUri = clip?.videoUri
            if (trimMode && videoUri != null) {
                VideoPreview(
                    uri = videoUri,
                    aspect = clip.aspect,
                    playing = vm.previewPlaying && !vm.scrubbing,
                    muted = muted,
                    seek = vm.seekRequest,
                    initialPositionMs = vm.previewPos,
                    onPosition = vm::onVideoPosition,
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                )
            }
            val shown = when {
                scrub != null -> scrub
                trimMode && videoUri != null -> null // lo muestra el reproductor
                trimMode -> sourceFrame
                showPreview -> preview
                else -> img?.frames?.getOrNull(frameIndex)
            }
            if (shown != null) {
                val image = remember(shown) { shown.asImageBitmap() }
                EditorCanvas(
                    image = image,
                    tool = vm.tool,
                    view = view,
                    cropRect = cropRect,
                    lasso = lasso,
                    eraserSizePx = with(density) { eraserDp.dp.toPx() },
                    onEraseStroke = { pts, w -> vm.applyErase(pts.map { PointF(it.x, it.y) }, w) },
                    interactive = !showPreview && vm.busyMessage == null,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Avisos superiores: animación y tipo de paquete
            Column(
                Modifier.align(Alignment.TopCenter).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (trimMode) {
                    InfoChip(
                        when {
                            vm.scrubbing -> "Fotograma exacto del tirador"
                            vm.previewWhole -> (if (clip!!.videoUri != null) "Vídeo completo" else "Clip completo") +
                                " · ${formatSeconds(vm.previewPos)} de ${formatSeconds(clip.durationMs)}"
                            else -> "Repitiendo el tramo elegido"
                        }
                    )
                    if (vm.trimDirty) InfoChip("Tramo nuevo sin aplicar: pulsa Aplicar o cambia de herramienta")
                } else if (img != null && img.isAnimated) {
                    InfoChip(
                        if (vm.animatedPack) "Animado · ${img.frames.size} fotogramas · ${"%.1f".format(img.durations.sum() / 1000f)} s"
                        else "Este paquete es estático: se guardará el primer fotograma"
                    )
                } else if (img != null && vm.animatedPack) {
                    InfoChip("Paquete animado: la imagen se guardará como animación fija")
                }
            }

            if (view.isZoomed) {
                SmallFloatingActionButton(
                    onClick = view::reset,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                ) { Icon(Icons.Default.ZoomOutMap, "Ajustar a la pantalla") }
            }

            vm.busyMessage?.let { msg ->
                Surface(
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier.padding(24.dp),
                ) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Text(msg, Modifier.padding(start = 16.dp))
                    }
                }
            }
        }
    }

    if (showSave) {
        SaveDialog(
            initial = vm.emojis,
            onDismiss = { showSave = false },
            onSave = { emojis ->
                showSave = false
                vm.emojis = emojis
                vm.save(onSaved)
            },
        )
    }
    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("¿Descartar cambios?") },
            text = { Text("Perderás las ediciones de esta imagen.") },
            confirmButton = { TextButton(onClick = { confirmExit = false; onClose() }) { Text("Descartar") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Seguir editando") } },
        )
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ToolPanel(
    vm: EditorViewModel,
    muted: Boolean,
    onToggleMute: () -> Unit,
    cropRect: Rect,
    imageSize: Pair<Int, Int>?,
    lassoPoints: Int,
    eraserDp: Float,
    onEraserDp: (Float) -> Unit,
    onResetCrop: () -> Unit,
    onSquareCrop: () -> Unit,
    onApplyCrop: () -> Unit,
    onClearLasso: () -> Unit,
    onApplyLasso: () -> Unit,
) {
    val busy = vm.busyMessage != null
    when (vm.tool) {
        EditorTool.TRIM -> vm.clip?.let { clip -> TrimPanel(vm, clip, muted, onToggleMute) }
        EditorTool.CROP -> Column {
            Hint("Arrastra las esquinas o mueve el recuadro. Pellizca con dos dedos para ampliar.")
            val isFull = imageSize != null && cropRect.left <= 0f && cropRect.top <= 0f &&
                cropRect.right >= imageSize.first && cropRect.bottom >= imageSize.second
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onResetCrop) { Text("Restablecer") }
                TextButton(onClick = onSquareCrop) { Text("Cuadrado") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onApplyCrop, enabled = !busy && !isFull) { Text("Aplicar recorte") }
            }
        }
        EditorTool.LASSO -> Column {
            Hint("Rodea con el dedo la parte que quieres conservar (se cierra sola al soltar). Usa dos dedos para ampliar y moverte.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClearLasso, enabled = lassoPoints > 0) { Text("Borrar trazo") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onApplyLasso, enabled = !busy && lassoPoints >= 3) { Text("Recortar selección") }
            }
        }
        EditorTool.SMART -> Column {
            Hint(
                "Detecta automáticamente a la persona, animal u objeto principal y elimina el fondo" +
                    (if (vm.image?.isAnimated == true && vm.animatedPack) " de cada fotograma" else "") +
                    ". Sin servicios de Google Play o sin conexión usa un modo integrado que detecta personas."
            )
            Button(
                onClick = vm::applySmartCutout,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
                Text("Quitar fondo automáticamente", Modifier.padding(start = 8.dp))
            }
        }
        EditorTool.ERASE -> Column {
            Hint("Pasa el dedo por las zonas que quieras borrar. Amplía con dos dedos para retocar detalles.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Pincel", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = eraserDp,
                    onValueChange = onEraserDp,
                    valueRange = 6f..80f,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size((eraserDp / 80f * 32f).dp.coerceAtLeast(4.dp))
                            .clip(CircleShape).background(MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
                    )
                }
            }
        }
        EditorTool.BORDER -> Column {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ColorSwatch(color = null, selected = vm.borderColor == null) { vm.borderColor = null }
                BORDER_COLORS.forEach { c ->
                    ColorSwatch(color = c, selected = vm.borderColor == c) { vm.borderColor = c }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (vm.borderColor == null) "Sin borde" else "Grosor ${vm.borderWidth}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Slider(
                    value = vm.borderWidth.toFloat(),
                    onValueChange = { vm.borderWidth = it.toInt() },
                    valueRange = 2f..32f,
                    enabled = vm.borderColor != null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun ColorSwatch(color: Int?, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier
            .size(40.dp)
            .border(BorderStroke(if (selected) 3.dp else 1.dp, ring), CircleShape)
            .padding(4.dp)
            .clip(CircleShape)
            .background(if (color != null) Color(color) else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (color == null) {
            Icon(Icons.Default.Block, "Sin borde", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SaveDialog(initial: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var text by remember { mutableStateOf(initial.joinToString("")) }
    val parsed = parseEmojis(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Guardar sticker") },
        text = {
            Column {
                Text(
                    "Elige de 1 a 3 emojis que describan el sticker. WhatsApp los usa para sugerirlo.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    QUICK_EMOJIS.forEach { e ->
                        Text(
                            e,
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.clip(CircleShape).clickable {
                                text = (parseEmojis(text) + e).takeLast(3).joinToString("")
                            }.padding(4.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(parsed) }, enabled = parsed.isNotEmpty()) { Text("Guardar") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private val QUICK_EMOJIS = listOf("😀", "😂", "😍", "😢", "😡", "👍", "❤️")

@Composable
private fun TrimPanel(vm: EditorViewModel, clip: ClipSource, muted: Boolean, onToggleMute: () -> Unit) {
    val busy = vm.busyMessage != null
    val dur = clip.durationMs
    Column {
        // Barra de reproducción del vídeo/GIF completo
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.previewPlaying = !vm.previewPlaying }) {
                Icon(
                    if (vm.previewPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (vm.previewPlaying) "Pausar" else "Reproducir",
                )
            }
            var dragging by remember { mutableStateOf<Float?>(null) }
            Slider(
                value = dragging ?: (vm.previewPos.toFloat() / max(1L, dur)),
                onValueChange = {
                    dragging = it
                    vm.seekPreview((it * dur).toLong(), precise = false)
                },
                onValueChangeFinished = {
                    dragging?.let { vm.seekPreview((it * dur).toLong(), precise = true) }
                    dragging = null
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                "${formatSeconds(vm.previewPos)} / ${formatSeconds(dur)}",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (clip.videoUri != null) {
                IconButton(onClick = onToggleMute) {
                    Icon(
                        if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        if (muted) "Activar sonido" else "Silenciar",
                    )
                }
            }
        }
        // Marcar inicio/fin en el punto que se está viendo
        Row(
            Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = vm::markStart, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.FirstPage, null, Modifier.size(18.dp))
                Text("Inicio aquí", Modifier.padding(start = 4.dp), maxLines = 1)
            }
            OutlinedButton(onClick = vm::markEnd, modifier = Modifier.weight(1f)) {
                Text("Fin aquí", Modifier.padding(end = 4.dp), maxLines = 1)
                Icon(Icons.Default.LastPage, null, Modifier.size(18.dp))
            }
        }
        TrimTimeline(
            durationMs = dur,
            start = vm.trimStart,
            end = vm.trimEnd,
            aspect = clip.aspect,
            frameStepMs = clip.frameStepMs,
            snap = clip::snap,
            thumbnail = vm::thumbnail,
            onRangeChange = vm::setTrim,
            onScrub = vm::scrubTo,
            playheadMs = vm.previewPos,
            minLengthMs = ClipSource.MIN_CLIP_MS,
            maxLengthMs = ClipSource.MAX_CLIP_MS,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${formatSeconds(vm.trimStart)} → ${formatSeconds(vm.trimEnd)}",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    "Tramo de ${formatSeconds(vm.trimEnd - vm.trimStart)} · máx. 10 s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = vm::resetTrim, enabled = vm.trimDirty && !busy) { Text("Deshacer") }
            Button(onClick = vm::applyTrim, enabled = vm.trimDirty && !busy) { Text("Aplicar") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = vm.previewWhole,
                onClick = { vm.choosePreviewWhole(true) },
                label = { Text(if (clip.videoUri != null) "Ver vídeo entero" else "Ver todo") },
            )
            FilterChip(
                selected = !vm.previewWhole,
                onClick = { vm.choosePreviewWhole(false) },
                label = { Text("Repetir el tramo") },
            )
        }
    }
}
