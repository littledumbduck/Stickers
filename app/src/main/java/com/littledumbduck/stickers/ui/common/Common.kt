package com.littledumbduck.stickers.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.littledumbduck.stickers.data.StickerLimits
import com.littledumbduck.stickers.data.StickerPack
import java.io.File
import java.text.BreakIterator

/** Fondo de cuadros para visualizar la transparencia. */
fun DrawScope.drawCheckerboard(
    topLeft: Offset = Offset.Zero,
    area: Size = size,
    cell: Float = 12.dp.toPx(),
    light: Color = Color(0xFFF2F2F2),
    dark: Color = Color(0xFFD9D9D9),
) {
    drawRect(light, topLeft, area)
    var y = 0f
    var row = 0
    while (y < area.height) {
        var x = if (row % 2 == 0) 0f else cell
        while (x < area.width) {
            val w = minOf(cell, area.width - x)
            val h = minOf(cell, area.height - y)
            drawRect(dark, Offset(topLeft.x + x, topLeft.y + y), Size(w, h))
            x += cell * 2
        }
        y += cell
        row++
    }
}

@Composable
fun CheckerboardBackground(modifier: Modifier = Modifier) {
    Canvas(modifier) { drawCheckerboard(cell = 8.dp.toPx()) }
}

/** Extrae los emojis de un texto (máximo 3), ignorando letras, números y espacios. */
fun parseEmojis(text: String): List<String> {
    val it = BreakIterator.getCharacterInstance()
    it.setText(text)
    val out = mutableListOf<String>()
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
        val g = text.substring(start, end)
        if (isEmojiLike(g)) out += g
        start = end
        end = it.next()
    }
    return out.take(StickerLimits.MAX_EMOJIS)
}

private fun isEmojiLike(grapheme: String): Boolean {
    var i = 0
    while (i < grapheme.length) {
        val cp = grapheme.codePointAt(i)
        if (cp in 0x1F000..0x1FAFF || Character.getType(cp) == Character.OTHER_SYMBOL.toInt()) return true
        i += Character.charCount(cp)
    }
    return false
}

@Composable
fun PackInfoDialog(
    title: String,
    initialName: String = "",
    initialPublisher: String = "",
    confirmText: String = "Guardar",
    /** Muestra la opción de paquete animado (solo al crear: luego no se puede cambiar). */
    askAnimated: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (name: String, publisher: String, animated: Boolean) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var publisher by rememberSaveable { mutableStateOf(initialPublisher) }
    var animated by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(StickerLimits.MAX_TEXT) },
                    label = { Text("Nombre del paquete") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = publisher,
                    onValueChange = { publisher = it.take(StickerLimits.MAX_TEXT) },
                    label = { Text("Autor") },
                    singleLine = true,
                )
                if (askAnimated) {
                    Row(
                        Modifier.fillMaxWidth().clickable { animated = !animated },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Paquete animado")
                            Text(
                                "Para GIF, vídeos y stickers animados. WhatsApp no permite mezclar animados y estáticos.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = animated, onCheckedChange = { animated = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && publisher.isNotBlank(),
                onClick = { onConfirm(name, publisher, animated) },
            ) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun EmojiDialog(
    initial: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial.joinToString("")) }
    val parsed = parseEmojis(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Emojis del sticker") },
        text = {
            Column {
                Text(
                    "WhatsApp usa los emojis para sugerir el sticker. Escribe de 1 a 3.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Text("Se guardarán: ${parsed.joinToString(" ").ifEmpty { "—" }}")
            }
        },
        confirmButton = {
            TextButton(enabled = parsed.isNotEmpty(), onClick = { onConfirm(parsed) }) { Text("Aceptar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Diálogo para elegir a qué paquete añadir stickers. */
@Composable
fun PackPickerDialog(
    packs: List<StickerPack>,
    packDir: (String) -> File,
    onDismiss: () -> Unit,
    onPick: (StickerPack) -> Unit,
    onCreateNew: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Elegir paquete") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onCreateNew)
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(40.dp).padding(8.dp))
                        Text("Nuevo paquete", Modifier.padding(start = 12.dp))
                    }
                    HorizontalDivider()
                }
                items(packs, key = { it.identifier }) { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !p.isFull) { onPick(p) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(
                            model = p.trayImageFile?.let { File(packDir(p.identifier), it) },
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(40.dp),
                        )
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(p.name)
                            Text(
                                (if (p.animated) "Animado · " else "") +
                                    if (p.isFull) "Lleno (${p.stickers.size}/${StickerLimits.MAX_STICKERS})"
                                    else "${p.stickers.size}/${StickerLimits.MAX_STICKERS} stickers",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
