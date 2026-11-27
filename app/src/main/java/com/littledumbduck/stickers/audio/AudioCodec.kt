package com.littledumbduck.stickers.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Lectura de la pista de audio de cualquier archivo (MP3, M4A, OGG, WAV, FLAC… y vídeos MP4/WebM)
 * y codificación a AAC en un .m4a. Todo con los códecs del sistema ([MediaCodec]), sin librerías.
 * Bloqueante: llamar desde Dispatchers.IO / Default.
 */
object AudioCodec {

    private const val TIMEOUT_US = 10_000L

    /** Encuentra la primera pista de audio, o -1. */
    private fun audioTrack(ex: MediaExtractor): Int =
        (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: -1

    private fun extractor(context: Context, uri: Uri) = MediaExtractor().apply {
        if (uri.scheme == "file") setDataSource(requireNotNull(uri.path)) else setDataSource(context, uri, null)
    }

    /** ¿Tiene pista de audio? (muchos vídeos de pantalla o GIF convertidos no la tienen). */
    fun hasAudio(context: Context, uri: Uri): Boolean = runCatching {
        val ex = extractor(context, uri)
        try { audioTrack(ex) >= 0 } finally { ex.release() }
    }.getOrDefault(false)

    /** Duración de la pista de audio en ms (0 si no se conoce o no hay audio). */
    fun durationMs(context: Context, uri: Uri): Long = runCatching {
        val ex = extractor(context, uri)
        try {
            val t = audioTrack(ex)
            if (t < 0) 0L else {
                val f = ex.getTrackFormat(t)
                if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            }
        } finally { ex.release() }
    }.getOrDefault(0L).takeIf { it > 0 } ?: runCatching {
        // Algunos archivos no indican la duración de la pista: se pregunta al contenedor
        val r = android.media.MediaMetadataRetriever()
        try {
            if (uri.scheme == "file") r.setDataSource(uri.path) else r.setDataSource(context, uri)
            r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally { r.release() }
    }.getOrDefault(0L)

    /**
     * Decodifica el tramo [startMs, endMs) a mono, a [Pcm.RATE] Hz. Si el archivo es más corto,
     * devuelve lo que haya.
     */
    fun decodeMono(context: Context, uri: Uri, startMs: Long, endMs: Long): FloatArray {
        val out = FloatChunks()
        var rate = Pcm.RATE
        decode(context, uri, startMs * 1000, endMs * 1000) { mono, from, to, _, r ->
            rate = r
            out.add(mono, from, to)
        }
        return Pcm.resample(out.toArray(), rate, Pcm.RATE)
    }

    /**
     * Niveles (0…1) de [buckets] tramos iguales de todo el archivo, para dibujar la forma de onda.
     * Se calcula sobre la marcha, sin guardar el audio en memoria (sirve para canciones largas).
     */
    fun peaks(context: Context, uri: Uri, durationMs: Long, buckets: Int): FloatArray {
        val out = FloatArray(buckets)
        val durUs = max(1L, durationMs * 1000)
        decode(context, uri, 0, durUs) { mono, from, to, t0, rate ->
            for (i in from until to) {
                val t = t0 + i * 1_000_000L / rate
                val b = (t * buckets / durUs).toInt().coerceIn(0, buckets - 1)
                val v = kotlin.math.abs(mono[i])
                if (v > out[b]) out[b] = v
            }
        }
        val m = out.maxOrNull()?.takeIf { it > 1e-4f } ?: return out
        return FloatArray(buckets) { (out[it] / m).coerceIn(0f, 1f) }
    }

    /**
     * Bucle de decodificación: entrega a [sink] cada bloque mono con el rango de índices
     * que cae dentro de [startUs, endUs), su instante inicial y la frecuencia de muestreo.
     */
    private fun decode(
        context: Context,
        uri: Uri,
        startUs: Long,
        endUs: Long,
        sink: (mono: FloatArray, from: Int, to: Int, t0Us: Long, rate: Int) -> Unit,
    ) {
        val ex = extractor(context, uri)
        var codec: MediaCodec? = null
        try {
            val track = audioTrack(ex)
            require(track >= 0) { "Este archivo no tiene sonido" }
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = requireNotNull(codec.getInputBuffer(idx))
                        val size = ex.readSampleData(buf, 0)
                        val t = ex.sampleTime
                        if (size < 0 || t > endUs + 500_000) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, size, t, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        if (info.size > 0) {
                            val buf = requireNotNull(codec.getOutputBuffer(o))
                            buf.position(info.offset).limit(info.offset + info.size)
                            buf.order(ByteOrder.LITTLE_ENDIAN)
                            val mono = if (floatPcm) {
                                val arr = FloatArray(info.size / 4).also { buf.asFloatBuffer().get(it) }
                                Pcm.monoFromFloat(arr, channels)
                            } else {
                                val arr = ShortArray(info.size / 2).also { buf.asShortBuffer().get(it) }
                                Pcm.monoFrom16(arr, channels)
                            }
                            val t0 = info.presentationTimeUs
                            val (from, to) = Pcm.window(t0, mono.size, rate, startUs, endUs)
                            if (to > from) sink(mono, from, to, t0, rate)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    /** Codifica muestras mono a AAC-LC en un contenedor MP4 (.m4a). */
    fun encodeM4a(samples: FloatArray, out: File, rate: Int = Pcm.RATE, bitRate: Int = 96_000) {
        require(samples.isNotEmpty()) { "No hay sonido que guardar" }
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val pcm = Pcm.to16BitBytes(samples)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        out.parentFile?.mkdirs()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var track = -1
            var offset = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = requireNotNull(codec.getInputBuffer(idx))
                        buf.clear()
                        val n = min(buf.remaining(), pcm.size - offset) and 1.inv()
                        val pts = (offset / 2).toLong() * 1_000_000 / rate
                        if (n <= 0) {
                            codec.queueInputBuffer(idx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(pcm, offset, n)
                            codec.queueInputBuffer(idx, 0, n, pts, 0)
                            offset += n
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    o >= 0 -> {
                        val buf = requireNotNull(codec.getOutputBuffer(o))
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (info.size > 0 && !config && muxerStarted) {
                            buf.position(info.offset).limit(info.offset + info.size)
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { if (muxerStarted) muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    /** Acumula trozos de muestras sin copiar en cada paso. */
    private class FloatChunks {
        private val chunks = ArrayList<FloatArray>()
        private var total = 0
        fun add(src: FloatArray, from: Int, to: Int) {
            chunks += src.copyOfRange(from, to)
            total += to - from
        }
        fun toArray(): FloatArray {
            val out = FloatArray(max(0, total))
            var p = 0
            for (c in chunks) { c.copyInto(out, p); p += c.size }
            return out
        }
    }
}
