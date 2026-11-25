package com.littledumbduck.stickers

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import com.littledumbduck.stickers.data.TikTokDownloader
import com.littledumbduck.stickers.ui.tiktok.TikTokImportScreen
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.littledumbduck.stickers.ui.editor.EditorScreen
import com.littledumbduck.stickers.ui.home.HomeScreen
import com.littledumbduck.stickers.ui.pack.PackDetailScreen
import com.littledumbduck.stickers.ui.theme.StickersTheme
import com.littledumbduck.stickers.ui.whatsapp.WhatsAppStickersScreen

class MainActivity : ComponentActivity() {
    /** Texto recibido con "Compartir" (p. ej. un enlace de TikTok), pendiente de atender. */
    private val sharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            StickersTheme {
                AppNavigation(sharedText = sharedText.value, onSharedHandled = { sharedText.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedText.value = intent.getStringExtra(Intent.EXTRA_TEXT)
        }
    }
}

object Routes {
    const val HOME = "home"
    const val PACK = "pack/{packId}"
    const val EDITOR = "editor/{packId}?src={src}&sticker={sticker}"
    const val WA_PICK = "wa_pick/{packId}"
    const val TIKTOK = "tiktok?url={url}&packId={packId}"

    fun tiktok(url: String? = null, packId: String? = null) =
        "tiktok?url=${url?.let(Uri::encode).orEmpty()}&packId=${packId?.let(Uri::encode).orEmpty()}"

    fun pack(id: String) = "pack/${Uri.encode(id)}"
    fun waPick(packId: String) = "wa_pick/${Uri.encode(packId)}"

    /** Abre el editor con una imagen nueva ([src]) o para reeditar un sticker existente ([sticker]). */
    fun editor(packId: String, src: Uri? = null, sticker: String? = null): String {
        val sb = StringBuilder("editor/${Uri.encode(packId)}")
        sb.append("?src=").append(src?.let { Uri.encode(it.toString()) }.orEmpty())
        sb.append("&sticker=").append(sticker?.let { Uri.encode(it) }.orEmpty())
        return sb.toString()
    }
}

@androidx.compose.runtime.Composable
fun AppNavigation(
    nav: NavHostController = rememberNavController(),
    sharedText: String? = null,
    onSharedHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    // Enlace compartido desde TikTok → pantalla de descarga
    LaunchedEffect(sharedText) {
        val text = sharedText ?: return@LaunchedEffect
        val link = TikTokDownloader.findLink(text)
        if (link != null) nav.navigate(Routes.tiktok(url = link))
        else Toast.makeText(context, "Comparte un enlace de TikTok para crear un sticker", Toast.LENGTH_LONG).show()
        onSharedHandled()
    }
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenPack = { nav.navigate(Routes.pack(it)) },
                onEditImage = { packId, uri -> nav.navigate(Routes.editor(packId, src = uri)) },
                onFromTikTok = { nav.navigate(Routes.tiktok()) },
            )
        }
        composable(
            Routes.TIKTOK,
            arguments = listOf(
                navArgument("url") { type = NavType.StringType; defaultValue = "" },
                navArgument("packId") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            TikTokImportScreen(
                initialUrl = entry.arguments?.getString("url").orEmpty().ifEmpty { null },
                fixedPackId = entry.arguments?.getString("packId").orEmpty().ifEmpty { null },
                onBack = { nav.popBackStack() },
                onDownloaded = { packId, video ->
                    nav.navigate(Routes.editor(packId, src = video)) {
                        popUpTo(Routes.TIKTOK) { inclusive = true }
                    }
                },
            )
        }
        composable(
            Routes.PACK,
            arguments = listOf(navArgument("packId") { type = NavType.StringType }),
        ) { entry ->
            val packId = entry.arguments?.getString("packId").orEmpty()
            PackDetailScreen(
                packId = packId,
                onBack = { nav.popBackStack() },
                onNewSticker = { uri -> nav.navigate(Routes.editor(packId, src = uri)) },
                onEditSticker = { file -> nav.navigate(Routes.editor(packId, sticker = file)) },
                onImportFromWhatsApp = { nav.navigate(Routes.waPick(packId)) },
                onImportFromTikTok = { nav.navigate(Routes.tiktok(packId = packId)) },
            )
        }
        composable(
            Routes.WA_PICK,
            arguments = listOf(navArgument("packId") { type = NavType.StringType }),
        ) { entry ->
            val packId = entry.arguments?.getString("packId").orEmpty()
            WhatsAppStickersScreen(
                targetPackId = packId,
                onBack = { nav.popBackStack() },
                onOpenPack = { nav.popBackStack() },
                onEditImage = { id, uri ->
                    nav.navigate(Routes.editor(id, src = uri)) {
                        popUpTo(Routes.WA_PICK) { inclusive = true }
                    }
                },
            )
        }
        composable(
            Routes.EDITOR,
            arguments = listOf(
                navArgument("packId") { type = NavType.StringType },
                navArgument("src") { type = NavType.StringType; defaultValue = "" },
                navArgument("sticker") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val args = entry.arguments
            val packId = args?.getString("packId").orEmpty()
            val src = args?.getString("src").orEmpty().ifEmpty { null }?.let(Uri::parse)
            val sticker = args?.getString("sticker").orEmpty().ifEmpty { null }
            EditorScreen(
                packId = packId,
                sourceUri = src,
                stickerFile = sticker,
                onClose = { nav.popBackStack() },
                onSaved = {
                    // Volver a la ficha del paquete (creándola si venimos de la pestaña de WhatsApp)
                    if (!nav.popBackStack(Routes.PACK, inclusive = false)) {
                        nav.popBackStack()
                        nav.navigate(Routes.pack(packId))
                    }
                },
            )
        }
    }
}
