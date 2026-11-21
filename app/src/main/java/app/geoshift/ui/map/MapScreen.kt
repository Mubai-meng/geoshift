package app.geoshift.ui.map

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PinDrop
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.geoshift.core.Geo
import app.geoshift.core.LatLon
import app.geoshift.data.Place
import app.geoshift.data.RoadProfile
import app.geoshift.map.MapSource
import app.geoshift.ui.AppState
import app.geoshift.ui.AppViewModel
import app.geoshift.ui.components.InputDialog
import app.geoshift.ui.components.formatDistance
import app.geoshift.ui.components.glass
import app.geoshift.ui.theme.isDark
import app.geoshift.ui.theme.palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
fun MapScreen(
    s: AppState,
    vm: AppViewModel,
    contentPadding: PaddingValues,
    centerRequest: CenterRequest?,
    onCenter: (CenterRequest) -> Unit,
    onOpenSetup: () -> Unit,
    onOpenAmapKey: () -> Unit = {},
    visible: Boolean = true,
) {
    val r = s.runtime
    var selected by remember { mutableStateOf<LatLon?>(null) }
    var address by remember { mutableStateOf("") }
    var addressLoading by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val draft = remember { mutableStateListOf<LatLon>() }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var saveDialog by remember { mutableStateOf<String?>(null) }
    var routeDialog by remember { mutableStateOf(false) }
    var mapMenu by remember { mutableStateOf(false) }
    var searchGeneration by remember { mutableIntStateOf(0) }
    val camera = remember { MapCamera(r.position) }
    var mapBearing by remember { mutableDoubleStateOf(0.0) }
    var bottomCardHeight by remember { mutableIntStateOf(0) }
    // Height of the top overlay (search bar + history / tip / results), so the right-hand tools start below it.
    var topOverlayHeight by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // With the user's AMap Android key the AMap layer is the official SDK map; MapLibre otherwise.
    val sdk = vm.engine.amapSdk
    val sdkKey by vm.engine.store.sdkKey.collectAsState()
    val sdkStyleId by vm.engine.store.sdkStyleId.collectAsState()
    val realPosition by vm.engine.realPosition.collectAsState()

    // The real position can only be read while not simulating; pause polling while off-tab.
    LaunchedEffect(r.enabled, visible) {
        while (!r.enabled && visible) {
            withContext(Dispatchers.IO) { vm.engine.refreshRealPosition() }
            delay(15_000)
        }
    }

    // Open on the phone's real position rather than the last simulated one: once per launch, only when
    // not simulating, and never after the user has moved the map or picked a point meanwhile.
    LaunchedEffect(Unit) {
        if (vm.launchCentered) return@LaunchedEffect
        // Asked afresh: the state's capabilities are still the defaults right after launch.
        if (!withContext(Dispatchers.IO) { runCatching { vm.engine.capabilities(includePing = false).preciseLocation }.getOrDefault(false) }) return@LaunchedEffect
        vm.launchCentered = true
        if (r.enabled) return@LaunchedEffect
        var shown = camera.target
        fun untouched() = selected == null && vm.state.value?.runtime?.enabled != true &&
            Geo.distance(camera.target, shown) < 30.0
        withContext(Dispatchers.IO) { vm.engine.refreshRealPosition() }
        vm.engine.realPosition.value?.let { if (untouched()) { onCenter(CenterRequest(it)); shown = it } }
        // The cached fix may be old: correct it with a fresh one (silently; up to ~15 s indoors).
        val fresh = withContext(Dispatchers.IO) { runCatching { vm.engine.deviceLocation() }.getOrNull() } ?: return@LaunchedEffect
        if (untouched() && Geo.distance(fresh, shown) > 30.0) onCenter(CenterRequest(fresh))
    }

    var sdkFailed by remember(sdkKey) { mutableStateOf(false) }
    val officialAmap = s.settings.mapSource == MapSource.AMAP && sdkKey.isNotEmpty() && s.settings.privacyAccepted &&
        sdk.mapSupported && !sdkFailed
    // A key that does not authenticate would leave the official map blank: check it first.
    LaunchedEffect(sdkKey, s.settings.mapSource) {
        if (!officialAmap) return@LaunchedEffect
        val problem = withContext(Dispatchers.IO) { vm.engine.amapKeyProblem() } ?: return@LaunchedEffect
        sdkFailed = true
        vm.toast("高德 Key 不可用，已改用高德瓦片地图。$problem")
    }
    val focus = LocalFocusManager.current

    val searchHistory = s.settings.searchHistory
    val saveSearchHistory: (String) -> Unit = { keyword ->
        vm.run { store.updateSettings { it.copy(searchHistory = (listOf(keyword) + it.searchHistory.filter { k -> k != keyword }).take(6)) } }
    }
    val clearSearchHistory: () -> Unit = { vm.run { store.updateSettings { it.copy(searchHistory = emptyList()) } } }

    val doSearch: (String) -> Unit = { kw ->
        focus.clearFocus()
        val keyword = kw.trim()
        val generation = ++searchGeneration
        if (keyword.isEmpty()) {
            vm.toast("请输入地点关键词")
        } else if (!s.settings.privacyAccepted) {
            vm.toast("位置搜索需要联网，请先在设置中同意隐私说明")
        } else {
            saveSearchHistory(keyword)
            searching = true
            vm.run<List<Place>>(onDone = {
                if (searchGeneration == generation) {
                    searching = false
                    results = it
                    if (it.isEmpty()) vm.toast("未找到相关位置，尝试使用不同的关键词搜索")
                }
            }) {
                try {
                    search(keyword, camera.target)
                } catch (t: Throwable) {
                    if (searchGeneration == generation) searching = false
                    throw t
                }
            }
        }
    }

    // Back closes what is open on the map before leaving the app: results, the picked point, then the
    // route editor (twice when it holds points, so they are not lost by accident).
    var backArmedAt by remember { mutableLongStateOf(0L) }
    BackHandler(enabled = visible && (results.isNotEmpty() || selected != null || editing)) {
        when {
            results.isNotEmpty() -> { searchGeneration++; searching = false; results = emptyList(); focus.clearFocus() }
            selected != null -> selected = null
            draft.isEmpty() || SystemClock.elapsedRealtime() - backArmedAt < 2000 -> { editing = false; draft.clear() }
            else -> { backArmedAt = SystemClock.elapsedRealtime(); vm.toast("再按一次返回将放弃本次路线编辑") }
        }
    }

    // Road routes: each section between tapped points is snapped to roads in the background.
    val router = vm.engine.router
    val profile = s.settings.roadProfile
    var roadPath by remember { mutableStateOf<List<LatLon>?>(null) }
    var roadError by remember { mutableStateOf<String?>(null) }
    var roadBusy by remember { mutableStateOf(false) }
    val waypoints = draft.toList()
    LaunchedEffect(waypoints, profile, editing) {
        roadError = null
        if (!editing || !profile.road || waypoints.size < 2) {
            roadPath = null; roadBusy = false; return@LaunchedEffect
        }
        roadBusy = true
        delay(300)
        try {
            roadPath = withContext(Dispatchers.IO) { router.route(profile, waypoints) }
            roadBusy = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            roadPath = null; roadError = e.message ?: "道路规划失败"; roadBusy = false
        }
    }

    val tipDismissed = s.settings.mapTipDismissed
    val currentRoute = s.currentRoute
    val road = profile.road && roadPath != null
    val editRoute = if (road) roadPath!! else waypoints
    val editAnchors = if (road) waypoints else emptyList()
    val editLength = remember(editRoute) { Geo.routeLength(editRoute) }

    // Address of the simulated position for the status card (cancellable, does not touch global busy state).
    var simAddress by remember { mutableStateOf("") }
    LaunchedEffect(r.position, r.routeMode, s.settings.privacyAccepted) {
        if (r.routeMode || !s.settings.privacyAccepted) { simAddress = ""; return@LaunchedEffect }
        val p = r.position
        if (selected != null && selected == p && address.isNotEmpty()) { simAddress = address; return@LaunchedEffect }
        simAddress = ""
        delay(500)
        val resolved = withContext(Dispatchers.IO) { vm.engine.reverseGeocodeSuspend(p) }
        if (vm.state.value?.runtime?.position == p) simAddress = resolved
    }

    // Reverse-geocode the selected point (cancellable on new tap, shows loading indicator while fetching).
    LaunchedEffect(selected, s.settings.privacyAccepted) {
        val p = selected
        if (p == null || !s.settings.privacyAccepted) {
            addressLoading = false
            return@LaunchedEffect
        }
        if (address.isNotEmpty()) {
            addressLoading = false
            return@LaunchedEffect
        }
        addressLoading = true
        val resolved = withContext(Dispatchers.IO) { vm.engine.reverseGeocodeSuspend(p) }
        if (selected == p) {
            address = resolved
            addressLoading = false
        }
    }

    fun select(p: LatLon, presetAddress: String = "") {
        selected = p
        address = presetAddress
        addressLoading = presetAddress.isEmpty() && s.settings.privacyAccepted
        searchGeneration++
        searching = false
        results = emptyList()
        focus.clearFocus()
    }

    Box(Modifier.fillMaxSize()) {
        if (officialAmap) AmapMap(
            modifier = Modifier.fillMaxSize(), sdk = sdk, keyRevision = sdkKey,
            virtual = r.position, simulating = r.enabled, real = realPosition,
            selected = if (editing) null else selected, route = if (editing) editRoute else currentRoute,
            routeEditing = editing, anchors = if (editing) editAnchors else emptyList(),
            dark = isDark(s.settings.theme), styleId = sdkStyleId, camera = camera, center = centerRequest,
            logoBottomMargin = bottomCardHeight, visible = visible,
            onBearingChanged = { mapBearing = it },
            onTap = { p -> if (editing) draft.add(p) else select(p) },
            onLongPress = { p -> if (!editing) select(p) },
            onFailure = { message -> sdkFailed = true; vm.toast("$message，已改用高德瓦片地图") },
            onSlow = { vm.toast("高德官方地图加载较慢，请检查网络；仍是空白可在图层菜单换个图层，或到「设置 → 高德 Key」点「验证 Key」") },
        ) else VectorMap(
            modifier = Modifier.fillMaxSize(), virtual = r.position, simulating = r.enabled, real = realPosition,
            selected = if (editing) null else selected, route = if (editing) editRoute else currentRoute,
            routeEditing = editing, anchors = if (editing) editAnchors else emptyList(),
            online = s.settings.privacyAccepted, dark = isDark(s.settings.theme), source = s.settings.mapSource,
            camera = camera, center = centerRequest, visible = visible,
            onBearingChanged = { mapBearing = it },
            onTap = { p -> if (editing) draft.add(p) else select(p) },
            onLongPress = { p -> if (!editing) select(p) },
        )

        // ---- Top: search bar (also adds waypoints while editing a route) ----
        Column(Modifier.onSizeChanged { topOverlayHeight = it.height }.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
            SearchBar(
                query = query,
                searching = searching,
                placeholder = if (editing) "搜索地点添加途经点，或直接点地图" else "搜索地点、地址或坐标",
                routeMode = editing,
                onQuery = { query = it; searchGeneration++; searching = false; results = emptyList() },
                onSearch = { doSearch(query) },
                onClear = { query = ""; searchGeneration++; searching = false; results = emptyList() },
            )
            AnimatedVisibility(query.isEmpty() && results.isEmpty() && searchHistory.isNotEmpty()) {
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().glass(CircleShape)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.History,
                            contentDescription = "搜索历史",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            searchHistory.forEach { item ->
                                SuggestionChip(
                                    onClick = {
                                        query = item
                                        doSearch(item)
                                    },
                                    label = { Text(item, style = MaterialTheme.typography.bodySmall, maxLines = 1) },
                                    shape = CircleShape,
                                    border = null,
                                    colors = SuggestionChipDefaults.suggestionChipColors(containerColor = palette.fill),
                                )
                            }
                        }
                        IconButton(onClick = clearSearchHistory, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Rounded.Close, "清除搜索历史", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
            AnimatedVisibility(!tipDismissed && !editing && results.isEmpty()) {
                MapTip(
                    onSwitch = { mapMenu = true },
                    onKey = onOpenAmapKey,
                    onClose = { vm.run { store.updateSettings { it.copy(mapTipDismissed = true) } } },
                )
            }
            AnimatedVisibility(results.isNotEmpty()) {
                Box(Modifier.padding(top = 8.dp).fillMaxWidth().glass(MaterialTheme.shapes.large)) {
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        itemsIndexed(results, key = { i, place -> "${place.name}|${place.position.lat}|${place.position.lon}|$i" }) { index, place ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    if (editing) {
                                        draft.add(place.position)
                                        query = ""
                                        searchGeneration++
                                        searching = false
                                        results = emptyList()
                                        focus.clearFocus()
                                        onCenter(CenterRequest(place.position, 16.0))
                                        vm.toast("已添加第 ${draft.size} 个点：${place.name}")
                                    } else {
                                        select(place.position, presetAddress = place.address.ifEmpty { place.name })
                                        onCenter(CenterRequest(place.position, 16.0))
                                    }
                                }.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.Place, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(place.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (place.address.isNotEmpty()) Text(
                                        place.address, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (editing) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Rounded.Add, "添加到路线", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                            }
                            if (index < results.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }

        // ---- Right: floating tools ----
        // Hidden while search results are open (they would cover the list's right edge).
        if (results.isEmpty()) Column(
            // Centred between the top overlay and the bottom card, scrollable so small screens never clip tools.
            Modifier.align(Alignment.CenterEnd)
                .padding(
                    top = with(density) { topOverlayHeight.toDp() },
                    end = 12.dp,
                    bottom = with(density) { bottomCardHeight.toDp() },
                )
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MapCompass(
                bearing = mapBearing,
                visible = visible,
                onClick = { heading ->
                    if (abs(signedBearing(mapBearing)) > 1.0) {
                        onCenter(CenterRequest(camera.target, camera.zoom, bearing = 0.0))
                        vm.toast("已回正正北")
                    } else if (heading != null) {
                        val norm = ((heading % 360f + 360f) % 360f)
                        val dir = when (((norm / 45f) + 0.5f).toInt() % 8) {
                            0 -> "正北"; 1 -> "东北"; 2 -> "正东"; 3 -> "东南"
                            4 -> "正南"; 5 -> "西南"; 6 -> "正西"; else -> "西北"
                        }
                        vm.toast("当前朝向：$dir ${norm.toInt()}°")
                    } else {
                        vm.toast("当前已指向正北（0°）")
                    }
                },
            )
            ToolGroup {
                Box {
                    MapTool(Icons.Rounded.Layers, "地图图层：${s.settings.mapSource.label}") { mapMenu = true }
                    DropdownMenu(expanded = mapMenu, onDismissRequest = { mapMenu = false }) {
                        MapSource.entries.forEach { source ->
                            DropdownMenuItem(
                                text = { Text(if (source == MapSource.AMAP && sdkKey.isNotEmpty()) "高德地图 · 官方 SDK（已填 Key）" else source.menuLabel) },
                                trailingIcon = { if (source == s.settings.mapSource) Icon(Icons.Rounded.Check, "已选择") },
                                onClick = {
                                    mapMenu = false
                                    if (source != s.settings.mapSource) vm.run("已切换至${source.label}") {
                                        updateSettings { it.copy(mapSource = source) }
                                    }
                                },
                            )
                        }
                    }
                }
            }
            ToolGroup {
                MapTool(Icons.Rounded.PinDrop, "回到模拟位置") { onCenter(CenterRequest(r.position)) }
                ToolDivider()
                MapTool(Icons.Rounded.MyLocation, "真实位置") {
                    val cached = realPosition
                    if (r.enabled && cached != null) {
                        select(cached)
                        onCenter(CenterRequest(cached, 16.0))
                        vm.toast("已定位到模拟前的真实位置")
                    } else {
                        vm.run<LatLon>(onDone = { select(it); onCenter(CenterRequest(it, 16.0)) }) { deviceLocation() }
                    }
                }
            }
            ToolGroup {
                MapTool(Icons.Rounded.Route, "路线编辑", active = editing) {
                    if (!editing) { draft.clear(); selected = null; query = ""; searchGeneration++; searching = false; results = emptyList(); focus.clearFocus() }
                    editing = !editing
                }
                ToolDivider()
                MapTool(Icons.Rounded.SportsEsports, "摇杆", active = r.joystick) {
                    vm.run(if (r.joystick) "摇杆悬浮窗已关闭" else "摇杆服务已启动") { joystick(!r.joystick) }
                }
            }
            ToolGroup {
                MapTool(Icons.Rounded.Add, "放大（长按连续放大）", repeatOnHold = true) {
                    val next = (camera.zoom + 1.0).coerceAtMost(19.0)
                    camera.zoom = next
                    onCenter(CenterRequest(camera.target, next))
                }
                ToolDivider()
                MapTool(Icons.Rounded.Remove, "缩小（长按连续缩小）", repeatOnHold = true) {
                    val next = (camera.zoom - 1.0).coerceAtLeast(3.0)
                    camera.zoom = next
                    onCenter(CenterRequest(camera.target, next))
                }
            }
        }

        // ---- Bottom: contextual card ----
        val sel = selected
        val mode = when {
            editing -> 2
            sel != null -> 1
            else -> 0
        }
        AnimatedContent(
            targetState = mode,
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomCardHeight = it.height }.padding(contentPadding).padding(12.dp),
            transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { it / 2 } + fadeOut()) },
            label = "bottom",
        ) { m ->
            when (m) {
                2 -> RouteEditorCard(
                    count = draft.size,
                    length = editLength,
                    profile = profile,
                    // "沿 OpenStreetMap 道路" / "沿高德道路": space only around Latin names.
                    roadStatus = when {
                        profile == RoadProfile.FLY -> "点与点之间直线飞行，按飞行速度播放"
                        profile == RoadProfile.STRAIGHT -> "纯手选：点哪走哪，点与点之间直线连接，不调用道路服务"
                        !profile.road -> null
                        draft.size < 2 -> "点选途经点，路线会沿${spaced(router.source)}道路连接"
                        roadBusy -> "正在沿道路规划…"
                        roadError != null -> roadError
                        else -> "已沿${spaced(router.source)}道路连接"
                    },
                    roadFailed = roadError != null,
                    onProfile = { p -> vm.run { store.updateSettings { it.copy(roadProfile = p) } } },
                    onUndo = { if (draft.isNotEmpty()) draft.removeAt(draft.lastIndex) },
                    onClear = { draft.clear() },
                    onSave = {
                        when {
                            draft.size < 2 -> vm.toast("请至少选择两个路线点")
                            profile.road && roadBusy -> vm.toast("道路还在规划，请稍候")
                            profile.road && roadPath == null -> vm.toast(roadError ?: "道路规划失败，可改用手选")
                            else -> routeDialog = true
                        }
                    },
                    onClose = { editing = false; draft.clear() },
                )
                1 -> if (sel != null) PreviewCard(
                    point = sel,
                    address = address,
                    addressLoading = addressLoading,
                    distance = Geo.distance(sel, r.position),
                    running = r.enabled,
                    onClose = { selected = null },
                    onSetVirtual = {
                        vm.run(if (r.enabled) "已移到这里" else "已设为模拟点，点开始即从这里模拟", onDone = { _: Unit -> selected = null }) { setLocation(sel) }
                    },
                    onStart = { vm.run("模拟定位已开启", onDone = { _: Unit -> selected = null }) { startPoint(sel) } },
                    onRouteTo = {
                        draft.clear()
                        draft.add(r.position)
                        draft.add(sel)
                        selected = null
                        editing = true
                    },
                    onSave = { saveDialog = address },
                    onCopyCoord = { vm.toast("位置坐标已复制到剪贴板") },
                )
                else -> StatusCard(
                    s, simAddress,
                    onToggle = {
                        if (r.enabled) vm.run("模拟定位已关闭") { stop() }
                        else vm.run("模拟定位已开启") { if (r.routeMode && currentRoute.size >= 2) startRoute() else startPoint() }
                    },
                    onPause = {
                        vm.run(if (r.routeFinished) "路线重新开始" else if (r.paused) "路线模拟继续" else "路线模拟已暂停") { pauseRoute(!r.paused) }
                    },
                    onOpenSetup = onOpenSetup,
                    onCopyCoord = { vm.toast("虚拟坐标已复制到剪贴板") },
                )
            }
        }

        if (!officialAmap) Text(
            s.settings.mapSource.attribution,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomStart).padding(contentPadding).padding(start = 6.dp, bottom = 2.dp)
                .background(palette.glass.copy(alpha = 0.85f), MaterialTheme.shapes.extraSmall).padding(horizontal = 6.dp, vertical = 1.dp),
        )
    }

    saveDialog?.let { suggestion ->
        val p = selected
        InputDialog(
            "收藏位置", initial = suggestion.ifEmpty { "未命名位置" }, label = "名称",
            hint = if (suggestion.isNotEmpty()) "名称建议：$suggestion" else null,
            onDismiss = { saveDialog = null },
        ) { name ->
            if (p != null) vm.run<String>("已收藏") { savePlace(name, p, address) }
        }
    }
    if (routeDialog) {
        val points = editRoute
        val via = waypoints
        val kind = profile
        val title = when {
            kind == RoadProfile.STRAIGHT -> "路线 ${via.size} 点"
            else -> "${kind.label}路线 ${formatDistance(editLength)}"
        }
        InputDialog(
            "保存路线", initial = title, label = "名称",
            hint = (when {
                kind == RoadProfile.FLY -> "飞行 · ${via.size} 个途经点"
                !kind.road -> "共 ${via.size} 个点"
                else -> "沿道路${kind.label} · ${via.size} 个途经点"
            }) + " · ${formatDistance(editLength)}路线",
            onDismiss = { routeDialog = false },
        ) { name ->
            vm.run<String>("路线保存成功", onDone = { editing = false; draft.clear() }) { saveRoute(name, points, via, kind) }
        }
    }
}

/** Pads a name with spaces when it starts with a Latin letter, for mixed Chinese / English text. */
private fun spaced(name: String) = if (name.firstOrNull()?.let { it < '\u0080' } == true) " $name " else name
