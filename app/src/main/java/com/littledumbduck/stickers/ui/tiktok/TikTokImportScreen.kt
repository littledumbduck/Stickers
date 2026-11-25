package com.littledumbduck.stickers.ui.tiktok

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.littledumbduck.stickers.data.PackRepository
import com.littledumbduck.stickers.data.StickerLimits
import com.littledumbduck.stickers.data.TikTokDownloader
import com.littledumbduck.stickers.ui.common.PackInfoDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Valor del selector de paquete que significa "crear uno nuevo". */
private const val NEW_PACK = "__nuevo__"

/**
 * Pegar (o recibir compartido) un enlace de TikTok, descargar el vídeo sin marca de agua y
 * abrirlo en el editor. Los vídeos solo pueden ir a paquetes animados.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TikTokImportScreen(
    initialUrl: String?,
    fixedPackId: String?,
    onBack: () -> Unit,
    onDownloaded: (packId: String, video: Uri) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val repo = remember { PackRepository.get(context) }
    val packs by repo.packs.collectAsStateWithLifecycle()
    val animatedPacks = packs.filter { it.animated && !it.isFull }
    val scope = rememberCoroutineScope()

    var url by rememberSaveable { mutableStateOf(initialUrl.orEmpty()) }
    var selected by rememberSaveable { mutableStateOf(fixedPackId) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var askNewPack by remember { mutableStateOf(false) }

    val target = selected ?: if (animatedPacks.isEmpty()) NEW_PACK else null
    val link = TikTokDownloader.findLink(url)

    fun startDownload(packId: String) {
        val l = link ?: return
        downloading = true
        progress = null
        error = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    TikTokDownloader.download(l, File(context.cacheDir, "tiktok")) { p ->
                        scope.launch { progress = p }
                    }
                }
            }
            downloading = false
            result.onSuccess { r ->
                if (r.watermarked) {
                    Toast.makeText(
                        context,
                        "TikTok solo ofreció la versión con marca de agua para este vídeo",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                onDownloaded(packId, Uri.fromFile(r.file))
            }.onFailure {
                error = it.message ?: "No se pudo descargar el vídeo"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Desde TikTok") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Pega el enlace de un vídeo (en TikTok: Compartir → Copiar enlace) o compártelo " +
                    "directamente a esta app. Se descarga sin marca de agua y se abre en el editor " +
                    "para elegir el tramo y recortarlo.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; error = null },
                label = { Text("Enlace de TikTok") },
                placeholder = { Text("https://vm.tiktok.com/…") },
                singleLine = true,
                enabled = !downloading,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    IconButton(
                        enabled = !downloading,
                        onClick = { clipboard.getText()?.text?.let { url = TikTokDownloader.findLink(it) ?: it; error = null } },
                    ) { Icon(Icons.Default.ContentPaste, "Pegar") }
                },
                isError = url.isNotBlank() && link == null,
                supportingText = {
                    if (url.isNotBlank() && link == null) Text("No parece un enlace de TikTok")
                },
                modifier = Modifier.fillMaxWidth().testTag("tiktokUrl"),
            )

            if (fixedPackId == null) {
                Text("Paquete animado de destino", style = MaterialTheme.typography.titleSmall)
                animatedPacks.forEach { p ->
                    PackOption(
                        text = "${p.name} · ${p.stickers.size}/${StickerLimits.MAX_STICKERS}",
                        selected = target == p.identifier,
                        enabled = !downloading,
                    ) { selected = p.identifier }
                }
                PackOption(
                    text = "Nuevo paquete animado",
                    selected = target == NEW_PACK,
                    enabled = !downloading,
                    icon = true,
                ) { selected = NEW_PACK }
            }

            if (downloading) {
                val p = progress
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    if (p != null) "Descargando… ${(p * 100).toInt()} %" else "Buscando el vídeo…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = {
                    when (val t = target) {
                        null -> Unit
                        NEW_PACK -> askNewPack = true
                        else -> startDownload(t)
                    }
                },
                enabled = !downloading && link != null && target != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                Text("Descargar y editar", Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "El vídeo es de quien lo publicó: úsalo para tus stickers personales.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (askNewPack) {
        PackInfoDialog(
            title = "Nuevo paquete animado",
            confirmText = "Crear y descargar",
            onDismiss = { askNewPack = false },
            onConfirm = { name, publisher, _ ->
                askNewPack = false
                scope.launch {
                    val pack = withContext(Dispatchers.IO) { repo.createPack(name, publisher, animated = true) }
                    selected = pack.identifier
                    startDownload(pack.identifier)
                }
            },
        )
    }
}

@Composable
private fun PackOption(text: String, selected: Boolean, enabled: Boolean, icon: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        if (icon) Icon(Icons.Default.Add, null, Modifier.size(18.dp).padding(end = 4.dp))
        Text(text)
    }
}
