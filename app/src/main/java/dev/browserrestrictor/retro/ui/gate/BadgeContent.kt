package dev.browserrestrictor.retro.ui.gate

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.browserrestrictor.retro.domain.GateMode
import dev.browserrestrictor.retro.domain.MINUTE_MS
import dev.browserrestrictor.retro.domain.StateSnapshot
import dev.browserrestrictor.retro.ui.components.formatCompactTime
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil

private val BadgeGrey = Color(0xFF171310)
private val BadgeContentColor = Color(0xFFF5F2EA)
private const val RESTING_ALPHA = 0.46f
private const val ACTIVE_ALPHA = 0.96f
private const val FADE_DELAY_MS = 3_000L
private val HORIZONTAL_SWIPE_THRESHOLD = 44.dp

@Composable
fun BadgeContent(
    snapshot: StateSnapshot,
    onDrag: (deltaY: Float) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var interactionGeneration by remember { mutableIntStateOf(0) }
    var interacting by remember { mutableStateOf(false) }

    fun markInteraction() {
        interacting = true
        interactionGeneration += 1
    }

    LaunchedEffect(interactionGeneration) {
        if (interactionGeneration == 0) return@LaunchedEffect
        delay(FADE_DELAY_MS)
        interacting = false
    }

    val opacity by animateFloatAsState(
        targetValue = if (interacting) ACTIVE_ALPHA else RESTING_ALPHA,
        animationSpec = tween(durationMillis = 500),
        label = "Browser badge opacity",
    )
    val shape = RoundedCornerShape(18.dp)
    val horizontalSwipeThresholdPx = with(LocalDensity.current) {
        HORIZONTAL_SWIPE_THRESHOLD.toPx()
    }
    val interactionModifier = Modifier
        .pointerInput(onDrag, expanded, horizontalSwipeThresholdPx) {
            var totalDragX = 0f
            var totalDragY = 0f
            var horizontalActionTaken = false
            detectDragGestures(
                onDragStart = {
                    totalDragX = 0f
                    totalDragY = 0f
                    horizontalActionTaken = false
                    markInteraction()
                },
                onDragEnd = { markInteraction() },
                onDragCancel = { markInteraction() },
                onDrag = { change, dragAmount ->
                    change.consume()
                    markInteraction()
                    totalDragX += dragAmount.x
                    totalDragY += dragAmount.y
                    onDrag(dragAmount.y)

                    if (
                        !horizontalActionTaken &&
                        abs(totalDragX) >= horizontalSwipeThresholdPx &&
                        abs(totalDragX) > abs(totalDragY)
                    ) {
                        when {
                            totalDragX < 0f && !expanded -> expanded = true
                            totalDragX > 0f && expanded -> expanded = false
                        }
                        horizontalActionTaken = true
                    }
                },
            )
        }
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) {
            markInteraction()
        }

    Row(
        modifier = Modifier
            .alpha(opacity)
            .shadow(6.dp, shape)
            .background(BadgeGrey, shape)
            .then(interactionModifier)
            .semantics {
                contentDescription = badgeDescription(snapshot, expanded)
            }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (expanded) {
            ExpandedBadge(snapshot)
        } else {
            CollapsedBadge(snapshot)
        }
    }
}

@Composable
private fun CollapsedBadge(snapshot: StateSnapshot) {
    val unlock = snapshot.activeUnlock
    if (unlock == null) {
        Text(
            text = nonSessionStatus(snapshot),
            style = MaterialTheme.typography.labelMedium,
            color = BadgeContentColor,
        )
        DragGrip()
        return
    }

    val remainingMs = (unlock.expiresAtWallMs - snapshot.wallNowMs).coerceAtLeast(0)
    val totalMs = (unlock.expiresAtWallMs - unlock.grantedAtWallMs).coerceAtLeast(1)
    val progress = (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    val minutes = ceil(remainingMs / MINUTE_MS.toDouble()).toLong()

    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(24.dp),
            color = BadgeContentColor,
            trackColor = BadgeContentColor.copy(alpha = 0.22f),
            strokeWidth = 2.5.dp,
        )
        Text(
            text = minutes.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = BadgeContentColor,
        )
    }
    DragGrip()
}

@Composable
private fun ExpandedBadge(snapshot: StateSnapshot) {
    val unlock = snapshot.activeUnlock
    if (unlock != null) {
        BadgeValue(
            label = "Unlock",
            value = "${formatCompactTime(unlock.expiresAtWallMs - snapshot.wallNowMs)} left",
        )
        BadgeDivider()
    } else {
        BadgeValue(label = "Status", value = nonSessionStatus(snapshot))
        BadgeDivider()
    }

    val effectiveRemainingMs = snapshot.budgetSnapshots
        .filter { it.enabled }
        .mapNotNull { it.remainingMs }
        .minOrNull()
    BadgeValue(
        label = "Available",
        value = effectiveRemainingMs?.let { "${formatCompactTime(it)} today" } ?: "No limit",
    )
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = "Collapse status badge",
        modifier = Modifier
            .size(24.dp)
            .background(BadgeContentColor.copy(alpha = 0.10f), CircleShape)
            .padding(3.dp),
        tint = BadgeContentColor,
    )
}

@Composable
private fun BadgeValue(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = BadgeContentColor,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = BadgeContentColor.copy(alpha = 0.86f),
        )
    }
}

@Composable
private fun BadgeDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(34.dp)
            .background(BadgeContentColor.copy(alpha = 0.28f)),
    )
}

@Composable
private fun DragGrip() {
    Box(
        Modifier
            .width(2.dp)
            .height(16.dp)
            .background(BadgeContentColor.copy(alpha = 0.34f), RoundedCornerShape(2.dp)),
    )
}

private fun badgeDescription(snapshot: StateSnapshot, expanded: Boolean): String {
    val unlock = snapshot.activeUnlock?.let {
        "Unlock ${formatCompactTime(it.expiresAtWallMs - snapshot.wallNowMs)} left"
    } ?: nonSessionStatus(snapshot)
    if (!expanded) return "$unlock. Swipe left to expand. Drag vertically to move."
    val available = snapshot.budgetSnapshots
        .filter { it.enabled }
        .mapNotNull { it.remainingMs }
        .minOrNull()
        ?.let { "${formatCompactTime(it)} available today" }
        ?: "No active usage limit"
    return "$unlock. $available. Swipe right to collapse. Drag vertically to move."
}

private fun nonSessionStatus(snapshot: StateSnapshot): String = when (snapshot.canonicalMode) {
    GateMode.ACCESS_GRANTED_CURRENT_VISIT -> "This visit"
    GateMode.ACCESS_GRANTED_NO_DELAY -> "No delay"
    else -> "Browser available"
}
