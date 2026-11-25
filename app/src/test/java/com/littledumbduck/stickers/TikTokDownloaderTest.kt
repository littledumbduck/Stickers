package com.littledumbduck.stickers

import com.littledumbduck.stickers.data.TikTokDownloader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TikTokDownloaderTest {

    /** Página con la misma estructura que la de TikTok (datos inventados). */
    private fun page(detailKey: String = "webapp.video-detail", status: Int = 0, item: JSONObject? = sampleItem()) =
        """<html><head><script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">""" +
            JSONObject().put(
                "__DEFAULT_SCOPE__",
                JSONObject().put(
                    detailKey,
                    JSONObject().put("statusCode", status).apply {
                        if (item != null) put("itemInfo", JSONObject().put("itemStruct", item))
                    },
                ),
            ).toString() + "</script></head></html>"

    private fun sampleItem() = JSONObject()
        .put("id", "7000000000000000001")
        .put("desc", "prueba #sticker")
        .put("author", JSONObject().put("uniqueId", "alguien"))
        .put(
            "video", JSONObject()
                .put("duration", 7)
                .put("playAddr", "https://v16-webapp.tiktok.com/abc/play.mp4")
                .put("downloadAddr", "https://v16-webapp.tiktok.com/abc/download_wm.mp4")
                .put(
                    "bitrateInfo", JSONArray()
                        .put(variant("bytevc2", "https://v19.tiktokcdn.com/vvc.mp4"))
                        .put(variant("bytevc1", "https://v19.tiktokcdn.com/x/media-video-hvc1/"))
                        .put(variant("h264", "https://v19.tiktokcdn.com/h264.mp4"))
                        .put(variant("h264", "https://www.tiktok.com/aweme/broken"))
                )
        )

    private fun variant(codec: String, url: String) = JSONObject()
        .put("CodecType", codec)
        .put("PlayAddr", JSONObject().put("UrlList", JSONArray().put(url)))

    @Test
    fun prefersCleanUrlsAndKeepsWatermarkedAsLastResort() {
        val info = TikTokDownloader.parse(page(), "https://www.tiktok.com/@alguien/video/7000000000000000001")
        assertEquals("7000000000000000001", info.id)
        assertEquals("alguien", info.author)
        assertEquals(7, info.durationSec)
        assertEquals(
            listOf("https://v16-webapp.tiktok.com/abc/play.mp4", "https://v19.tiktokcdn.com/h264.mp4"),
            info.cleanUrls,
        )
        assertEquals(listOf("https://v16-webapp.tiktok.com/abc/download_wm.mp4"), info.watermarkedUrls)
    }

    @Test
    fun understandsMobileLayoutAndErrors() {
        val info = TikTokDownloader.parse(page("webapp.reflow.video.detail"), "https://www.tiktok.com/@a/video/1")
        assertFalse(info.cleanUrls.isEmpty())

        fun message(html: String) = runCatching { TikTokDownloader.parse(html, "u") }.exceptionOrNull()?.message
        assertEquals("El vídeo no existe o se ha eliminado", message(page(status = 10204, item = null)))
        assertEquals("El vídeo es privado", message(page(status = 10222, item = null)))
        assertTrue(message(page(item = sampleItem().put("imagePost", JSONObject())))!!.contains("fotos"))
        assertTrue(message("<html>verificación</html>")!!.contains("verificación"))
    }

    @Test
    fun findsLinksInSharedText() {
        assertEquals(
            "https://vm.tiktok.com/ZMabc123/",
            TikTokDownloader.findLink("Mira este vídeo https://vm.tiktok.com/ZMabc123/ #fyp"),
        )
        assertEquals(
            "https://www.tiktok.com/@user.name/video/7123456789012345678?is_from_webapp=1",
            TikTokDownloader.findLink("https://www.tiktok.com/@user.name/video/7123456789012345678?is_from_webapp=1."),
        )
        assertNull(TikTokDownloader.findLink("https://youtube.com/watch?v=x"))
    }

    /** Descarga real: solo con TIKTOK_LIVE_URL definido (no se ejecuta en una compilación normal). */
    @Test
    fun liveDownload() {
        val url = System.getProperty("tiktokLiveUrl")
        assumeTrue("Sin TIKTOK_LIVE_URL", !url.isNullOrBlank())
        val dir = File(System.getProperty("snapshotDir") ?: "build", "tiktok-live")
        val progress = mutableListOf<Float?>()
        val r = TikTokDownloader.download(url!!, dir) { progress += it }
        println("TIKTOK id=${r.info.id} autor=${r.info.author} marca=${r.watermarked} bytes=${r.file.length()} limpias=${r.info.cleanUrls.size} archivo=${r.file}")
        assertFalse("se descargó la versión con marca de agua", r.watermarked)
        assertTrue(r.file.length() > 50_000)
        assertTrue(progress.isNotEmpty())
    }
}
