package com.ngerchev.energy.ui

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Small built-in icon set (Material-style 24dp paths), so no large icon library is needed. */
object Icons {
    private fun vec(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
        }.build()

    private val price = vec("price", "M5,9.2h3V19H5V9.2zM10.6,5h2.8v14h-2.8V5zM16.2,13H19v6h-2.8V13z")
    private val grid = vec("grid", "M7,2v11h3v9l7,-12h-4l4,-8z")
    private val battery = vec(
        "battery",
        "M15.67,4H14V2h-4v2H8.33C7.6,4 7,4.6 7,5.33v15.33C7,21.4 7.6,22 8.33,22h7.33c0.74,0 1.34,-0.6 1.34,-1.33V5.33C17,4.6 16.4,4 15.67,4zM11,20v-5.5H9L13,7v5.5h2L11,20z",
    )
    private val info = vec(
        "info",
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM13,17h-2v-6h2v6zM13,9h-2V7h2v2z",
    )
    private val refresh = vec(
        "refresh",
        "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z",
    )

    @Composable fun Price() = Icon(price, contentDescription = "Цени")
    @Composable fun Grid() = Icon(grid, contentDescription = "Мрежа")
    @Composable fun Battery() = Icon(battery, contentDescription = "BESS")
    @Composable fun Info() = Icon(info, contentDescription = "Инфо")
    @Composable fun Refresh() = Icon(refresh, contentDescription = "Обнови")
}
