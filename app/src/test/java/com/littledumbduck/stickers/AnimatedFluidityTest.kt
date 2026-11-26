package com.littledumbduck.stickers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.littledumbduck.stickers.data.StickerBuilder
import com.littledumbduck.stickers.data.StickerLimits
import com.littledumbduck.stickers.image.ClipSource
import com.littledumbduck.stickers.image.EditImage
import com.littledumbduck.stickers.image.ImageLoader
import com.littledumbduck.stickers.image.StickerProcessor
import com.littledumbduck.stickers.image.WebpCodec
import org.junit.Assert.assertEquals
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
class AnimatedFluidityTest {

    /** Pelota que se mueve sobre fondo transparente (como un sujeto con el fondo quitado). */
    private fun ball(i: Int, size: Int = 300): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawRect(20f, 20f, 60f, 60f, Paint().apply { color = Color.BLUE }) // parte fija
        c.drawCircle(60f + i * 4f, 150f, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED })
        return b
    }

    private fun maxDiff(a: Int, b: Int): Int {
        if (Color.alpha(a) == 0 && Color.alpha(b) == 0) return 0
        return maxOf(
            abs(Color.alpha(a) - Color.alpha(b)), abs(Color.red(a) - Color.red(b)),
            abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)),
        )
    }

    /** Lee las cabeceras ANMF: (x, y, ancho, alto, mezcla). */
    private fun anmfRects(bytes: ByteArray): List<IntArray> {
        fun u24(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16)
        val out = mutableListOf<IntArray>()
        var p = 12
        while (p + 8 <= bytes.size) {
            val tag = String(bytes, p, 4, Charsets.US_ASCII)
            val size = u24(p + 4) or ((bytes[p + 7].toInt() and 0xFF) shl 24)
            if (tag == "ANMF") {
                val d = p + 8
                out += intArrayOf(u24(d) * 2, u24(d + 3) * 2, u24(d + 6) + 1, u24(d + 9) + 1, if (bytes[d + 15].toInt() and 2 == 0) 1 else 0)
            }
            p += 8 + size + (size and 1)
        }
        return out
    }

    @Test
    fun deltaFramesOnlyStoreWhatMovesAndDecodeExactly() {
        val frames = (0 until 20).map { ball(it) }
        val durations = List(20) { 33 }
        val bytes = WebpCodec.encodeAnimated(frames, durations, lossless = true)
        val rects = anmfRects(bytes)
        assertEquals(20, rects.size)
        assertEquals(300, rects[0][2]) // el primero, entero
        for (r in rects.drop(1)) {
            assertTrue("solo la zona de la pelota: ${r.toList()}", r[2] < 100 && r[3] < 80)
            assertEquals(0, r[0] % 2)
        }
        // Se decodifica igual que el original (con fotogramas parciales y mezcla)
        val img = WebpCodec.decodeAnimated(bytes, 512, 60)!!
        assertEquals(durations, img.durations)
        for (i in frames.indices) for (y in 0 until 300 step 5) for (x in 0 until 300 step 5) {
            val d = maxDiff(frames[i].getPixel(x, y), img.frames[i].getPixel(x, y))
            assertTrue("fotograma $i ($x,$y) diferencia $d", d <= 2)
        }
        File("build/test-anim-delta.webp").apply { parentFile?.mkdirs() }.writeBytes(bytes)
    }

    @Test
    fun deltaEncodingIsMuchSmallerThanFullFrames() {
        val frames = (0 until 30).map { ball(it) }
        val durations = List(30) { 33 }
        val delta = WebpCodec.encodeAnimated(frames, durations, lossless = false, quality = 80)
        // Referencia: cada fotograma entero (un WebP por fotograma)
        val full = frames.sumOf { f ->
            java.io.ByteArrayOutputStream().also { f.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it) }.size()
        }
        assertTrue("delta ${delta.size} B vs completos $full B", delta.size < full * 0.6)
    }

    @Test
    fun objectDisappearingOverTransparencyIsNotLeftBehind() {
        // Un píxel que pasa de opaco a transparente no se puede mezclar: debe reemplazarse
        val a = ball(0)
        val b = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val bytes = WebpCodec.encodeAnimated(listOf(a, b, a), listOf(100, 100, 100), lossless = false, quality = 90)
        val img = WebpCodec.decodeAnimated(bytes, 512, 60)!!
        assertEquals(0, Color.alpha(img.frames[1].getPixel(60, 150)))
        assertEquals(0, Color.alpha(img.frames[1].getPixel(40, 40)))
        assertTrue(Color.red(img.frames[2].getPixel(60, 150)) > 200)
    }

    @Test
    fun identicalFramesAreMergedButAnimationKeepsTwoFrames() {
        val f = ball(0)
        val bytes = WebpCodec.encodeAnimated(listOf(f, f, f, f), listOf(100, 100, 100, 100), lossless = false)
        val info = WebpCodec.info(bytes)!!
        assertEquals(2, info.frameCount)
        assertEquals(400, info.totalDuration)
        assertTrue(info.durations.all { it >= 8 })
        // Con uno que cambia en medio: 3 fotogramas y las duraciones sumadas
        val moved = WebpCodec.info(
            WebpCodec.encodeAnimated(listOf(f, f, ball(5), ball(5)), listOf(50, 60, 70, 80), lossless = true)
        )!!
        assertEquals(listOf(110, 150), moved.durations)
    }

    @Test
    fun placementPutsSmallFramesInsideTheCanvas() {
        val frames = (0 until 6).map { ball(it, size = 100) }
        val bytes = WebpCodec.encodeAnimated(
            frames, List(6) { 50 }, lossless = true, placement = WebpCodec.Placement(512, 512, 206, 100),
        )
        val img = WebpCodec.decodeAnimated(bytes, 512, 60)!!
        assertEquals(512, img.width)
        assertEquals(frames[3].getPixel(40, 40), img.frames[3].getPixel(246, 140))
        assertEquals(0, Color.alpha(img.frames[3].getPixel(5, 5)))
    }

    @Test
    fun keepsEveryFrameWhenItFits() {
        val composed = StickerProcessor.composeAnimation((0 until 60).map { ball(it) }, Color.WHITE, 10)
        val r = WebpCodec.encodeAnimatedUnder(
            composed.frames, List(60) { 33 }, StickerLimits.ANIMATED_STICKER_MAX_BYTES, composed.placement,
        )
        assertEquals(60, r.frames)
        assertTrue("calidad ${r.quality}", r.quality >= 80)
        assertTrue(r.bytes.size <= StickerLimits.ANIMATED_STICKER_MAX_BYTES)
        File("build/test-anim-60fps.webp").writeBytes(r.bytes)
    }

    @Test
    fun dropsAsFewFramesAsPossibleWhenTooBig() {
        val rnd = Random(7)
        // Ruido: no se puede comprimir, así que hay que quitar fotogramas
        val frames = (0 until 90).map {
            Bitmap.createBitmap(IntArray(200 * 200) { rnd.nextInt() or (0xFF shl 24) }, 200, 200, Bitmap.Config.ARGB_8888)
        }
        val max = 300 * 1024
        val r = WebpCodec.encodeAnimatedUnder(frames, List(90) { 33 }, max)
        assertTrue(r.bytes.size <= max)
        assertTrue(r.reducedFps)
        assertEquals(WebpCodec.DEFAULT_MIN_QUALITY, r.quality)
        // Casi en el límite: con un 15 % más de fotogramas ya no cabría
        val more = (r.frames * 1.15).toInt().coerceAtMost(90)
        if (more > r.frames) {
            val (fs, ds) = WebpCodec.selectFrames(frames, List(90) { 33 }, more)
            val bigger = WebpCodec.encodeAnimated(fs, ds, lossless = false, quality = r.quality)
            assertTrue("con $more fotogramas ocupa ${bigger.size}", bigger.size > max)
        }
    }

    @Test
    fun builderReportsFluidityAndSmootherModeKeepsMoreFrames() {
        val rnd = Random(11)
        val frames = (0 until 60).map {
            Bitmap.createBitmap(IntArray(256 * 256) { rnd.nextInt() or (0xFF shl 24) }, 256, 256, Bitmap.Config.ARGB_8888)
        }
        val img = EditImage(frames, List(60) { 50 })
        val normal = StickerBuilder.build(img, animatedPack = true, null, 0)
        val report = normal.report!!
        assertTrue(report.needsWarning)
        assertTrue(report.canTradeQualityForFps)
        assertEquals(20f, report.sourceFps, 0.01f)
        assertTrue(StickerBuilder.isValidAnimatedSticker(normal.webp))
        val smooth = StickerBuilder.build(img, animatedPack = true, null, 0, minQuality = StickerBuilder.SMOOTH_MIN_QUALITY)
        assertTrue("${smooth.report!!.frames} vs ${report.frames}", smooth.report!!.frames > report.frames)
        assertTrue(StickerBuilder.isValidAnimatedSticker(smooth.webp))
    }

    @Test
    fun selectFramesSpreadsEvenlyAndKeepsDuration() {
        val frames = (0 until 10).map { ball(it, 10) }
        val (fs, ds) = WebpCodec.selectFrames(frames, List(10) { 100 }, 4)
        assertEquals(4, fs.size)
        assertEquals(1000, ds.sum())
        assertEquals(listOf(200, 300, 200, 300), ds)
        assertTrue(fs[1] === frames[2])
    }

    @Test
    fun clipsAreSampledAtUpTo30Fps() {
        // Vídeo a 30 fps: 5 s → 150 fotogramas (antes 50, a 10 fps)
        val t30 = ClipSource.sampleTimes(0, 5000, 1000.0 / 30, 300)
        assertEquals(150, t30.size)
        assertEquals(5000, ClipSource.durationsFor(t30, 5000).sum())
        // Vídeo a 60 fps: se limita a 30 fps
        assertEquals(60, ClipSource.sampleTimes(1000, 3000, 1000.0 / 60, 300).size)
        // Vídeo a 24 fps: se respetan sus 24
        assertEquals(24, ClipSource.sampleTimes(0, 1000, 1000.0 / 24, 300).size)
        // Sin memoria para todos: se reparten por el tramo entero
        val capped = ClipSource.sampleTimes(0, 10_000, 1000.0 / 30, 120)
        assertEquals(120, capped.size)
        assertTrue(capped.last() > 9_800)
        assertTrue(ImageLoader.maxAnimatedFrames(288, 512) >= ImageLoader.MIN_ANIMATED_FRAMES)
    }

    @Test
    fun repeatedFramesFromSamplingAreMerged() {
        val a = ball(0, 20)
        val b = ball(3, 20)
        val img = ClipSource.mergeRepeated(listOf(a, a.copy(Bitmap.Config.ARGB_8888, false), b, b), listOf(33, 33, 34, 33))
        assertEquals(2, img.frames.size)
        assertEquals(listOf(66, 67), img.durations)
    }
}
