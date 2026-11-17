package com.littledumbduck.stickers.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.littledumbduck.stickers.ui.common.drawCheckerboard
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Zoom y desplazamiento de la vista del editor. */
@Stable
class ViewTransform {
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
    val isZoomed get() = zoom > 1.01f

    fun reset() {
        zoom = 1f
        pan = Offset.Zero
    }

    /** Aplica un pellizco de factor [factor] alrededor de [centroid] y un desplazamiento [delta]. */
    fun onPinch(centroid: Offset, factor: Float, delta: Offset) {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val f = newZoom / zoom
        zoom = newZoom
        pan = if (newZoom <= MIN_ZOOM + 0.01f) Offset.Zero else (pan - centroid) * f + centroid + delta
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 10f
    }
}

/** Transformación entre coordenadas de la imagen y de la pantalla. */
private data class Fit(val scale: Float, val ox: Float, val oy: Float) {
    fun toImage(p: Offset) = Offset((p.x - ox) / scale, (p.y - oy) / scale)
    fun toScreen(p: Offset) = Offset(p.x * scale + ox, p.y * scale + oy)
}

/** Imagen centrada y ajustada al contenedor, y luego ampliada/desplazada según [view]. */
private fun fit(container: IntSize, iw: Int, ih: Int, pad: Float, zoom: Float, pan: Offset): Fit {
    val aw = (container.width - 2 * pad).coerceAtLeast(1f)
    val ah = (container.height - 2 * pad).coerceAtLeast(1f)
    val s = min(aw / iw, ah / ih)
    val ox = (container.width - iw * s) / 2f
    val oy = (container.height - ih * s) / 2f
    return Fit(s * zoom, ox * zoom + pan.x, oy * zoom + pan.y)
}

private enum class CropHandle { TL, TR, BL, BR, MOVE }

/**
 * Lienzo del editor. Muestra la imagen sobre un fondo de cuadros y gestiona:
 *  - Un dedo: la herramienta activa (CROP: esquinas o mover; LASSO: trazar; ERASE: borrar).
 *  - Dos dedos: zoom (pellizcar) y desplazamiento. Si se apoya el segundo dedo a mitad de un
 *    trazo, el trazo se cancela.
 */
