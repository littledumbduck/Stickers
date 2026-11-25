package com.littledumbduck.stickers.ui.pack

import android.app.Activity
import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.littledumbduck.stickers.data.PackRepository
import com.littledumbduck.stickers.data.Sticker
import com.littledumbduck.stickers.data.StickerLimits
import com.littledumbduck.stickers.data.StickerPack
import com.littledumbduck.stickers.ui.common.EmojiDialog
import com.littledumbduck.stickers.ui.common.PackInfoDialog
import com.littledumbduck.stickers.whatsapp.WhatsAppIntegration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackDetailScreen(
    packId: String,
    onBack: () -> Unit,
    onNewSticker: (Uri) -> Unit,
    onEditSticker: (fileName: String) -> Unit,
    onImportFromWhatsApp: () -> Unit,
    onImportFromTikTok: () -> Unit = {},
) {
    val context = LocalContext.current
    val repo = remember { PackRepository.get(context) }
    val packs by repo.packs.collectAsStateWithLifecycle()
    val pack = packs.firstOrNull { it.identifier == packId }
    val scope = rememberCoroutineScope()

    var menu by remember { mutableStateOf(false) }
    var editInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var sheetSticker by remember { mutableStateOf<Sticker?>(null) }
    var emojiFor by remember { mutableStateOf<Sticker?>(null) }
    var deleteSticker by remember { mutableStateOf<Sticker?>(null) }
    var chooseApp by remember { mutableStateOf(false) }

    // Estado en WhatsApp
    var installedApps by remember { mutableStateOf(emptyList<String>()) }
    var addedIn by remember { mutableStateOf(emptySet<String>()) }
    var refresh by remember { mutableStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    LaunchedEffect(refresh, pack?.imageDataVersion) {
        val (apps, added) = withContext(Dispatchers.IO) {
            WhatsAppIntegration.installedApps(context) to WhatsAppIntegration.appsWithPack(context, packId)
        }
        installedApps = apps
        addedIn = added
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onNewSticker(uri)
    }
    val addToWhatsApp = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_CANCELED) {
            res.data?.getStringExtra(WhatsAppIntegration.EXTRA_VALIDATION_ERROR)?.let {
                Toast.makeText(context, "WhatsApp rechazó el paquete: $it", Toast.LENGTH_LONG).show()
            }
        }
        refresh++
    }

    fun launchWhatsApp(pkg: String) {
        val p = pack ?: return
        try {
            addToWhatsApp.launch(WhatsAppIntegration.addPackIntent(p.identifier, p.name, pkg))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "${WhatsAppIntegration.label(pkg)} no está disponible", Toast.LENGTH_LONG).show()
        }
    }

    if (pack == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pack.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") }
                },
                actions = {
                    IconButton(onClick = { editInfo = true }) { Icon(Icons.Default.Edit, "Editar datos") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Más") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Importar de WhatsApp") },
                            leadingIcon = { Icon(Icons.Outlined.Collections, null) },
                            enabled = !pack.isFull,
                            onClick = { menu = false; onImportFromWhatsApp() },
                        )
                        DropdownMenuItem(
                            text = { Text("Eliminar paquete") },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menu = false; confirmDelete = true },
                        )
                    }
                },
            )
        },
        bottomBar = {
            WhatsAppBar(
                pack = pack,
                installedApps = installedApps,
                addedIn = addedIn,
                onAdd = {
                    when (installedApps.size) {
                        0 -> Unit
                        1 -> launchWhatsApp(installedApps.first())
                        else -> chooseApp = true
                    }
                },
            )
        },
    ) { padding ->
        val dir = repo.packDir(pack.identifier)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { PackHeader(pack, dir) }
            items(pack.stickers, key = { it.fileName }) { st ->
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .clickable { sheetSticker = st },
                ) {
                    AsyncImage(
                        File(dir, st.fileName), null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(6.dp),
                    )
                    Text(
                        st.emojis.joinToString(""),
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (!pack.isFull) {
                item {
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
                                RoundedCornerShape(14.dp),
                            )
                            .clickable {
                                // En paquetes animados también se aceptan vídeos (los GIF ya son imágenes)
                                val type = if (pack.animated) ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                else ActivityResultContracts.PickVisualMedia.ImageOnly
                                pickImage.launch(PickVisualMediaRequest(type))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                if (pack.animated) "GIF o vídeo" else "Desde galería",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                item {
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
                                RoundedCornerShape(14.dp),
                            )
                            .clickable(onClick = onImportFromWhatsApp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Collections, null, tint = MaterialTheme.colorScheme.primary)
                            Text("De WhatsApp", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (pack.animated) {
                    item {
                        Box(
                            Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .border(
                                    BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
                                    RoundedCornerShape(14.dp),
                                )
                                .clickable(onClick = onImportFromTikTok),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary)
                                Text("De TikTok", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    // ----- Diálogos -----

    sheetSticker?.let { st ->
        val index = pack.stickers.indexOfFirst { it.fileName == st.fileName }
        ModalBottomSheet(onDismissRequest = { sheetSticker = null }) {
            Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        File(repo.packDir(pack.identifier), st.fileName), null,
                        modifier = Modifier.size(160.dp),
                    )
                }
                ListItem(
                    headlineContent = { Text("Editar imagen") },
                    leadingContent = { Icon(Icons.Default.Edit, null) },
                    modifier = Modifier.clickable { sheetSticker = null; onEditSticker(st.fileName) },
                )
                ListItem(
                    headlineContent = { Text("Cambiar emojis") },
                    supportingContent = { Text(st.emojis.joinToString(" ")) },
                    leadingContent = { Icon(Icons.Default.EmojiEmotions, null) },
                    modifier = Modifier.clickable { sheetSticker = null; emojiFor = st },
                )
                ListItem(
                    headlineContent = { Text("Usar como icono del paquete") },
                    leadingContent = { Icon(Icons.Default.Image, null) },
                    modifier = Modifier.clickable {
                        sheetSticker = null
                        scope.launch(Dispatchers.IO) { repo.setTrayFromSticker(pack.identifier, st.fileName) }
                    },
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = index > 0, onClick = {
                        scope.launch(Dispatchers.IO) { repo.moveSticker(pack.identifier, st.fileName, -1) }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null)
                        Text("Mover antes")
                    }
                    TextButton(enabled = index in 0 until pack.stickers.lastIndex, onClick = {
                        scope.launch(Dispatchers.IO) { repo.moveSticker(pack.identifier, st.fileName, 1) }
                    }) {
                        Text("Mover después")
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                    }
                }
                ListItem(
                    headlineContent = { Text("Eliminar", color = MaterialTheme.colorScheme.error) },
                    leadingContent = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { sheetSticker = null; deleteSticker = st },
                )
            }
        }
    }

    emojiFor?.let { st ->
        EmojiDialog(
            initial = st.emojis,
            onDismiss = { emojiFor = null },
            onConfirm = { list ->
                emojiFor = null
                scope.launch(Dispatchers.IO) { repo.updateEmojis(pack.identifier, st.fileName, list) }
            },
        )
    }

    deleteSticker?.let { st ->
        AlertDialog(
            onDismissRequest = { deleteSticker = null },
            title = { Text("¿Eliminar sticker?") },
            text = {
                if (pack.stickers.size <= StickerLimits.MIN_STICKERS && addedIn.isNotEmpty()) {
                    Text("El paquete quedará con menos de ${StickerLimits.MIN_STICKERS} stickers y WhatsApp no podrá actualizarlo hasta que añadas más.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteSticker = null
                    scope.launch(Dispatchers.IO) { repo.deleteSticker(pack.identifier, st.fileName) }
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { deleteSticker = null }) { Text("Cancelar") } },
        )
    }

    if (editInfo) {
        PackInfoDialog(
            title = "Datos del paquete",
            initialName = pack.name,
            initialPublisher = pack.publisher,
            onDismiss = { editInfo = false },
            onConfirm = { name, publisher, _ ->
                editInfo = false
                scope.launch(Dispatchers.IO) { repo.updatePackInfo(pack.identifier, name, publisher) }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Eliminar \"${pack.name}\"?") },
            text = { Text("Se borrarán todos sus stickers de esta app. Si estaba añadido, desaparecerá de WhatsApp.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        withContext(Dispatchers.IO) { repo.deletePack(pack.identifier) }
                        onBack()
                    }
                }) { Text("Eliminar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }

    if (chooseApp) {
        AlertDialog(
            onDismissRequest = { chooseApp = false },
            title = { Text("¿A qué WhatsApp?") },
            text = {
                Column {
                    installedApps.forEach { pkg ->
                        ListItem(
                            headlineContent = { Text(WhatsAppIntegration.label(pkg)) },
                            supportingContent = { if (pkg in addedIn) Text("Ya añadido · se actualizará") },
                            modifier = Modifier.clickable { chooseApp = false; launchWhatsApp(pkg) },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { chooseApp = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun PackHeader(pack: StickerPack, dir: File) {
    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            pack.trayImageFile?.let { AsyncImage(File(dir, it), "Icono", Modifier.size(52.dp)) }
        }
        Column(Modifier.padding(start = 16.dp)) {
            Text(pack.name, style = MaterialTheme.typography.titleLarge)
            Text(
                "de ${pack.publisher} · ${pack.stickers.size}/${StickerLimits.MAX_STICKERS} stickers" +
                    if (pack.animated) " · animado" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Toca un sticker para editarlo, cambiar sus emojis u ordenarlo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WhatsAppBar(
    pack: StickerPack,
    installedApps: List<String>,
    addedIn: Set<String>,
    onAdd: () -> Unit,
) {
    val error = pack.validationError()
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            when {
                installedApps.isEmpty() ->
                    Text("WhatsApp no está instalado", color = MaterialTheme.colorScheme.error)
                error != null ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                addedIn.isNotEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(
                        "Añadido a ${addedIn.joinToString(" y ") { WhatsAppIntegration.label(it) }}. " +
                            "Los cambios se sincronizan solos.",
                        Modifier.padding(start = 6.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onAdd,
                enabled = installedApps.isNotEmpty() && error == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Send, null, Modifier.size(18.dp))
                Text(
                    if (addedIn.containsAll(installedApps) && installedApps.isNotEmpty()) "Volver a enviar a WhatsApp"
                    else "Añadir a WhatsApp",
                    Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
