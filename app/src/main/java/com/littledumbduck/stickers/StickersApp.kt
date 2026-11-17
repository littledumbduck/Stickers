package com.littledumbduck.stickers

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder

class StickersApp : Application(), ImageLoaderFactory {
    /** Coil con soporte de WebP animado / GIF para que los stickers animados se muevan en las listas. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(ImageDecoderDecoder.Factory())
            else add(GifDecoder.Factory())
        }
        .build()
}
