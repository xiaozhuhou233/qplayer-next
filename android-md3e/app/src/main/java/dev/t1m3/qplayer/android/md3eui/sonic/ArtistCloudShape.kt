package dev.t1m3.qplayer.android.md3eui.sonic

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Wide, soft cloud silhouette. A page-owned seed keeps its outline fixed while images load/scroll. */
internal class ArtistCloudShape(seed: Int) : Shape {
    private val random = Random(seed)
    private val lobes = random.nextInt(8, 12)
    private val phase = random.nextFloat() * 2.0 * PI
    private val depth = .11f + random.nextFloat() * .035f
    private val samples = List(120) { i ->
        val angle = i * 2.0 * PI / 120
        val radius = 1.0 + depth * sin(lobes * angle + phase) + .025 * sin(3 * angle + phase)
        Offset((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())
    }
    private val left = samples.minOf { it.x }
    private val top = samples.minOf { it.y }
    private val width = samples.maxOf { it.x } - left
    private val height = samples.maxOf { it.y } - top

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        // Normalize into the measured bounds; leave room for spline extrema so nothing clips.
        val points = samples.map {
            Offset((.015f + .97f * (it.x - left) / width) * size.width,
                (.015f + .97f * (it.y - top) / height) * size.height)
        }
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in points.indices) {
            val p0 = points[(i + points.size - 1) % points.size]
            val p1 = points[i]
            val p2 = points[(i + 1) % points.size]
            val p3 = points[(i + 2) % points.size]
            val c1 = p1 + (p2 - p0) / 6f
            val c2 = p2 - (p3 - p1) / 6f
            path.cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
        }
        path.close()
        return androidx.compose.ui.graphics.Outline.Generic(path)
    }
}
