package com.littledumbduck.stickers.audio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Convierte un sticker (estático o animado) y su sonido en un MP4 (H.264 + AAC) de 512×512.
 *
 * Es el formato que WhatsApp acepta hoy para crear un sticker con sonido: en su editor
 * ("Crear sticker" → elegir el vídeo) se puede conservar el audio original del vídeo. WhatsApp
 * todavía no permite que otras apps entreguen el sonido dentro de un paquete de stickers.
 */
object StickerVideoExporter {

    const val SIZE = 512
    private const val FPS = 25
    private const val FRAME_MS = 1000L / FPS
    private const val TIMEOUT_US = 10_000L

    /**
     * @param frames fotogramas del sticker (con transparencia); uno solo si es estático.
     * @param durations duración de cada fotograma en ms.
     * @param audio .m4a con el sonido, o null para un vídeo mudo.
     * @param background color con el que se rellena la transparencia (los vídeos no tienen canal alfa).
     * @param minDurationMs duración mínima del vídeo: la animación se repite hasta cubrir el sonido.
     */
    fun export(
        frames: List<Bitmap>,
        durations: List<Int>,
        audio: File?,
        background: Int,
        out: File,
        minDurationMs: Long = 0,
    ) {
        require(frames.isNotEmpty())
        val animMs = if (frames.size == 1) 0L else durations.sumOf { max(1, it).toLong() }
        val audioMs = audio?.let { audioDurationMs(it) } ?: 0L
        val totalMs = max(max(animMs, audioMs), max(minDurationMs, 1000L)).coerceAtMost(SoundMixer.MAX_MS)

        // Fotogramas ya compuestos sobre el fondo, convertidos a YUV una sola vez
        val yuv = frames.map { Yuv.fromArgb(flatten(it, background), SIZE, SIZE) }
        val starts = LongArray(frames.size).also { arr ->
            var acc = 0L
            durations.forEachIndexed { i, d -> arr[i] = acc; acc += max(1, d) }
        }
        fun frameAt(t: Long): Int {
            if (frames.size == 1 || animMs <= 0) return 0
            val local = t % animMs
            var i = starts.binarySearch(local)
            if (i < 0) i = -i - 2
            return i.coerceIn(0, frames.lastIndex)
        }

        out.parentFile?.mkdirs()
        out.delete()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, SIZE, SIZE).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val encoderName = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
            ?: error("Este móvil no tiene codificador de vídeo H.264")
        val codec = MediaCodec.createByCodecName(encoderName)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var audioEx: MediaExtractor? = null
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            // La pista de audio se copia tal cual (ya es AAC), sin volver a codificar
            var audioFormat: MediaFormat? = null
            if (audio != null) {
                audioEx = MediaExtractor().apply { setDataSource(audio.absolutePath) }
                val t = (0 until audioEx.trackCount).firstOrNull {
                    audioEx.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                }
                if (t != null) {
                    audioEx.selectTrack(t)
                    audioFormat = audioEx.getTrackFormat(t)
                }
            }

            val frameCount = ((totalMs + FRAME_MS - 1) / FRAME_MS).toInt()
            val info = MediaCodec.BufferInfo()
            var videoTrack = -1
            var audioTrack = -1
            var next = 0
            var outputDone = false
            while (!outputDone) {
                if (next <= frameCount) {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        if (next == frameCount) {
                            codec.queueInputBuffer(idx, 0, 0, totalMs * 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val t = next * FRAME_MS
                            val img = requireNotNull(codec.getInputImage(idx)) { "Codificador sin entrada de imagen" }
                            writeImage(img, yuv[frameAt(t)])
                            codec.queueInputBuffer(idx, 0, SIZE * SIZE * 3 / 2, t * 1000, 0)
                        }
                        next++
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(codec.outputFormat)
                        audioFormat?.let { audioTrack = muxer.addTrack(it) }
                        muxer.start()
                        muxerStarted = true
                    }
                    o >= 0 -> {
                        val buf = requireNotNull(codec.getOutputBuffer(o))
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !config && muxerStarted) {
                            buf.position(info.offset).limit(info.offset + info.size)
                            muxer.writeSampleData(videoTrack, buf, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }

            // Muestras de audio hasta la duración del vídeo
            val ex = audioEx
            if (ex != null && audioTrack >= 0) {
                val buf = ByteBuffer.allocate(256 * 1024)
                val ai = MediaCodec.BufferInfo()
                while (true) {
                    val size = ex.readSampleData(buf, 0)
                    if (size < 0 || ex.sampleTime > totalMs * 1000) break
                    ai.set(0, size, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(audioTrack, buf, ai)
                    ex.advance()
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { audioEx?.release() }
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    fun audioDurationMs(file: File): Long = runCatching {
        val ex = MediaExtractor().apply { setDataSource(file.absolutePath) }
        try {
            (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }
                ?.getLong(MediaFormat.KEY_DURATION)?.div(1000) ?: 0L
        } finally { ex.release() }
    }.getOrDefault(0L)

    /** Sticker de 512×512 sobre un fondo opaco. */
    private fun flatten(src: Bitmap, background: Int): IntArray {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(background or 0xFF000000.toInt())
        val s = min(SIZE.toFloat() / src.width, SIZE.toFloat() / src.height)
        val w = src.width * s
        val h = src.height * s
        c.save()
        c.translate((SIZE - w) / 2f, (SIZE - h) / 2f)
        c.scale(s, s)
        c.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
        return IntArray(SIZE * SIZE).also { bmp.getPixels(it, 0, SIZE, 0, 0, SIZE, SIZE) }
    }

    /** Copia los planos Y, U y V respetando el paso de fila y de píxel que pida el codificador. */
    private fun writeImage(img: Image, yuv: Yuv.Planes) {
        val planes = img.planes
        writePlane(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride, yuv.y, SIZE, SIZE)
        writePlane(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride, yuv.u, SIZE / 2, SIZE / 2)
        writePlane(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride, yuv.v, SIZE / 2, SIZE / 2)
    }

    private fun writePlane(dst: ByteBuffer, rowStride: Int, pixelStride: Int, src: ByteArray, w: Int, h: Int) {
        if (pixelStride == 1 && rowStride == w) {
            dst.position(0)
            dst.put(src, 0, min(src.size, dst.remaining()))
            return
        }
        for (row in 0 until h) {
            val base = row * rowStride
            for (col in 0 until w) {
                val p = base + col * pixelStride
                if (p < dst.limit()) dst.put(p, src[row * w + col])
            }
        }
    }
}

/** Conversión ARGB → YUV 4:2:0 (BT.601, rango limitado), la que esperan los codificadores H.264. */
object Yuv {
    class Planes(val y: ByteArray, val u: ByteArray, val v: ByteArray)

    fun fromArgb(argb: IntArray, w: Int, h: Int): Planes {
        val y = ByteArray(w * h)
        val cw = w / 2
        val ch = h / 2
        val u = ByteArray(cw * ch)
        val v = ByteArray(cw * ch)
        for (j in 0 until h) for (i in 0 until w) {
            val c = argb[j * w + i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            y[j * w + i] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(0, 255).toByte()
        }
        for (j in 0 until ch) for (i in 0 until cw) {
            // Media de los 4 píxeles del bloque 2×2
            var r = 0; var g = 0; var b = 0
            for (dy in 0..1) for (dx in 0..1) {
                val c = argb[(2 * j + dy) * w + 2 * i + dx]
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            r /= 4; g /= 4; b /= 4
            u[j * cw + i] = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
            v[j * cw + i] = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(0, 255).toByte()
        }
        return Planes(y, u, v)
    }
}
