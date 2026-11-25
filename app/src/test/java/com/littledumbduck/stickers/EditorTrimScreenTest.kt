package com.littledumbduck.stickers

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Abre el editor con una animación larga y comprueba la herramienta "Duración". */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class EditorTrimScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var vm: EditorViewModel

    /** [count] fotogramas de [frameMs]: un círculo que cruza la imagen cambiando de color. */
    private fun openEditor(count: Int, frameMs: Int) {
        val ctx = rule.activity
        val frames = (0 until count).map { i ->
            Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.HSVToColor(floatArrayOf(i * 6f, 0.8f, 0.95f)) }
                Canvas(this).drawCircle(30f + i * 196f / count, 128f, 40f, p)
            }
        }
        val file = File(ctx.cacheDir, "anim_$count.webp").apply {
            writeBytes(WebpCodec.encodeAnimated(frames, List(count) { frameMs }, lossless = false, quality = 80))
        }
        val pack = PackRepository.get(ctx).createPack("Prueba", "Yo", animated = true)
        vm = EditorViewModel(ctx.application as Application)
        // La vista previa se reproduce sin parar: el reloj se avanza a mano
        rule.mainClock.autoAdvance = false
        rule.setContent {
            StickersTheme {
                EditorScreen(pack.identifier, Uri.fromFile(file), null, onClose = {}, onSaved = {}, vm = vm)
            }
        }
        waitFor { vm.clip != null && vm.busyMessage == null }
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

    private fun tick(ms: Long) {
        var left = ms
        while (left > 0) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.mainClock.advanceTimeBy(minOf(100, left))
            left -= 100
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun snapshot(name: String) {
        val dir = System.getProperty("snapshotDir") ?: return
        tick(500)
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bmp)) }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun longClipCanBeWatchedEntirelyAndMarked() {
        // 60 s: como un vídeo largo. Se abre con los primeros 10 s seleccionados…
        openEditor(count = 60, frameMs = 1000)
        assertEquals(EditorTool.TRIM, vm.tool)
        assertEquals(60_000L, vm.clip!!.durationMs)
        assertEquals(0L to 10_000L, vm.trimStart to vm.trimEnd)

        // …pero la vista previa recorre el clip entero, más allá del tramo
        vm.seekPreview(30_000)
        tick(2_000)
        assertTrue("posición ${vm.previewPos}", vm.previewPos in 31_000..33_000)
        rule.onNodeWithText("Ver vídeo entero").assertDoesNotExist() // es una animación, no un vídeo
        rule.onNodeWithText("Ver todo").assertIsDisplayed()

        // Marcar inicio en el segundo 45: conserva la duración de 10 s
        rule.runOnUiThread { vm.previewPlaying = false; vm.seekPreview(45_000) }
        rule.onNodeWithText("Inicio aquí").performClick()
        assertEquals(45_000L to 55_000L, vm.trimStart to vm.trimEnd)

        // Marcar fin en el 58: pasaría de 10 s, así que el inicio se ajusta a 48
        rule.runOnUiThread { vm.seekPreview(58_000) }
        rule.onNodeWithText("Fin aquí").performClick()
        assertEquals(48_000L to 58_000L, vm.trimStart to vm.trimEnd)
        assertTrue(vm.trimDirty)
        snapshot("editor_duracion_largo.png")

        // Repetir solo el tramo: la vista previa se queda dentro de [48, 58)
        rule.onNodeWithText("Repetir el tramo").performClick()
        rule.runOnUiThread { vm.previewPlaying = true }
        repeat(12) {
            tick(1_000)
            assertTrue("posición ${vm.previewPos}", vm.previewPos in 48_000 until 58_000)
        }

        // Aplicar extrae los fotogramas del tramo nuevo
        rule.onNodeWithText("Aplicar").performClick()
        waitFor { !vm.trimDirty && vm.busyMessage == null }
        assertEquals(10, vm.image!!.frames.size)
        assertEquals(10_000, vm.image!!.durations.sum())
    }

    @Test
    fun markEndNearTheEndReachesTheEnd() {
        openEditor(count = 60, frameMs = 100)
        assertEquals(0L to 6_000L, vm.trimStart to vm.trimEnd)
        rule.runOnUiThread { vm.previewPlaying = false; vm.seekPreview(1_000) }
        rule.onNodeWithText("Inicio aquí").performClick()
        rule.runOnUiThread { vm.seekPreview(5_990) }
        rule.onNodeWithText("Fin aquí").performClick()
        assertEquals(1_000L to 6_000L, vm.trimStart to vm.trimEnd)
        rule.onNodeWithText("Aplicar").assertIsDisplayed()
        assertTrue(rule.onAllNodes(hasText("Tramo de 5.00 s · máx. 10 s")).fetchSemanticsNodes().isNotEmpty())
    }
}
