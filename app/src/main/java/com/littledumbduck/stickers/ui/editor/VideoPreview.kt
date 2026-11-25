package com.littledumbduck.stickers.ui.editor

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Reproductor del vídeo completo (con el [MediaPlayer] del sistema) para elegir el tramo.
 * Informa de la posición en cada fotograma de pantalla y atiende las peticiones de salto.
 */
@Composable
fun VideoPreview(
    uri: Uri,
    aspect: Float,
    playing: Boolean,
    muted: Boolean,
    seek: SeekRequest?,
    /** Posición en la que empezar cuando el vídeo está listo. */
    initialPositionMs: Long,
    onPosition: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(uri) { MediaPlayer() }
    var prepared by remember(uri) { mutableStateOf(false) }
    var failed by remember(uri) { mutableStateOf(false) }
    val currentOnPosition by rememberUpdatedState(onPosition)
    val startAt by rememberUpdatedState(initialPositionMs)

    DisposableEffect(player) {
        runCatching {
            player.setDataSource(context, uri)
            player.isLooping = true
            player.setOnPreparedListener { mp ->
                if (startAt > 0) runCatching { mp.seekTo(startAt, MediaPlayer.SEEK_CLOSEST) }
                prepared = true
            }
            player.setOnErrorListener { _, _, _ -> failed = true; true }
            player.prepareAsync()
        }.onFailure { failed = true }
        onDispose {
            prepared = false
            runCatching { player.release() }
        }
    }

    LaunchedEffect(prepared, muted) {
        if (prepared) runCatching { player.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f) }
    }
    LaunchedEffect(prepared, playing) {
        if (!prepared) return@LaunchedEffect
        runCatching { if (playing) player.start() else if (player.isPlaying) player.pause() }
    }
    LaunchedEffect(prepared, seek) {
        val s = seek ?: return@LaunchedEffect
        if (!prepared) return@LaunchedEffect
        val mode = if (s.precise) MediaPlayer.SEEK_CLOSEST else MediaPlayer.SEEK_CLOSEST_SYNC
        runCatching { player.seekTo(s.ms, mode) }
    }
    LaunchedEffect(prepared) {
        if (!prepared) return@LaunchedEffect
        while (true) {
            withFrameMillis { }
            val pos = runCatching { if (player.isPlaying) player.currentPosition.toLong() else null }.getOrNull()
            if (pos != null) currentOnPosition(pos)
        }
    }

    Box(modifier, contentAlignment = Alignment.Center) {
        if (!failed) {
            AndroidView(
                factory = { ctx ->
                    TextureView(ctx).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            private var surface: Surface? = null
                            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                                surface = Surface(st).also { s -> runCatching { player.setSurface(s) } }
                            }
                            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                                runCatching { player.setSurface(null) }
                                surface?.release()
                                surface = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                        }
                    }
                },
                modifier = Modifier.aspectRatio(aspect.coerceIn(0.2f, 5f)),
            )
        }
    }
}
