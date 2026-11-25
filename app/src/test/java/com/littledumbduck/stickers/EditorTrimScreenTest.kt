package com.littledumbduck.stickers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.littledumbduck.stickers.data.PackRepository
import com.littledumbduck.stickers.image.WebpCodec
import com.littledumbduck.stickers.ui.editor.EditorScreen
import com.littledumbduck.stickers.ui.theme.StickersTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Abre el editor con una animación de 6 s y comprueba la herramienta "Duración". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class EditorTrimScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun trimToolShowsTimelineForAnimation() {
        val ctx = rule.activity
        // 60 fotogramas de 100 ms: un círculo que cruza la imagen cambiando de color
        val frames = (0 until 60).map { i ->
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.HSVToColor(floatArrayOf(i * 6f, 0.8f, 0.95f)) }
                Canvas(this).drawCircle(30f + i * 3.3f, 128f, 40f, p)
            }
        }
        val file = File(ctx.cacheDir, "anim.webp").apply {
            writeBytes(WebpCodec.encodeAnimated(frames, List(60) { 100 }, lossless = false, quality = 80))
        }
        val pack = PackRepository.get(ctx).createPack("Prueba", "Yo", animated = true)

        rule.setContent {
            StickersTheme {
                EditorScreen(pack.identifier, Uri.fromFile(file), null, onClose = {}, onSaved = {})
            }
        }
        rule.waitUntil(10_000) {
            rule.onAllNodesWithTextSafe("Duración 6.00 s de 6.00 s · máx. 10 s")
        }
        rule.onNodeWithText("Duración").assertIsDisplayed()
        rule.onNodeWithText("Aplicar").assertIsDisplayed()

        System.getProperty("snapshotDir")?.let { dir ->
            rule.waitForIdle()
            val view = rule.activity.window.decorView
            val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            rule.runOnUiThread { view.draw(Canvas(bmp)) }
            File(dir, "editor_duracion.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextSafe(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()
}
