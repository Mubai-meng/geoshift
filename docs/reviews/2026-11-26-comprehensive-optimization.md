# 2026-11-26：全栈稳定性、防风控仿真度、性能与交互全面优化

- **版本**：Geoshift `3.5.14` → `3.6.0`（versionCode `85`）+ GeoshiftMotion `1.0.3` → `1.1.0`（versionCode `5`）
- **基线提交**：`91650d7`（`v3.5.14`）
- **范围**：`:app`（`core/`、`data/`、`service/`、`xposed/`、`ui/`）与 `:motion`（`CadenceModel.kt`、`MotionModule.kt`）及 JVM 回归测试

---

## 一、核心缺陷修复（P0）

1. **冷启动竞态修复**（依据：源码检查 + 构造流程）
   - `Geoshift.resumeAfterRestart()` 改为 `suspend` 并先等待 `store.ready.first { it }`，避免磁盘加载完成前读到默认 `privacyAccepted=false` 导致漏接续并反推 `active=false` 误停后台模拟；`SimulationService` 冷启动同样补齐 `store.ready` 等待。
2. **路线编辑后系统侧不生效修复**（依据：源码检查 + 单测）
   - `RouteAnchor` 新增 `contentHash`（取自 `RoutePlan.idOf(points)`），`FrameworkBridge.uploadedRoutes` 改为 `routeId -> contentHash` 映射并在 `updateRoute` / `importFrom` 失效缓存，`Sim.loadRoute` 比对 `planHash`，确保编辑已有路线后系统侧立即重载新点位。
3. **配置去重与跨 NTP 校时同开机校验**（依据：源码检查 + 单测）
   - `FrameworkBridge.push()` 构造 `stable` 时归零 `bootAt` 与 `writtenAt`，消除 1–2 ms 墙钟抖动引发的高频重复写 `RemotePreferences` 与 `SET` Binder 调用。
   - `SimConfig` 新增 `bootCount`（读取 `Settings.Global.BOOT_COUNT`），`sameBoot()` 优先按精确开机计数校验，不受 NTP 校时或手动改时间影响，并兼容旧配置的 `bootAt ± 60s` 回退。
4. **系统进程权限与自身豁免补齐**（依据：源码检查）
   - `PhoneSide` 补齐 `isOwnUid()` 豁免 Geoshift 自身，开启基站模拟时仍可扫描真实基站；`SystemSide.isOwnUid()`、`PhoneSide.isOwnUid()`、`AppScope.listed()` 在调用 `PackageManager` 前后包裹 `Binder.clearCallingIdentity()` / `restoreCallingIdentity()`，避免 Android 11+ 包可见性干扰。
5. **GeoshiftMotion 跨日清零、历史查询防护与空闲退避**（依据：源码检查 + 单测）
   - `StepAccumulator` 新增按本地自然日重置的 `todaySyntheticSteps`（供 MIUI `StepsProvider` 使用），同时保持 `totalSyntheticSteps` 单调递增（供 `TYPE_STEP_COUNTER` 使用）。
   - 新增 `MiuiStepsFilter`：跳过 `:push` / `:sandboxed_process` / `:webview` 等辅助子进程；拦截 MIUI 步数查询时校验末尾行 `_end_time` 与 `selection`/`selectionArgs`，防止污染昨天/历史日期步数记录；无模拟且无传感器监听时轮询间隔平滑退避至 5s 降低耗电。

---

## 二、防风控仿真度与系统兼容性加固（P1）

1. **多源定位错峰与常量脱敏**（依据：源码检查 + 单测）
   - `FixBuilder` 为 `network` 提供器增加独立于 GPS/fused 的 4–10 m 缓慢漂移，为 `bearingAccuracy` / `verticalAccuracy` 加入平滑噪声；`LocationHooks` 定位泵将 `network` 错峰至每 3 拍推送一次，消除多源同毫秒锁步特征。
   - `Nmea` 根据实际在用星座动态生成 `GNS` 四字符模式串，并替换写死的 `0.0,M` 大地水准面起伏为平滑地理近似值。
2. **多 AP Wi-Fi 扫描与基站射频参数补全**（依据：源码检查 + 单测）
   - `SimConfig` / `Runtime` 新增 `wifiScan`，载入「环境记录」时保留最多 16 个周边热点供 `Fakes.scanResults()` 返回，并生成 5 秒缓存桶内的连贯扫描时间戳。
   - `Fakes.cell()` 为 LTE 补齐 `mRsrq`/`mRssnr`/`mCqi`/`mTimingAdvance`，为 NR 补齐 `mSsRsrq`/`mSsSinr`。
3. **反内联与高频路径优化**（依据：源码检查）
   - 为 `LocationHooks` 的 `getLastLocation*` 与 `PhoneSide` 的基站查询 getter 补齐 `hooks.deoptimize(m)`；`LocationHooks.onCached()` 自动登记 provider manager；新增 `WeakHashMap` 缓存 `manager -> (mName, mProvider, lock)`；统一 2 处遗漏的 `Reflect.recordMissing()` 记录缺失时间。

---

## 三、性能、存储与交互体验优化（P2 & P3）

1. **路线存储拆分为索引 + 单文件折线**：`Store.kt` 新增 `SplitRouteStorage`（`routes-meta.json` + `routes/<id>.json`），平滑迁移旧 `routes.json`，路线排序不再重写全部坐标点，列表写入统一走 `diskWriter` 异步落盘。
2. **UI 与服务性能优化**：`positionName()` 增加经纬度包围盒粗筛；`MapScreen` 逆地理编码跳过后台 Tab 与摇杆拖动；`MockScreen` 诊断列表按依赖 `remember` 缓存；Xposed 模式摇杆步进不再每 100ms 触发 `SimulationService.nudge()`；`AmapMap` 距离判断改用纯 Kotlin `Geo.distance()`。
3. **诊断体验增强**：系统诊断新增「步频联动（GeoshiftMotion）」条目、点击条目查看完整堆栈弹窗，以及「复制完整诊断报告」一键导出设备/系统/框架/完整堆栈信息。
4. **路线与通知栏体验**：支持 **GPX 轨迹文件导入与导出**（[Gpx.kt](../../app/src/main/java/app/geoshift/data/Gpx.kt)）；路线编辑新增**多步撤销栈**与**节点后插入中点 / 地图点中节点后插入**；`SimulationService` 路线播放通知栏新增「暂停 / 继续 / 重播」快捷按钮。

---

## 四、验证快照与构建产物

- **JVM 单元测试**（`bash tests/run-checks.sh`，替身回归）：**60/60 通过**（`:app` 53 项 + `:motion` 7 项，0 skipped / 0 failures / 0 errors）。
- **Android 静态检查**（`./gradlew --no-daemon :app:lintRelease :motion:lintRelease`）：两模块均 **0 errors** 通过。
- **发布构建与签名**：
  - `../releases/Geoshift-3.6.0.apk`（SHA-256：`b5a0d48e81cea133f4b7634e1817e53f956cf66be322e737902ad2ce095da75d`）
  - `../releases/GeoshiftMotion-1.1.0.apk`（SHA-256：`9e2012295aa5b1d8f56fcfc5a85cd858090b1a6b9493b02118bf31c33f3101f8`）
- **待真机验证项**：`Settings.Global.BOOT_COUNT` 跨重启校验、多 AP Wi-Fi 扫描及小米 HyperOS / MIUI 跨日步数查询过滤待实机复测。
