package com.littledumbduck.stickers.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import java.io.ByteArrayOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lectura y escritura de WebP animado. Android sabe codificar y decodificar WebP de una sola
 * imagen, pero no da acceso a los fotogramas de uno animado ni sabe crearlo. Aquí se hace a mano
 * el contenedor RIFF (chunks VP8X / ANIM / ANMF) reutilizando el códec de Android para cada
 * fotograma.
 *
 * Formato: https://developers.google.com/speed/webp/docs/riff_container
 */
object WebpCodec {

    data class Info(val width: Int, val height: Int, val durations: List<Int>) {
        val frameCount get() = durations.size
        val totalDuration get() = durations.sum()
    }

    private class Chunk(val fourcc: String, val start: Int, val dataStart: Int, val size: Int) {
        /** Fin del chunk incluido el byte de relleno. */
        val end get() = dataStart + size + (size and 1)
    }

    // ---------------- Detección / información ----------------

    fun isWebp(bytes: ByteArray) = bytes.size >= 12 &&
        ascii(bytes, 0) == "RIFF" && ascii(bytes, 8) == "WEBP"

    fun isAnimated(bytes: ByteArray): Boolean {
        if (!isWebp(bytes)) return false
        val vp8x = chunks(bytes, 12, riffEnd(bytes)).firstOrNull { it.fourcc == "VP8X" } ?: return false
        return (bytes[vp8x.dataStart].toInt() and 0x02) != 0
    }

    /** Dimensiones y duraciones de un WebP animado (null si no lo es). */
    fun info(bytes: ByteArray): Info? {
        if (!isAnimated(bytes)) return null
        val top = chunks(bytes, 12, riffEnd(bytes))
        val vp8x = top.first { it.fourcc == "VP8X" }
        val w = u24(bytes, vp8x.dataStart + 4) + 1
        val h = u24(bytes, vp8x.dataStart + 7) + 1
        val durations = top.filter { it.fourcc == "ANMF" }.map { u24(bytes, it.dataStart + 12) }
        return Info(w, h, durations)
    }

    // ---------------- Decodificación ----------------

    /**
     * Decodifica todos los fotogramas ya compuestos (cada uno es la imagen completa del lienzo).
     * Si hay más de [maxFrames] se agrupan fotogramas sumando sus duraciones.
     */
    fun decodeAnimated(bytes: ByteArray, maxDim: Int, maxFrames: Int): EditImage? {
        if (!isAnimated(bytes)) return null
        val top = chunks(bytes, 12, riffEnd(bytes))
        val vp8x = top.first { it.fourcc == "VP8X" }
        val cw = u24(bytes, vp8x.dataStart + 4) + 1
        val ch = u24(bytes, vp8x.dataStart + 7) + 1
        val anmfs = top.filter { it.fourcc == "ANMF" }
        if (anmfs.isEmpty()) return null

        // Qué fotogramas conservar
        val step = max(1, (anmfs.size + maxFrames - 1) / maxFrames)
        val scale = min(1f, maxDim.toFloat() / max(cw, ch))
        val outW = max(1, (cw * scale).roundToInt())
        val outH = max(1, (ch * scale).roundToInt())

        val canvasBmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(canvasBmp)
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        val frames = mutableListOf<Bitmap>()
        val durations = mutableListOf<Int>()

        anmfs.forEachIndexed { i, a ->
            val d = a.dataStart
            val x = u24(bytes, d) * 2
            val y = u24(bytes, d + 3) * 2
            val fw = u24(bytes, d + 6) + 1
            val fh = u24(bytes, d + 9) + 1
            val duration = u24(bytes, d + 12)
            val flags = bytes[d + 15].toInt()
            val noBlend = (flags and 0x02) != 0
            val dispose = (flags and 0x01) != 0

            val frameBmp = decodeFrame(bytes, d + 16, a.dataStart + a.size, fw, fh)
            if (frameBmp != null) {
                if (noBlend) canvas.drawRect(x.toFloat(), y.toFloat(), (x + fw).toFloat(), (y + fh).toFloat(), clear)
                canvas.drawBitmap(frameBmp, x.toFloat(), y.toFloat(), null)
            }
            if (i % step == 0) {
                val snap = if (scale < 1f) Bitmap.createScaledBitmap(canvasBmp, outW, outH, true)
                else canvasBmp.copy(Bitmap.Config.ARGB_8888, true)
                frames += snap
                durations += normalizeDuration(duration)
            } else {
                durations[durations.lastIndex] += normalizeDuration(duration)
            }
            if (dispose) canvas.drawRect(x.toFloat(), y.toFloat(), (x + fw).toFloat(), (y + fh).toFloat(), clear)
        }
        return EditImage(frames, durations)
    }

