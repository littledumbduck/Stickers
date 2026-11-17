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
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Block
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
import com.littledumbduck.stickers.ui.common.parseEmojis
import kotlinx.coroutines.delay
import android.graphics.Color as AColor

private val BORDER_COLORS = listOf(
    AColor.WHITE, AColor.BLACK, 0xFFFF3B30.toInt(), 0xFFFF9500.toInt(), 0xFFFFCC00.toInt(),
    0xFF34C759.toInt(), 0xFF25D366.toInt(), 0xFF00C7BE.toInt(), 0xFF007AFF.toInt(),
    0xFF5856D6.toInt(), 0xFFAF52DE.toInt(), 0xFFFF2D55.toInt(), 0xFFA2845E.toInt(),
)

private fun EditorTool.icon(): ImageVector = when (this) {
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

    val bitmap = vm.bitmap
    val snackbar = remember { SnackbarHostState() }
    var showSave by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var eraserDp by remember { mutableStateOf(28f) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }

    // Estado de las herramientas (se reinicia cuando cambia la imagen)
    val cropRect = remember(bitmap) {
        mutableStateOf(Rect(0f, 0f, (bitmap?.width ?: 1).toFloat(), (bitmap?.height ?: 1).toFloat()))
    }
    val lasso = remember(bitmap) { mutableStateListOf<Offset>() }

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
    LaunchedEffect(bitmap, vm.borderColor, vm.borderWidth, vm.tool) {
        if (vm.tool != EditorTool.BORDER) return@LaunchedEffect
        delay(60) // pequeño "debounce" mientras se arrastra el control deslizante
        preview = vm.renderPreview()
    }

    BackHandler { if (vm.canUndo) confirmExit = true else onClose() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isEditingExisting) "Editar sticker" else "Nuevo sticker") },
                navigationIcon = {
                    IconButton(onClick = { if (vm.canUndo) confirmExit = true else onClose() }) {
                        Icon(Icons.Default.Close, "Cerrar")
                    }
                },
                actions = {
                    IconButton(onClick = vm::undo, enabled = vm.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Deshacer")
                    }
                    IconButton(onClick = vm::rotate, enabled = bitmap != null) {
                        Icon(Icons.Default.RotateRight, "Girar")
                    }
                    IconButton(onClick = vm::flip, enabled = bitmap != null) {
                        Icon(Icons.Default.Flip, "Voltear")
                    }
                    IconButton(onClick = { showSave = true }, enabled = bitmap != null && vm.busyMessage == null) {
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
                            cropRect = cropRect.value,
                            imageSize = bitmap?.let { it.width to it.height },
                            lassoPoints = lasso.size,
                            eraserDp = eraserDp,
                            onEraserDp = { eraserDp = it },
                            onResetCrop = {
                                bitmap?.let { cropRect.value = Rect(0f, 0f, it.width.toFloat(), it.height.toFloat()) }
                            },
                            onSquareCrop = {
                                bitmap?.let {
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
                            },
                            onClearLasso = { lasso.clear() },
                            onApplyLasso = {
                                vm.applyLasso(lasso.map { PointF(it.x, it.y) })
                                lasso.clear()
                            },
                        )
                    }
                }
                NavigationBar {
                    EditorTool.entries.forEach { t ->
                        NavigationBarItem(
                            selected = vm.tool == t,
                            onClick = { vm.tool = t },
                            icon = { Icon(t.icon(), null) },
                            label = { Text(t.label) },
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
            val shown = if (showPreview) preview else bitmap
            if (shown != null) {
                val image = remember(shown) { shown.asImageBitmap() }
                EditorCanvas(
                    image = image,
                    tool = vm.tool,
                    cropRect = cropRect,
                    lasso = lasso,
                    eraserSizePx = with(density) { eraserDp.dp.toPx() },
                    onEraseStroke = { pts, w -> vm.applyErase(pts.map { PointF(it.x, it.y) }, w) },
                    interactive = !showPreview && vm.busyMessage == null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            vm.busyMessage?.let { msg ->
                Surface(
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
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
private fun ToolPanel(
    vm: EditorViewModel,
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
        EditorTool.CROP -> Column {
            Hint("Arrastra las esquinas o mueve el recuadro.")
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
            Hint("Rodea con el dedo la parte que quieres conservar. Se cerrará sola al soltar.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClearLasso, enabled = lassoPoints > 0) { Text("Borrar trazo") }
                Spacer(Modifier.weight(1f))
                Button(onClick = onApplyLasso, enabled = !busy && lassoPoints >= 3) { Text("Recortar selección") }
            }
        }
        EditorTool.SMART -> Column {
            Hint("Detecta automáticamente a la persona, animal u objeto principal y elimina el fondo. Funciona sin conexión una vez descargado el modelo.")
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
            Hint("Pasa el dedo por las zonas que quieras borrar. Usa Deshacer si te equivocas.")
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
