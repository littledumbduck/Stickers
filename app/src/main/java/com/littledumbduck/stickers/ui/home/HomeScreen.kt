package com.littledumbduck.stickers.ui.home

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.littledumbduck.stickers.data.PackRepository
import com.littledumbduck.stickers.data.StickerLimits
import com.littledumbduck.stickers.data.StickerPack
import com.littledumbduck.stickers.ui.common.PackInfoDialog
import com.littledumbduck.stickers.ui.whatsapp.WhatsAppStickersContent
import com.littledumbduck.stickers.whatsapp.WhatsAppIntegration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPack: (String) -> Unit,
    onEditImage: (packId: String, uri: Uri) -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { PackRepository.get(context) }
    val packs by repo.packs.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var showCreate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Paquetes que ya están en WhatsApp (se revisa al volver a la app)
    var added by remember { mutableStateOf<Set<String>>(emptySet()) }
    var refreshKey by remember { mutableStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refreshKey++
        onPauseOrDispose { }
    }
    LaunchedEffect(packs, refreshKey) {
        added = withContext(Dispatchers.IO) {
            packs.filter { WhatsAppIntegration.appsWithPack(context, it.identifier).isNotEmpty() }
                .map { it.identifier }.toSet()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(title = { Text("Gestor de Stickers") })
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Mis paquetes") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("De WhatsApp") })
                }
            }
        },
        floatingActionButton = {
            if (tab == 0) {
                ExtendedFloatingActionButton(
                    onClick = { showCreate = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Nuevo paquete") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (tab == 0) {
                PackList(packs, added, repo::packDir, onOpenPack, onCreate = { showCreate = true })
            } else {
                WhatsAppStickersContent(
                    targetPackId = null,
                    onOpenPack = onOpenPack,
                    onEditImage = onEditImage,
                )
            }
        }
    }

    if (showCreate) {
        PackInfoDialog(
            title = "Nuevo paquete",
            confirmText = "Crear",
            askAnimated = true,
            onDismiss = { showCreate = false },
            onConfirm = { name, publisher, animated ->
                showCreate = false
                scope.launch {
                    val pack = withContext(Dispatchers.IO) { repo.createPack(name, publisher, animated) }
                    onOpenPack(pack.identifier)
                }
            },
        )
    }
}

@Composable
private fun PackList(
    packs: List<StickerPack>,
    added: Set<String>,
    packDir: (String) -> File,
    onOpenPack: (String) -> Unit,
    onCreate: () -> Unit,
) {
    if (packs.isEmpty()) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.Collections, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(16.dp))
            Text("Aún no tienes paquetes", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Crea un paquete y añade stickers desde tu galería o desde los stickers que ya tienes en WhatsApp.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(onClick = onCreate) { Text("Crear mi primer paquete") }
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(packs, key = { it.identifier }) { pack ->
            PackCard(pack, pack.identifier in added, packDir(pack.identifier)) { onOpenPack(pack.identifier) }
        }
    }
}

@Composable
private fun PackCard(pack: StickerPack, isAdded: Boolean, dir: File, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    pack.trayImageFile?.let {
                        AsyncImage(File(dir, it), null, Modifier.size(40.dp), contentScale = ContentScale.Fit)
                    }
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(pack.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        (if (pack.animated) "Animado · " else "") +
                            "${pack.publisher} · ${pack.stickers.size}/${StickerLimits.MAX_STICKERS}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isAdded) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Añadido a WhatsApp",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            if (pack.stickers.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pack.stickers.take(5).forEach { s ->
                        AsyncImage(File(dir, s.fileName), null, Modifier.size(52.dp), contentScale = ContentScale.Fit)
                    }
                    if (pack.stickers.size > 5) {
                        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                            Text("+${pack.stickers.size - 5}", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            pack.validationError()?.let {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(2.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
