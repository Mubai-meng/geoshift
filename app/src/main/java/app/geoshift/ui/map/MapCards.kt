package app.geoshift.ui.map

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Directions
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PinDrop
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.geoshift.core.LatLon
import app.geoshift.data.RoadProfile
import app.geoshift.ui.AppState
import app.geoshift.ui.components.CoordinateRow
import app.geoshift.ui.components.formatDistance
import app.geoshift.ui.components.glass
import app.geoshift.ui.theme.Danger
import app.geoshift.ui.theme.Warning

@Composable
internal fun BottomCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().animateContentSize().glass(MaterialTheme.shapes.extraLarge, elevation = 8.dp).padding(20.dp)) { content() }
}

/** Idle / running panel: state and position on the left, one round start-stop button on the right. */
@Composable
internal fun StatusCard(
    s: AppState,
    address: String,
    onToggle: () -> Unit,
    onPause: () -> Unit,
    onOpenSetup: () -> Unit,
    onCopyCoord: () -> Unit,
) {
    val r = s.runtime
    val haptic = LocalHapticFeedback.current
    val ready = s.settings.privacyAccepted && s.caps.preciseLocation &&
        (if (s.settings.xposed) s.caps.hookActive else s.caps.mockSelected)
    val live = MaterialTheme.colorScheme.tertiary
    BottomCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape).background(
                            when {
                                r.enabled && r.paused -> Warning
                                r.enabled -> live
                                r.error.isNotEmpty() -> Danger
                                else -> MaterialTheme.colorScheme.outline
                            },
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            r.enabled && r.paused -> "路线已暂停"
                            r.enabled && r.routeMode -> "路线模拟中"
                            r.enabled -> "正在模拟"
                            r.error.isNotEmpty() -> "模拟启动失败"
                            else -> "未在模拟"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (r.enabled) live else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(4.dp))
                val name = s.positionName
                if (name.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Bookmark, null, Modifier.size(18.dp), tint = if (r.enabled) live else MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                CoordinateRow(
                    r.position.lat, r.position.lon,
                    style = if (name.isNotEmpty()) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                    onCopied = onCopyCoord,
                )
                if (name.isEmpty() || r.routeMode || (address.isNotEmpty() && address != name)) Text(
                    when {
                        r.enabled && r.routeMode -> String.format(
                            java.util.Locale.ROOT, "%s · %.0f km/h · %d%%",
                            r.routeName.ifEmpty { "未命名路线" }, r.speed * 3.6, (r.progress * 100).toInt(),
                        )
                        address.isNotEmpty() -> address
                        r.enabled -> "所有定位来源都在这个点"
                        else -> "点地图选点，或直接在此处开始"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (r.enabled && r.routeMode) {
                Surface(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPause()
                    },
                    shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            when {
                                r.routeFinished -> Icons.Rounded.Replay
                                r.paused -> Icons.Rounded.PlayArrow
                                else -> Icons.Rounded.Pause
                            },
                            when {
                                r.routeFinished -> "重新开始"
                                r.paused -> "继续"
                                else -> "暂停"
                            },
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
            }
            Surface(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle()
                },
                shape = CircleShape, shadowElevation = 2.dp,
                color = if (r.enabled) Danger else MaterialTheme.colorScheme.primary,
                contentColor = Color.White, modifier = Modifier.size(60.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (r.enabled) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        if (r.enabled) "停止模拟" else "开始模拟",
                        Modifier.size(30.dp),
                    )
                }
            }
        }
        if (r.enabled && r.routeMode) {
            Spacer(Modifier.size(12.dp))
            LinearProgressIndicator(
                progress = { r.progress.toFloat() },
                modifier = Modifier.fillMaxWidth().clip(CircleShape),
                color = live, drawStopIndicator = {},
            )
        }
        if (r.error.isNotEmpty() && !r.enabled) {
            Spacer(Modifier.size(8.dp))
            Text(r.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (!ready) {
            Spacer(Modifier.size(12.dp))
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(onClick = onOpenSetup).padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "还有权限或模块未配置", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f),
                )
                Text("去设置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
internal fun PreviewCard(
    point: LatLon,
    address: String,
    addressLoading: Boolean,
    distance: Double,
    running: Boolean,
    onClose: () -> Unit,
    onSetVirtual: () -> Unit,
    onStart: () -> Unit,
    onRouteTo: () -> Unit,
    onSave: () -> Unit,
    onCopyCoord: () -> Unit,
) {
    BottomCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("选中的位置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    when {
                        address.isNotEmpty() -> address
                        addressLoading -> "正在获取地址…"
                        else -> "未知地址"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (address.isEmpty() && addressLoading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CoordinateRow(point.lat, point.lon, style = MaterialTheme.typography.bodySmall, onCopied = onCopyCoord)
                    Text(
                        " · 距模拟位置 ${formatDistance(distance)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "关闭") }
        }
        Spacer(Modifier.size(14.dp))
        // One clear main action, then the secondary ones as equal labelled tiles.
        Button(
            onClick = if (running) onSetVirtual else onStart,
            modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(if (running) Icons.Rounded.NearMe else Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (running) "移到这里" else "从这里开始模拟", style = MaterialTheme.typography.titleSmall, maxLines = 1)
        }
        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!running) ActionTile(Icons.Rounded.PinDrop, "设为模拟点", Modifier.weight(1f), onSetVirtual)
            ActionTile(Icons.Rounded.Directions, "路线到这里", Modifier.weight(1f), onRouteTo)
            ActionTile(Icons.Rounded.BookmarkAdd, "收藏", Modifier.weight(1f), onSave)
        }
    }
}

/** Secondary action of the selection card: icon over a short label, never truncated. */
@Composable
private fun ActionTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 60.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(
            Modifier.padding(vertical = 9.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RouteEditorCard(
    count: Int,
    length: Double,
    profile: RoadProfile,
    roadStatus: String?,
    roadFailed: Boolean,
    onProfile: (RoadProfile) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    BottomCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("路线编辑", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("$count 个点位 · ${formatDistance(length)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "退出编辑") }
        }
        // Five choices do not fit a narrow phone; let the row scroll instead of clipping.
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoadProfile.entries.forEach { p ->
                FilterChip(selected = p == profile, onClick = { onProfile(p) }, label = { Text(p.label) })
            }
        }
        if (roadStatus != null) Text(
            roadStatus, style = MaterialTheme.typography.bodySmall,
            color = if (roadFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(8.dp))
        // Default 24 dp side padding leaves the save button too narrow on small phones ("保存路/线").
        val compact = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onUndo, enabled = count > 0, contentPadding = compact) {
                Icon(Icons.AutoMirrored.Rounded.Undo, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("撤销", maxLines = 1, softWrap = false)
            }
            OutlinedButton(onClick = onClear, enabled = count > 0, contentPadding = compact) {
                Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("清空", maxLines = 1, softWrap = false)
            }
            Button(onClick = onSave, enabled = count >= 2, modifier = Modifier.weight(1f), contentPadding = compact) {
                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("保存路线", maxLines = 1, softWrap = false)
            }
        }
    }
}
