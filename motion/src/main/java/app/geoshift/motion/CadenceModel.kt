package app.geoshift.motion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Pure-Kotlin gait & cadence model driven by Geoshift's live simulated speed (m/s).
 *
 * - Stationary / paused / red light (< 0.25 m/s) -> 0 Hz (no steps)
 * - Walking (0.25 .. 2.0 m/s) -> ~1.45 .. 2.15 Hz (87 .. 129 steps/min)
 * - Running (2.0 .. 5.5 m/s)  -> ~2.15 .. 3.20 Hz (129 .. 192 steps/min)
 * - Vehicle / flight (> 5.5 m/s, ~20 km/h) -> 0 Hz (driving does not generate footsteps)
 */
object CadenceModel {
    const val MIN_SPEED_MPS = 0.25
    const val WALK_MAX_MPS = 2.0
    const val MAX_RUN_MPS = 5.5

    /** Step frequency in Hz (steps per second), with gentle ±4% organic variation. */
    fun cadenceHz(speedMps: Double, seed: Long, timeSec: Double): Double {
        if (!speedMps.isFinite() || speedMps < MIN_SPEED_MPS || speedMps > MAX_RUN_MPS) return 0.0
        val base = if (speedMps <= WALK_MAX_MPS) {
            val t = (speedMps - MIN_SPEED_MPS) / (WALK_MAX_MPS - MIN_SPEED_MPS)
            1.45 + 0.70 * t
        } else {
            val t = (speedMps - WALK_MAX_MPS) / (MAX_RUN_MPS - WALK_MAX_MPS)
            2.15 + 1.05 * t
        }
        val phaseOffset = ((seed and 0xFFFFL) / 65536.0) * 2.0 * PI
        val wobble = 1.0 + 0.035 * sin(timeSec * 0.37 + phaseOffset) + 0.015 * cos(timeSec * 0.83 + phaseOffset * 1.7)
        return (base * wobble).coerceIn(1.2, 3.4)
    }

    /**
     * Synthetic 3-axis gait acceleration delta (m/s²) to add onto accelerometer / linear-acceleration
     * readings at [phaseRad] (where 2π = 1 full footstep).
     */
    fun gaitDelta(phaseRad: Double, speedMps: Double, seed: Long, timeSec: Double): FloatArray {
        if (!speedMps.isFinite() || speedMps < MIN_SPEED_MPS || speedMps > MAX_RUN_MPS) {
            return floatArrayOf(0f, 0f, 0f)
        }
        val intensity = ((speedMps - MIN_SPEED_MPS) / (MAX_RUN_MPS - MIN_SPEED_MPS)).coerceIn(0.0, 1.0)
        // Vertical bounce: ~1.3 m/s² for easy walk up to ~4.2 m/s² for fast run.
        val ampZ = 1.3 + 2.9 * intensity
        val vertical = ampZ * (sin(phaseRad) + 0.28 * sin(2.0 * phaseRad + 0.55))
        // Left/right sway oscillates at half the step frequency (one full cycle per stride = 2 steps).
        val ampX = 0.35 + 0.65 * intensity
        val lateral = ampX * sin(phaseRad * 0.5 + 0.3)
        // Fore/aft braking & push-off.
        val ampY = 0.45 + 0.85 * intensity
        val foreAft = ampY * cos(phaseRad + 0.25)
        // Subtle deterministic micro-variation so consecutive cycles are never identical.
        val p = ((seed ushr 16) and 0xFFFFL) / 65536.0 * 2.0 * PI
        val n = 0.08 * sin(timeSec * 5.7 + p)
        return floatArrayOf((lateral + n).toFloat(), (foreAft - n).toFloat(), (vertical + n).toFloat())
    }
}

/**
 * Integrates cadence over monotonic time so step counter, step detector, and accelerometer waveform
 * stay phase-locked with zero drift.
 */
class StepAccumulator {
    private var lastMs = -1L
    private var currentDayKey = -1L
    @Volatile private var stepPhase = 0.0 // fractional steps since start; integer crossings emit steps
    @Volatile var totalSyntheticSteps: Long = 0L
        private set
    /** Synthetic steps accumulated in the current local calendar day (resets at midnight for daily providers). */
    @Volatile var todaySyntheticSteps: Long = 0L
        private set

    /** Current gait phase in radians (0 .. 4π so half-frequency stride sway stays continuous). */
    val phaseRad: Double get() = (stepPhase % 2.0) * 2.0 * PI

