# 2026-11-23：收藏排序、摇杆停路线、路线重编辑与运动方式修复

- **版本**：Geoshift `3.5.9` → `3.5.11`（versionCode `81`） + GeoshiftMotion `1.0.1` → `1.0.2`（versionCode `3`）
- **基线提交**：`0f19381`（`v3.5.8` 后）
- **范围**：`:app`（存储、引擎、摇杆服务、收藏页、地图路线编辑、系统侧 `MOTION` 通道）与 `:motion`（步频模型、步数追踪器、传感器事件分发）

---

## 一、已完成修改

### 1. 收藏地址 / 路线 / 环境自定义排序与置顶
- **问题**：`HistoryScreen` 默认强制按 `createdAt` 倒序，无法把高频打卡位置或常用路线排到第 1 位；`renamePlace` 调用 `put()` 还会把重命名项挪到顶部。
- **修改文件**：
  - `app/src/main/java/app/geoshift/data/Store.kt`：在 `RecordList<T>` 增加 `replaceInPlace`、`moveToTop`、`moveBy`。
  - `app/src/main/java/app/geoshift/data/Geoshift.kt`：增加 `moveRecordToTop`、`moveRecord`，`renamePlace` 改用 `replaceInPlace`。
  - `app/src/main/java/app/geoshift/ui/history/HistoryScreen.kt`：
    - 新增 `Sort.CUSTOM("自定义顺序")` 并设为默认排序（未手动调整时与原有新添加置顶顺序一致）。
    - 条目底部菜单新增「置顶（排到第 1 位）」。
    - 顶栏新增「调整顺序」模式（`reorderMode`，进入时自动切到自定义顺序并隐藏搜索框，避免过滤子集与全量列表下标错位），支持逐条置顶 / 上移 / 下移。
- **兼容性**：`Backup.export` / `Backup.import` 天然按列表顺序序列化，备份导入自动保留自定义顺序。

### 2. 路线运行中开启摇杆与快捷停止 / 暂停
- **问题**：原 `Geoshift.joystick(true)` 在路线运行时直接拒绝开启摇杆；悬浮窗没有快捷停止/暂停控制，切到其他 App 跑路线时必须切回 Geoshift 才能停。
- **修改文件**：
  - `app/src/main/java/app/geoshift/data/Geoshift.kt`：放开路线运行中开启摇杆的限制；若在路线运行中手动推动摇杆，平滑切为当前位置的单点移动（清除 `anchor` / `routeMode`），避免与 `advanceRoute()` 互相拉扯。
  - `app/src/main/java/app/geoshift/service/JoystickService.kt`：
    - 使用主线程 `CoroutineScope` 监听 `store.runtime` 与 `store.settings` 变化并重绘悬浮窗。
    - 展开态右下角新增红色停止按钮（`■`，模拟中显示，点击立即 `engine.stop()`）；左下角新增暂停/继续按钮（`⏸` / `▶`，路线运行中显示）。
    - 最小化悬浮球在模拟中以薄荷绿/橙色指示运行与暂停状态。
    - 复用 `playPath` 消除 `onDraw` 中的 `DrawAllocation`。

### 3. 已保存路线重新进入编辑 & 删除任意节点
- **问题**：路线保存后只能删除重画；编辑态仅支持「撤销最后一个点」或「清空」，无法直接删掉中间某个节点。
- **修改文件**：
  - `app/src/main/java/app/geoshift/data/Geoshift.kt`：新增 `updateRoute()`，支持简化/校验、`replaceInPlace` 保留排序与 `createdAt`、清 `plans` 缓存、若为当前选中路线则同步名称并停止旧回放。
  - `app/src/main/java/app/geoshift/ui/history/HistoryScreen.kt` & `GeoshiftApp.kt`：路线菜单新增「编辑路线（增删节点）」，跳回地图并注入 `editRouteRequest`。
  - `app/src/main/java/app/geoshift/ui/map/MapCards.kt` & `MapScreen.kt` & `AmapMap.kt`：
    - `RouteEditorCard` 新增可展开「节点」列表（标明起点 / `#n` / 终点、等宽坐标），支持点击居中定位与单点 `×` 删除；编辑已有路线时按钮显示「保存修改」。
    - 地图编辑态点中已有节点（按当前缩放换算约 24 dp 命中半径，含高德 SDK `setOnMarkerClickListener` 转发）弹窗询问「删除此节点」或「添加新点」。

### 4. 运动方式与 `GeoshiftMotion` 传感器联动修复
- **问题**：
  1. `MOTION` 通道只传瞬时速度，骑行（6.0 m/s ±20% 下探至 4.8 m/s）及驾车起步/过弯/红灯减速经过 `0.25–5.5 m/s` 时被 `CadenceModel` 误判为跑步/步行并刷出步数与步态加速度。
  2. `MotionModule.emitSteps` 在 `geoshift-motion` 后台线程直接反射调用 `SensorEventQueue.dispatchSensorEvent`，存在跨线程与队列已注销竞态风险。
  3. 硬件 `TYPE_STEP_COUNTER` 首次事件晚于合成步数到达时，直接叠加 `hardwareStepBase` 会造成累计步数瞬间跳变。
  4. `SpeedMode.DRIVE` 文案为「驾驶」而 `RoadProfile.CAR` 为「驾车」；控制台诊断提示仍写「本工具不伪造传感器」。
