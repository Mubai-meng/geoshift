# 2026-11-24：系统定位诊断异常排查、兼容性修复、堆栈记录与多用户兼容

- **版本**：Geoshift `3.5.11` → `3.5.12`（versionCode `82`）
- **基线提交**：`93d77c5`（`v3.5.11`）
- **发布提交**：`8a4fbb1`（tag `v3.5.12`）
- **范围**：`:app`（`xposed/system/*`、`xposed/Reflect.kt`、`xposed/Runtime.kt`、`xposed/Fakes.kt`、`data/Diagnostics.kt`、`data/FrameworkBridge.kt`、`data/Models.kt`、`ui/AppViewModel.kt`、`ui/mock/MockScreen.kt` 及 JVM 回归测试）

---

## 一、排查背景与已知异常

针对真机诊断报告的 4 项异常及系统分身作用域疑问，进行了源码级调用链排查：

1. **`LocationProviderManager$LocationListenerRegistration.getIdentity` 方法查找失败**（依据：源码检查）
   - 调用链：`LocationHooks.onDelivery()` → `progress()` / `registrationUid()` → `Reflect.call(registration, "getIdentity")` → `Reflect.find()` 未命中 → `Reflect.missingMember()` 写入全局 `Reflect.missing`。
   - 根因：Android 15/16 或经过优化的 `services.jar` 中，`Registration.getIdentity()` 可能被内联移除（仅留 `mIdentity` / `mCallerIdentity` 字段）或改名为 `getCallerIdentity()` / `getAttributionSource()`；同时非关键日志路径 `targetOf()` 使用了会污染 `Reflect.missing` 的 `Reflect.call()`，而 `selectedOnly` 模式下 `registrationUid()` 失败返回 `-1` 会导致选中应用被误判为未选中而丢弃模拟位置。
2. **系统定位入口安装出现 `IllegalStateException` 与 `report=1, last=0`**（依据：源码检查）
   - 调用链：`Module.onSystemServerStarting()` → `SystemSide.install()` → `step("location_system")` → `LocationHooks.install()` 第 73 行 `check(reports > 0 && cached > 0)` 抛 `IllegalStateException: 定位入口缺失：report=1, last=0`。
   - 根因：`LocationHooks.install()` 原先写死 `nested = false` 且严格匹配 `it.name == "getLastLocation" && it.returnType == Location::class.java`，未兼容 `getLastLocationUnsafe` / `getLastLocationLocked` 或返回 `LocationResult` 的变体。
3. **半初始化中断与跨组件连带影响**（依据：源码检查）
   - `SystemSide.step()` 捕获了异常，未阻断兄弟组件 `CallbackHooks.install()` 与 `WifiHooks.install()`；
   - 但 `LocationHooks.install()` 在第 73 行 `check(...)` **之前**已安装 `onReportLocation`（丢弃真实定位）与 `acceptLocationChange`（交付拦截），抛异常后跳过了第 74–77 行的 `watchRegistrations()`、`Sim.onChange { schedule(0) }` 与 `schedule(0)`，导致：
     - 定位泵 `geoshift-pump` 从未启动（真实定位被丢弃但无模拟推送）；
     - `CallbackOwners.watch(lms / TelephonyRegistry)` 未安装，连带导致 `CallbackHooks` 在 `selectedOnly` 模式下所有回调归属未知。
4. **配置同步正常，但存在历史报告残留与真实错误被掩盖**（依据：源码检查）
   - `MockScreen.kt` 原先的 `when` 顺序中 `System.currentTimeMillis() - report.time > 60_000 -> "历史报告"` 排在 `!report.ok -> "出现错误"` **之前**，导致开机超过 60 秒后，`location_system` 的安装失败错误被降级显示为橙色「历史报告」且不计入错误总数；同时 `config_*` 的 `synced` 稳态在 60 秒后也被误标为「历史报告」。
   - `Diag.snapshot()` 每次 `PING` 都用 `System.currentTimeMillis()` 重写 `missing` 的时间戳，与开机失败时间戳语义倒置；`Diag.reports` 缺少会话起始边界，无法区分未启动、当前会话运行态与上一次模拟残留。
