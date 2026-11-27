package com.littledumbduck.stickers

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.littledumbduck.stickers.data.PackRepository
import com.littledumbduck.stickers.image.WebpCodec
import com.littledumbduck.stickers.ui.editor.EditorScreen
import com.littledumbduck.stickers.ui.editor.EditorTool
import com.littledumbduck.stickers.ui.editor.EditorViewModel
import com.littledumbduck.stickers.ui.theme.StickersTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Elegir un fotograma de un clip (paquetes estáticos y "fotograma fijo") y la pestaña Sonido. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class EditorFrameAndSoundTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var vm: EditorViewModel

    private val colors = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)

    /** Animación de 4 colores lisos de 1 s cada uno. */
    private fun open(animatedPack: Boolean) {
        val ctx = rule.activity
        val frames = colors.map { c -> Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(c) } }
        val file = File(ctx.cacheDir, "colors.webp").apply {
            writeBytes(WebpCodec.encodeAnimated(frames, List(frames.size) { 1000 }, lossless = true))
        }
        val pack = PackRepository.get(ctx).createPack("Prueba", "Yo", animated = animatedPack)
        vm = EditorViewModel(ctx.application as Application)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            StickersTheme {
                EditorScreen(pack.identifier, Uri.fromFile(file), null, onClose = {}, onSaved = {}, vm = vm)
            }
        }
        waitFor { vm.clip != null && vm.image != null && vm.busyMessage == null }
    }

    private fun waitFor(condition: () -> Boolean) {
        repeat(400) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.mainClock.advanceTimeByFrame()
            if (condition()) return
            Thread.sleep(10)
        }
        error("No se cumplió la condición")
    }

    private fun centerColor() = vm.image!!.first.let { it.getPixel(it.width / 2, it.height / 2) }

    @Test
    fun staticPackPicksAnyFrameOfAClip() {
        open(animatedPack = false)
        assertTrue(vm.stillMode)
        assertEquals(EditorTool.TRIM, vm.tool)
        assertFalse(vm.image!!.isAnimated)
        assertEquals(Color.RED, centerColor())
        rule.onNodeWithText("Fotograma").assertIsDisplayed()

        // Ir al segundo 2,5 (azul) y quedarse con ese fotograma
        rule.runOnUiThread { vm.previewPlaying = false; vm.seekPreview(2_500) }
        rule.onNodeWithText("Usar este fotograma").performClick()
        waitFor { !vm.trimDirty && vm.busyMessage == null }
        assertEquals(2_000L, vm.framePos)
        assertEquals(Color.BLUE, centerColor())

        // Paso a paso: siguiente fotograma → amarillo
        rule.runOnUiThread { vm.stepFrame(1) }
        rule.onNodeWithText("Usar este fotograma").performClick()
        waitFor { !vm.trimDirty && vm.busyMessage == null }
        assertEquals(Color.YELLOW, centerColor())
    }

    @Test
    fun animatedPackCanSwitchToAStillFrameAndBack() {
        open(animatedPack = true)
        assertFalse(vm.stillMode)
        assertTrue(vm.image!!.isAnimated)

        rule.runOnUiThread { vm.previewPlaying = false; vm.seekPreview(1_200) }
        rule.onNodeWithText("Fotograma fijo").performClick()
        assertTrue(vm.stillMode)
        rule.onNodeWithText("Usar este fotograma").performClick()
        waitFor { !vm.trimDirty && vm.busyMessage == null }
        assertFalse(vm.image!!.isAnimated)
        assertEquals(Color.GREEN, centerColor())

        rule.onNodeWithText("Volver a animación").performClick()
        // Al cambiar de herramienta se aplica el tramo animado
        rule.runOnUiThread { vm.selectTool(EditorTool.BORDER) }
        waitFor { !vm.trimDirty && vm.busyMessage == null }
        assertTrue(vm.image!!.isAnimated)
    }

    @Test
    fun soundTabOffersSources() {
        open(animatedPack = true)
        rule.runOnUiThread { vm.selectTool(EditorTool.AUDIO) }
        waitFor { vm.busyMessage == null }
        rule.onNodeWithText("Sin sonido").assertIsDisplayed()
        rule.onNodeWithText("Archivo").assertIsDisplayed()
        rule.onNodeWithText("Otro vídeo").assertIsDisplayed()
        rule.onNodeWithText("Grabar").assertIsDisplayed()
        // Una animación WebP no es un vídeo: no hay "audio del vídeo"
        rule.onNodeWithText("Del vídeo").assertDoesNotExist()
        assertNull(vm.audioKind)
        assertNull(vm.soundSpec())
        assertEquals(4_000L, vm.stickerLengthMs)
    }
}
