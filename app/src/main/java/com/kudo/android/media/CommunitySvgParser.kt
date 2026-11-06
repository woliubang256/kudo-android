package com.kudo.android.media

import coil3.Image
import coil3.annotation.ExperimentalCoilApi
import coil3.svg.Svg
import coil3.svg.SvgImage
import com.caverock.androidsvg.SvgTextBoundsCompat
import okio.BufferedSource

@OptIn(ExperimentalCoilApi::class)
internal object CommunitySvgParser : Svg.Parser {
    override fun parse(source: BufferedSource): Svg {
        val parsed = Svg.Parser.DEFAULT.parse(source)
        return object : Svg by parsed {
            private var boundsPrepared = false

            override fun asImage(width: Int, height: Int): Image {
                val image = parsed.asImage(width, height)
                if (image is SvgImage && !boundsPrepared) {
                    SvgTextBoundsCompat.prepare(image.svg, width, height, image.renderOptions)
                    boundsPrepared = true
                }
                return image
            }
        }
    }
}
