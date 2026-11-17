package com.littledumbduck.stickers.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.PointF
import android.os.Build
import com.littledumbduck.stickers.data.StickerLimits
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Operaciones de imagen puras (sin UI) usadas por el editor y el repositorio. */
object StickerProcessor {

    /** Margen transparente que WhatsApp recomienda alrededor del contenido. */
    private const val MARGIN = 16

    // ---------------- Edición ----------------

    /** Recorta la zona rectangular indicada (coordenadas de la imagen). */
    fun crop(src: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Bitmap {
        val l = left.coerceIn(0, src.width - 1)
        val t = top.coerceIn(0, src.height - 1)
        val r = right.coerceIn(l + 1, src.width)
        val b = bottom.coerceIn(t + 1, src.height)
        return Bitmap.createBitmap(src, l, t, r - l, b - t)
    }

    /** Conserva solo lo que queda dentro del trazo a mano alzada y recorta el sobrante. */
    fun lassoCut(src: Bitmap, points: List<PointF>): Bitmap {
        if (points.size < 3) return src
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            close()
        }
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        canvas.drawPath(path, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(src, 0f, 0f, paint)
        return trimTransparent(out)
    }

    /** Borra (hace transparente) un trazo de pincel. */
    fun erase(src: Bitmap, points: List<PointF>, brushWidth: Float): Bitmap {
        if (points.isEmpty()) return src
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = brushWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            if (points.size == 1) lineTo(points[0].x + 0.1f, points[0].y + 0.1f)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        }
        canvas.drawPath(path, paint)
        return out
    }