5. **系统分身只有一个作用域的原因及多用户隐患**（依据：源码检查）
   - `module.prop` 为 `staticScope=true`，`scope.list` 仅含 `system`、`com.android.phone`、`com.android.bluetooth`、`com.google.android.bluetooth`。后三者仅存在于主用户（User 0），在分身用户下被框架过滤，仅剩 `system`。
   - 连带发现：`SystemSide.ownUid()` 原先只查询 User 0 的 `app.geoshift` UID，若 Geoshift 安装在系统分身（`uid = userId * 100000 + appId`），会导致 `PING` / `SET` / `MOVE` 鉴权失败及 `WifiHooks` 无法豁免自身。

---

## 二、已完成修改

### 1. 系统定位反射兼容层与身份回退
- **新增** `app/src/main/java/app/geoshift/xposed/system/LocationCompat.kt`：
  - `discoverLastLocationMethods()`：按优先级匹配 `getLastLocation`（返回 `Location` 或 `LocationResult`）→ `getLastLocationUnsafe` / `getLastLocationLocked` / `getLastKnownLocation` → 嵌套类同名方法。
  - `resolveIdentity()`：无副作用探测 `getIdentity` / `getCallerIdentity` / `getAttributionSource` 方法及 `mIdentity` / `mCallerIdentity` / `mAttributionSource` 字段（沿父类链），再提取 `uid` 与 `packageName`。
  - `describeLocationCandidates()` / `describeIdentityCandidates()`：缺失时生成候选方法/字段签名摘要，便于真机定位具体 ROM 差异。
- **修改** `app/src/main/java/app/geoshift/xposed/system/LocationHooks.kt`：
  - `install()` 改为**先探测并校验，再安装任何 hook**，避免半瘫痪状态；失败时异常消息附带候选签名。
  - `onCached()` 同时支持返回 `Location` 与 `LocationResult`。
  - `targetOf()` 身份不可用时不污染 `Reflect.missing`（仅首次记日志含候选签名）；`registrationUid()` 缺失时明确登记 `Reflect.recordMissing(..., "定位归属")`，不掩盖真实错误。
  - `watchRegistrations()` 重命名为 `internal fun installRegistrations()`。
- **修改** `app/src/main/java/app/geoshift/xposed/system/SystemSide.kt`：
  - 将 `location.installRegistrations()` 独立置于 `step("callbacks", "回调归属")`，确保即使 `LocationHooks.install()` 失败也不连带破坏 `CallbackOwners` 与 `CallbackHooks`。

### 2. 异常堆栈记录与诊断状态机重构
- **修改** `app/src/main/java/app/geoshift/xposed/Runtime.kt` & `Reflect.kt`：
  - `Diag.report()` 新增可选参数 `error: Throwable? = null`，新增 `Diag.formatStack()` 格式化并截断异常堆栈（含 `Caused by`，默认最多 10 帧 / 1200 字符）存入 JSON `stack` 字段。
  - `Diag` 记录 `sessionStartedAt`（在 `Sim.publish()` 检测到 `active` 从 `false → true` 时更新），由 `SystemSide.handle("PING")` 通过 `session_started_at` 传给 App。
  - `Reflect` 新增 `missingAt` 与 `recordMissing()`，记录真实首次缺失时间，`Diag.snapshot()` 不再每次 `PING` 伪造当前时间。
- **新增** `app/src/main/java/app/geoshift/data/Diagnostics.kt`：
  - `Diagnostics.classify()` 严格按以下顺序判定：
    1. `!report.ok || report.stage == "error"` → 始终为「出现错误」（永不降级为「历史报告」）。
    2. 明确未启用的可选环境开关（`wifi` / `bluetooth`）→ 「未启用」。
    3. `report == null` → `missing*` 为「无」；运行项且 `!simActive` 为「未启动」；其余为「暂无报告」。
    4. 稳态阶段（`installed` / `synced` / `stale`）→ 保持稳态，不因超过 60 秒降级。
    5. 运行态阶段（`injected` / `read` / `waiting`）→ `!simActive` 或 `report.time < sessionStartedAt` 判为「历史报告」；当前会话内按实时阶段显示（`location_pump` 超 60 秒无新心跳判为「等待中」）。
- **修改** `Models.kt`、`FrameworkBridge.kt`、`AppViewModel.kt`、`MockScreen.kt`：
  - `Report` 增加 `stack: String = ""`；`AppState` 增加 `sessionStartedAt: Long`；`MockScreen` 接入 `Diagnostics.classify()` 并在诊断条目中展示精简异常堆栈。

