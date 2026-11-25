package com.littledumbduck.stickers

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.littledumbduck.stickers.image.ClipSource
import com.littledumbduck.stickers.image.EditImage
import com.littledumbduck.stickers.image.FramesClip
import com.littledumbduck.stickers.image.GifClip
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ClipSourceTest {

    private fun solid(c: Int) = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(c) }

    private val clip = FramesClip(
        EditImage(listOf(solid(Color.RED), solid(Color.GREEN), solid(Color.BLUE)), listOf(100, 200, 300))
    )

    @Test
    fun framesClipTimeline() {
        assertEquals(600, clip.durationMs)
        assertEquals(2f, clip.aspect)
        assertEquals(0L, clip.snap(99))
        assertEquals(100L, clip.snap(250))
        assertEquals(300L, clip.snap(599))
        assertEquals(Color.GREEN, clip.frameAt(299, 512).getPixel(1, 1))
        assertEquals(Color.BLUE, clip.frameAt(300, 512).getPixel(1, 1))
    }

    @Test
    fun framesClipExtractKeepsOnlyOverlapOfEachFrame() {
        val part = clip.extract(50, 400)
        assertEquals(3, part.frames.size)
        assertEquals(listOf(50, 200, 100), part.durations)
        val single = clip.extract(320, 330)
        assertEquals(1, single.frames.size)
        assertEquals(Color.BLUE, single.first.getPixel(1, 1))
    }

    @Test
    fun gifClipFromFile() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("three_frames.gif").readBytes()
        val gif = GifClip.create(bytes)
        // Robolectric no implementa android.graphics.Movie; en un móvil sí existe.
        assumeTrue("Movie no disponible en este entorno", gif != null)
        gif!!
        assertEquals(300, gif.durationMs)
        assertTrue(Color.green(gif.frameAt(150, 512).getPixel(5, 5)) > 200)
        val part = gif.extract(100, 300)
        assertEquals(200, part.durations.sum())
        assertTrue(Color.green(part.first.getPixel(5, 5)) > 200)
        assertTrue(Color.blue(part.frames.last().getPixel(5, 5)) > 200)
    }

    @Test
    fun openDetectsAnimatedFiles() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = ctx.cacheDir
        fun res(name: String) = File(dir, name).apply {
            writeBytes(this@ClipSourceTest.javaClass.classLoader!!.getResourceAsStream(name).readBytes())
        }
        val webp = ClipSource.open(ctx, Uri.fromFile(res("pillow_anim.webp")))
        assertTrue(webp is FramesClip)
        assertEquals(600, webp!!.durationMs)
        val gif = ClipSource.open(ctx, Uri.fromFile(res("three_frames.gif")))
        assumeTrue("Movie no disponible en este entorno", gif != null)
        assertTrue(gif is GifClip)
    }
}
