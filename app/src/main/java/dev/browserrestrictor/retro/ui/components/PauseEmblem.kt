package dev.browserrestrictor.retro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.browserrestrictor.retro.ui.theme.EditorialTheme

@Composable
fun PauseEmblem(modifier: Modifier = Modifier) {
    val accent = EditorialTheme.colors.accent
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
    ) {
        Box(Modifier.size(8.dp, 30.dp).background(accent, RoundedCornerShape(4.dp)))
        Box(Modifier.size(8.dp, 30.dp).background(accent, RoundedCornerShape(4.dp)))
    }
}
