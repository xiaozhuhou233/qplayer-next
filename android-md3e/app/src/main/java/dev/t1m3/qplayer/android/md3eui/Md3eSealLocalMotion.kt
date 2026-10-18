package dev.t1m3.qplayer.android.md3eui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Drawing and animation recipes adapted from JunkFood02/Seal (GPL-3.0),
// revision 7677f61a20fda4210e84215fef9e9b51d25daa47. See assets/licenses/Seal-GPL-3.0.txt.
private val sealErrorIcon = ImageVector.Builder("SealError", 24.dp, 24.dp, 24f, 24f)
    .addPath(addPathNodes("M12,2a10,10 0,1 0,0,20a10,10 0,0 0,0,-20zM13,17h-2v-2h2zM13,13h-2V7h2z"),
        fill = SolidColor(Color.Black)).build()

// DownloadDialogContent changes whole stages on X while status text changes on Y.
@Composable
internal fun Md3eSealStage(
    stateKey: String,
    modifier: Modifier = Modifier,
    content: @Composable (String) -> Unit,
) {
    val claude = LocalClaudeDesign.current
    AnimatedContent(
        targetState = stateKey,
        modifier = modifier,
        transitionSpec = { if (claude) ClaudeOneTake.page(true) else Md3eSealSharedAxis.x({ it / 4 }, { -it / 4 }) },
        label = "seal_dialog_stage",
    ) { content(it) }
}

// SelectionGroup.kt: selected corners morph 20 -> 32 dp with MediumLow spring;
// both foreground and background animate using Compose's original color spec.
@Composable
internal fun Md3eSealSelectionItem(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val corner by animateDpAsState(
        if (selected) 32.dp else 20.dp,
        animationSpec = if (LocalClaudeDesign.current) ClaudeOneTake.snappy() else spring(stiffness = Spring.StiffnessMediumLow),
        label = "seal_selection_shape",
    )
    val container by animateColorAsState(
        when {
            !enabled -> scheme.onSurface.copy(alpha = 0.12f)
            selected -> scheme.primaryFixed
            else -> scheme.surfaceContainer
        }, label = "seal_selection_container",
    )
    val foreground by animateColorAsState(
        when {
            !enabled -> scheme.onSurface.copy(alpha = 0.38f)
            selected -> scheme.onPrimaryFixed
            else -> scheme.onSurface
        }, label = "seal_selection_content",
    )
    Surface(selected = selected, onClick = onClick, modifier = modifier,
        enabled = enabled, shape = RoundedCornerShape(corner), color = container,
        contentColor = foreground) {
        Row(Modifier.heightIn(min = 32.dp).widthIn(min = 56.dp)
            .padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { content() }
        }
    }
}

// Seal VideoCardV2 animates only the local state label when its state class changes.
@Composable
internal fun Md3eSealStatusText(
    stateKey: String,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelMedium,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    showIndicator: Boolean = false,
    progress: Float = -1f,
) {
    val claude = LocalClaudeDesign.current
    AnimatedContent(
        targetState = stateKey to text,
        modifier = modifier,
        transitionSpec = { if (claude) ClaudeOneTake.page(true) else Md3eSealSharedAxis.y({ it / 5 }, { -it / 5 }) },
        contentKey = { it.first },
        label = "seal_status",
    ) { (visibleState, visibleText) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showIndicator) {
                when (visibleState) {
                    "completed" -> Icon(Md3eIcons.CheckCircle, null, Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.primary)
                    "error" -> Icon(sealErrorIcon, null, Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.error)
                    "running", "loading" -> if (progress < 0f) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.5.dp)
                    } else {
                        CircularProgressIndicator(progress = { progress },
                            modifier = Modifier.size(14.dp), strokeWidth = 2.5.dp)
                    }
                }
                if (visibleState in setOf("completed", "error", "running", "loading"))
                    Spacer(Modifier.width(8.dp))
            }
            Text(visibleText, style = style.merge(letterSpacing = 0.sp), color = color)
        }
    }
}

// Seal VideoCardV2 keeps progress animation and its circle drawing inside the control.
@Composable
internal fun Md3eSealProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondaryFixed,
    background: Color = MaterialTheme.colorScheme.onSecondaryFixed.copy(alpha = 0.68f),
    content: @Composable BoxScope.() -> Unit = {},
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "seal_progress",
    )
    Box(modifier.size(64.dp).clip(CircleShape).drawBehind { drawCircle(background) }, contentAlignment = Alignment.Center) {
        if (progress < 0f) {
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                color = color,
                trackColor = Color.Transparent,
            )
        } else {
            CircularProgressIndicator(
                progress = { animated },
                modifier = Modifier.size(64.dp),
                color = color,
                trackColor = Color.Transparent,
                gapSize = 0.dp,
            )
        }
        content()
    }
}
