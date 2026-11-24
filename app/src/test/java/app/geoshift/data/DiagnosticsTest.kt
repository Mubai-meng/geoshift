package app.geoshift.data

import app.geoshift.xposed.Diag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    private val now = 1_000_000L

    @Test
    fun oldErrorIsNeverDowngradedToHistoricalReport() {
        val bootInstallError = Report(
            ok = false,
            time = now - 600_000L, // 10 minutes ago
            stage = "error",
            detail = "系统定位入口安装失败：IllegalStateException 定位入口缺失：report=1, last=0",
            process = "system_server",
        )
        val status = Diagnostics.classify(
            key = "location_system",
            report = bootInstallError,
            simActive = false,
            hideEnvironment = true,
            wifiEnabled = false,
            sessionStartedAt = 0L,
            nowMs = now,
        )
        assertEquals("出现错误", status.label)
        assertEquals(DiagnosticTone.ERROR, status.tone)
        assertTrue(status.isError)
    }

    @Test
    fun steadyConfigSyncDoesNotExpireAfterSixtySeconds() {
        val synced = Report(
            ok = true,
            time = now - 300_000L,
            stage = "synced",
            detail = "已收到配置：未模拟",
            process = "system_server",
        )
        val status = Diagnostics.classify(
            key = "config_system",
            report = synced,
            simActive = false,
            hideEnvironment = true,
            wifiEnabled = false,
            sessionStartedAt = 0L,
            nowMs = now,
        )
        assertEquals("已同步", status.label)
        assertEquals(DiagnosticTone.INFO, status.tone)
    }

    @Test
    fun distinguishesNotStartedFromHistoricalLeftoverAndActiveSession() {
        // 1. Not started yet: null report while sim is off -> 未启动
        val notStarted = Diagnostics.classify(
            key = "location_pump",
            report = null,
            simActive = false,
            hideEnvironment = true,
            wifiEnabled = false,
            sessionStartedAt = 0L,
            nowMs = now,
        )
        assertEquals("未启动", notStarted.label)

        // 2. Leftover report after simulation stopped -> 历史报告
        val leftover = Report(ok = true, time = now - 5_000L, stage = "injected", detail = "ok", process = "system_server")
        val afterStop = Diagnostics.classify(
            key = "location_pump",
            report = leftover,
            simActive = false,
            hideEnvironment = true,
            wifiEnabled = false,
            sessionStartedAt = now - 20_000L,
            nowMs = now,
        )
        assertEquals("历史报告", afterStop.label)
        assertEquals(DiagnosticTone.WARN, afterStop.tone)

        // 3. New session started after previous report -> still 历史报告 until new session emits
        val prevSession = Diagnostics.classify(
            key = "location_delivery",
            report = leftover,
            simActive = true,
            hideEnvironment = true,
            wifiEnabled = false,
            sessionStartedAt = now - 1_000L,
            nowMs = now,
        )
        assertEquals("历史报告", prevSession.label)

        // 4. Report within active session -> 已注入
        val activeNow = Diagnostics.classify(
            key = "location_pump",
            report = Report(ok = true, time = now - 500L, stage = "injected", detail = "ok", process = "system_server"),
            simActive = true,
            hideEnvironment = true,
            wifiEnabled = false,
            sessionStartedAt = now - 10_000L,
            nowMs = now,
        )
        assertEquals("已注入", activeNow.label)
        assertEquals(DiagnosticTone.LIVE, activeNow.tone)
    }

    @Test
    fun formatStackCapturesCauseAndTruncatesSafely() {
        val root = IllegalStateException("定位入口缺失：report=1, last=0")
        val wrapped = RuntimeException("install failed", root)
        val stack = Diag.formatStack(wrapped, maxFrames = 3, maxChars = 1200)
        assertTrue(stack.contains("java.lang.RuntimeException: install failed"))
        assertTrue(stack.contains("Caused by: java.lang.IllegalStateException: 定位入口缺失：report=1, last=0"))
        assertTrue(stack.length <= 1200)

        val truncated = Diag.formatStack(wrapped, maxFrames = 10, maxChars = 120)
        assertTrue(truncated.length <= 120)
    }
}
