package com.littledumbduck.stickers.ui.editor

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.littledumbduck.stickers.audio.AudioKind
import com.littledumbduck.stickers.audio.SoundMixer
import com.littledumbduck.stickers.audio.VoiceRecorder
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Pestaña "Sonido" del editor: elegir de dónde sale el audio, recortarlo y ajustarlo. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPanel(vm: EditorViewModel) {
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }

    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.setAudioSource(uri, AudioKind.FILE, displayName(context, uri))
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setAudioSource(uri, AudioKind.OTHER_VIDEO, displayName(context, uri) ?: "Otro vídeo")
    }

    val busy = vm.busyMessage != null
    val kind = vm.audioKind
    Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
        // ---- Fuente ----
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = kind == null,
                onClick = vm::removeAudio,
                label = { Text("Sin sonido") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeOff, null, Modifier.size(18.dp)) },
            )
            if (vm.videoHasAudio && vm.clip?.videoUri != null) {
                FilterChip(
                    selected = kind == AudioKind.VIDEO_ORIGINAL,
                    onClick = { vm.setAudioSource(null, AudioKind.VIDEO_ORIGINAL, "Audio de este vídeo") },
                    enabled = !busy,
                    label = { Text("Del vídeo") },
                    leadingIcon = { Icon(Icons.Default.Videocam, null, Modifier.size(18.dp)) },
                )
            }
            if (vm.hasSavedAudio) {
                FilterChip(
                    selected = kind == AudioKind.EXISTING,
                    onClick = vm::restoreSavedAudio,
                    enabled = !busy,
                    label = { Text("Guardado") },
                    leadingIcon = { Icon(Icons.Default.MusicNote, null, Modifier.size(18.dp)) },
                )
            }
            FilterChip(
                selected = kind == AudioKind.FILE,
                onClick = { pickAudio.launch("audio/*") },
                enabled = !busy,
                label = { Text("Archivo") },
                leadingIcon = { Icon(Icons.Default.AudioFile, null, Modifier.size(18.dp)) },
            )
            FilterChip(
                selected = kind == AudioKind.OTHER_VIDEO,
                onClick = {
                    pickVideo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                },
                enabled = !busy,
                label = { Text("Otro vídeo") },
                leadingIcon = { Icon(Icons.Default.Movie, null, Modifier.size(18.dp)) },
            )
            FilterChip(
                selected = kind == AudioKind.RECORDING,
                onClick = { vm.stopAudio(); recording = true },
                enabled = !busy,
                label = { Text("Grabar") },
                leadingIcon = { Icon(Icons.Default.Mic, null, Modifier.size(18.dp)) },
            )
        }

        if (kind == null) {
            Text(
                "Añade sonido al sticker: el audio del propio vídeo, una canción o audio de tus archivos, " +
                    "el sonido de otro vídeo o una nota de voz (máx. ${SoundMixer.MAX_MS / 1000} s). " +
                    "WhatsApp aún no deja que otras apps incluyan el sonido en el paquete: al guardar, " +
                    "desde la ficha del paquete podrás exportarlo como vídeo con sonido para crearlo en WhatsApp.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        val (start, end) = vm.audioRange
        val dur = vm.audioSourceMs.coerceAtLeast(1)
        val synced = kind == AudioKind.VIDEO_ORIGINAL && vm.audioSync && !vm.stillMode && vm.clip != null

        // ---- Reproducir + resumen ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = vm::toggleAudioPreview, enabled = !busy || vm.audioPlaying) {
                Icon(
                    if (vm.audioPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                    if (vm.audioPlaying) "Parar" else "Escuchar",
                )
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    vm.audioName ?: kind.label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${formatSeconds(start)} → ${formatSeconds(end)} · ${formatSeconds(end - start)} de ${formatSeconds(dur)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- Forma de onda y tramo ----
        Waveform(
            peaks = vm.audioPeaks,
            selection = start.toFloat() / dur to end.toFloat() / dur,
            modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 10.dp),
        )
        RangeSlider(
            value = start.toFloat()..end.toFloat(),
            onValueChange = { r -> vm.setAudioRange(r.start.toLong(), r.endInclusive.toLong()) },
            valueRange = 0f..dur.toFloat(),
            enabled = !synced,
            modifier = Modifier.fillMaxWidth(),
        )
        if (kind == AudioKind.VIDEO_ORIGINAL && !vm.stillMode && vm.clip != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Seguir el tramo de \"Duración\"",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = vm.audioSync, onCheckedChange = { vm.audioSync = it })
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = { vm.shiftAudio(-500) }, enabled = !synced && start > 0) { Text("◀ 0,5 s") }
            OutlinedButton(onClick = { vm.shiftAudio(500) }, enabled = !synced && end < dur) { Text("0,5 s ▶") }
            if (vm.stickerLengthMs != null) {
                OutlinedButton(onClick = vm::matchStickerLength, enabled = !synced) { Text("Igualar al sticker") }
            }
            OutlinedButton(
                onClick = { vm.setAudioRange(0, minOf(dur, SoundMixer.MAX_MS)) },
                enabled = !synced,
            ) { Text("Desde el principio") }
        }

        // ---- Ajustes ----
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Volumen ${(vm.audioVolume * 100).roundToInt()} %",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(end = 8.dp),
            )
            Slider(
                value = vm.audioVolume,
                onValueChange = { vm.audioVolume = (it * 20).roundToInt() / 20f },
                valueRange = 0f..2f,
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = vm.fadeIn, onClick = { vm.fadeIn = !vm.fadeIn }, label = { Text("Entrada suave") })
            FilterChip(selected = vm.fadeOut, onClick = { vm.fadeOut = !vm.fadeOut }, label = { Text("Salida suave") })
            FilterChip(
                selected = vm.normalizeAudio,
                onClick = { vm.normalizeAudio = !vm.normalizeAudio },
                label = { Text("Normalizar") },
            )
        }
        if (vm.canMixVideoAudio) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (vm.bedVolume > 0f) "Audio del vídeo debajo ${(vm.bedVolume * 100).roundToInt()} %"
                    else "Mezclar con el audio del vídeo",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Slider(
                    value = vm.bedVolume,
                    onValueChange = { vm.bedVolume = (it * 20).roundToInt() / 20f },
                    valueRange = 0f..1.5f,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (!vm.animatedPack) {
            Text(
                "En WhatsApp solo los stickers animados suenan: este se exportará como un vídeo de la imagen fija con su sonido.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    if (recording) {
        RecordDialog(
            onDismiss = { recording = false },
            onRecorded = { file ->
                recording = false
                vm.setAudioSource(Uri.fromFile(file), AudioKind.RECORDING, "Nota de voz")
            },
        )
    }
}

/** Forma de onda con el tramo elegido resaltado. */
@Composable
private fun Waveform(peaks: FloatArray?, selection: Pair<Float, Float>, modifier: Modifier) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    val band = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            val (a, b) = selection
            drawRect(band, topLeft = Offset(a * size.width, 0f), size = Size((b - a) * size.width, size.height))
            val p = peaks
            if (p == null || p.isEmpty()) {
                drawLine(off, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2f)
                return@Canvas
            }
            val w = size.width / p.size
            for (i in p.indices) {
                val x = i * w + w / 2
                val frac = (i + 0.5f) / p.size
                val h = (p[i].coerceAtLeast(0.04f)) * size.height
                drawLine(
                    if (frac in a..b) on else off,
                    Offset(x, (size.height - h) / 2),
                    Offset(x, (size.height + h) / 2),
                    strokeWidth = (w * 0.7f).coerceAtLeast(1f),
                )
            }
        }
    }
}

