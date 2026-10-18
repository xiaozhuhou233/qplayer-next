package dev.t1m3.qplayer.android.md3eui.sonic

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.cos
import kotlin.math.sin

/**
 * Custom organic scalloped cloud / flower contour shape matching the Artist Spotlight
 * hero visual in Image 9.png.
 */
class ScallopedCloudShape(private val lobes: Int = 8) : Shape {
  override fun createOutline(
    size: Size,
    layoutDirection: LayoutDirection,
    density: Density
  ): Outline {
    val path = Path()
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val baseRadius = (minOf(size.width, size.height) / 2f) * 0.72f
    val lobeRadius = (minOf(size.width, size.height) / 2f) * 0.32f

    // Center circular body
    path.addOval(
      Rect(
        centerX - baseRadius,
        centerY - baseRadius,
        centerX + baseRadius,
        centerY + baseRadius
      )
    )

    // Add surrounding petal/cloud lobes
    val angleStep = (2 * Math.PI) / lobes
    for (i in 0 until lobes) {
      val angle = i * angleStep
      val lobeCenterX = centerX + (baseRadius * 0.85f * cos(angle)).toFloat()
      val lobeCenterY = centerY + (baseRadius * 0.85f * sin(angle)).toFloat()
      val lobePath = Path().apply {
        addOval(
          Rect(
            lobeCenterX - lobeRadius,
            lobeCenterY - lobeRadius,
            lobeCenterX + lobeRadius,
            lobeCenterY + lobeRadius
          )
        )
      }
      path.op(path, lobePath, androidx.compose.ui.graphics.PathOperation.Union)
    }

    return androidx.compose.ui.graphics.Outline.Generic(path)
  }
}
