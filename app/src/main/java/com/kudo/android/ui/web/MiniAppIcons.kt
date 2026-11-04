package com.kudo.android.ui.web

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** WeChat-style capsule shapes; Icon applies the current theme tint. */
internal object MiniAppIcons {
    val More = vector("MiniAppMore", "M512 384a128 128 0 1 1 0 256 128 128 0 0 1 0-256zM149.333333 426.666667a85.333333 85.333333 0 1 1 0 170.666666 85.333333 85.333333 0 0 1 0-170.666666z m725.333334 0a85.333333 85.333333 0 1 1 0 170.666666 85.333333 85.333333 0 0 1 0-170.666666z")
    val Close = vector("MiniAppClose",
        "M512 1024C229.216 1024 0 794.784 0 512S229.216 0 512 0s512 229.216 512 512-229.216 512-512 512z m0-128c212.064 0 384-171.936 384-384S724.064 128 512 128 128 299.936 128 512s171.936 384 384 384z",
        "M512 512m-160 0a160 160 0 1 0 320 0 160 160 0 1 0-320 0Z")

    private fun vector(name: String, vararg paths: String) = ImageVector.Builder(name, 24.dp, 24.dp, 1024f, 1024f).apply {
        paths.forEach { addPath(PathParser().parsePathString(it).toNodes(), fill = SolidColor(Color.Black)) }
    }.build()
}
