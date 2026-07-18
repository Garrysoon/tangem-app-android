@file:Suppress("all")

package com.tangem.core.ui.res.generated.icons

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Auto-generated from design tokens. Do not edit manually.
 */

private var _ic_loading_spinner_24: ImageVector? = null

val Icons.ic_loading_spinner_24: ImageVector
    get() {
        if (_ic_loading_spinner_24 != null) return _ic_loading_spinner_24!!
        _ic_loading_spinner_24 = ImageVector.Builder(
            name = "ic_loading_spinner_24",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                pathFillType = PathFillType.NonZero,
                pathData = addPathNodes("M12 2a10 10 0 0 1 10 10"),
            )
        }.build()
        return _ic_loading_spinner_24!!
    }

@Composable
@Preview(showBackground = true)
private fun IcLoadingSpinner24Preview() {
    Icon(
        imageVector = Icons.ic_loading_spinner_24,
        contentDescription = null,
    )
}
