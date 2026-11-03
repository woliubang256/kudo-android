package com.kudo.android.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** User-supplied app square shape; Icon supplies the menu's theme tint. */
internal val AppSquareIcon: ImageVector by lazy {
    ImageVector.Builder("AppSquare", 24.dp, 24.dp, 1024f, 1024f).apply {
        addPath(PathParser().parsePathString(
            "M512 0a512 512 0 1 0 0 1024A512 512 0 0 0 512 0z m302.848 468.48a187.264 187.264 0 0 1-105.408 78.656c-1.664 0-1.664 0-3.328 1.728-6.72 1.6-13.44 3.328-21.76 3.328-31.808 0-48.512-21.76-40.192-46.848 6.72-18.432 25.088-35.2 46.848-41.856 35.2-13.44 58.624-41.856 58.624-75.328 0-45.12-43.52-81.92-97.088-81.92-53.568 0-97.088 36.8-97.088 81.92v249.344c0 60.16-35.072 112.064-86.912 142.208a190.912 190.912 0 0 1-97.088 25.088c-102.016 0-184.064-75.264-184.064-167.296 0-30.08 8.32-56.896 23.424-81.984A187.264 187.264 0 0 1 316.16 476.8c8.384-1.6 15.04-3.264 23.424-3.264 31.808 0 48.512 21.76 40.192 46.848-6.72 18.368-23.424 33.408-43.52 41.792a12.672 12.672 0 0 0-6.656 3.328c-31.872 13.44-53.632 40.256-53.632 73.664 0 45.184 43.52 81.92 97.088 81.92 53.568 0 97.024-36.736 97.024-81.92V389.888c0-60.288 35.2-112.128 87.04-142.208a190.272 190.272 0 0 1 97.024-25.152c102.08 0 184.064 75.328 184.064 167.296a148.672 148.672 0 0 1-23.424 78.72z"
        ).toNodes(), fill = SolidColor(Color.Black))
    }.build()
}
