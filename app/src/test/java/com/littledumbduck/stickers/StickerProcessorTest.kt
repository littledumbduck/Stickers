package com.littledumbduck.stickers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import com.littledumbduck.stickers.data.StickerLimits
import com.littledumbduck.stickers.image.StickerProcessor
import com.littledumbduck.stickers.ui.common.parseEmojis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class StickerProcessorTest {

    private fun squareOnTransparent(size: Int, square: Int, color: Int = Color.RED): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val off = (size - square) / 2f
        Canvas(bmp).drawRect(off, off, off + square, off + square, Paint().apply { this.color = color })
        return bmp
    }

    @Test
    fun outlineSurroundsShapeWithColor() {
        val src = squareOnTransparent(40, 20) // cuadrado en [10,30)
        val out = StickerProcessor.addOutline(src, Color.BLUE, 5)
        assertEquals(50, out.width)
        assertEquals(50, out.height)
        // centro: color original
        assertEquals(Color.RED, out.getPixel(25, 25))
        // 3 px a la izquierda del cuadrado (x=10+5-3=12): borde azul opaco
        assertEquals(Color.BLUE, out.getPixel(12, 25))
        // 8 px fuera: transparente
        assertEquals(0, Color.alpha(out.getPixel(7, 25)))
        // la esquina diagonal queda redondeada: a (4,4) en diagonal (~5.6px) no hay borde
        assertEquals(0, Color.alpha(out.getPixel(15 - 4, 15 - 4)))
    }

    @Test
    fun composeProduces512WithTransparentMargin() {
        val src = squareOnTransparent(300, 100)
        val out = StickerProcessor.composeSticker(src, Color.WHITE, 12)
        assertEquals(StickerLimits.STICKER_SIZE, out.width)
        assertEquals(StickerLimits.STICKER_SIZE, out.height)
        assertEquals(0, Color.alpha(out.getPixel(2, 2)))
        assertEquals(Color.RED, out.getPixel(256, 256))
        // A la izquierda del contenido escalado está el borde blanco
        val firstOpaque = (0 until 512).first { Color.alpha(out.getPixel(it, 256)) > 200 }
        assertEquals(Color.WHITE, out.getPixel(firstOpaque + 2, 256))
    }

    @Test
    fun composeWithoutBorderKeepsContentEdge() {
        val out = StickerProcessor.composeSticker(squareOnTransparent(100, 50), null, 12)
        val firstOpaque = (0 until 512).first { Color.alpha(out.getPixel(it, 256)) > 200 }
        assertEquals(Color.RED, out.getPixel(firstOpaque + 1, 256))
        assertEquals(16, firstOpaque, 1.0f)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Float) =
        assertTrue("esperado ~$expected, obtenido $actual", kotlin.math.abs(expected - actual) <= tolerance)

    @Test
    fun webpFitsWhatsAppLimitEvenForNoise() {
        val rnd = Random(1)
        val px = IntArray(512 * 512) { rnd.nextInt() or (0xFF shl 24) }
        val bmp = Bitmap.createBitmap(px, 512, 512, Bitmap.Config.ARGB_8888)
        val bytes = StickerProcessor.encodeWebp(bmp)
        assertTrue("WebP de ${bytes.size} bytes", bytes.size <= StickerLimits.STICKER_MAX_BYTES)
        // Cabecera RIFF....WEBP
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals("WEBP", String(bytes, 8, 4))
    }

    @Test
    fun trayIconIs96AndSmall() {
        val png = StickerProcessor.makeTrayIcon(squareOnTransparent(512, 300))
        assertTrue(png.size <= StickerLimits.TRAY_MAX_BYTES)
        val decoded = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
        assertEquals(96, decoded.width)
    }

    @Test
    fun lassoKeepsOnlyInsideAndTrims() {
        val src = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val tri = listOf(PointF(10f, 10f), PointF(90f, 10f), PointF(10f, 90f))
        val out = StickerProcessor.lassoCut(src, tri)
        assertTrue(out.width in 79..81)
        assertEquals(Color.GREEN, out.getPixel(5, 5))
        assertEquals(0, Color.alpha(out.getPixel(out.width - 3, out.height - 3)))
    }

    @Test
    fun eraseMakesStrokeTransparent() {
        val src = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val out = StickerProcessor.erase(src, listOf(PointF(0f, 25f), PointF(50f, 25f)), 6f)
        assertEquals(0, Color.alpha(out.getPixel(25, 25)))
        assertEquals(Color.BLACK, out.getPixel(25, 5))
    }

    @Test
    fun emojiParsing() {
        assertEquals(listOf("😀", "👍🏽", "❤️"), parseEmojis("😀 hola 👍🏽,❤️🔥"))
        assertEquals(emptyList<String>(), parseEmojis("abc 123"))
        assertEquals(listOf("👨‍👩‍👧"), parseEmojis("👨‍👩‍👧"))
    }
}
