package com.littledumbduck.stickers.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/** Graba una nota de voz con el micrófono en AAC (.m4a), como mucho [SoundMixer.MAX_MS]. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    var file: File? = null
        private set
    private var startedAt = 0L

    val isRecording get() = recorder != null

    /** Ms grabados hasta ahora. */
    val elapsedMs get() = if (recorder != null) System.currentTimeMillis() - startedAt else 0L

    /** Nivel de entrada (0…1) desde la última consulta. */
    fun level(): Float = runCatching { (recorder?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f)

    fun start(onMaxReached: () -> Unit) {
        stop()
        val out = File(context.cacheDir, "recordings").apply { mkdirs() }
            .let { File(it, "voz_${System.currentTimeMillis()}.m4a") }
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(Pcm.RATE)
            r.setAudioEncodingBitRate(96_000)
            r.setMaxDuration(SoundMixer.MAX_MS.toInt())
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxReached()
            }
            r.setOutputFile(out.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw IllegalStateException("No se pudo usar el micrófono", e)
        }
        recorder = r
        file = out
        startedAt = System.currentTimeMillis()
    }

    /** Para la grabación y devuelve el archivo, o null si no se grabó nada válido. */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        return file?.takeIf { ok && it.length() > 0 }
    }

    fun release() {
        stop()
    }
}
