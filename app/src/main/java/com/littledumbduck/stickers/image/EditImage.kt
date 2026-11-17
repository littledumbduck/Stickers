package com.littledumbduck.stickers.image

import android.graphics.Bitmap

/**
 * Imagen que se edita: un único fotograma (estática) o varios con su duración en ms (animada).
 * Todos los fotogramas tienen siempre el mismo tamaño, así cada operación del editor se aplica
 * por igual a toda la animación.
 */
class EditImage(val frames: List<Bitmap>, val durations: List<Int>) {
    init {
        require(frames.isNotEmpty() && frames.size == durations.size)
    }

    val isAnimated get() = frames.size > 1
    val width get() = frames[0].width
    val height get() = frames[0].height
    val first get() = frames[0]
    val byteCount: Long get() = frames.sumOf { it.allocationByteCount.toLong() }

    /** Aplica la misma operación a todos los fotogramas. */
    fun map(op: (Bitmap) -> Bitmap) = EditImage(frames.map(op), durations)

    companion object {
        fun single(bitmap: Bitmap) = EditImage(listOf(bitmap), listOf(0))
    }
}
