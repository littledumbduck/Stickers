package com.littledumbduck.stickers.ui.whatsapp

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.littledumbduck.stickers.data.PackRepository
import com.littledumbduck.stickers.data.StickerImporter
import com.littledumbduck.stickers.data.StickerLimits
import com.littledumbduck.stickers.ui.common.PackInfoDialog
import com.littledumbduck.stickers.ui.common.PackPickerDialog
import com.littledumbduck.stickers.whatsapp.WaSticker
import com.littledumbduck.stickers.whatsapp.WhatsAppStickerSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pantalla completa usada al importar desde la ficha de un paquete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppStickersScreen(
    targetPackId: String,
    onBack: () -> Unit,
    onOpenPack: (String) -> Unit,
    onEditImage: (packId: String, uri: Uri) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stickers de WhatsApp") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            WhatsAppStickersContent(targetPackId, onOpenPack, onEditImage)
        }
    }
}

private enum class PendingAction { IMPORT, EDIT }

/**
 * Muestra los stickers guardados por WhatsApp y permite añadirlos a un paquete (tal cual o
 * pasando por el editor). Si [targetPackId] es null se pregunta a qué paquete añadirlos.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WhatsAppStickersContent(
    targetPackId: String?,
    onOpenPack: (String) -> Unit,
    onEditImage: (packId: String, uri: Uri) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { PackRepository.get(context) }
    val packs by repo.packs.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var treeUri by remember { mutableStateOf(WhatsAppStickerSource.savedTree(context)) }
    var stickers by remember { mutableStateOf<List<WaSticker>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val selected = remember { mutableStateListOf<Uri>() }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var showCreatePack by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { WhatsAppStickerSource.saveTree(context, uri) }
                .onSuccess { treeUri = uri; reload++ }
                .onFailure { Toast.makeText(context, "No se pudo acceder a la carpeta", Toast.LENGTH_LONG).show() }
        }
    }

    LaunchedEffect(treeUri, reload) {
        val tree = treeUri ?: return@LaunchedEffect
        stickers = null
        error = null
        runCatching { withContext(Dispatchers.IO) { WhatsAppStickerSource.listStickers(context, tree) } }
            .onSuccess { stickers = it; selected.retainAll(it.map { s -> s.uri }.toSet()) }
            .onFailure { error = "No se pudo leer la carpeta: ${it.message}" }
    }

    fun runAction(packId: String, action: PendingAction) {
        when (action) {
            PendingAction.EDIT -> selected.firstOrNull()?.let { onEditImage(packId, it) }
            PendingAction.IMPORT -> {
                val uris = selected.toList()
                busy = true
                scope.launch {
                    val res = withContext(Dispatchers.IO) { StickerImporter.import(context, packId, uris) }
                    busy = false
                    selected.clear()
                    val msg = buildString {
                        append("${res.imported} stickers añadidos")
                        if (res.skipped > 0) append(" · ${res.skipped} omitidos (paquete lleno o error)")
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    onOpenPack(packId)
                }
            }
        }
    }

    fun requestAction(action: PendingAction) {
        if (targetPackId != null) runAction(targetPackId, action) else pending = action
    }

    if (treeUri == null) {
        FolderAccessIntro(
            onPickWhatsApp = { pickFolder.launch(WhatsAppStickerSource.initialFolderUri(business = false)) },
            onPickBusiness = { pickFolder.launch(WhatsAppStickerSource.initialFolderUri(business = true)) },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (val list = stickers) {
                    null -> "Cargando…"
                    else -> "${list.size} stickers · toca para seleccionar, mantén para editar"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { reload++ }) { Icon(Icons.Default.Refresh, "Recargar") }
            IconButton(onClick = {
                WhatsAppStickerSource.clearTree(context)
                treeUri = null
                stickers = null
            }) { Icon(Icons.Default.FolderOpen, "Cambiar carpeta") }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val list = stickers
            when {
                error != null -> Text(error!!, Modifier.align(Alignment.Center).padding(24.dp))
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "No se encontraron stickers en esta carpeta. Elige la carpeta \"WhatsApp Stickers\".",
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = {
                        pickFolder.launch(WhatsAppStickerSource.initialFolderUri())
                    }) { Text("Elegir carpeta") }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(88.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.uri.toString() }) { st ->
                        val isSel = st.uri in selected
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .then(
                                    if (isSel) Modifier.border(
                                        BorderStroke(3.dp, MaterialTheme.colorScheme.primary),
                                        RoundedCornerShape(12.dp),
                                    ) else Modifier
                                )
                                .combinedClickable(
                                    onClick = { if (isSel) selected.remove(st.uri) else selected.add(st.uri) },
                                    onLongClick = {
                                        selected.clear()
                                        selected.add(st.uri)
                                        requestAction(PendingAction.EDIT)
                                    },
                                ),
                        ) {
                            AsyncImage(
                                model = st.uri,
                                contentDescription = st.name,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize().padding(6.dp),
                            )
                            if (isSel) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp)
                                        .background(Color.White, RoundedCornerShape(50)),
                                )
                            }
                        }
                    }
                }
            }
            if (busy) {
                Surface(Modifier.fillMaxSize(), color = Color.Black.copy(alpha = 0.3f)) {
                    Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            }
        }

        if (selected.isNotEmpty()) {
            Surface(tonalElevation = 3.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = { selected.clear() }) { Text("Quitar (${selected.size})") }
                    Spacer(Modifier.weight(1f))
                    if (selected.size == 1) {
                        OutlinedButton(onClick = { requestAction(PendingAction.EDIT) }) {
                            Icon(Icons.Default.Edit, null, Modifier.size(18.dp))
                            Text("Editar", Modifier.padding(start = 6.dp))
                        }
                    }
                    Button(
                        enabled = !busy && selected.size <= StickerLimits.MAX_STICKERS,
                        onClick = { requestAction(PendingAction.IMPORT) },
                    ) {
                        Icon(Icons.Outlined.AddCircleOutline, null, Modifier.size(18.dp))
                        Text("Añadir", Modifier.padding(start = 6.dp))
                    }
                }
            }
        }
    }

    pending?.let { action ->
        if (showCreatePack) {
            PackInfoDialog(
                title = "Nuevo paquete",
                confirmText = "Crear",
                askAnimated = true,
                onDismiss = { showCreatePack = false; pending = null },
                onConfirm = { name, publisher, animated ->
                    showCreatePack = false
                    pending = null
                    scope.launch {
                        val pack = withContext(Dispatchers.IO) { repo.createPack(name, publisher, animated) }
                        runAction(pack.identifier, action)
                    }
                },
            )
        } else {
            PackPickerDialog(
                packs = packs,
                packDir = repo::packDir,
                onDismiss = { pending = null },
                onPick = { pack ->
                    pending = null
                    runAction(pack.identifier, action)
                },
                onCreateNew = { showCreatePack = true },
            )
        }
    }
}

@Composable
private fun FolderAccessIntro(onPickWhatsApp: () -> Unit, onPickBusiness: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.FolderOpen, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Lee los stickers de WhatsApp", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "WhatsApp guarda los stickers que recibes en la carpeta \"WhatsApp Stickers\". " +
                "Android pide que le des permiso una sola vez: se abrirá el selector en esa carpeta, " +
                "pulsa \"Usar esta carpeta\" y luego \"Permitir\".",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onPickWhatsApp, modifier = Modifier.fillMaxWidth()) { Text("Dar acceso (WhatsApp)") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onPickBusiness, modifier = Modifier.fillMaxWidth()) {
            Text("Dar acceso (WhatsApp Business)")
        }
    }
}
