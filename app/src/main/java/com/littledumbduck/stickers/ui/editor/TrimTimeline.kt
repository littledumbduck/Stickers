package com.littledumbduck.stickers.ui.editor

import android.graphics.Bitmap
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwipeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** "1,25 s" con el separador decimal del idioma del móvil. */
fun formatSeconds(ms: Long): String = String.format(Locale.getDefault(), "%.2f s", ms / 1000f)

/** Miniaturas de la línea de tiempo, indexadas por instante para poder usar la más cercana. */
@Stable
private class ThumbCache {
    val map = TreeMap<Long, ImageBitmap>()
    var version by mutableIntStateOf(0)
    val wanted = MutableStateFlow<List<Long>>(emptyList())

    fun nearest(t: Long): ImageBitmap? {
        val lo = map.floorEntry(t)
        val hi = map.ceilingEntry(t)
        return when {
            lo == null -> hi?.value
            hi == null -> lo.value
            t - lo.key <= hi.key - t -> lo.value
            else -> hi.value
        }
    }

    /** Evita crecer sin límite: descarta las miniaturas más alejadas de lo que se ve ahora. */
    fun trim(center: Long, maxEntries: Int = 400) {
        while (map.size > maxEntries) {
            val first = map.firstKey()
            val last = map.lastKey()
            map.remove(if (abs(first - center) > abs(last - center)) first else last)
        }
    }
}

private enum class TrimTarget { START, END, RANGE }

/**
 * Línea de tiempo para elegir el tramo [start, end] de un vídeo o GIF.
 *
 *  - Arrastra los tiradores para mover el inicio o el final, o el centro para mover todo el tramo.
 *  - Mientras arrastras, desliza el dedo **hacia arriba** para ampliar la línea de tiempo alrededor
 *    del tirador: cuanto más arriba, más precisión (cada ~40 dp duplica el zoom) y más fotogramas
 *    se ven. Al soltar vuelve a la vista completa.
 *  - [onScrub] informa del instante que se está tocando para mostrar ese fotograma en grande.
 */
