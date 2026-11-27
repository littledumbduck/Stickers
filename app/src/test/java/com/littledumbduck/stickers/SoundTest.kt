package com.littledumbduck.stickers

import com.littledumbduck.stickers.audio.Pcm
import com.littledumbduck.stickers.audio.Yuv
import com.littledumbduck.stickers.data.Sticker
import com.littledumbduck.stickers.data.StickerPack
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.sin

/** Procesado de audio (mezcla, remuestreo, fundidos…), conversión de color y modelo de datos. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SoundTest {

    @Test
    fun stereoIsMixedToMono() {
        val stereo = shortArrayOf(32767, -32767, 16384, 16384)
        val mono = Pcm.monoFrom16(stereo, 2)
        assertEquals(2, mono.size)
        assertEquals(0f, mono[0], 1e-4f)
        assertEquals(0.5f, mono[1], 1e-3f)
        assertArrayEquals(floatArrayOf(0.5f), Pcm.monoFromFloat(floatArrayOf(1f, 0f), 2), 1e-6f)
    }

    @Test
    fun resampleKeepsDurationAndShape() {
        val rate = 48_000
        val tone = FloatArray(rate) { sin(2 * Math.PI * 440 * it / rate).toFloat() }
        val out = Pcm.resample(tone, rate, Pcm.RATE)
        assertEquals(Pcm.RATE, out.size)
        // Misma onda en el mismo instante
        for (i in listOf(100, 5_000, 30_000)) {
            val expected = sin(2 * Math.PI * 440 * i / Pcm.RATE).toFloat()
            assertEquals(expected, out[i], 0.02f)
        }
        assertEquals(1_000L, Pcm.durationMs(out.size))
    }

    @Test
    fun fadesGainMixAndNormalize() {
        val ones = FloatArray(1000) { 1f }
        val f = Pcm.fades(ones, 1000, inMs = 100, outMs = 200)
        assertEquals(0f, f[0], 1e-6f)
        assertEquals(0.5f, f[50], 1e-6f)
        assertEquals(1f, f[500], 1e-6f)
        assertEquals(0f, f[999], 1e-6f)
        assertEquals(0.5f, f[899], 1e-2f)

        val mixed = Pcm.mix(floatArrayOf(0.2f, 0.2f, 0.2f), floatArrayOf(0.1f))
        assertArrayEquals(floatArrayOf(0.3f, 0.2f, 0.2f), mixed, 1e-6f)
        assertArrayEquals(floatArrayOf(0.4f, -0.4f), Pcm.gain(floatArrayOf(0.2f, -0.2f), 2f), 1e-6f)

        val quiet = floatArrayOf(0.1f, -0.2f, 0.05f)
        val n = Pcm.normalize(quiet, peak = 0.8f)
        assertEquals(-0.8f, n[1], 1e-6f)
        assertEquals(0.4f, n[0], 1e-6f)
        // El silencio no se amplifica (sería solo ruido)
        assertArrayEquals(floatArrayOf(0f, 0f), Pcm.normalize(floatArrayOf(0f, 0f)), 0f)
    }

    @Test
    fun pcm16IsClippedLittleEndian() {
        val b = Pcm.to16BitBytes(floatArrayOf(2f, -2f, 0f))
        assertEquals(0xFF.toByte(), b[0]); assertEquals(0x7F.toByte(), b[1]) // 32767
        assertEquals(0x01.toByte(), b[2]); assertEquals(0x80.toByte(), b[3]) // -32767
        assertEquals(0, b[4].toInt()); assertEquals(0, b[5].toInt())
    }

    @Test
    fun windowSelectsSamplesInsideRange() {
        // Bloque de 1000 muestras a 1000 Hz que empieza en 0,5 s; se pide [1 s, 1,2 s)
        assertEquals(500 to 700, Pcm.window(500_000, 1000, 1000, 1_000_000, 1_200_000))
        // Bloque totalmente fuera
        assertEquals(0 to 0, Pcm.window(2_000_000, 1000, 1000, 0, 1_000_000))
        // Bloque totalmente dentro
        assertEquals(0 to 1000, Pcm.window(0, 1000, 1000, 0, 5_000_000))
    }

    @Test
    fun peaksForWaveform() {
        val p = Pcm.peaks(floatArrayOf(0f, 0.5f, -1f, 0.25f), 2)
        assertArrayEquals(floatArrayOf(0.5f, 1f), p, 1e-6f)
    }

    @Test
    fun yuvOfPrimaryColors() {
        fun planes(c: Int) = Yuv.fromArgb(IntArray(4) { c }, 2, 2)
        val white = planes(0xFFFFFFFF.toInt())
        assertEquals(235, white.y[0].toInt() and 0xFF)
        assertEquals(128, white.u[0].toInt() and 0xFF)
        assertEquals(128, white.v[0].toInt() and 0xFF)
        val black = planes(0xFF000000.toInt())
        assertEquals(16, black.y[0].toInt() and 0xFF)
        val red = planes(0xFFFF0000.toInt())
        assertTrue(abs((red.v[0].toInt() and 0xFF) - 240) <= 1)
        assertTrue(abs((red.y[0].toInt() and 0xFF) - 82) <= 1)
        assertEquals(1, red.u.size)
    }

    @Test
    fun stickerAudioSurvivesJson() {
        val st = Sticker("sticker_a.webp", listOf("😀"), audioFile = "sound_a.m4a", audioKind = "RECORDING", audioDurationMs = 3200)
        val pack = StickerPack("p", "n", "a", "tray.png", stickers = listOf(st, Sticker("sticker_b.webp", listOf("😂"))))
        val back = StickerPack.fromJson(org.json.JSONObject(pack.toJson().toString()))
        assertEquals(st, back.stickers[0])
        assertNull(back.stickers[1].audioFile)
        assertEquals(1, back.soundCount)
    }
}