    fun rotate90(src: Bitmap): Bitmap {
        val m = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun flipHorizontal(src: Bitmap): Bitmap {
        val m = Matrix().apply { postScale(-1f, 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Elimina los bordes totalmente transparentes. */
    fun trimTransparent(src: Bitmap, alphaThreshold: Int = 8): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((px[row + x] ushr 24) > alphaThreshold) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return src // completamente transparente
        if (minX == 0 && minY == 0 && maxX == w - 1 && maxY == h - 1) return src
        return Bitmap.createBitmap(src, minX, minY, maxX - minX + 1, maxY - minY + 1)
    }

    // ---------------- Borde ----------------

    /**
     * Añade un contorno de [width] píxeles y color [color] alrededor de la silueta (zonas no
     * transparentes). Devuelve una imagen [width] píxeles mayor por cada lado.
     *
     * Usa una transformada de distancia euclídea exacta (Felzenszwalb & Huttenlocher) para que el
     * contorno quede redondeado y uniforme, como los stickers "troquelados".
     */
    fun addOutline(src: Bitmap, color: Int, width: Int): Bitmap {
        if (width <= 0) return src
        val r = width
        val sw = src.width
        val sh = src.height
        val w = sw + 2 * r
        val h = sh + 2 * r
        val srcPx = IntArray(sw * sh)
        src.getPixels(srcPx, 0, sw, 0, 0, sw, sh)

        // f = 0 dentro de la silueta, "infinito" fuera
        val grid = FloatArray(w * h) { INF }
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                if ((srcPx[y * sw + x] ushr 24) >= 128) grid[(y + r) * w + (x + r)] = 0f
            }
        }
        squaredDistanceTransform(grid, w, h)

        val rgb = color and 0x00FFFFFF
        val baseAlpha = Color.alpha(color)
        val outPx = IntArray(w * h)
        for (i in 0 until w * h) {
            val d = sqrt(grid[i])
            val a = when {
                d <= r - 0.5f -> 1f
                d < r + 0.5f -> (r + 0.5f - d) // borde suavizado
                else -> 0f
            }
            if (a > 0f) outPx[i] = ((a * baseAlpha).roundToInt() shl 24) or rgb
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPx, 0, w, 0, 0, w, h)
        Canvas(out).drawBitmap(src, r.toFloat(), r.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private const val INF = 1e20f

    private fun squaredDistanceTransform(grid: FloatArray, w: Int, h: Int) {
        val n = max(w, h)
        val f = FloatArray(n)
        val d = FloatArray(n)
        val v = IntArray(n)
        val z = FloatArray(n + 1)
        // columnas
        for (x in 0 until w) {
            for (y in 0 until h) f[y] = grid[y * w + x]
            edt1d(f, h, d, v, z)
            for (y in 0 until h) grid[y * w + x] = d[y]
        }
        // filas
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) f[x] = grid[row + x]
            edt1d(f, w, d, v, z)
            for (x in 0 until w) grid[row + x] = d[x]
        }
    }

    private fun edt1d(f: FloatArray, n: Int, d: FloatArray, v: IntArray, z: FloatArray) {
        var k = 0
        v[0] = 0
        z[0] = -INF
        z[1] = INF
        for (q in 1 until n) {
            var s = intersect(f, q, v[k])
            // z[0] = -INF, así que el bucle nunca baja de k = 0
            while (s <= z[k]) {
                k--
                s = intersect(f, q, v[k])
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = INF
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val dq = (q - v[k]).toFloat()
            d[q] = dq * dq + f[v[k]]
        }
    }

    private fun intersect(f: FloatArray, q: Int, p: Int): Float =
        ((f[q] + q.toFloat() * q) - (f[p] + p.toFloat() * p)) / (2f * q - 2f * p)

    // ---------------- Composición final ----------------

    /**
     * Genera el sticker definitivo de 512x512: recorta transparencias, escala para que quepa con
     * margen y, si se indica, dibuja el borde.
     */
    fun composeSticker(src: Bitmap, borderColor: Int?, borderWidth: Int): Bitmap {
        val size = StickerLimits.STICKER_SIZE
        val trimmed = trimTransparent(src)
        val bw = if (borderColor != null) borderWidth.coerceIn(0, 48) else 0
        val available = (size - 2 * MARGIN - 2 * bw).toFloat()
        val scale = available / max(trimmed.width, trimmed.height)
        val tw = max(1, (trimmed.width * scale).roundToInt())
        val th = max(1, (trimmed.height * scale).roundToInt())
        var content = Bitmap.createScaledBitmap(trimmed, tw, th, true)
        if (bw > 0 && borderColor != null) content = addOutline(content, borderColor, bw)

        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val left = (size - content.width) / 2f
        val top = (size - content.height) / 2f
        Canvas(out).drawBitmap(content, left, top, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** Ajusta una imagen cualquiera a 512x512 sin margen extra (para importar stickers ya hechos). */
    fun fitTo512(src: Bitmap): Bitmap {
        val size = StickerLimits.STICKER_SIZE
        if (src.width == size && src.height == size) return src
        val scale = min(size.toFloat() / src.width, size.toFloat() / src.height)
        val tw = max(1, (src.width * scale).roundToInt())
        val th = max(1, (src.height * scale).roundToInt())
        val scaled = Bitmap.createScaledBitmap(src, tw, th, true)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(scaled, (size - tw) / 2f, (size - th) / 2f, null)
        return out
    }

    // ---------------- Codificación ----------------

    /** Codifica en WebP ajustando la calidad hasta quedar por debajo de 100 KB. */
    fun encodeWebp(bitmap: Bitmap, maxBytes: Int = StickerLimits.STICKER_MAX_BYTES): ByteArray {
        @Suppress("DEPRECATION")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
        var last = ByteArray(0)
        for (q in intArrayOf(95, 90, 80, 70, 60, 50, 40, 30, 20, 10, 5)) {
            val bos = ByteArrayOutputStream()
            bitmap.compress(format, q, bos)
            last = bos.toByteArray()
            if (last.size <= maxBytes) return last
        }
        return last
    }

    /** Icono de bandeja: 96x96 PNG (< 50 KB). */
    fun makeTrayIcon(sticker: Bitmap): ByteArray {
        val s = StickerLimits.TRAY_SIZE
        val scaled = Bitmap.createScaledBitmap(sticker, s, s, true)
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, bos)
        val png = bos.toByteArray()
        return if (png.size <= StickerLimits.TRAY_MAX_BYTES) png
        else encodeWebp(scaled, StickerLimits.TRAY_MAX_BYTES)
    }

    fun encodePng(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}
