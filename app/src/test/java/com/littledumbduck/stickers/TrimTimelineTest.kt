package com.littledumbduck.stickers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.littledumbduck.stickers.ui.editor.TrimTimeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h300dp-xxhdpi")
class TrimTimelineTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val duration = 20_000L
    private var start by mutableLongStateOf(0L)
    private var end by mutableLongStateOf(5_000L)
    private var scrub by mutableStateOf<Long?>(null)

    /** Miniatura con un color distinto según el instante y el segundo dibujado como barras. */
    private fun thumb(t: Long): Bitmap {
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.HSVToColor(floatArrayOf((t % 20_000) / 20_000f * 360f, 0.6f, 0.9f)))
        val p = Paint().apply { color = Color.WHITE }
        repeat(((t / 1000) % 10).toInt() + 1) { Canvas(b).drawRect(6f + it * 6f, 44f, 9f + it * 6f, 58f, p) }
        return b
    }

    private fun setContent() {
        rule.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier.padding(top = 160.dp).width(400.dp)) {
                        TrimTimeline(
                            durationMs = duration,
                            start = start,
                            end = end,
                            aspect = 1f,
                            frameStepMs = 33,
                            snap = { it / 33 * 33 },
                            thumbnail = { t, _ -> thumb(t) },
                            onRangeChange = { s, e -> start = s; end = e },
                            onScrub = { scrub = it },
                            modifier = Modifier.fillMaxWidth().testTag("timeline"),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    /** x de un instante en el lienzo (16 dp de margen a cada lado; la tira está al final del nodo). */
    private fun xOf(t: Long, width: Float, density: Float) =
        16 * density + t.toFloat() / duration * (width - 32 * density)

    private fun save(name: String) {
        val dir = System.getProperty("snapshotDir") ?: return
        rule.waitForIdle()
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bmp)) }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun dragEndHandleWithAndWithoutPrecision() {
        setContent()
        save("timeline_idle.png")
        var plainDelta = 0L
        // 1) Arrastre normal del final 100 px a la izquierda
        rule.onNodeWithTag("timeline").performTouchInput {
            val d = density
            val y = height - 28 * d
            val x = xOf(end, width.toFloat(), d)
            down(Offset(x, y))
            moveBy(Offset(-100f, 0f))
            up()
        }
        rule.waitForIdle()
        plainDelta = 5_000L - end
        assertTrue("arrastre normal: $plainDelta ms", plainDelta in 1500..2500)
        assertEquals(null, scrub) // al soltar se quita la vista previa

        // 2) Mismo movimiento horizontal, pero tras subir el dedo: mucho más preciso
        val endBefore = end
        var preciseDelta = 0L
        rule.onNodeWithTag("timeline").performTouchInput {
            val d = density
            val y = height - 28 * d
            val x = xOf(end, width.toFloat(), d)
            down(Offset(x, y))
            moveBy(Offset(0f, -160 * d)) // 4 pasos de 40 dp → ×16
            moveBy(Offset(-100f, 0f))
        }
        rule.waitForIdle()
        preciseDelta = endBefore - end
        save("timeline_precision.png")
        rule.onNodeWithTag("timeline").performTouchInput { up() }
        rule.waitForIdle()
        assertTrue("con precisión: $preciseDelta ms (normal $plainDelta)", preciseDelta in 1..(plainDelta / 8))
        assertTrue(end - start >= 200)
    }

    @Test
    fun rangeIsLimitedToTenSeconds() {
        setContent()
        rule.onNodeWithTag("timeline").performTouchInput {
            val d = density
            val y = height - 28 * d
            down(Offset(xOf(end, width.toFloat(), d), y))
            moveBy(Offset(width.toFloat(), 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(0L, start)
        assertTrue("fin $end", end in 9_900..10_000)
    }

    @Test
    fun draggingMiddleMovesWholeRange() {
        setContent()
        rule.onNodeWithTag("timeline").performTouchInput {
            val d = density
            val y = height - 28 * d
            down(Offset(xOf(2_500, width.toFloat(), d), y))
            moveBy(Offset(150f, 0f))
            up()
        }
        rule.waitForIdle()
        assertTrue("inicio $start", start > 1_000)
        assertTrue("duración ${end - start}", end - start in 4_950..5_050)
    }
}
