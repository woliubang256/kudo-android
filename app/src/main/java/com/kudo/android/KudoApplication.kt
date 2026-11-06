package com.kudo.android

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.svg.SvgDecoder
import com.kudo.android.media.CommunitySvgParser
import com.kudo.android.data.AppContainer
import com.kudo.android.media.communityAnimations

class KudoApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // WebSettings in AppContainer must initialize on the application's main thread.
        container = AppContainer(this)
    }

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context).components {
        add(OkHttpNetworkFetcherFactory(callFactory = { container.imageHttpClient }))
        add(SvgDecoder.Factory(parser = CommunitySvgParser))
        communityAnimations()
    }.build()
}
