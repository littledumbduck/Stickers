package com.littledumbduck.stickers.audio

import android.content.Context
import android.net.Uri
import java.io.File

/** De dónde sale el sonido de un sticker. */
enum class AudioKind(val label: String) {
    VIDEO_ORIGINAL("Audio del vídeo"),
    FILE("Archivo de audio"),
    OTHER_VIDEO("Audio de otro vídeo"),
    RECORDING("Nota de voz"),
    EXISTING("Sonido actual"),
    ;

    companion object {
        fun fromName(name: String?) = entries.firstOrNull { it.name == name }
    }
}

/** Pista secundaria mezclada por debajo de la principal (p. ej. el audio original del vídeo bajo una música). */
data class AudioBed(val uri: Uri, val startMs: Long, val volume: Float)

/**
 * Receta del sonido de un sticker: tramo de la fuente y efectos. Se convierte en un .m4a
 * (AAC mono 44,1 kHz) con [SoundMixer].
 */
data class SoundSpec(
    val uri: Uri,
    val kind: AudioKind,
    val startMs: Long,
    val endMs: Long,
    /** 0…2 (200 %). */
    val volume: Float = 1f,
    val fadeInMs: Int = 0,
    val fadeOutMs: Int = 0,
    val normalize: Boolean = false,
    val bed: AudioBed? = null,
) {
    val lengthMs get() = endMs - startMs
}

object SoundMixer {

    /** Duración máxima del sonido: la misma que la de un sticker animado. */
    const val MAX_MS = 10_000L
    const val MIN_MS = 300L

    /** Genera las muestras finales (bloqueante). */
    fun render(context: Context, spec: SoundSpec): FloatArray {
        val end = minOf(spec.endMs, spec.startMs + MAX_MS)
        var main = Pcm.gain(AudioCodec.decodeMono(context, spec.uri, spec.startMs, end), spec.volume)
        require(main.isNotEmpty()) { "No hay sonido en el tramo elegido" }
        spec.bed?.takeIf { it.volume > 0f }?.let { bed ->
            val len = Pcm.durationMs(main.size)
            val under = runCatching {
                Pcm.gain(AudioCodec.decodeMono(context, bed.uri, bed.startMs, bed.startMs + len), bed.volume)
            }.getOrNull()
            if (under != null) main = Pcm.mix(main, under).copyOf(main.size)
        }
        if (spec.normalize) main = Pcm.normalize(main)
        if (spec.fadeInMs > 0 || spec.fadeOutMs > 0) main = Pcm.fades(main, Pcm.RATE, spec.fadeInMs, spec.fadeOutMs)
        return main
    }

    /** Genera el .m4a en [out] y devuelve su duración en ms. */
    fun renderToFile(context: Context, spec: SoundSpec, out: File): Long {
        val samples = render(context, spec)
        AudioCodec.encodeM4a(samples, out)
        return Pcm.durationMs(samples.size)
    }
}
