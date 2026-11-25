package app.geoshift.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenceModelTest {
    @Test
    fun stationaryAndVehicleProduceZeroCadence() {
        assertEquals(0.0, CadenceModel.cadenceHz(0.0, 42L, 10.0), 1e-9)
        assertEquals(0.0, CadenceModel.cadenceHz(0.15, 42L, 10.0), 1e-9)
        assertEquals(0.0, CadenceModel.cadenceHz(8.0, 42L, 10.0), 1e-9)
        assertEquals(0.0, CadenceModel.cadenceHz(Double.NaN, 42L, 10.0), 1e-9)
    }

    @Test
    fun walkingAndRunningCadenceAreRealistic() {
        val walkHz = CadenceModel.cadenceHz(1.4, 123L, 5.0)
        assertTrue("walking cadence $walkHz Hz should be ~1.6-2.1 Hz", walkHz in 1.5..2.2)

        val runHz = CadenceModel.cadenceHz(3.0, 123L, 5.0)
        assertTrue("running cadence $runHz Hz should be ~2.2-2.9 Hz", runHz in 2.2..3.0)
        assertTrue("running cadence should exceed walking cadence", runHz > walkHz)
    }

    @Test
    fun accumulatorIntegratesStepsMonotonicallyAndPausesAtZeroSpeed() {
        val acc = StepAccumulator()
        assertEquals(0, acc.advance(1000L, 1.4, 7L))
        var steps = 0
        for (i in 1..100) {
            steps += acc.advance(1000L + i * 100L, 1.4, 7L)
        }
        // 10 seconds of brisk walking (~1.8-2.0 Hz) -> ~16-22 steps.
        assertTrue("expected 15..23 steps in 10s, got $steps", steps in 15..23)
        assertEquals(steps.toLong(), acc.totalSyntheticSteps)

        // Stopped at a red light for 5 seconds -> 0 new steps.
        var stoppedSteps = 0
        for (i in 1..50) {
            stoppedSteps += acc.advance(11000L + i * 100L, 0.0, 7L)
        }
        assertEquals(0, stoppedSteps)
        assertEquals(steps.toLong(), acc.totalSyntheticSteps)
    }

    @Test
    fun gaitDeltaIsZeroWhenStillAndBoundedWhenMoving() {
        val still = CadenceModel.gaitDelta(1.2, 0.0, 99L, 3.0)
        assertEquals(0f, still[0], 1e-6f)
        assertEquals(0f, still[1], 1e-6f)
        assertEquals(0f, still[2], 1e-6f)

        val walk = CadenceModel.gaitDelta(Math.PI / 2, 1.4, 99L, 3.0)
        assertTrue("vertical walk bounce should be non-trivial", kotlin.math.abs(walk[2]) > 0.5f)
        assertTrue("vertical walk bounce should stay realistic", kotlin.math.abs(walk[2]) < 6.0f)
    }

    @Test
    fun stepCounterTrackerAvoidsBaselineJump() {
        // Case 1: Hardware arrives first -> preserves real boot count and adds synthetic steps.
        val t1 = StepCounterTracker()
        assertEquals(8500f, t1.onHardware(8500f, 0L), 1e-4f)
        assertEquals(8510f, t1.onSynthetic(10L), 1e-4f)
        assertEquals(8512f, t1.onHardware(8502f, 10L), 1e-4f)

        // Case 2: Synthetic arrives before hardware -> later hardware baseline does not spike counter.
        val t2 = StepCounterTracker()
        assertEquals(10f, t2.onSynthetic(10L), 1e-4f)
        assertEquals(10f, t2.onHardware(8500f, 10L), 1e-4f)
        assertEquals(13f, t2.onHardware(8503f, 10L), 1e-4f)
        assertEquals(18f, t2.onSynthetic(15L), 1e-4f)
    }

    @Test
    fun dayRolloverResetsTodayStepsWhileKeepingTotalMonotonic() {
        val acc = StepAccumulator()
        val day1 = 2026330L
        val day2 = 2026331L
        acc.advance(1_000L, 1.5, 7L, dayKey = day1)
        for (i in 1..50) acc.advance(1_000L + i * 100L, 1.5, 7L, dayKey = day1)
        val day1Steps = acc.todaySyntheticSteps
        assertTrue("expected > 0 steps on day 1, got $day1Steps", day1Steps > 0L)
        assertEquals(day1Steps, acc.totalSyntheticSteps)

        // Next local calendar day: todaySyntheticSteps resets, totalSyntheticSteps keeps growing.
        for (i in 1..20) acc.advance(6_000L + i * 100L, 1.5, 7L, dayKey = day2)
        assertTrue(acc.todaySyntheticSteps in 1L until day1Steps)
        assertEquals(day1Steps + acc.todaySyntheticSteps, acc.totalSyntheticSteps)
    }

    @Test
    fun miuiStepsFilterSkipsAuxiliaryProcessesAndHistoricalQueries() {
        assertTrue(MiuiStepsFilter.isAuxiliaryProcess("com.tencent.mm:push"))
        assertTrue(MiuiStepsFilter.isAuxiliaryProcess("com.example.app:sandboxed_process0"))
        assertTrue(!MiuiStepsFilter.isAuxiliaryProcess("com.tencent.mm"))

        val todayStart = 1_795_622_400_000L
        val yesterdayEnd = todayStart - 3_600_000L
        val todayEnd = todayStart + 3_600_000L

        // Cursor containing yesterday's rows -> do not pollute historical query.
        assertTrue(!MiuiStepsFilter.shouldApplyToCursor(3, yesterdayEnd, null, null, todayStart))
        // Cursor containing today's rows -> apply.
        assertTrue(MiuiStepsFilter.shouldApplyToCursor(3, todayEnd, null, null, todayStart))
        // Empty cursor with explicit yesterday selectionArgs -> do not synthesize row into yesterday.
        assertTrue(!MiuiStepsFilter.shouldApplyToCursor(0, null, "_begin_time >= ? AND _end_time < ?", arrayOf((todayStart - 86_400_000L).toString(), todayStart.toString()), todayStart))
        // Empty cursor querying today -> synthesize row.
        assertTrue(MiuiStepsFilter.shouldApplyToCursor(0, null, "_begin_time >= ?", arrayOf(todayStart.toString()), todayStart))
    }
}
