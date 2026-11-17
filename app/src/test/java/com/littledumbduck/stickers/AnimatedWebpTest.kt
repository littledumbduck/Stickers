package com.littledumbduck.stickers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import androidx.compose.ui.geometry.Offset
import com.littledumbduck.stickers.data.StickerBuilder
import com.littledumbduck.stickers.data.StickerLimits
import com.littledumbduck.stickers.image.EditImage
import com.littledumbduck.stickers.image.StickerProcessor
import com.littledumbduck.stickers.image.WebpCodec
import com.littledumbduck.stickers.ui.editor.ViewTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AnimatedWebpTest {

    private fun frame(w: Int, h: Int, color: Int, x: Float = 0f): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).drawRect(x + w * 0.2f, h * 0.2f, x + w * 0.6f, h * 0.8f, Paint().apply { this.color = color })
        return b
    }

    private fun close(expected: Int, actual: Int, tol: Int = 12) =
        abs(Color.red(expected) - Color.red(actual)) <= tol &&
            abs(Color.green(expected) - Color.green(actual)) <= tol &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tol &&
            abs(Color.alpha(expected) - Color.alpha(actual)) <= tol

    @Test
    fun decodesAnimationMadeByLibwebp() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("pillow_anim.webp").readBytes()
        assertTrue(WebpCodec.isAnimated(bytes))
        val info = WebpCodec.info(bytes)!!
        assertEquals(64, info.width)
        assertEquals(listOf(100, 200, 300), info.durations)
        val img = WebpCodec.decodeAnimated(bytes, 512, 60)!!
        assertEquals(3, img.frames.size)
        assertEquals(listOf(100, 200, 300), img.durations)
        val expected = listOf(Color.RED, Color.GREEN, Color.BLUE)
        img.frames.forEachIndexed { i, f ->
            assertEquals(64, f.width)
            assertTrue("fotograma $i: ${Integer.toHexString(f.getPixel(30, 20))}", close(expected[i], f.getPixel(30, 20)))
            assertEquals(0, Color.alpha(f.getPixel(2, 2)))
        }
    }

    @Test
    fun roundTripLossyKeepsFramesDurationsAndAlpha() {
        val frames = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).map { frame(100, 80, it) }
        val bytes = WebpCodec.encodeAnimated(frames, listOf(50, 60, 70, 80), lossless = false, quality = 90)
        assertTrue(WebpCodec.isAnimated(bytes))
        val img = WebpCodec.decodeAnimated(bytes, 512, 60)!!
        assertEquals(listOf(50, 60, 70, 80), img.durations)
        assertEquals(100, img.width)
        listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW).forEachIndexed { i, c ->
            assertTrue("fotograma $i", close(c, img.frames[i].getPixel(40, 40), tol = 30))
            assertEquals(0, Color.alpha(img.frames[i].getPixel(95, 5)))
        }
        // Para que Pillow/libwebp lo verifique de forma independiente (ver tools/verify_webp.py)
        File("build/test-anim-lossy.webp").apply { parentFile?.mkdirs() }.writeBytes(bytes)
    }

    @Test
    fun losslessSourceRoundTripIsExact() {
        val frames = listOf(frame(64, 64, Color.MAGENTA), frame(64, 64, Color.CYAN, x = 10f))
        val bytes = WebpCodec.encodeAnimated(frames, listOf(100, 100), lossless = true)
        val img = WebpCodec.decodeAnimated(bytes, 512, 60)!!
        for (i in frames.indices) for (y in 0 until 64 step 7) for (x in 0 until 64 step 7) {
            assertEquals(frames[i].getPixel(x, y), img.frames[i].getPixel(x, y))
        }
        File("build/test-anim-lossless.webp").writeBytes(bytes)
    }

    @Test
    fun decodeCapsFramesBySummingDurations() {
        val frames = (0 until 10).map { frame(32, 32, if (it % 2 == 0) Color.RED else Color.BLUE) }
        val bytes = WebpCodec.encodeAnimated(frames, List(10) { 40 }, lossless = true)
        val img = WebpCodec.decodeAnimated(bytes, 512, 4)!!
        assertEquals(4, img.frames.size) // pasos de 3: 0,3,6,9
        assertEquals(400, img.durations.sum())
    }

    @Test
    fun animatedStickerFitsWhatsAppLimitsEvenForNoise() {
        val rnd = Random(3)
        val frames = (0 until 30).map {
            val px = IntArray(256 * 256) { rnd.nextInt() or (0xFF shl 24) }
            Bitmap.createBitmap(px, 256, 256, Bitmap.Config.ARGB_8888)
        }
        val out = StickerBuilder.build(EditImage(frames, List(30) { 100 }), animatedPack = true, Color.WHITE, 10)
        assertTrue("${out.webp.size} bytes", out.webp.size <= StickerLimits.ANIMATED_STICKER_MAX_BYTES)
        assertTrue(StickerBuilder.isValidAnimatedSticker(out.webp))
        assertEquals("webp", out.sourceExtension)
        File("build/test-anim-sticker.webp").writeBytes(out.webp)
    }

    @Test
    fun staticImageInAnimatedPackBecomesTwoFrameAnimation() {
        val out = StickerBuilder.build(EditImage.single(frame(300, 300, Color.RED)), animatedPack = true, null, 0)
        val info = WebpCodec.info(out.webp)!!
        assertEquals(2, info.frameCount)
        assertEquals(512, info.width)
        assertTrue(StickerBuilder.isValidAnimatedSticker(out.webp))
        assertEquals("png", out.sourceExtension)
    }

    @Test
    fun animatedImageInStaticPackUsesFirstFrame() {
        val img = EditImage(listOf(frame(100, 100, Color.RED), frame(100, 100, Color.BLUE)), listOf(100, 100))
        val out = StickerBuilder.build(img, animatedPack = false, null, 0)
        assertFalse(WebpCodec.isAnimated(out.webp))
        assertTrue(out.webp.size <= StickerLimits.STICKER_MAX_BYTES)
    }

    @Test
    fun durationsAreClampedToWhatsAppRules() {
        val frames = List(5) { frame(10, 10, Color.RED) }
        val (f, d) = WebpCodec.fitDurations(frames, listOf(0, 3, 4000, 4000, 4000))
        assertEquals(listOf(100, 8, 4000, 4000, 1892), d)
        assertEquals(5, f.size)
        assertTrue(d.sum() <= 10_000)
    }

    @Test
    fun framesStayAlignedAfterLassoAndCompose() {
        // El contenido se mueve entre fotogramas: el encuadre debe ser común a todos
        val img = EditImage(listOf(frame(200, 200, Color.RED), frame(200, 200, Color.BLUE, x = 60f)), listOf(100, 100))
        val cut = StickerProcessor.lassoCut(img, listOf(PointF(0f, 0f), PointF(200f, 0f), PointF(200f, 200f), PointF(0f, 200f)))
        assertEquals(cut.frames[0].width, cut.frames[1].width)
        val composed = StickerProcessor.composeFrames(cut.frames, Color.WHITE, 8)
        // El rojo (izquierda) y el azul (desplazado) no deben quedar centrados en el mismo sitio
        val redX = (0 until 512).first { close(Color.RED, composed[0].getPixel(it, 256)) }
        val blueX = (0 until 512).first { close(Color.BLUE, composed[1].getPixel(it, 256)) }
        assertTrue("rojo $redX, azul $blueX", blueX > redX + 20)
    }

    @Test
    fun pinchZoomKeepsPointUnderFingers() {
        val v = ViewTransform()
        val c = Offset(300f, 400f)
        v.onPinch(c, 2f, Offset.Zero)
        assertEquals(2f, v.zoom)
        // screen = base*zoom + pan → el punto c (base = c con zoom 1) sigue en c
        assertEquals(c, c * v.zoom + v.pan)
        v.onPinch(c, 100f, Offset.Zero)
        assertEquals(ViewTransform.MAX_ZOOM, v.zoom)
        v.onPinch(c, 0.001f, Offset(5f, 5f))
        assertEquals(1f, v.zoom)
        assertEquals(Offset.Zero, v.pan)
        assertNotNull(v)
    }
}
