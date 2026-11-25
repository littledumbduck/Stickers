package com.littledumbduck.stickers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.littledumbduck.stickers.data.PackRepository
import com.littledumbduck.stickers.image.ClipSource
import com.littledumbduck.stickers.ui.theme.StickersTheme
import com.littledumbduck.stickers.ui.tiktok.TikTokImportScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class TikTokImportScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var downloaded: Pair<String, Uri>? = null

    private fun show(url: String?, packId: String?) {
        rule.setContent {
            StickersTheme {
                TikTokImportScreen(url, packId, onBack = {}, onDownloaded = { p, u -> downloaded = p to u })
            }
        }
        rule.waitForIdle()
    }

    private fun snapshot(name: String) {
        val dir = System.getProperty("snapshotDir") ?: return
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bmp)) }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun rejectsTextThatIsNotATikTokLink() {
        PackRepository.get(rule.activity).createPack("Animados", "Yo", animated = true)
        show(null, null)
        rule.onNodeWithText("Descargar y editar").assertIsNotEnabled()
        rule.onNodeWithTag("tiktokUrl").performTextInput("https://youtube.com/watch?v=x")
        rule.onNodeWithText("No parece un enlace de TikTok").assertExists()
        rule.onNodeWithText("Descargar y editar").assertIsNotEnabled()
        // Solo se ofrecen paquetes animados (y crear uno nuevo)
        rule.onNodeWithText("Animados · 0/30").assertExists()
        rule.onNodeWithText("Nuevo paquete animado").assertExists()
    }

    @Test
    fun liveDownloadOpensEditor() {
        val url = System.getProperty("tiktokLiveUrl")
        assumeTrue("Sin TIKTOK_LIVE_URL", !url.isNullOrBlank())
        val pack = PackRepository.get(rule.activity).createPack("Mis TikToks", "Yo", animated = true)
        show(null, null)
        rule.onNodeWithTag("tiktokUrl").performTextInput("Mira esto $url #fyp")
        rule.onNodeWithText("Mis TikToks · 0/30").performClick()
        rule.onNodeWithText("Descargar y editar").assertIsEnabled()
        snapshot("tiktok_pantalla.png")
        rule.onNodeWithText("Descargar y editar").performClick()
        repeat(600) {
            if (downloaded != null) return@repeat
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        val (packId, video) = requireNotNull(downloaded) { "no terminó la descarga" }
        assertEquals(pack.identifier, packId)
        assertTrue(File(video.path!!).length() > 50_000)
        assertTrue(ClipSource.isVideo(rule.activity, video))
        assertTrue(rule.onAllNodes(hasText("No se pudo", substring = true)).fetchSemanticsNodes().isEmpty())
    }
}
