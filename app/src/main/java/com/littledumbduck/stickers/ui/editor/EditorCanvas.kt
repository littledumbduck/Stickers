package com.littledumbduck.stickers.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.littledumbduck.stickers.ui.common.drawCheckerboard
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** Transformación entre coordenadas de la imagen y de la pantalla (imagen centrada y ajustada). */
private data class Fit(val scale: Float, val ox: Float, val oy: Float) {
    fun toImage(p: Offset) = Offset((p.x - ox) / scale, (p.y - oy) / scale)
    fun toScreen(p: Offset) = Offset(p.x * scale + ox, p.y * scale + oy)
}

private fun fit(container: IntSize, iw: Int, ih: Int, pad: Float): Fit {
    val aw = (container.width - 2 * pad).coerceAtLeast(1f)
    val ah = (container.height - 2 * pad).coerceAtLeast(1f)
    val s = min(aw / iw, ah / ih)
    return Fit(s, (container.width - iw * s) / 2f, (container.height - ih * s) / 2f)
}

private enum class CropHandle { TL, TR, BL, BR, MOVE }

/**
 * Lienzo del editor. Muestra la imagen sobre un fondo de cuadros y gestiona los gestos de la
 * herramienta activa:
 *  - CROP: arrastrar las esquinas o mover el rectángulo.
 *  - LASSO: trazar con el dedo el contorno a conservar.
 *  - ERASE: borrar con el dedo.
 */
@Composable
fun EditorCanvas(
    image: ImageBitmap,
    tool: EditorTool,
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

    val gestures = if (!interactive) Modifier else Modifier.pointerInput(tool, image) {
        val pad = 20.dp.toPx()
        val handleTol = 36.dp.toPx()
        val minCrop = 24f
        when (tool) {
            EditorTool.CROP -> {
                var handle: CropHandle? = null
                detectDragGestures(
                    onDragStart = { pos ->
                        val f = fit(size, iw, ih, pad)
                        val r = cropRect.value
                        val corners = listOf(
                            CropHandle.TL to r.topLeft, CropHandle.TR to r.topRight,
                            CropHandle.BL to r.bottomLeft, CropHandle.BR to r.bottomRight,
                        )
                        handle = corners
                            .map { (h, c) -> h to (f.toScreen(c) - pos).getDistance() }
                            .filter { it.second < handleTol }
                            .minByOrNull { it.second }?.first
                            ?: if (r.contains(f.toImage(pos))) CropHandle.MOVE else null
                    },
                    onDragEnd = { handle = null },
                    onDragCancel = { handle = null },
                    onDrag = { change, drag ->
                        val h = handle ?: return@detectDragGestures
                        change.consume()
                        val f = fit(size, iw, ih, pad)
                        val d = drag / f.scale
                        val r = cropRect.value
                        cropRect.value = when (h) {
                            CropHandle.MOVE -> {
                                val dx = d.x.coerceIn(-r.left, iw - r.right)
                                val dy = d.y.coerceIn(-r.top, ih - r.bottom)
                                r.translate(dx, dy)
                            }
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
                    },
                )
            }
            EditorTool.LASSO -> detectDragGestures(
                onDragStart = { pos ->
                    val f = fit(size, iw, ih, pad)
                    lasso.clear()
                    lasso.add(clampTo(f.toImage(pos), iw, ih))
                },
                onDrag = { change, _ ->
                    change.consume()
                    val f = fit(size, iw, ih, pad)
                    val p = clampTo(f.toImage(change.position), iw, ih)
                    // Evita guardar miles de puntos casi iguales
                    val last = lasso.lastOrNull()
                    if (last == null || hypot(p.x - last.x, p.y - last.y) * f.scale > 2f) lasso.add(p)
                },
            )
            EditorTool.ERASE -> detectDragGestures(
                onDragStart = { pos ->
                    val f = fit(size, iw, ih, pad)
                    eraseStroke.clear()
                    eraseStroke.add(f.toImage(pos))
                },
                onDrag = { change, _ ->
                    change.consume()
                    val f = fit(size, iw, ih, pad)
                    eraseStroke.add(f.toImage(change.position))
                },
                onDragEnd = {
                    val f = fit(size, iw, ih, pad)
                    if (eraseStroke.isNotEmpty()) currentOnErase(eraseStroke.toList(), currentEraserSize / f.scale)
                    eraseStroke.clear()
                },
                onDragCancel = { eraseStroke.clear() },
            )
            else -> Unit
        }
    }

    Canvas(modifier.then(gestures)) {
        val pad = 20.dp.toPx()
        val f = fit(IntSize(size.width.roundToInt(), size.height.roundToInt()), iw, ih, pad)
        val dstTopLeft = Offset(f.ox, f.oy)
        val dstSize = Size(iw * f.scale, ih * f.scale)

        drawCheckerboard(dstTopLeft, dstSize)
        drawImage(
            image = image,
            dstOffset = IntOffset(f.ox.roundToInt(), f.oy.roundToInt()),
            dstSize = IntSize(dstSize.width.roundToInt().coerceAtLeast(1), dstSize.height.roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.Medium,
        )

        if (!interactive) return@Canvas
        when (tool) {
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
    listOf(tl to Offset(1f, 1f), Offset(br.x, tl.y) to Offset(-1f, 1f),
        Offset(tl.x, br.y) to Offset(1f, -1f), br to Offset(-1f, -1f)).forEach { (c, dir) ->
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
