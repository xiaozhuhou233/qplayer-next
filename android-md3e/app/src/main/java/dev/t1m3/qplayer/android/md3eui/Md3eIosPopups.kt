@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.t1m3.qplayer.android.md3eui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.t1m3.qplayer.android.ui.IosAwareAlertDialog
import dev.t1m3.qplayer.android.ui.IosAwareButton
import dev.t1m3.qplayer.android.ui.IosAwareOutlinedButton
import dev.t1m3.qplayer.android.ui.IosAwareIconButton

@Composable
internal fun Md3eAdaptiveSheet(onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(), content: @Composable ColumnScope.() -> Unit) {
    if (!LocalIosDesign.current) {
        ModalBottomSheet(onDismissRequest, modifier, sheetState = sheetState, content = content)
    } else IosAwareAlertDialog(onDismissRequest, confirmButton = {}, modifier = modifier,
        scrollableContent = false, showActions = false,
        text = { Column(Modifier.fillMaxWidth(), content = content) })
}

@Composable
internal fun Md3eAdaptiveMenu(expanded: Boolean, onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    if (!LocalIosDesign.current) DropdownMenu(expanded, onDismissRequest, modifier, content = content)
    else if (expanded) IosAwareAlertDialog(onDismissRequest, confirmButton = {}, modifier = modifier,
        showActions = false, text = { Column(Modifier.fillMaxWidth(), content = content) })
}

@Composable
internal fun Md3eAdaptiveButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape, colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(), border: androidx.compose.foundation.BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit) {
    if (LocalIosDesign.current) IosAwareButton(onClick, modifier, enabled, content)
    else Button(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
}

@Composable
internal fun Md3eAdaptiveOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    if (LocalIosDesign.current) IosAwareOutlinedButton(onClick, modifier, enabled, content)
    else OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
}

@Composable
internal fun Md3eAdaptiveFilledIconButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, shape: Shape = IconButtonDefaults.filledShape,
    colors: IconButtonColors = IconButtonDefaults.filledIconButtonColors(),
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource? = null,
    content: @Composable () -> Unit) {
    if (LocalIosDesign.current) IosAwareIconButton(onClick, modifier, enabled, content)
    else FilledIconButton(onClick = onClick, modifier = modifier, enabled = enabled,
        shape = shape, colors = colors, interactionSource = interactionSource, content = content)
}
