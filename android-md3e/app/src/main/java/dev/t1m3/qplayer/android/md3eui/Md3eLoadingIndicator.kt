package dev.t1m3.qplayer.android.md3eui

import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Share the theme decision across every expressive morphing indicator. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun Md3eLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    contained: Boolean = false,
) {
    if (LocalClaudeDesign.current) {
        SpinningDeformIcon(
            painter = painterResource(R.drawable.ic_claude_color),
            contentDescription = "加载中",
            modifier = modifier,
            size = 40.dp,
        )
    } else if (contained) {
        ContainedLoadingIndicator(modifier)
    } else {
        LoadingIndicator(modifier, color = color)
    }
}
