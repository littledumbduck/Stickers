package com.littledumbduck.stickers.ui.pack

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.littledumbduck.stickers.audio.SoundStickerExport
import com.littledumbduck.stickers.data.PackRepository
import com.littledumbduck.stickers.data.Sticker
import com.littledumbduck.stickers.data.StickerPack
import com.littledumbduck.stickers.whatsapp.WhatsAppIntegration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Reproduce el sonido de un sticker; devuelve una función para parar. */
@Composable
fun rememberStickerSoundPlayer(): Pair<(File) -> Unit, () -> Unit> {
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    DisposableEffect(Unit) { onDispose { player[0]?.release(); player[0] = null } }
    val stop = {
        runCatching { player[0]?.stop() }
        player[0]?.release()
        player[0] = null
    }
    val play = { file: File ->
        stop()
        player[0] = runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                // Como en WhatsApp: el sonido se repite tres veces
                var left = 2
                setOnCompletionListener { mp -> if (left-- > 0) mp.start() }
                start()
            }
        }.getOrNull()
    }
    return play to stop
}

/** Pide (solo en Android 9 o anterior) permiso para escribir en la galería y después ejecuta la acción. */
@Composable
private fun rememberStorageGate(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pending?.invoke()
        else Toast.makeText(context, "Sin permiso de almacenamiento no se puede guardar", Toast.LENGTH_LONG).show()
        pending = null
    }
    return { action ->
        val granted = !SoundStickerExport.needsStoragePermission || ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) action() else {
            pending = action
            launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}

/**
 * Exportar un sticker con sonido para WhatsApp. WhatsApp todavía no acepta sonido en los paquetes
 * de otras apps, así que se crea dentro de WhatsApp a partir del vídeo (o del audio suelto).
 */
