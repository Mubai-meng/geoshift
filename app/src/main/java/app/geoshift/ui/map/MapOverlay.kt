package app.geoshift.ui.map

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.geoshift.ui.components.glass
import app.geoshift.ui.theme.palette

/** Small dismissible hint for when the map stays blank (blocked tile server, network, region). */
@Composable
internal fun MapTip(onSwitch: () -> Unit, onKey: () -> Unit, onClose: () -> Unit) {
    val link = MaterialTheme.typography.labelMedium
    Box(Modifier.padding(top = 8.dp).glass(CircleShape)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("地图不显示？", style = link, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "换个图层", style = link, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onSwitch).padding(horizontal = 4.dp, vertical = 6.dp),
            )
            Text("·", style = link, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "填写高德 Key", style = link, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onKey).padding(horizontal = 4.dp, vertical = 6.dp),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Close, "关闭提示，不再显示", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun SearchBar(
    query: String,
    searching: Boolean,
    placeholder: String = "搜索地点、地址或坐标",
    routeMode: Boolean = false,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
) {
    Box(Modifier.glass(CircleShape)) {
        TextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
            leadingIcon = {
                Icon(
                    Icons.Rounded.Search, null,
                    tint = if (routeMode) palette.blue else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                when {
                    searching -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    query.isNotEmpty() -> IconButton(onClick = onClear) { Icon(Icons.Rounded.Close, "清除") }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Pill-shaped floating group holding one or more map tools. */
@Composable
internal fun ToolGroup(content: @Composable () -> Unit) {
    Column(Modifier.glass(CircleShape).padding(vertical = 2.dp)) { content() }
}

@Composable
internal fun ToolDivider() =
    HorizontalDivider(Modifier.width(48.dp).padding(horizontal = 12.dp), thickness = 0.5.dp, color = palette.separator)

@Composable
internal fun MapTool(
    icon: ImageVector,
    description: String,
    active: Boolean = false,
    repeatOnHold: Boolean = false,
    iconModifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val currentOnClick by rememberUpdatedState(onClick)
    val interactionModifier = if (repeatOnHold) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                currentOnClick()
                val held = withTimeoutOrNull(280L) {
                    waitForUpOrCancellation()
                    false
                } ?: true
                if (held) {
                    while (true) {
                        val up = withTimeoutOrNull(130L) {
                            waitForUpOrCancellation()
                            true
                        } ?: false
                        if (up) break
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        currentOnClick()
                    }
                }
            }
        }
    } else {
        Modifier.clickable {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            currentOnClick()
        }
    }
    Box(
        Modifier.size(48.dp).padding(3.dp).clip(CircleShape)
            .then(interactionModifier)
            .background(if (active) palette.blue else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, description, modifier = iconModifier.size(22.dp),
            tint = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Standard compass widget: 3D beveled red/slate needle + dial ticks pointing to North.
 * Reads [rememberDeviceHeading] locally so sensor updates only recompose this 48dp button,
 * never the entire map screen.
 */
@Composable
internal fun MapCompass(
    bearing: Double,
    visible: Boolean,
    modifier: Modifier = Modifier,
    onClick: (deviceHeading: Float?) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val deviceHeading by rememberDeviceHeading(enabled = visible)
    // Combine phone sensor azimuth (if available) with map rotation so the red needle points to North.
    val totalHeading = (deviceHeading?.toDouble() ?: 0.0) + bearing
    val signed = signedBearing(totalHeading)
    // Unwrapped needle angle: crossing north (359° → 1°) turns 2°, not a full circle.
    var needleTarget by remember { mutableFloatStateOf((-signed).toFloat()) }
    SideEffect {
        val delta = ((-signed - needleTarget) % 360.0 + 540.0) % 360.0 - 180.0
        needleTarget += delta.toFloat()
    }
    val animatedBearing by animateFloatAsState(
        targetValue = needleTarget,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "compass",
    )
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    val cardinalColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f)
    val dialModifier = remember(tickColor, cardinalColor) {
        Modifier.drawWithCache {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val outerR = size.width * 0.47f
            val halfW = size.width * 0.135f
            val len = size.height * 0.34f

            val ticks = List(12) { i ->
                val angleRad = Math.toRadians((i * 30.0) - 90.0)
                val isCardinal = i % 3 == 0
                val innerTickR = outerR * (if (isCardinal) 0.80f else 0.87f)
                val cosA = kotlin.math.cos(angleRad).toFloat()
                val sinA = kotlin.math.sin(angleRad).toFloat()
                Triple(
                    Offset(cx + innerTickR * cosA, cy + innerTickR * sinA),
                    Offset(cx + outerR * cosA, cy + outerR * sinA),
                    isCardinal,
                )
            }
            val northLeft = Path().apply { moveTo(cx, cy - len); lineTo(cx - halfW, cy); lineTo(cx, cy); close() }
            val northRight = Path().apply { moveTo(cx, cy - len); lineTo(cx + halfW, cy); lineTo(cx, cy); close() }
            val southLeft = Path().apply { moveTo(cx, cy + len); lineTo(cx - halfW, cy); lineTo(cx, cy); close() }
            val southRight = Path().apply { moveTo(cx, cy + len); lineTo(cx + halfW, cy); lineTo(cx, cy); close() }
            val cardinalStroke = size.width * 0.045f
            val minorStroke = size.width * 0.03f
            val center = Offset(cx, cy)

            onDrawBehind {
                ticks.forEachIndexed { i, (start, end, isCardinal) ->
                    drawLine(
                        color = when {
                            i == 0 -> Color(0xFFDC3545)
                            isCardinal -> cardinalColor
                            else -> tickColor
                        },
                        start = start,
                        end = end,
                        strokeWidth = if (isCardinal) cardinalStroke else minorStroke,
                    )
                }
                drawPath(northLeft, color = Color(0xFFDC3545))
                drawPath(northRight, color = Color(0xFFFF5252))
                drawPath(southLeft, color = Color(0xFF8A93A6))
                drawPath(southRight, color = Color(0xFFBCC5D4))
                drawCircle(color = Color.White, radius = halfW * 0.65f, center = center)
                drawCircle(color = Color(0xFF1E2330), radius = halfW * 0.36f, center = center)
            }
        }
    }

    Surface(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick(deviceHeading)
        },
        shape = CircleShape,
        color = Color.Transparent,
        modifier = modifier.size(48.dp).glass(CircleShape),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .graphicsLayer { rotationZ = animatedBearing }
                    .then(dialModifier),
            )
        }
    }
}

/** Map bearing (0..360) as -180..180, so 359° counts as 1° west of north. */
internal fun signedBearing(bearing: Double): Double = ((bearing % 360.0) + 540.0) % 360.0 - 180.0