@Composable
fun EditorCanvas(
    image: ImageBitmap,
    tool: EditorTool,
    view: ViewTransform,
    cropRect: MutableState<Rect>,
    lasso: SnapshotStateList<Offset>,
    eraserSizePx: Float,
    onEraseStroke: (points: List<Offset>, widthInImage: Float) -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    val iw = image.width
    val ih = image.height
    val eraseStroke = remember { mutableStateListOf<Offset>() }
    val currentEraserSize by rememberUpdatedState(eraserSizePx)
    val currentOnErase by rememberUpdatedState(onEraseStroke)
    val activeTool = if (interactive) tool else null
    // El bloque de gestos no se reinicia en cada fotograma de una animación, así que lee siempre
    // los objetos de estado actuales.
    val cropState by rememberUpdatedState(cropRect)
    val lassoState by rememberUpdatedState(lasso)

    val gestures = Modifier.pointerInput(activeTool, iw, ih) {
        val pad = 20.dp.toPx()
        val handleTol = 36.dp.toPx()
        val minCrop = 24f
        fun currentFit() = fit(size, iw, ih, pad, view.zoom, view.pan)

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var multiTouch = false
            val lassoBackup = lassoState.toList()
            val cropBackup = cropState.value
            var handle: CropHandle? = null

            // ---- inicio con un dedo ----
            run {
                val f = currentFit()
                val p = f.toImage(down.position)
                when (activeTool) {
                    EditorTool.CROP -> {
                        val r = cropState.value
                        handle = listOf(
                            CropHandle.TL to r.topLeft, CropHandle.TR to r.topRight,
                            CropHandle.BL to r.bottomLeft, CropHandle.BR to r.bottomRight,
                        ).map { (h, c) -> h to (f.toScreen(c) - down.position).getDistance() }
                            .filter { it.second < handleTol }
                            .minByOrNull { it.second }?.first
                            ?: if (r.contains(p)) CropHandle.MOVE else null
                    }
                    EditorTool.LASSO -> {
                        lassoState.clear()
                        lassoState.add(clampTo(p, iw, ih))
                    }
                    EditorTool.ERASE -> {
                        eraseStroke.clear()
                        eraseStroke.add(p)
                    }
                    else -> Unit
                }
            }

            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) {
                    // ---- fin ----
                    if (!multiTouch && activeTool == EditorTool.ERASE && eraseStroke.isNotEmpty()) {
                        currentOnErase(eraseStroke.toList(), currentEraserSize / currentFit().scale)
                    }
                    eraseStroke.clear()
                    break
                }
                if (pressed.size >= 2) {
                    if (!multiTouch) {
                        // Segundo dedo: cancelar lo que se estuviera dibujando
                        multiTouch = true
                        eraseStroke.clear()
                        if (activeTool == EditorTool.LASSO) {
                            lassoState.clear()
                            lassoState.addAll(lassoBackup)
                        }
                        if (activeTool == EditorTool.CROP) cropState.value = cropBackup
                    }
                    view.onPinch(event.calculateCentroid(useCurrent = true), event.calculateZoom(), event.calculatePan())
                    event.changes.forEach { it.consume() }
                    continue
                }
                if (multiTouch) continue // queda un dedo tras el pellizco: se ignora hasta soltar

                val change = pressed[0]
                val f = currentFit()
                when (activeTool) {
                    EditorTool.CROP -> handle?.let { h ->
                        val d = change.positionChange() / f.scale
                        cropState.value = moveCrop(cropState.value, h, d, iw, ih, minCrop)
                    }
                    EditorTool.LASSO -> {
                        val p = clampTo(f.toImage(change.position), iw, ih)
                        val last = lassoState.lastOrNull()
                        // Evita guardar miles de puntos casi iguales
                        if (last == null || hypot(p.x - last.x, p.y - last.y) * f.scale > 2f) lassoState.add(p)
                    }
                    EditorTool.ERASE -> eraseStroke.add(f.toImage(change.position))
                    else -> Unit
                }
                change.consume()
            }
        }
    }

    Canvas(modifier.clipToBounds().then(gestures)) {
        val pad = 20.dp.toPx()
        val f = fit(IntSize(size.width.roundToInt(), size.height.roundToInt()), iw, ih, pad, view.zoom, view.pan)
        val dstTopLeft = Offset(f.ox, f.oy)
        val dstSize = Size(iw * f.scale, ih * f.scale)

        // Cuadros solo en la parte visible (con zoom la imagen puede ser enorme)
        val visLeft = max(0f, dstTopLeft.x)
        val visTop = max(0f, dstTopLeft.y)
        val visRight = min(size.width, dstTopLeft.x + dstSize.width)
        val visBottom = min(size.height, dstTopLeft.y + dstSize.height)
        if (visRight > visLeft && visBottom > visTop) {
            drawCheckerboard(Offset(visLeft, visTop), Size(visRight - visLeft, visBottom - visTop))
        }
        drawImage(
            image = image,
            dstOffset = IntOffset(f.ox.roundToInt(), f.oy.roundToInt()),
            dstSize = IntSize(dstSize.width.roundToInt().coerceAtLeast(1), dstSize.height.roundToInt().coerceAtLeast(1)),
            filterQuality = if (f.scale > 2f) FilterQuality.None else FilterQuality.Medium,
        )

        when (activeTool) {
            EditorTool.CROP -> drawCropOverlay(f, cropRect.value, dstTopLeft, dstSize)
            EditorTool.LASSO -> drawLasso(f, lasso)
            EditorTool.ERASE -> if (eraseStroke.isNotEmpty()) {
                val path = Path().apply {
                    val first = f.toScreen(eraseStroke.first())
                    moveTo(first.x, first.y)
                    eraseStroke.drop(1).forEach { val p = f.toScreen(it); lineTo(p.x, p.y) }
                    if (eraseStroke.size == 1) lineTo(first.x + 0.1f, first.y + 0.1f)
                }
                drawPath(
                    path, Color(0x99FF3B30),
                    style = Stroke(eraserSizePx, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            else -> Unit
        }
    }
}

private fun moveCrop(r: Rect, h: CropHandle, d: Offset, iw: Int, ih: Int, minCrop: Float): Rect = when (h) {
    CropHandle.MOVE -> r.translate(d.x.coerceIn(-r.left, iw - r.right), d.y.coerceIn(-r.top, ih - r.bottom))
    CropHandle.TL -> Rect(
        (r.left + d.x).coerceIn(0f, r.right - minCrop),
        (r.top + d.y).coerceIn(0f, r.bottom - minCrop), r.right, r.bottom,
    )
    CropHandle.TR -> Rect(
        r.left, (r.top + d.y).coerceIn(0f, r.bottom - minCrop),
        (r.right + d.x).coerceIn(r.left + minCrop, iw.toFloat()), r.bottom,
    )
    CropHandle.BL -> Rect(
        (r.left + d.x).coerceIn(0f, r.right - minCrop), r.top,
        r.right, (r.bottom + d.y).coerceIn(r.top + minCrop, ih.toFloat()),
    )
    CropHandle.BR -> Rect(
        r.left, r.top,
        (r.right + d.x).coerceIn(r.left + minCrop, iw.toFloat()),
        (r.bottom + d.y).coerceIn(r.top + minCrop, ih.toFloat()),
    )
}

private fun clampTo(p: Offset, w: Int, h: Int) = Offset(p.x.coerceIn(0f, w.toFloat()), p.y.coerceIn(0f, h.toFloat()))

private fun DrawScope.drawCropOverlay(f: Fit, r: Rect, imgTopLeft: Offset, imgSize: Size) {
    val tl = f.toScreen(r.topLeft)
    val br = f.toScreen(r.bottomRight)
    val shade = Color.Black.copy(alpha = 0.55f)
    val imgRight = imgTopLeft.x + imgSize.width
    val imgBottom = imgTopLeft.y + imgSize.height
    // Oscurece lo que queda fuera
    drawRect(shade, imgTopLeft, Size(imgSize.width, tl.y - imgTopLeft.y))
    drawRect(shade, Offset(imgTopLeft.x, br.y), Size(imgSize.width, imgBottom - br.y))
    drawRect(shade, Offset(imgTopLeft.x, tl.y), Size(tl.x - imgTopLeft.x, br.y - tl.y))
    drawRect(shade, Offset(br.x, tl.y), Size(imgRight - br.x, br.y - tl.y))

    val w = br.x - tl.x
    val h = br.y - tl.y
    drawRect(Color.White, tl, Size(w, h), style = Stroke(2.dp.toPx()))
    // Cuadrícula de tercios
    val grid = Color.White.copy(alpha = 0.5f)
    for (i in 1..2) {
        drawLine(grid, Offset(tl.x + w * i / 3, tl.y), Offset(tl.x + w * i / 3, br.y), 1.dp.toPx())
        drawLine(grid, Offset(tl.x, tl.y + h * i / 3), Offset(br.x, tl.y + h * i / 3), 1.dp.toPx())
    }
    // Asas
    val len = 18.dp.toPx()
    val sw = 4.dp.toPx()
    listOf(
        tl to Offset(1f, 1f), Offset(br.x, tl.y) to Offset(-1f, 1f),
        Offset(tl.x, br.y) to Offset(1f, -1f), br to Offset(-1f, -1f),
    ).forEach { (c, dir) ->
        drawLine(Color.White, c, Offset(c.x + len * dir.x, c.y), sw, StrokeCap.Round)
        drawLine(Color.White, c, Offset(c.x, c.y + len * dir.y), sw, StrokeCap.Round)
    }
}

private fun DrawScope.drawLasso(f: Fit, points: List<Offset>) {
    if (points.size < 2) return
    val path = Path().apply {
        val first = f.toScreen(points.first())
        moveTo(first.x, first.y)
        points.drop(1).forEach { val p = f.toScreen(it); lineTo(p.x, p.y) }
        close()
    }
    drawPath(path, Color(0x3300E676))
    drawPath(path, Color.White, style = Stroke(4.dp.toPx(), join = StrokeJoin.Round))
    drawPath(
        path, Color(0xFF00876A),
        style = Stroke(
            2.dp.toPx(), join = StrokeJoin.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
        ),
    )
}