@Composable
fun SoundExportDialog(pack: StickerPack, sticker: Sticker, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate = rememberStorageGate()
    var bg by remember { mutableIntStateOf(SoundStickerExport.BACKGROUNDS.first().first) }
    var busy by remember { mutableStateOf<String?>(null) }
    var video by remember { mutableStateOf<Pair<Int, File>?>(null) }

    fun withVideo(action: (File) -> Unit) {
        val cached = video?.takeIf { it.first == bg && it.second.exists() }?.second
        if (cached != null) return action(cached)
        busy = "Generando vídeo con sonido…"
        scope.launch {
            val res = runCatching { withContext(Dispatchers.IO) { SoundStickerExport.exportVideo(context, pack, sticker, bg) } }
            busy = null
            res.onSuccess { video = bg to it; action(it) }
                .onFailure { Toast.makeText(context, it.message ?: "No se pudo generar el vídeo", Toast.LENGTH_LONG).show() }
        }
    }

    fun runIo(label: String, ok: String, block: () -> Unit) {
        busy = label
        scope.launch {
            val res = runCatching { withContext(Dispatchers.IO) { block() } }
            busy = null
            Toast.makeText(context, res.exceptionOrNull()?.message ?: ok, Toast.LENGTH_LONG).show()
        }
    }

    fun start(intent: android.content.Intent?) {
        try {
            if (intent != null) context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No hay ninguna app para esto", Toast.LENGTH_LONG).show()
        }
    }

    AlertDialog(
        onDismissRequest = { if (busy == null) onDismiss() },
        title = { Text("Sticker con sonido para WhatsApp") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "WhatsApp todavía no deja que otras apps añadan el sonido dentro del paquete, " +
                        "pero puedes crear el sticker con sonido en WhatsApp en un momento:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "1. Guarda el vídeo en la galería.\n" +
                        "2. En WhatsApp abre un chat → emoji → Stickers → Crear y elige el vídeo.\n" +
                        "3. Pulsa el botón de sonido y deja «Audio original». También puedes usar " +
                        "«Importar archivo» con el audio guardado en Música/${SoundStickerExport.ALBUM}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text("Fondo del vídeo", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                    SoundStickerExport.BACKGROUNDS.forEach { (color, name) ->
                        Box(
                            Modifier.size(36.dp)
                                .border(
                                    BorderStroke(
                                        if (bg == color) 3.dp else 1.dp,
                                        if (bg == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                    CircleShape,
                                )
                                .padding(4.dp).clip(CircleShape).background(Color(color))
                                .clickable { bg = color },
                        )
                    }
                }
                Text(
                    SoundStickerExport.BACKGROUNDS.first { it.first == bg }.second +
                        if (bg == SoundStickerExport.BACKGROUNDS[2].first || bg == SoundStickerExport.BACKGROUNDS[3].first)
                            " · útil si luego quitas el fondo en WhatsApp" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                busy?.let {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Text(it, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                val enabled = busy == null
                Button(
                    onClick = {
                        gate {
                            withVideo { f ->
                                runIo("Guardando…", "Vídeo guardado en la galería (${SoundStickerExport.ALBUM})") {
                                    SoundStickerExport.saveVideoToGallery(context, f)
                                }
                            }
                        }
                    },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                    Text("Guardar vídeo en la galería", Modifier.padding(start = 8.dp))
                }
                FilledTonalButton(
                    onClick = { withVideo { f -> start(SoundStickerExport.shareIntent(context, f, "video/mp4", "Compartir vídeo")) } },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                    Text("Compartir vídeo", Modifier.padding(start = 8.dp))
                }
                if (sticker.hasAudio) {
                    OutlinedButton(
                        onClick = {
                            gate {
                                runIo("Guardando…", "Audio guardado en Música/${SoundStickerExport.ALBUM}") {
                                    SoundStickerExport.saveAudioToMusic(
                                        context, SoundStickerExport.exportAudio(context, pack, sticker),
                                    )
                                }
                            }
                        },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.AudioFile, null, Modifier.size(18.dp))
                        Text("Guardar solo el audio", Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val f = runCatching {
                                    withContext(Dispatchers.IO) { SoundStickerExport.exportAudio(context, pack, sticker) }
                                }.getOrNull() ?: return@launch
                                start(SoundStickerExport.shareIntent(context, f, "audio/mp4", "Compartir audio"))
                            }
                        },
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                        Text("Compartir audio", Modifier.padding(start = 8.dp))
                    }
                }
                val apps = remember { WhatsAppIntegration.installedApps(context) }
                apps.forEach { pkg ->
                    TextButton(
                        onClick = { start(SoundStickerExport.openWhatsAppIntent(context, pkg)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.OpenInNew, null, Modifier.size(18.dp))
                        Text("Abrir ${WhatsAppIntegration.label(pkg)}", Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = busy == null) { Text("Cerrar") } },
    )
}

/** Guarda en la galería los vídeos de todos los stickers con sonido del paquete. */
@Composable
fun ExportAllSoundsDialog(pack: StickerPack, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gate = rememberStorageGate()
    val withSound = remember(pack) { pack.stickers.filter { it.hasAudio } }
    var bg by remember { mutableIntStateOf(SoundStickerExport.BACKGROUNDS.first().first) }
    var done by remember { mutableIntStateOf(-1) }
    var failed by remember { mutableIntStateOf(0) }
    val running = done in 0 until withSound.size

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("Exportar stickers con sonido") },
        text = {
            Column {
                Text(
                    "Se guardarán ${withSound.size} vídeos en la galería (${SoundStickerExport.ALBUM}). " +
                        "Después créalos en WhatsApp con Stickers → Crear.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 10.dp)) {
                    SoundStickerExport.BACKGROUNDS.forEach { (color, _) ->
                        Box(
                            Modifier.size(32.dp)
                                .border(
                                    BorderStroke(
                                        if (bg == color) 3.dp else 1.dp,
                                        if (bg == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                    CircleShape,
                                )
                                .padding(4.dp).clip(CircleShape).background(Color(color))
                                .clickable(enabled = !running) { bg = color },
                        )
                    }
                }
                if (done >= 0) {
                    LinearProgressIndicator(
                        progress = { done.toFloat() / withSound.size.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (running) "Exportando ${done + 1} de ${withSound.size}…"
                        else "Listo: ${withSound.size - failed} guardados" + if (failed > 0) ", $failed con error" else "",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (done < 0) {
                Button(onClick = {
                    gate {
                        done = 0
                        failed = 0
                        scope.launch {
                            for (st in withSound) {
                                val ok = runCatching {
                                    withContext(Dispatchers.IO) {
                                        val f = SoundStickerExport.exportVideo(context, pack, st, bg)
                                        SoundStickerExport.saveVideoToGallery(context, f)
                                    }
                                }.isSuccess
                                if (!ok) failed++
                                done++
                            }
                        }
                    }
                }, enabled = withSound.isNotEmpty()) { Text("Exportar") }
            } else {
                TextButton(onClick = onDismiss, enabled = !running) { Text("Cerrar") }
            }
        },
        dismissButton = { if (done < 0) TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

/** Quita el sonido de un sticker (la imagen no cambia). */
fun removeStickerSound(context: android.content.Context, packId: String, fileName: String) =
    PackRepository.get(context).setAudio(packId, fileName, null)