    /** Primer fotograma de cualquier WebP (animado o no). */
    fun decodeFirstFrame(bytes: ByteArray): Bitmap? =
        if (isAnimated(bytes)) decodeAnimated(bytes, Int.MAX_VALUE, 1)?.frames?.firstOrNull()
        else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    /** Reconstruye un WebP independiente con los sub-chunks de un ANMF y lo decodifica. */
    private fun decodeFrame(bytes: ByteArray, start: Int, end: Int, w: Int, h: Int): Bitmap? {
        val sub = chunks(bytes, start, end)
        val alph = sub.firstOrNull { it.fourcc == "ALPH" }
        val img = sub.firstOrNull { it.fourcc == "VP8 " || it.fourcc == "VP8L" } ?: return null
        val out = ByteArrayOutputStream()
        if (alph != null && img.fourcc == "VP8 ") {
            out.write(vp8xChunk(w, h, animated = false, alpha = true))
            out.write(bytes, alph.start, alph.end - alph.start)
        }
        out.write(bytes, img.start, img.end - img.start)
        val webp = wrapRiff(out.toByteArray())
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(webp, 0, webp.size, opts)
    }

    // ---------------- Codificación ----------------

    /**
     * Dónde van los fotogramas dentro del lienzo final. Los stickers se componen en un lienzo de
     * 512x512 pero el contenido (siempre en la misma posición) suele ocupar menos; así no hace
     * falta guardar en memoria fotogramas completos de 512x512.
     */
    class Placement(val canvasWidth: Int, val canvasHeight: Int, val left: Int, val top: Int)

    /** Resultado de [encodeAnimatedUnder]: el archivo y cuánta fluidez/calidad se ha conservado. */
    class AnimatedResult(
        val bytes: ByteArray,
        /** Fotogramas usados de los [sourceFrames] disponibles. */
        val frames: Int,
        val sourceFrames: Int,
        /** Calidad WebP con la que se ha codificado (0-100). */
        val quality: Int,
        val durationMs: Int,
    ) {
        val fps get() = if (durationMs > 0) frames * 1000f / durationMs else 0f
        val sourceFps get() = if (durationMs > 0) sourceFrames * 1000f / durationMs else 0f
        val reducedFps get() = frames < sourceFrames
    }

    /** Calidad por debajo de la cual se prefiere quitar fotogramas antes que seguir bajándola. */
    const val DEFAULT_MIN_QUALITY = 40