@Composable
fun TrimTimeline(
    durationMs: Long,
    start: Long,
    end: Long,
    aspect: Float,
    frameStepMs: Long,
    snap: (Long) -> Long,
    thumbnail: suspend (timeMs: Long, maxDim: Int) -> Bitmap?,
    onRangeChange: (start: Long, end: Long) -> Unit,
    onScrub: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    playheadMs: Long? = null,
    minLengthMs: Long = 200L,
    maxLengthMs: Long = 10_000L,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val dur = max(1L, durationMs).toFloat()
    val minLen = min(minLengthMs, durationMs).toFloat()
    val maxLen = min(maxLengthMs, durationMs).toFloat()
    // Ventana mínima visible: unos 8 fotogramas o 0,3 s
    val minWindow = max(300f, frameStepMs * 8f).coerceAtMost(dur)
    val maxZoom = max(1f, dur / minWindow)

    var viewStart by remember(durationMs) { mutableFloatStateOf(0f) }
    var viewSpan by remember(durationMs) { mutableFloatStateOf(dur) }
    var dragging by remember { mutableStateOf<TrimTarget?>(null) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val cache = remember(durationMs) { ThumbCache() }

    val currentStart by rememberUpdatedState(start)
    val currentEnd by rememberUpdatedState(end)
    val currentOnRange by rememberUpdatedState(onRangeChange)
    val currentOnScrub by rememberUpdatedState(onScrub)
    val currentSnap by rememberUpdatedState(snap)
    val currentThumb by rememberUpdatedState(thumbnail)

    val insetPx = with(density) { 16.dp.toPx() }
    val stripH = with(density) { 56.dp.toPx() }
    val thumbPx = with(density) { 72.dp.toPx() }.roundToInt()

    // Rejilla de miniaturas visibles
    val contentW = (size.width - 2 * insetPx).coerceAtLeast(1f)
    val tileW = (stripH * aspect).coerceIn(stripH * 0.5f, stripH * 1.5f)
    val tiles = max(1, ceil(contentW / tileW).toInt())
    val tileKeys = remember(viewStart, viewSpan, tiles, durationMs) {
        List(tiles) { i -> snap((viewStart + (i + 0.5f) * viewSpan / tiles).toLong()) }
    }
    LaunchedEffect(tileKeys) { cache.wanted.value = tileKeys }
    LaunchedEffect(cache) {
        cache.wanted.collectLatest { keys ->
            // Primero las del centro (donde suele estar el dedo), luego hacia fuera
            val order = keys.distinct().sortedBy { abs(keys.indexOf(it) - keys.size / 2) }
            for (k in order) {
                if (cache.map.containsKey(k)) continue
                val bmp = currentThumb(k, thumbPx) ?: continue
                cache.map[k] = bmp.asImageBitmap()
                cache.trim(k)
                cache.version++
            }
        }
    }

    val zoom = dur / viewSpan
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.SwipeUp, null,
                Modifier.size(16.dp),
                tint = if (zoom > 1.05f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (dragging != null && zoom > 1.05f) {
                    String.format(Locale.getDefault(), "Precisión ×%.1f · ", zoom) +
                        formatSeconds(if (dragging == TrimTarget.END) end else start)
                } else {
                    "Arrastra los extremos; desliza hacia arriba para más precisión"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Spacer(Modifier.height(6.dp))

        val primary = MaterialTheme.colorScheme.primary
        val onPrimary = MaterialTheme.colorScheme.onPrimary
        val placeholder = MaterialTheme.colorScheme.surfaceVariant
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .onSizeChanged { size = it }
                .clipToBounds()
                .pointerInput(durationMs, minLen, maxLen) {
                    val handleTol = 28.dp.toPx()
                    val zoomStepPx = 40.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val w = (this.size.width - 2 * insetPx).coerceAtLeast(1f)
                        fun xOf(t: Float) = insetPx + (t - viewStart) / viewSpan * w
                        fun tOf(x: Float) = viewStart + (x - insetPx) / w * viewSpan

                        var s = currentStart.toFloat()
                        var e = currentEnd.toFloat()
                        val dStart = abs(down.position.x - xOf(s))
                        val dEnd = abs(down.position.x - xOf(e))
                        val target = when {
                            min(dStart, dEnd) < handleTol -> if (dStart <= dEnd) TrimTarget.START else TrimTarget.END
                            down.position.x > xOf(s) && down.position.x < xOf(e) -> TrimTarget.RANGE
                            // Toque fuera del tramo: se lleva hasta ahí el extremo más cercano
                            down.position.x <= xOf(s) -> TrimTarget.START
                            else -> TrimTarget.END
                        }
                        // Instante que sigue al dedo (sin ajustar a fotogramas para no "atascarse")
                        var handleT = when (target) {
                            TrimTarget.START -> if (dStart < handleTol) s else tOf(down.position.x)
                            TrimTarget.END -> if (dEnd < handleTol) e else tOf(down.position.x)
                            TrimTarget.RANGE -> tOf(down.position.x)
                        }
                        val grab = handleT - s // para mover el tramo entero
                        dragging = target
                        down.consume()

                        fun applyHandle() {
                            when (target) {
                                TrimTarget.START -> {
                                    handleT = handleT.coerceIn(max(0f, e - maxLen), e - minLen)
                                    s = handleT
                                }
                                TrimTarget.END -> {
                                    handleT = handleT.coerceIn(s + minLen, min(dur, s + maxLen))
                                    e = handleT
                                }
                                TrimTarget.RANGE -> {
                                    val len = e - s
                                    s = (handleT - grab).coerceIn(0f, dur - len)
                                    e = s + len
                                }
                            }
                            var ns = currentSnap(s.toLong())
                            var ne = if (e >= dur) durationMs else currentSnap(e.toLong())
                            if (ne - ns < minLen) ne = min(durationMs, ns + minLen.toLong())
                            if (ne - ns > maxLen) ns = ne - maxLen.toLong()
                            currentOnRange(ns, ne)
                            currentOnScrub(if (target == TrimTarget.END) max(ns, ne - 1) else ns)
                        }
                        applyHandle()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            // Zoom según lo que el dedo ha subido desde el toque inicial
                            val up = (down.position.y - change.position.y).coerceAtLeast(0f)
                            val newZoom = 2f.pow(up / zoomStepPx).coerceIn(1f, maxZoom)
                            // Desplazamiento horizontal a la precisión actual
                            handleT += change.positionChange().x * viewSpan / w
                            applyHandle()
                            // Reencuadre: la ventana se centra en el tirador, que queda bajo el dedo
                            viewSpan = dur / newZoom
                            val frac = ((change.position.x - insetPx) / w).coerceIn(0f, 1f)
                            val anchorT = when (target) {
                                TrimTarget.START -> s
                                TrimTarget.END -> e
                                TrimTarget.RANGE -> s + grab
                            }
                            viewStart = (anchorT - frac * viewSpan).coerceIn(0f, dur - viewSpan)
                            change.consume()
                        }
                        dragging = null
                        currentOnScrub(null)
                        // Vuelta suave a la vista completa
                        val fromStart = viewStart
                        val fromSpan = viewSpan
                        scope.launch {
                            animate(0f, 1f) { p, _ ->
                                viewStart = fromStart * (1 - p)
                                viewSpan = fromSpan + (dur - fromSpan) * p
                            }
                        }
                    }
                },
        ) {
            @Suppress("UNUSED_EXPRESSION") cache.version // redibuja al llegar miniaturas
            val w = (this.size.width - 2 * insetPx).coerceAtLeast(1f)
            val h = this.size.height
            fun xOf(t: Float) = insetPx + (t - viewStart) / viewSpan * w

            // Fondo de toda la tira (incluidos los márgenes de los tiradores)
            drawRoundRect(placeholder, Offset.Zero, this.size, CornerRadius(6.dp.toPx()))
            // Fotogramas (recorte centrado para rellenar cada casilla)
            val tw = w / tiles
            for (i in 0 until tiles) {
                val left = insetPx + i * tw
                val img = cache.map[tileKeys.getOrNull(i) ?: 0L] ?: cache.nearest(tileKeys.getOrNull(i) ?: 0L)
                if (img == null) {
                    drawRect(placeholder, Offset(left, 0f), Size(tw + 0.5f, h))
                    continue
                }
                val tileAspect = tw / h
                val imgAspect = img.width.toFloat() / img.height
                val (sw, sh) = if (imgAspect > tileAspect) (img.height * tileAspect) to img.height.toFloat()
                else img.width.toFloat() to (img.width / tileAspect)
                drawImage(
                    img,
                    srcOffset = IntOffset(((img.width - sw) / 2).roundToInt(), ((img.height - sh) / 2).roundToInt()),
                    srcSize = IntSize(sw.roundToInt().coerceAtLeast(1), sh.roundToInt().coerceAtLeast(1)),
                    dstOffset = IntOffset(left.roundToInt(), 0),
                    dstSize = IntSize(ceil(tw).toInt(), h.roundToInt()),
                )
            }

            // Zonas descartadas
            val xs = xOf(start.toFloat())
            val xe = xOf(end.toFloat())
            val shade = Color.Black.copy(alpha = 0.6f)
            if (xs > insetPx) drawRect(shade, Offset(insetPx, 0f), Size(xs - insetPx, h))
            if (xe < insetPx + w) drawRect(shade, Offset(xe, 0f), Size(insetPx + w - xe, h))

            // Marco del tramo y tiradores
            val bw = 3.dp.toPx()
            val handleW = 14.dp.toPx()
            drawRect(primary, Offset(xs, 0f), Size(xe - xs, h), style = Stroke(bw))
            listOf(xs - handleW to TrimTarget.START, xe to TrimTarget.END).forEach { (x, t) ->
                val active = dragging == t || dragging == TrimTarget.RANGE
                drawRoundRect(
                    if (active) primary else primary.copy(alpha = 0.9f),
                    Offset(x, 0f), Size(handleW, h), CornerRadius(4.dp.toPx()),
                )
                drawLine(
                    onPrimary, Offset(x + handleW / 2, h * 0.32f), Offset(x + handleW / 2, h * 0.68f),
                    2.dp.toPx(),
                )
            }

            // Cabezal de reproducción
            if (playheadMs != null && dragging == null) {
                val xp = xOf(playheadMs.toFloat())
                if (xp in xs..xe) drawLine(Color.White, Offset(xp, 0f), Offset(xp, h), 2.dp.toPx())
            }
        }
    }
}
