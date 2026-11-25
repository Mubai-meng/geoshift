package app.geoshift.data

/** Semantic bucket for one row in the system diagnostics panel. */
enum class DiagnosticTone { ERROR, LIVE, INFO, WARN, MUTED }

data class DiagnosticStatus(val label: String, val tone: DiagnosticTone) {
    val isError get() = tone == DiagnosticTone.ERROR
    val isHealthy get() = label in setOf("已命中", "已注入", "已安装", "已同步", "无")
}

/**
 * Classifies one diagnostic entry while strictly separating:
 *   1. Real errors (never downgraded to "历史报告" just because they happened >60s ago).
 *   2. Steady states ("已安装", "已同步", "已忽略") that remain valid without periodic heartbeats.
 *   3. Current active-session runtime states ("已注入", "已命中", "等待中").
 *   4. Not-started state ("未启动") when simulation is off.
 *   5. Historical reports ("历史报告") left over from an earlier simulation session.
 */
object Diagnostics {
    private const val HEARTBEAT_STALE_MS = 60_000L
    private val RUNTIME_ONLY_KEYS = setOf("location_pump", "location_delivery")
    private val RUNTIME_STAGES = setOf("injected", "read", "waiting")
    private val STEADY_STAGES = setOf("installed", "synced", "stale")

    fun classify(
        key: String,
        report: Report?,
        simActive: Boolean,
        hideEnvironment: Boolean,
        wifiEnabled: Boolean,
        sessionStartedAt: Long,
        nowMs: Long,
    ): DiagnosticStatus {
        // 1. Real errors ALWAYS stay errors, regardless of age.
        if (report != null && (!report.ok || report.stage == "error")) {
            return DiagnosticStatus("出现错误", DiagnosticTone.ERROR)
        }

        // 2. Explicitly disabled optional environment shields.
        if (key == "wifi" && !hideEnvironment && !wifiEnabled) {
            return DiagnosticStatus("未启用", DiagnosticTone.MUTED)
        }
        if (key == "bluetooth" && !hideEnvironment) {
            return DiagnosticStatus("未启用", DiagnosticTone.MUTED)
        }

        // 3. No report yet.
        if (report == null) {
            return when {
                key.startsWith("missing") -> DiagnosticStatus("无", DiagnosticTone.MUTED)
                key == "motion" -> DiagnosticStatus("未联动", DiagnosticTone.MUTED)
                key in RUNTIME_ONLY_KEYS && !simActive -> DiagnosticStatus("未启动", DiagnosticTone.MUTED)
                else -> DiagnosticStatus("暂无报告", DiagnosticTone.MUTED)
            }
        }

        // 4. Steady-state reports do not expire after 60s.
        if (report.stage in STEADY_STAGES) {
            return when (report.stage) {
                "installed" -> DiagnosticStatus("已安装", DiagnosticTone.INFO)
                "synced" -> DiagnosticStatus("已同步", DiagnosticTone.INFO)
                "stale" -> DiagnosticStatus("已忽略", DiagnosticTone.MUTED)
                else -> DiagnosticStatus("已安装", DiagnosticTone.INFO)
            }
        }

        // 5. Runtime activity reports: distinguish current session vs historical leftover.
        if (report.stage in RUNTIME_STAGES) {
            val isHistorical = !simActive || (sessionStartedAt > 0L && report.time < sessionStartedAt)
            if (isHistorical) {
                return DiagnosticStatus("历史报告", DiagnosticTone.WARN)
            }
            if (key == "location_pump" && nowMs - report.time > HEARTBEAT_STALE_MS) {
                return DiagnosticStatus("等待中", DiagnosticTone.WARN)
            }
            return when (report.stage) {
                "read" -> DiagnosticStatus("已命中", DiagnosticTone.LIVE)
                "injected" -> DiagnosticStatus("已注入", DiagnosticTone.LIVE)
                "waiting" -> DiagnosticStatus("等待中", DiagnosticTone.WARN)
                else -> DiagnosticStatus("已安装", DiagnosticTone.INFO)
            }
        }

        return DiagnosticStatus("已安装", DiagnosticTone.INFO)
    }
}
