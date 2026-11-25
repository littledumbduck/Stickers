package com.littledumbduck.stickers.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descarga vídeos de TikTok sin marca de agua.
 *
 * La página web de un vídeo incluye sus datos en el JSON `__UNIVERSAL_DATA_FOR_REHYDRATION__`.
 * Ahí aparecen dos direcciones: `playAddr` (la que usa el reproductor web, **sin** marca de agua)
 * y `downloadAddr` (la del botón "Descargar", **con** marca de agua). Se usa la primera y solo si
 * no hay ninguna se recurre a la segunda, avisando. Es el mismo método que usa yt-dlp.
 *
 * Todo se hace directamente contra tiktok.com, sin servicios de terceros.
 * Las llamadas son bloqueantes: usar desde Dispatchers.IO.
 */
object TikTokDownloader {

    class TikTokException(message: String) : IOException(message)

    data class VideoInfo(
        val id: String,
        val author: String?,
        val description: String?,
        val durationSec: Int?,
        /** Direcciones sin marca de agua, de mejor a peor. */
        val cleanUrls: List<String>,
        /** Direcciones con marca de agua (último recurso). */
        val watermarkedUrls: List<String>,
    )

    data class Result(val file: File, val info: VideoInfo, val watermarked: Boolean)

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

    private val LINK = Regex("""https?://(?:[\w-]+\.)*tiktokv?\.com/[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val VIDEO_ID = Regex("""/(?:video|v)/(\d{8,})""")
    private val DATA_SCRIPT = Regex(
        """<script[^>]+id="__UNIVERSAL_DATA_FOR_REHYDRATION__"[^>]*>(.*?)</script>""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** Primer enlace de TikTok dentro de un texto (p. ej. lo que comparte la app de TikTok). */
    fun findLink(text: String?): String? = text?.let { LINK.find(it)?.value?.trimEnd('.', ',', ')') }

    /**
     * Descarga el vídeo de [link] (enlace normal o corto tipo vm.tiktok.com) en [dir].
     * [onProgress] recibe 0..1, o null si no se conoce el tamaño.
     */
    fun download(link: String, dir: File, onProgress: (Float?) -> Unit = {}): Result {
        val cookies = LinkedHashMap<String, String>()
        val (pageUrl, html) = fetchPage(link, cookies)
        val info = parse(html, pageUrl)
        dir.mkdirs()
        // Solo se guardan las descargas recientes
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }

        val target = File(dir, "tiktok_${info.id}.mp4")
        var lastError: Exception? = null
        for ((url, watermarked) in info.cleanUrls.map { it to false } + info.watermarkedUrls.map { it to true }) {
            try {
                downloadVideo(url, pageUrl, cookies, target, onProgress)
                return Result(target, info, watermarked)
            } catch (e: Exception) {
                lastError = e
            }
        }
        target.delete()
        throw TikTokException("No se pudo descargar el vídeo (${lastError?.message ?: "sin enlaces"})")
    }

    /** Extrae los datos del vídeo del HTML de la página. */
    fun parse(html: String, pageUrl: String): VideoInfo {
        val json = DATA_SCRIPT.find(html)?.groupValues?.get(1)
            ?: throw TikTokException(
                "TikTok no ha devuelto los datos del vídeo (puede que haya pedido una verificación). " +
                    "Inténtalo de nuevo en un rato."
            )
        val scope = JSONObject(json).optJSONObject("__DEFAULT_SCOPE__")
            ?: throw TikTokException("Formato de página de TikTok desconocido")
        val detail = scope.optJSONObject("webapp.video-detail")
            ?: scope.optJSONObject("webapp.reflow.video.detail")
            ?: throw TikTokException("El enlace no es de un vídeo de TikTok")
        val status = detail.optInt("statusCode", 0)
        if (status != 0) throw TikTokException(statusMessage(status))
        val item = detail.optJSONObject("itemInfo")?.optJSONObject("itemStruct")
            ?: throw TikTokException("TikTok no ha devuelto el vídeo")
        if (item.has("imagePost")) {
            throw TikTokException("Es una publicación de fotos, no un vídeo. Guarda la foto y añádela desde la galería.")
        }
        val video = item.optJSONObject("video")
            ?: throw TikTokException("Este contenido no está disponible sin iniciar sesión en TikTok")

        val clean = LinkedHashSet<String>()
        // playAddr puede ser un texto o una lista de {src}
        when (val play = video.opt("playAddr")) {
            is String -> clean += play
            is JSONArray -> for (i in 0 until play.length()) play.optJSONObject(i)?.optString("src")?.let { clean += it }
        }
        // Variantes de calidad (también sin marca de agua); se descarta el códec propio de ByteDance (bytevc2)
        video.optJSONArray("bitrateInfo")?.let { arr ->
            val variants = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { !it.optString("CodecType").contains("bytevc2") }
                .sortedBy { if (it.optString("CodecType").contains("h264")) 0 else 1 } // H.264 se lee en cualquier móvil
            for (v in variants) {
                val list = v.optJSONObject("PlayAddr")?.optJSONArray("UrlList") ?: continue
                for (i in 0 until list.length()) clean += list.optString(i)
            }
        }
        val watermarked = listOfNotNull(
            video.optString("downloadAddr").takeIf { it.isNotEmpty() },
            video.optJSONObject("download")?.optString("url")?.takeIf { it.isNotEmpty() },
        )
        // Como yt-dlp: las de www.tiktok.com no funcionan y las "media-video-hvc1" pueden venir sin audio o dar 404
        fun usable(u: String) = u.startsWith("http") && !u.contains("://www.tiktok.com/") &&
            !URL(u).path.endsWith("/media-video-hvc1/")
        val id = item.optString("id").ifEmpty { VIDEO_ID.find(pageUrl)?.groupValues?.get(1) ?: "video" }
        return VideoInfo(
            id = id,
            author = item.optJSONObject("author")?.optString("uniqueId")?.ifEmpty { null },
            description = item.optString("desc").ifEmpty { null },
            durationSec = video.optInt("duration").takeIf { it > 0 },
            cleanUrls = clean.filter(::usable),
            watermarkedUrls = watermarked.filter(::usable),
        ).also {
            if (it.cleanUrls.isEmpty() && it.watermarkedUrls.isEmpty()) throw TikTokException("TikTok no ha dado ningún enlace al vídeo")
        }
    }