    /** Calidades que se prueban para mejorar la nitidez una vez fijados los fotogramas. */
    private val QUALITY_STEPS = intArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90)

    /** Esfuerzo del codificador sin pérdida (0 rápido … 100 lento); el resultado es exacto igual. */
    private const val LOSSLESS_EFFORT = 50

    /**
     * Crea un WebP animado (bucle infinito). Todos los fotogramas tienen el mismo tamaño y se
     * colocan según [placement] (por defecto ocupan todo el lienzo).
     *
     * Solo el primer fotograma se guarda entero; en los demás se guarda el rectángulo que cambia
     * respecto a lo que ya se ve (como hace libwebp), y los fotogramas que no cambian se funden con
     * el anterior. En los vídeos con el fondo quitado esto reduce mucho el tamaño, que es lo que
     * permite mantener más fotogramas por segundo dentro del límite de WhatsApp.
     */
    fun encodeAnimated(
        frames: List<Bitmap>,
        durations: List<Int>,
        lossless: Boolean,
        quality: Int = 80,
        placement: Placement? = null,
    ): ByteArray {
        require(frames.isNotEmpty() && frames.size == durations.size)
        val p = placement ?: Placement(frames[0].width, frames[0].height, 0, 0)
        val tolerance = if (lossless) 0 else toleranceFor(quality)
        val q = if (lossless) LOSSLESS_EFFORT else quality
        return DeltaEncoder(frames, durations, p, lossless, q, tolerance).encode()
    }

    /**
     * Codifica respetando el límite de WhatsApp para stickers animados, buscando siempre la mayor
     * fluidez posible:
     * 1. Se busca el mayor número de fotogramas (repartidos por igual en el tiempo) que cabe en
     *    [maxBytes] con la calidad [minQuality].
     * 2. Con esos fotogramas se sube la calidad todo lo que permita el límite.
     * Solo si ni con 2 fotogramas cabe, se baja la calidad por debajo de [minQuality].
     *
     * [onProgress] recibe los fps que se están probando (para mostrarlo en pantalla).
     */
    fun encodeAnimatedUnder(
        frames: List<Bitmap>,
        durations: List<Int>,
        maxBytes: Int,
        placement: Placement? = null,
        minQuality: Int = DEFAULT_MIN_QUALITY,
        onProgress: ((fps: Float) -> Unit)? = null,
    ): AnimatedResult {
        require(frames.isNotEmpty() && frames.size == durations.size)
        val total = durations.sum()
        val n = frames.size
        // Con un solo fotograma no hay nada que quitar; WhatsApp necesita al menos 2 (los añade el constructor)
        val minFrames = min(2, n)

        // selectFrames puede devolver menos si hay fotogramas largos: se informa del número real
        fun actual(count: Int) = selectFrames(frames, durations, count).first.size

        fun attempt(count: Int, q: Int): ByteArray {
            onProgress?.invoke(count * 1000f / max(1, total))
            val (fs, ds) = selectFrames(frames, durations, count)
            return encodeAnimated(fs, ds, lossless = false, quality = q, placement = placement)
        }

        // 1. Máximo de fotogramas con la calidad mínima
        val floorQ = minQuality.coerceIn(QUALITY_STEPS.first(), QUALITY_STEPS.last())
        var fit: Pair<Int, ByteArray>? = null
        val bigBytes = attempt(n, floorQ)
        if (bigBytes.size <= maxBytes) {
            fit = n to bigBytes
        } else {
            // Búsqueda por interpolación entre "cabe" (lo) y "no cabe" (hi): el tamaño crece con el
            // número de fotogramas casi en proporción, así que bastan pocos intentos.
            var lo = minFrames - 1
            var loSize = 0
            var hi = n
            var hiSize = bigBytes.size
            var tries = 0
            while (hi - lo > 1 && tries < 8) {
                val target = maxBytes * 0.97
                val guess = if (lo < minFrames) {
                    (hi * target / hiSize).toInt()
                } else {
                    (lo + (hi - lo) * (target - loSize) / (hiSize - loSize)).toInt()
                }.coerceIn(max(lo + 1, minFrames), hi - 1)
                val bytes = attempt(guess, floorQ)
                if (bytes.size <= maxBytes) {
                    lo = guess; loSize = bytes.size; fit = guess to bytes
                    // Precisión suficiente: a menos de un 4 % de fotogramas del máximo teórico
                    if (hi - lo <= max(1, hi / 25)) break
                } else {
                    hi = guess; hiSize = bytes.size
                }
                tries++
            }
            if (fit == null && lo < minFrames && hi > minFrames) {
                val bytes = attempt(minFrames, floorQ)
                if (bytes.size <= maxBytes) fit = minFrames to bytes
            }
        }

        if (fit == null) {
            // Ni con el mínimo de fotogramas: se baja la calidad hasta que quepa
            val lower = QUALITY_STEPS.filter { it < floorQ }.reversed().ifEmpty { listOf(floorQ) }
            var last = ByteArray(0)
            for (q in lower) {
                last = attempt(minFrames, q)
                if (last.size <= maxBytes) return AnimatedResult(last, actual(minFrames), n, q, total)
            }
            return AnimatedResult(last, actual(minFrames), n, lower.last(), total)
        }

        // 2. Con esos fotogramas, la mejor calidad que quepa (búsqueda binaria)
        val count = fit.first
        var best = fit.second
        var bestQ = floorQ
        val higher = QUALITY_STEPS.filter { it > floorQ }
        var a = 0
        var b = higher.lastIndex
        while (a <= b) {
            val mid = (a + b) / 2
            val bytes = attempt(count, higher[mid])
            if (bytes.size <= maxBytes) {
                best = bytes; bestQ = higher[mid]; a = mid + 1
            } else {
                b = mid - 1
            }
        }
        return AnimatedResult(best, actual(count), n, bestQ, total)
    }

    /**
     * Elige [count] fotogramas repartidos por igual en el tiempo; cada uno dura hasta el siguiente
     * elegido, así la animación conserva su duración total.
     */
    fun selectFrames(frames: List<Bitmap>, durations: List<Int>, count: Int): Pair<List<Bitmap>, List<Int>> {
        val n = frames.size
        if (count >= n) return frames to durations
        val starts = IntArray(n + 1)
        for (i in 0 until n) starts[i + 1] = starts[i] + durations[i]
        val total = starts[n].toDouble()
        val picked = ArrayList<Int>(count)
        for (j in 0 until count) {
            // Fotograma visible en el instante j * total / count
            val t = j * total / count
            var i = starts.binarySearch(t.toInt())
            if (i < 0) i = -i - 2
            i = i.coerceIn(0, n - 1)
            if (picked.isEmpty() || i > picked.last()) picked += i
        }
        val fs = picked.map { frames[it] }
        val ds = picked.mapIndexed { k, i -> starts[if (k + 1 < picked.size) picked[k + 1] else n] - starts[i] }
        return fs to ds
    }

    /** Diferencia de color tolerada para considerar que un píxel no ha cambiado. */
    private fun toleranceFor(quality: Int) = (100 - quality.coerceIn(0, 100)) / 10 + 1

    /** Un fotograma del WebP final: rectángulo del lienzo, modo de mezcla y su imagen comprimida. */
    private class Part(val x: Int, val y: Int, val w: Int, val h: Int, val blend: Boolean, var duration: Int) {
        var image: Bitmap? = null
        var data: ByteArray? = null
    }

    private class DeltaEncoder(
        private val frames: List<Bitmap>,
        private val durations: List<Int>,
        private val p: Placement,
        private val lossless: Boolean,
        private val quality: Int,
        private val tolerance: Int,
    ) {
        private val fw = frames[0].width
        private val fh = frames[0].height

        /** Lo que muestra el decodificador en la zona de los fotogramas (valores de origen). */
        private val shown = IntArray(fw * fh)
        private val cur = IntArray(fw * fh)

        fun encode(): ByteArray {
            require(p.left >= 0 && p.top >= 0 && p.left + fw <= p.canvasWidth && p.top + fh <= p.canvasHeight)
            val parts = ArrayList<Part>(frames.size)
            val batch = ArrayList<Part>()
            for (i in frames.indices) {
                val d = durations[i].coerceIn(0, 0xFFFFFF)
                val part = if (i == 0) firstFrame(d) else nextFrame(i, d)
                if (part == null) {
                    // Igual que lo que ya se ve: se alarga el fotograma anterior
                    parts.last().duration = (parts.last().duration + d).coerceAtMost(0xFFFFFF)
                    continue
                }
                parts += part
                batch += part
                if (batch.size >= BATCH) compressAll(batch)
            }
            compressAll(batch)
            if (parts.size == 1 && frames.size > 1) {
                // Todos iguales: WhatsApp exige al menos 2 fotogramas → uno vacío de 2x2 que no cambia nada
                val only = parts[0]
                val second = Part(0, 0, 2, 2, blend = true, duration = max(8, only.duration / 2))
                only.duration = max(8, only.duration - second.duration)
                second.image = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                parts += second
                compressAll(mutableListOf(second))
            }
            return assemble(parts)
        }

        private fun firstFrame(duration: Int): Part {
            val f = frames[0]
            f.getPixels(shown, 0, fw, 0, 0, fw, fh)
            val img = if (p.canvasWidth == fw && p.canvasHeight == fh) f
            else Bitmap.createBitmap(p.canvasWidth, p.canvasHeight, Bitmap.Config.ARGB_8888).also {
                it.setPixels(shown, 0, fw, p.left, p.top, fw, fh)
            }
            return Part(0, 0, p.canvasWidth, p.canvasHeight, blend = false, duration).also { it.image = img }
        }

        private fun nextFrame(i: Int, duration: Int): Part? {
            frames[i].getPixels(cur, 0, fw, 0, 0, fw, fh)
            var minX = fw
            var minY = fh
            var maxX = -1
            var maxY = -1
            var changed = 0
            var blendSafe = true
            for (y in 0 until fh) {
                val row = y * fw
                for (x in 0 until fw) {
                    val a = shown[row + x]
                    val b = cur[row + x]
                    if (differs(a, b)) {
                        changed++
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                        if (y < minY) minY = y
                        if (y > maxY) maxY = y
                        // Mezclar solo reproduce el píxel nuevo si es opaco o si debajo no había nada
                        if ((b ushr 24) != 255 && (a ushr 24) != 0) blendSafe = false
                    }
                }
            }
            if (maxX < 0) return null

            // El formato exige que el rectángulo empiece en coordenadas pares del lienzo
            val cx0 = (p.left + minX) and 1.inv()
            val cy0 = (p.top + minY) and 1.inv()
            val cx1 = p.left + maxX + 1
            val cy1 = p.top + maxY + 1
            val rw = cx1 - cx0
            val rh = cy1 - cy0
            // Con mezcla, los píxeles que no cambian van transparentes (comprimen casi gratis); si el
            // rectángulo cambia casi entero no compensa y se sustituye tal cual
            val blend = blendSafe && changed < rw * rh / 2
            val out = IntArray(rw * rh)
            for (yy in 0 until rh) {
                val fy = cy0 + yy - p.top
                if (fy < 0 || fy >= fh) continue // fila del margen del lienzo: transparente
                for (xx in 0 until rw) {
                    val fx = cx0 + xx - p.left
                    if (fx < 0 || fx >= fw) continue
                    val k = fy * fw + fx
                    val b = cur[k]
                    if (blend) {
                        if (differs(shown[k], b)) {
                            out[yy * rw + xx] = b
                            shown[k] = b
                        }
                    } else {
                        out[yy * rw + xx] = b
                        shown[k] = b
                    }
                }
            }
            return Part(cx0, cy0, rw, rh, blend, duration).also {
                it.image = Bitmap.createBitmap(out, rw, rh, Bitmap.Config.ARGB_8888)
            }
        }

        private fun differs(a: Int, b: Int): Boolean {
            if (a == b) return false
            val aa = a ushr 24
            val ba = b ushr 24
            if (aa == 0 && ba == 0) return false // transparente en los dos: el color no importa
            if (tolerance == 0) return true
            return abs(aa - ba) > tolerance ||
                abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) > tolerance ||
                abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) > tolerance ||
                abs((a and 0xFF) - (b and 0xFF)) > tolerance
        }

        /** Comprime en paralelo los fotogramas pendientes y libera sus imágenes. */
        private fun compressAll(batch: MutableList<Part>) {
            if (batch.isEmpty()) return
            val tasks = batch.map { part ->
                Callable {
                    val img = requireNotNull(part.image)
                    part.data = compressSingle(img, lossless, quality)
                    if (img !== frames[0]) img.recycle()
                    part.image = null
                }
            }
            if (tasks.size == 1) tasks[0].call()
            else pool.invokeAll(tasks).forEach { it.get() } // get() relanza los errores
            batch.clear()
        }

        private fun assemble(parts: List<Part>): ByteArray {
            val body = ByteArrayOutputStream()
            body.write(vp8xChunk(p.canvasWidth, p.canvasHeight, animated = true, alpha = true))
            // ANIM: color de fondo transparente + bucle infinito
            body.write(chunkBytes("ANIM", byteArrayOf(0, 0, 0, 0, 0, 0)))
            for (part in parts) {
                val single = requireNotNull(part.data)
                val sub = chunks(single, 12, riffEnd(single))
                val payload = ByteArrayOutputStream()
                payload.write(u24Bytes(part.x / 2))
                payload.write(u24Bytes(part.y / 2))
                payload.write(u24Bytes(part.w - 1))
                payload.write(u24Bytes(part.h - 1))
                payload.write(u24Bytes(part.duration))
                payload.write(if (part.blend) 0x00 else 0x02) // bit 1 = sin mezcla; nunca "dispose"
                sub.filter { it.fourcc == "ALPH" || it.fourcc == "VP8 " || it.fourcc == "VP8L" }
                    .forEach { payload.write(single, it.start, it.end - it.start) }
                body.write(chunkBytes("ANMF", payload.toByteArray()))
            }
            return wrapRiff(body.toByteArray())
        }

        companion object {
            /** Fotogramas que se preparan antes de comprimirlos (limita la memoria). */
            private const val BATCH = 24
        }
    }

    /** Hilos para comprimir fotogramas en paralelo (Bitmap.compress es seguro entre hilos). */
    private val pool: ExecutorService by lazy {
        val n = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
        Executors.newFixedThreadPool(n) { r -> Thread(r, "webp-encoder").apply { isDaemon = true } }
    }

    /**
     * Ajusta las duraciones a las reglas de WhatsApp: cada fotograma ≥ 8 ms (0 se interpreta como
     * 100 ms, como hacen los navegadores) y como mucho 10 s en total (se recortan los últimos).
     */
    fun fitDurations(frames: List<Bitmap>, durations: List<Int>, maxTotalMs: Int = 10_000): Pair<List<Bitmap>, List<Int>> {
        val ds = durations.map { normalizeDuration(it) }
        val keepF = mutableListOf<Bitmap>()
        val keepD = mutableListOf<Int>()
        var total = 0
        for (i in frames.indices) {
            if (total + ds[i] > maxTotalMs) {
                val rest = maxTotalMs - total
                if (rest >= 8) {
                    keepF += frames[i]
                    keepD += rest
                }
                break
            }
            keepF += frames[i]
            keepD += ds[i]
            total += ds[i]
        }
        return keepF to keepD
    }

    private fun normalizeDuration(d: Int) = when {
        d <= 0 -> 100
        d < 8 -> 8
        else -> d
    }

    private fun compressSingle(bmp: Bitmap, lossless: Boolean, quality: Int): ByteArray {
        val format = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                if (lossless) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP_LOSSY
            else -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        // Sin pérdida, la "calidad" es el esfuerzo de compresión: la imagen sale idéntica igualmente.
        // Antes de Android 11 solo hay un formato WEBP, que es sin pérdida únicamente con 100.
        val q = when {
            lossless && Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> 100
            lossless -> quality.coerceIn(0, 100)
            else -> quality.coerceIn(0, 99)
        }
        return ByteArrayOutputStream().also { bmp.compress(format, q, it) }.toByteArray()
    }

    // ---------------- Utilidades RIFF ----------------

    private fun riffEnd(bytes: ByteArray) = min(bytes.size, 8 + u32(bytes, 4))

    private fun chunks(bytes: ByteArray, from: Int, to: Int): List<Chunk> {
        val out = mutableListOf<Chunk>()
        var p = from
        while (p + 8 <= to) {
            val size = u32(bytes, p + 4)
            if (size < 0 || p + 8 + size > to) break
            val c = Chunk(ascii(bytes, p), p, p + 8, size)
            out += c
            p = c.end
        }
        return out
    }

    private fun vp8xChunk(w: Int, h: Int, animated: Boolean, alpha: Boolean): ByteArray {
        var flags = 0
        if (alpha) flags = flags or 0x10
        if (animated) flags = flags or 0x02
        val data = byteArrayOf(flags.toByte(), 0, 0, 0) + u24Bytes(w - 1) + u24Bytes(h - 1)
        return chunkBytes("VP8X", data)
    }

    private fun chunkBytes(fourcc: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(fourcc.toByteArray(Charsets.US_ASCII))
        out.write(u32Bytes(data.size))
        out.write(data)
        if (data.size and 1 == 1) out.write(0)
        return out.toByteArray()
    }

    private fun wrapRiff(body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size + 12)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(u32Bytes(body.size + 4))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(body)
        return out.toByteArray()
    }

    private fun ascii(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)

    private fun u24(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16)

    private fun u32(b: ByteArray, at: Int) = u24(b, at) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun u24Bytes(v: Int) = byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(), (v shr 16 and 0xFF).toByte())

    private fun u32Bytes(v: Int) = u24Bytes(v) + byteArrayOf((v ushr 24 and 0xFF).toByte())
}