/** Grabación de una nota de voz con el micrófono. */
@Composable
private fun RecordDialog(onDismiss: () -> Unit, onRecorded: (java.io.File) -> Unit) {
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var active by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var maxReached by remember { mutableStateOf(false) }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) error = "Sin permiso de micrófono no se puede grabar"
    }
    LaunchedEffect(Unit) { if (!granted) askPermission.launch(Manifest.permission.RECORD_AUDIO) }
    DisposableEffect(Unit) { onDispose { recorder.release() } }

    fun finish() {
        active = false
        val f = recorder.stop()
        if (f != null) onRecorded(f) else error = "No se grabó nada. Inténtalo de nuevo."
    }

    LaunchedEffect(active) {
        while (active) {
            elapsed = recorder.elapsedMs
            level = level * 0.6f + recorder.level() * 0.4f
            delay(60)
        }
    }
    LaunchedEffect(maxReached) {
        if (maxReached) {
            maxReached = false
            if (active) finish()
        }
    }

    AlertDialog(
        onDismissRequest = { if (!active) onDismiss() },
        title = { Text("Nota de voz") },
        text = {
            Column {
                Text(
                    if (active) "Grabando… ${formatSeconds(elapsed)} de ${SoundMixer.MAX_MS / 1000} s"
                    else "Pulsa Grabar y habla. Se para sola a los ${SoundMixer.MAX_MS / 1000} s.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { (elapsed.toFloat() / SoundMixer.MAX_MS).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { level.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary,
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (active) {
                Button(onClick = { finish() }) {
                    Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                    Text("Parar", Modifier.padding(start = 6.dp))
                }
            } else {
                Button(
                    enabled = granted,
                    onClick = {
                        error = null
                        runCatching { recorder.start { maxReached = true } }
                            .onSuccess { active = true; elapsed = 0 }
                            .onFailure { error = it.message ?: "No se pudo grabar" }
                    },
                ) {
                    Icon(Icons.Default.Mic, null, Modifier.size(18.dp))
                    Text("Grabar", Modifier.padding(start = 6.dp))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { recorder.release(); onDismiss() }) { Text("Cancelar") }
        },
    )
}

private fun displayName(context: android.content.Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()