### 3. 多用户 / 系统分身 UID 兼容与 Lint 清理
- **修改** `SystemSide.kt` & `WifiHooks.kt`：
  - 将只查 User 0 的 `ownUid()` 替换为 `isOwnUid(uid)`（纯逻辑抽离为 `SystemSide.isSelfUid()`，支持 `pm.getPackagesForUid(uid)`、按 `uid % 100000` 回退及主用户 `appId` 校验，且阻止 `appId < 10000` 的系统 UID 匹配）。
- **修改** `app/src/main/java/app/geoshift/xposed/Fakes.kt`：
  - 为 `gnss()` 添加 `@RequiresApi(Build.VERSION_CODES.R)`；为 `cell()` 中的 `CellInfoNr` 添加 `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)` 守卫，清零 `:app:lintRelease` 既有 4 处 `NewApi` 错误。

---

## 三、验证快照与构建产物

- **JVM 单元测试**（`bash tests/run-checks.sh`，替身回归）：**44/44 通过**（0 skipped / 0 failures / 0 errors）：
  - 原有 `FixTest`(6)、`GeoTest`(5)、`NmeaTest`(5)、`RoutePlanTest`(12)、`SimConfigTest`(6) 全部通过；
  - 新增 `app/src/test/java/app/geoshift/xposed/LocationCompatTest.kt`（6 项：覆盖 `getIdentity` 父类继承、`mIdentity` 字段回退、`getCallerIdentity`、完全缺失不污染 `Reflect.missing`、`getLastLocation` / `getLastLocationUnsafe` / 候选签名输出、以及多用户 `SystemSide.isSelfUid`）；
  - 新增 `app/src/test/java/app/geoshift/data/DiagnosticsTest.kt`（4 项：覆盖超时错误不降级、`synced` 稳态不过期、未启动/历史残留/当前会话区分、以及 `Diag.formatStack` 截断与 `Caused by` 保留）。
- **Android 静态检查**（`./gradlew :app:lintRelease :motion:lintRelease`，构建检查）：**0 errors** 通过。
- **发布构建与签名**（`./build-release.sh`）：
  - 产物：`../releases/Geoshift-3.5.12.apk`（28.8 MB，已通过 `apksigner verify`）
  - SHA-256：`962e31c922cc0f194ad370033bfd30f250ab1b6db8b6ed67431d854ce449d676`
  - GitHub Release：`v3.5.12`（附带 `Geoshift-3.5.12.apk`、`GeoshiftMotion-1.0.2.apk`、`GeoProbe.apk`），官网已通过 `tools/publish-site.sh` 同步至 `gh-pages`。

---

## 四、剩余限制与供其他 AI 复查的关注点

1. **真机尚未复测（待真机结果确认）**：
   - 以上 `LocationCompat` 兼容路径与多用户 `isSelfUid` 均已通过 JVM 替身回归测试，但尚未在出现 `report=1, last=0` 和 `getIdentity` 缺失的具体目标 ROM 真机上实测。
   - 若真机上 `LocationProviderManager` 的缓存定位方法名不在 `getLastLocation` / `getLastLocationUnsafe` / `getLastLocationLocked` / `getLastKnownLocation` 之列，`LocationHooks.install()` 仍会诚实报错，但本次新增的「候选签名 + 异常堆栈」会直接在诊断页输出真实方法签名，供下一步精准适配。
2. **其他已观察到的非本次范围潜在风险（供后续排查参考）**：
   - `motion/src/main/java/app/geoshift/motion/MotionModule.kt` 中 `installMiuiStepsHook()` Hook 了 `ContentResolver` 的所有 `query` 重载；Android 内部短参 `query(...)` 会转发至长参 `query(uri, projection, queryArgs, cancellationSignal)`，在部分 ROM 上可能导致 `wrapMiuiStepsCursor` 被重复包装、步数被叠加两次（需真机日志或单测进一步验证）。
   - `motion/src/main/java/app/geoshift/motion/CadenceModel.kt` 中 `StepAccumulator.totalSyntheticSteps` 与 `stepPhase` 在 `advance()` 内加锁写、在 `MotionModule` 回调线程无锁读，建议后续补 `@Volatile` 或同步读取。
   - `ui/mock/MockScreen.kt` 中堆栈当前以 `maxLines = 6` 内联展示；若后续需要完整复制诊断快照（含所有候选签名与完整堆栈），可在「系统定位诊断」卡片底部增加一键「复制诊断报告」按钮。
