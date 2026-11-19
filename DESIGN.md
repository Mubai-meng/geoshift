# Geoshift DESIGN.md

Geoshift 的界面规范。改 UI 之前先读这一份；代码中的单一来源是 `app/src/main/java/app/geoshift/ui/theme/Theme.kt`
（色板、形状、字号）和 `ui/components/Common.kt`（卡片、浮层、开关）。

结构参考 [Uber DESIGN.md](https://github.com/VoltAgent/awesome-design-md/tree/main/design-md/uber)
（同为以地图为主的出行类应用）：胶囊是唯一的交互形状，表面默认平面，阴影只给浮在地图上的东西。
配色沿用本应用自己的「长春花蓝 + 薄荷绿」，和 3.3.9 起的图标（蓝底玻璃定位针 + 薄荷/青色光环）保持一致，**不**照搬 Uber 的黑白配色。

## 1. 气质

- 地图优先：地图是画布，界面元素是放在地图上的实心浮层，而不是一层层的磨砂玻璃。
- 克制：每个视口只有一个主操作（蓝色胶囊或 16dp 大按钮），其余都是次要的浅色块。
- 状态靠颜色说话：蓝 = 可操作 / 品牌，薄荷绿 = 正在模拟，红 = 停止 / 危险。

## 2. 颜色

| 角色 | 浅色 | 深色 | 用途 |
|---|---|---|---|
| primary（长春花蓝） | `#5B67EA` | `#7C88FF` | 主按钮、链接、选中态，每个视口一处主操作 |
| tertiary / Success（薄荷绿） | `#00C897` | `#26E7A6` | 「模拟中」、已授予、开关打开 |
| Live 渐变 | `#00D2A0 → #05B6D4` | 同左 | **只**用于模拟中的状态大卡（StatusHero），别处不用渐变 |
| error / Danger | `#FF5252` | `#FF6B6B` | 停止模拟、删除 |
| Warning | `#FF9F43` | — | 提示 |
| background | `#F7F8FC` | `#12111D` | 页面底色 |
| surface | `#FFFFFF` | `#1D1B2D` | 卡片 |
| floating（Palette.glass） | `#FFFFFF` 不透明 | `#232136` 不透明 | 地图上的浮层控件 |
| floatingEdge | `#1E1F29` 8% | `#FFFFFF` 10% | 浮层发丝描边 |
| outlineVariant | `#E4E7F2` | `#36334D` | 卡片描边（×0.5 透明度） |

规则：
- 不再引入新的品牌色。`Palette` 里的 teal/indigo/purple/pink/orange 只用作设置页图标底色等分类色。
- 地图上的文字（比例尺等）放在不透明或高不透明度的底上，不要直接压在瓦片上。

## 3. 形状

| Token | 半径 | 用在 |
|---|---|---|
| 胶囊 `CircleShape` | 999 | **所有可点的东西**：按钮、搜索框、标签、地图工具组、提示条、搜索历史条 |
| `shapes.extraSmall` | 6dp | 小标签、内联链接点击区 |
| `shapes.small` | 8dp | 输入框内的行、小提示块 |
| `shapes.medium` | 12dp | 卡片内的子区块（「还有权限未配置」条等） |
| `shapes.large` | 16dp | 卡片、列表卡、搜索结果面板、大号主按钮、ActionTile |
| `shapes.extraLarge` | 28dp | 底部卡片 / 弹层 |

规则：方形卡片用 16dp，**不要**给卡片用胶囊；新代码引用 `MaterialTheme.shapes.*`，不要写死 `RoundedCornerShape(n.dp)`。

## 4. 层级与阴影

| 层级 | 做法 | 用在 |
|---|---|---|
| Level 0 平面 | 无阴影，0.6dp `outlineVariant` 描边 | 所有页面里的卡片（PortalCard / SettingGroup / ToggleTile / 列表卡） |
| Level 1 浮层 | `Modifier.glass()`：不透明 floating 底 + 发丝描边 + 4dp 阴影（16% 黑） | 地图上的搜索框、工具组、罗盘、提示条、搜索结果 |
| Level 2 底卡 | `glass(shapes.extraLarge, elevation = 8.dp)` | 地图底部卡片（选点卡、模拟状态卡） |

规则：不要给页面里的卡片加阴影；阴影只说明「它浮在地图上」。底部标签栏保留 Haze 半透明玻璃（相当于 Uber 的 translucent tab），这是唯一的半透明表面。

## 5. 字体

系统字体，层级按 Theme.kt `AppType`：标题 34/28/22 粗体，正文 17/15，辅助 13/12。
粗体只给标题；按钮和强调用 SemiBold。

## 6. 组件

- **主按钮**：`Button`，默认胶囊；地图底卡里的全宽主操作用 `shapes.large`（16dp）、最小高度 50dp —— 对应 Uber 的 `button-large-rounded`，是胶囊规则唯一的例外。
- **次要操作**：`ActionTile` / `FilledTonalButton`，primaryContainer 70% 底，不加阴影。
- **地图浮动按钮**：48dp 圆形或胶囊工具组，Level 1。
- **开始/停止**：60dp 圆形，蓝（开始）/ 红（停止）。
- **开关**：打开时为薄荷绿轨道。

## 7. 该做 / 别做

该做：
- 每个视口只放一个蓝色主操作。
- 浮在地图上的东西用 `glass()`，页面里的东西用平面卡片。
- 圆角走 `MaterialTheme.shapes`。

别做：
- 别在 StatusHero 以外加渐变。
- 别给列表卡和设置卡加投影。
- 别新增第三种品牌色，也别把主色换成黑色（那是 Uber 的品牌，不是我们的）。
- 别在地图上叠半透明磨砂面板（标签栏除外）——在复杂瓦片上会变得难以阅读。