    private fun statusMessage(code: Int) = when (code) {
        10204 -> "El vídeo no existe o se ha eliminado"
        10216, 10222 -> "El vídeo es privado"
        10231 -> "El vídeo no está disponible en tu país"
        else -> "TikTok no permite ver este vídeo (código $code)"
    }

    // ---------------- HTTP ----------------

    /** Descarga la página siguiendo redirecciones (enlaces cortos) y guardando las cookies. */
    private fun fetchPage(link: String, cookies: MutableMap<String, String>): Pair<String, String> {
        var url = link
        repeat(8) {
            val c = open(url, cookies, referer = null, accept = "text/html,application/xhtml+xml")
            val code = c.responseCode
            storeCookies(c, cookies)
            if (code in 300..399) {
                val next = c.getHeaderField("Location") ?: throw TikTokException("Redirección sin destino")
                c.disconnect()
                url = URL(URL(url), next).toString()
                if (URL(url).path.startsWith("/login")) throw TikTokException("TikTok pide iniciar sesión para ver este vídeo")
                return@repeat
            }
            if (code != 200) {
                c.disconnect()
                throw TikTokException("TikTok respondió con un error ($code)")
            }
            val html = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            return url to html
        }
        throw TikTokException("Demasiadas redirecciones")
    }

    private fun downloadVideo(
        url: String,
        referer: String,
        cookies: MutableMap<String, String>,
        target: File,
        onProgress: (Float?) -> Unit,
    ) {
        var current = url
        repeat(5) {
            val c = open(current, cookies, referer, accept = "*/*")
            val code = c.responseCode
            storeCookies(c, cookies)
            if (code in 300..399) {
                current = URL(URL(current), c.getHeaderField("Location") ?: error("Redirección sin destino")).toString()
                c.disconnect()
                return@repeat
            }
            if (code != 200 && code != 206) {
                c.disconnect()
                throw IOException("HTTP $code")
            }
            val total = c.contentLengthLong
            val tmp = File(target.parentFile, target.name + ".part")
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null)
                    }
                }
            }
            c.disconnect()
            // Comprobación mínima de que es un MP4 (caja "ftyp")
            val head = tmp.inputStream().use { s -> ByteArray(12).also { s.read(it) } }
            if (String(head, 4, 4, Charsets.US_ASCII) != "ftyp") {
                tmp.delete()
                throw IOException("La respuesta no es un vídeo")
            }
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("No se pudo guardar el vídeo")
            return
        }
        throw IOException("Demasiadas redirecciones")
    }

    private fun open(url: String, cookies: Map<String, String>, referer: String?, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", accept)
            setRequestProperty("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
            referer?.let { setRequestProperty("Referer", it) }
            if (cookies.isNotEmpty()) setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }

    private fun storeCookies(c: HttpURLConnection, cookies: MutableMap<String, String>) {
        c.headerFields.entries
            .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }
            .forEach { header ->
                val pair = header.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                if (name.isNotEmpty()) cookies[name] = pair.substringAfter('=', "").trim()
            }
    }
}