- **修改文件**：
  - `app/src/main/java/app/geoshift/core/SimConfig.kt`：新增 `footMotion: Boolean = true`（兼容旧配置）。
  - `app/src/main/java/app/geoshift/data/Geoshift.kt`：`footMotion = s.speed <= 5.5 && (s.customSpeed || s.speedMode == SpeedMode.WALK || s.speedMode == SpeedMode.RUN)`。
  - `app/src/main/java/app/geoshift/xposed/system/SystemSide.kt`：`MOTION` 响应增加 `foot` 字段。
  - `motion/src/main/java/app/geoshift/motion/CadenceModel.kt`：新增 `StepCounterTracker` 保证单调无跳变。
  - `motion/src/main/java/app/geoshift/motion/MotionModule.kt`：消费 `foot` 字段；Hook `BaseEventQueue` 构造函数记录各队列 `Looper` 并用对应 `Handler.post` 分发，分发前二次校验句柄仍活跃；接入 `StepCounterTracker`。
  - `app/src/main/java/app/geoshift/data/Models.kt` & `MockScreen.kt`：统一为「驾车」，更新诊断说明提及 `GeoshiftMotion`。

### 5. `3.5.10` 补充优化
- **路线规划新增「跑步」出行方式**：`Models.kt` 新增 `RoadProfile.RUN("跑步", true, "foot")`，与控制台 `SpeedMode` 一一对应；`Net.kt` 道路缓存键改按 `profile.osrm` 索引，步行/跑步切换复用缓存。
- **悬浮窗常驻一键启动 / 停止**：`JoystickService.kt` 右下角按钮改为常驻，未模拟时显示绿色 `▶`（按当前路线/单点模式直接启动），模拟中显示红色 `■`（停止）。
- **官网镜像清理**：实测移除返回 HTML 错误页的 `gh-proxy.net`，保留 `ghfast.top`、`gh-proxy.org`、`ghfile.geekertao.top`。

### 6. `GeoshiftMotion 1.0.2` 小米系统步数库适配与诊断日志
- **小米 HyperOS / MIUI 适配**：`MotionModule.kt` 新增 Hook `ContentResolver.query`，拦截 `content://com.miui.providers.steps`，在微信 / 小米运动健康查询今日步数分段时，将 `accumulator.totalSyntheticSteps` 叠加到返回 `Cursor` 的 `_steps` 列（0 行时构造单行 `MatrixCursor`）。
- **提前累计步数**：在 `onPackageReady` 即启动轻量 `1.5s` 轮询，保证在目标 App 未提前注册传感器或查询步数前，跑路线期间产生的步数也能实时累计，打开微信运动即可看到。
- **LSPosed / Vector 诊断日志**：记录模块加载进程、命中传感器类型、小米步数库查询叠加数及 `MOTION` 状态切换，便于真机排查。

### 7. `3.5.11` 地图路线与模拟点一键清除
- **一键清除路线**：`Geoshift.clearRoute()` 清空当前选中的路线轨迹（不删除收藏记录），地图底卡（`StatusCard`）与控制台路线卡均提供「清除路线 / 清除」入口。
- **一键清除模拟点**：`Runtime` 新增 `positionSet: Boolean = true`，`Geoshift.clearPoint()` 与 `clearSimulation()` 支持清除/隐藏地图上残留的模拟点标记（`AmapMap` 与 `VectorMap` 同步改为接收 `virtual: LatLon?`）。

---

## 二、验证快照

- **JVM 单元测试**：`:app:testDebugUnitTest`（34 项，0 失败）+ `:motion:testDebugUnitTest`（5 项，含新增 `stepCounterTrackerAvoidsBaselineJump`，0 失败）。
- **静态检查**：`:motion:lintRelease` 0 错误；`:app:lintRelease` 新增代码 0 错误 0 警告（仅有既有 `Fakes.kt` 的 4 条 `NewApi` 报错）。
- **发布构建**：`./build-release.sh` 与 `./build-motion.sh` 签名构建并通过 `apksigner verify`：
  - `../releases/Geoshift-3.5.11.apk`（28.8 MB，SHA-256：`4558e6540a7a0356052b3faf7a02968a8e9a6aa8ceb8e01ebb53bf2f016a2e96`）
  - `../releases/GeoshiftMotion-1.0.2.apk`（37 KB，SHA-256：`fcda7ca749d7f2178092b67a9a7ff4de16b987f956c5282ac1b8eb2c3fcf5ba9`）
- **真机/模拟器待验项**：
  - `GeoshiftMotion` 的 `BaseEventQueue` 构造函数 Hook 在不同 Android 版本/厂商 ROM 上是否总能捕获到 `Looper`（捕获不到时已兜底 `Looper.getMainLooper()`）。
  - 路线编辑在超多节点（>500 点的手选路线）下展开节点列表的交互流畅度。

---

## 三、供后续 AI 优化的方向建议

1. **收藏列表拖拽排序**：当前使用「置顶 + 上移/下移」按钮，稳定性高且零第三方依赖；后续可考虑在 `reorderMode` 中引入原生 Compose 长按拖拽手柄（注意与 `SwipeToDismissBox` 互斥）。
2. **路线节点插入与拖拽**：目前支持末尾追加、删除任意节点（列表或地图直点）；后续可在节点列表中支持「在某节点前/后插入」或地图上拖动节点微调坐标。
3. **跑步专属路线规划**：当前 `RoadProfile` 沿用步行道路（`FOOT`）规划跑步路线；如需更细粒度，可考虑在路线属性上区分默认播放速度（步行 vs 跑步）而不改变道路规划策略。
4. **摇杆与路线联动体验**：当前在路线中推动摇杆会自动切回单点模拟；若用户希望「暂时偏离路线微调后再回路线」，可考虑增加恢复/锁定摇杆开关。
5. **既有 Lint 清理**：`Fakes.kt` 中 4 处 `NewApi`（`GnssStatus.Builder` API 30 / `CellInfoNr` API 29）可补齐 `@RequiresApi` 或运行时 `SDK_INT` 守卫，使 `:app:lintRelease` 全绿。