    /** Advances to [nowMs] at [speedMps] and returns how many new footsteps occurred in this slice. */
    @Synchronized
    fun advance(nowMs: Long, speedMps: Double, seed: Long, dayKey: Long = 0L): Int {
        if (dayKey > 0L) {
            if (currentDayKey > 0L && currentDayKey != dayKey) {
                todaySyntheticSteps = 0L
            }
            currentDayKey = dayKey
        }
        if (lastMs < 0L) {
            lastMs = nowMs
            return 0
        }
        val dtMs = (nowMs - lastMs).coerceIn(0L, 2000L)
        lastMs = nowMs
        if (dtMs == 0L) return 0
        val hz = CadenceModel.cadenceHz(speedMps, seed, nowMs / 1000.0)
        if (hz <= 0.0) return 0
        val prevFloor = floor(stepPhase).toLong()
        stepPhase += hz * (dtMs / 1000.0)
        val nextFloor = floor(stepPhase).toLong()
        val delta = (nextFloor - prevFloor).toInt().coerceAtLeast(0)
        totalSyntheticSteps += delta
        todaySyntheticSteps += delta
        return delta
    }
}

/**
 * Pure-Kotlin helpers for process filtering and MIUI daily StepsProvider query scoping so historical
 * step queries are never polluted with today's synthetic steps.
 */
object MiuiStepsFilter {
    private val AUXILIARY_SUFFIXES = listOf(
        ":push", ":pushservice", ":sandboxed_process", ":webview",
        ":crash", ":daemon", ":tinker", ":hotfix", ":isolated",
    )

    fun isAuxiliaryProcess(processName: String): Boolean {
        val lower = processName.lowercase()
        return AUXILIARY_SUFFIXES.any { lower.contains(it) }
    }

    /**
     * Decides whether today's synthetic steps should be applied to a MIUI StepsProvider query result.
     * - If the cursor has rows and the last row's `_end_time` is before [todayStartMs], it is a
     *   historical query (yesterday / earlier) -> returns false.
     * - If the cursor is empty, inspect [selection]/[selectionArgs]: when the query explicitly
     *   bounds `_begin_time` or `_end_time` strictly below [todayStartMs], returns false.
     */
    fun shouldApplyToCursor(
        rowCount: Int,
        lastEndTimeMs: Long?,
        selection: String?,
        selectionArgs: Array<out String>?,
        todayStartMs: Long,
    ): Boolean {
        if (rowCount > 0 && lastEndTimeMs != null && lastEndTimeMs > 0L) {
            return lastEndTimeMs >= todayStartMs
        }
        if (rowCount == 0 && !selection.isNullOrBlank()) {
            val sel = selection.lowercase()
            // Check explicit numeric upper bounds in selectionArgs (e.g. "_end_time < ?" or "_begin_time <= ?").
            val hasUpperBound = sel.contains("_end_time <") || sel.contains("_end_time<") ||
                sel.contains("_begin_time <") || sel.contains("_begin_time<")
            if (hasUpperBound && selectionArgs != null) {
                val timestamps = selectionArgs.mapNotNull { it.trim().toLongOrNull() }.filter { it > 1_000_000_000_000L }
                if (timestamps.isNotEmpty() && timestamps.max() <= todayStartMs) return false
            }
            // Check inline numeric upper bounds (e.g. "_end_time < 1700000000000").
            val inlineUpper = Regex("""_(?:begin|end)_time\s*<=?\s*(\d{12,})""").findAll(sel)
                .mapNotNull { it.groupValues[1].toLongOrNull() }.toList()
            if (inlineUpper.isNotEmpty() && inlineUpper.max() <= todayStartMs) return false
        }
        return true
    }
}

/**
 * Keeps STEP_COUNTER strictly monotonic without sudden baseline jumps if synthetic steps start
 * arriving before the first hardware step-counter event.
 */
class StepCounterTracker {
    private var firstHardware = -1f
    private var maxHardware = 0f
    private var origin = -1f
    private var lastEmitted = -1f

    @Synchronized
    fun onHardware(raw: Float, syntheticTotal: Long): Float {
        if (firstHardware < 0f) {
            firstHardware = raw
            maxHardware = raw
            if (origin < 0f) origin = raw
        } else if (raw > maxHardware) {
            maxHardware = raw
        }
        return compute(syntheticTotal)
    }

    @Synchronized
    fun onSynthetic(syntheticTotal: Long): Float {
        if (origin < 0f) origin = 0f
        return compute(syntheticTotal)
    }

    private fun compute(syntheticTotal: Long): Float {
        val hwDelta = if (firstHardware >= 0f) (maxHardware - firstHardware).coerceAtLeast(0f) else 0f
        val value = maxOf(lastEmitted, origin + hwDelta + syntheticTotal.toFloat())
        lastEmitted = value
        return value
    }
}
