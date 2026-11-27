package com.littledumbduck.stickers.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Operaciones sobre audio en memoria: muestras mono en coma flotante (−1…1). Son funciones puras
 * (sin APIs de Android) para poder probarlas en el ordenador.
 */
object Pcm {

    /** Frecuencia de trabajo y de salida: la que mejor admiten todos los códecs AAC de Android. */
    const val RATE = 44_100

    /** Mezcla a mono muestras PCM de 16 bits intercaladas por canal. */
    fun monoFrom16(samples: ShortArray, channels: Int): FloatArray {
        val ch = max(1, channels)
        val n = samples.size / ch
        return FloatArray(n) { i ->
            var acc = 0f
            for (c in 0 until ch) acc += samples[i * ch + c]
            acc / ch / 32768f
        }
    }

    /** Mezcla a mono muestras en coma flotante intercaladas por canal. */
    fun monoFromFloat(samples: FloatArray, channels: Int): FloatArray {
        val ch = max(1, channels)
        val n = samples.size / ch
        return FloatArray(n) { i ->
            var acc = 0f
            for (c in 0 until ch) acc += samples[i * ch + c]
            acc / ch
        }
    }

    /** Cambia la frecuencia de muestreo con interpolación lineal. */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        val outLen = max(1L, input.size.toLong() * toRate / fromRate).toInt()
        val ratio = fromRate.toDouble() / toRate
        return FloatArray(outLen) { i ->
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(input.lastIndex)
            val i1 = min(i0 + 1, input.lastIndex)
            val f = (pos - i0).toFloat()
            input[i0] * (1 - f) + input[i1] * f
        }
    }

    fun gain(input: FloatArray, g: Float): FloatArray =
        if (g == 1f) input else FloatArray(input.size) { input[it] * g }

    /** Suma dos pistas (la más corta se completa con silencio). */
    fun mix(a: FloatArray, b: FloatArray): FloatArray =
        FloatArray(max(a.size, b.size)) { i -> (if (i < a.size) a[i] else 0f) + (if (i < b.size) b[i] else 0f) }

    /** Sube el volumen para que el pico quede en [peak] (no hace nada si es silencio). */
    fun normalize(input: FloatArray, peak: Float = 0.89f): FloatArray {
        var m = 0f
        for (s in input) m = max(m, abs(s))
        if (m < 1e-4f) return input
        return gain(input, peak / m)
    }

    /** Entrada y salida progresivas (lineales) de [inMs] y [outMs]. */
    fun fades(input: FloatArray, rate: Int, inMs: Int, outMs: Int): FloatArray {
        val out = input.copyOf()
        val nIn = min(out.size, (rate.toLong() * inMs / 1000).toInt())
        val nOut = min(out.size, (rate.toLong() * outMs / 1000).toInt())
        for (i in 0 until nIn) out[i] *= i.toFloat() / nIn
        for (i in 0 until nOut) out[out.size - 1 - i] *= i.toFloat() / nOut
        return out
    }

    /** Convierte a PCM de 16 bits (little endian), recortando lo que se salga de rango. */
    fun to16BitBytes(input: FloatArray): ByteArray {
        val bytes = ByteArray(input.size * 2)
        for (i in input.indices) {
            val v = (input[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
            bytes[2 * i] = (v and 0xFF).toByte()
            bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * Índices [from, to) de un bloque de [count] muestras que empieza en [t0Us] y que caen dentro
     * de [startUs, endUs).
     */
    fun window(t0Us: Long, count: Int, rate: Int, startUs: Long, endUs: Long): Pair<Int, Int> {
        val from = if (t0Us >= startUs) 0 else ((startUs - t0Us) * rate / 1_000_000).toInt()
        val to = if (endUs <= t0Us) 0 else min(count.toLong(), (endUs - t0Us) * rate / 1_000_000).toInt()
        return from.coerceIn(0, count) to to.coerceIn(0, count)
    }

    fun durationMs(samples: Int, rate: Int = RATE): Long = samples * 1000L / rate

    /** Niveles (0…1) de [buckets] tramos, para dibujar la forma de onda. */
    fun peaks(input: FloatArray, buckets: Int): FloatArray {
        if (input.isEmpty() || buckets <= 0) return FloatArray(max(0, buckets))
        return FloatArray(buckets) { b ->
            val from = (input.size.toLong() * b / buckets).toInt()
            val to = max(from + 1, (input.size.toLong() * (b + 1) / buckets).toInt()).coerceAtMost(input.size)
            var m = 0f
            for (i in from until to) m = max(m, abs(input[i]))
            m.coerceAtMost(1f)
        }
    }
}
