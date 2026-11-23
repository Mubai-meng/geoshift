package app.geoshift.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Companion Xposed module for Geoshift: synthesizes STEP_COUNTER, STEP_DETECTOR, ACCELEROMETER,
 * and LINEAR_ACCELERATION inside user-chosen target apps in sync with Geoshift's live speed.
 *
 * Kept separate from Geoshift so Geoshift's static system-only scope stays 100% zero-injection.
 */
class MotionModule : XposedModule() {
    private var installed = false
    private var processName = ""

    private data class MotionState(val active: Boolean, val foot: Boolean, val speed: Double, val seed: Long, val atMs: Long) {
        val effectiveSpeed: Double get() = if (active && foot) speed else 0.0
    }
    private class TrackedQueue(val handles: ConcurrentHashMap<Int, Int> = ConcurrentHashMap())

    @Volatile private var motion = MotionState(false, false, 0.0, 0L, 0L)
    @Volatile private var appContext: Context? = null

    private val queues = Collections.synchronizedMap(WeakHashMap<Any, TrackedQueue>())
    private val queueHandlers = Collections.synchronizedMap(WeakHashMap<Any, Handler>())
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val accumulator = StepAccumulator()
    private val counterTracker = StepCounterTracker()
    private val dispatchingSelf = ThreadLocal<Boolean>()
    private var dispatchMethod: Method? = null
    private var sensorHandleMethod: Method? = null

    private val workerThread by lazy { HandlerThread("geoshift-motion").apply { start() } }
    private val worker by lazy { Handler(workerThread.looper) }
    @Volatile private var tickerRunning = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (installed) return
        if (param.packageName == "android" || param.packageName == SELF_GEOSHIFT || param.packageName == "app.geoshift.motion") return
        installed = true
        installSensorHooks(param.classLoader)
    }

    private fun installSensorHooks(loader: ClassLoader) {
        try {
            sensorHandleMethod = Sensor::class.java.getDeclaredMethod("getHandle").apply { isAccessible = true }
            val baseQueue = Class.forName("android.hardware.SystemSensorManager\$BaseEventQueue", false, loader)
            val sensorQueue = Class.forName("android.hardware.SystemSensorManager\$SensorEventQueue", false, loader)

            dispatchMethod = sensorQueue.declaredMethods.firstOrNull {
                it.name == "dispatchSensorEvent" && it.parameterTypes.size == 4
            }?.apply { isAccessible = true }

            // Capture each event queue's Looper so synthetic events are dispatched on the exact
            // thread the target app registered its SensorEventListener on.
            for (ctor in baseQueue.declaredConstructors) {
                hook(ctor).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    val res = chain.proceed()
                    val q = chain.thisObject
                    val looper = chain.args.firstOrNull { it is Looper } as? Looper
                    if (q != null && looper != null) {
                        queueHandlers[q] = Handler(looper)
                    }
                    res
                }
            }

            // Track active sensor registrations so we can actively drive STEP_COUNTER / STEP_DETECTOR
            // even when the phone is sitting still on a desk.
            for (m in baseQueue.declaredMethods) {
                if (Modifier.isAbstract(m.modifiers)) continue
                when (m.name) {
                    "addSensor" -> if (m.parameterTypes.firstOrNull() == Sensor::class.java) {
                        hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                            val ok = chain.proceed()
                            if (ok == true) {
                                val sensor = chain.args[0] as? Sensor
                                val q = chain.thisObject
                                if (sensor != null && q != null && isTrackedType(sensor.type)) {
                                    track(q, sensor)
                                }
                            }
                            ok
                        }
                    }
                    "removeSensor" -> if (m.parameterTypes.firstOrNull() == Sensor::class.java) {
                        hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                            val sensor = chain.args[0] as? Sensor
                            val q = chain.thisObject
                            if (sensor != null && q != null) untrack(q, sensor)
                            chain.proceed()
                        }
                    }
                    "dispose" -> {
                        hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                            chain.thisObject?.let { queues.remove(it) }
                            chain.proceed()
                        }
                    }
                }
            }

            // Intercept incoming hardware events: overlay gait waveform on accel, add offset on step counter.
            dispatchMethod?.let { dm ->
                hook(dm).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain ->
                    if (dispatchingSelf.get() == true) return@intercept chain.proceed()
                    val q = chain.thisObject
                    val handle = (chain.args[0] as? Int) ?: return@intercept chain.proceed()
                    val values = chain.args[1] as? FloatArray ?: return@intercept chain.proceed()
                    val type = q?.let { queues[it]?.handles?.get(handle) }
                    if (type != null) {
                        val state = motion
                        when (type) {
                            Sensor.TYPE_STEP_COUNTER -> {
                                if (values.isNotEmpty()) {
                                    val synth = accumulator.totalSyntheticSteps
                                    val adjusted = counterTracker.onHardware(values[0], synth)
                                    if (adjusted != values[0]) {
                                        val copy = values.clone()
                                        copy[0] = adjusted
                                        val nextArgs = chain.args.toTypedArray()
                                        nextArgs[1] = copy
                                        return@intercept chain.proceed(nextArgs)
                                    }
                                }
                            }
                            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION -> {
                                val effSpeed = state.effectiveSpeed
                                if (effSpeed > 0.0 && values.size >= 3) {
                                    val nowMs = SystemClock.elapsedRealtime()
                                    val delta = CadenceModel.gaitDelta(accumulator.phaseRad, effSpeed, state.seed, nowMs / 1000.0)
                                    if (delta[0] != 0f || delta[1] != 0f || delta[2] != 0f) {
                                        val copy = values.clone()
                                        copy[0] += delta[0]
                                        copy[1] += delta[1]
                                        copy[2] += delta[2]
                                        val nextArgs = chain.args.toTypedArray()
                                        nextArgs[1] = copy
                                        return@intercept chain.proceed(nextArgs)
                                    }
                                }
                            }
                        }
                    }
                    chain.proceed()
                }
            }
        } catch (_: Throwable) {
            // Never crash the target app if a ROM renames internal SensorEventQueue methods.
        }
    }

    private fun isTrackedType(type: Int): Boolean =
        type == Sensor.TYPE_STEP_COUNTER ||
            type == Sensor.TYPE_STEP_DETECTOR ||
            type == Sensor.TYPE_ACCELEROMETER ||
            type == Sensor.TYPE_LINEAR_ACCELERATION

    private fun handleOf(sensor: Sensor): Int =
        runCatching { (sensorHandleMethod?.invoke(sensor) as? Int) ?: -1 }.getOrDefault(-1)

    private fun track(queue: Any, sensor: Sensor) {
        val h = handleOf(sensor)
        if (h < 0) return
        synchronized(queues) {
            queues.getOrPut(queue) { TrackedQueue() }.handles[h] = sensor.type
        }
        ensureTicker()
    }

    private fun untrack(queue: Any, sensor: Sensor) {
        val h = handleOf(sensor)
        if (h < 0) return
        synchronized(queues) {
            val tq = queues[queue] ?: return
            tq.handles.remove(h)
            if (tq.handles.isEmpty()) queues.remove(queue)
        }
    }

    private fun hasActiveListeners(): Boolean = synchronized(queues) { queues.isNotEmpty() }

    private fun ensureTicker() {
        if (tickerRunning) return
        tickerRunning = true
        worker.post(tickRunnable)
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!hasActiveListeners()) {
                tickerRunning = false
                return
            }
            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - motion.atMs >= POLL_MS) {
                motion = queryGeoshift(nowMs)
            }
            val st = motion
            val effSpeed = st.effectiveSpeed
            val steps = accumulator.advance(nowMs, effSpeed, st.seed)
            if (steps > 0) emitSteps(steps)
            val delayMs = if (effSpeed > 0.0 && CadenceModel.cadenceHz(effSpeed, st.seed, nowMs / 1000.0) > 0.0) STEP_TICK_MS else IDLE_TICK_MS
            worker.postDelayed(this, delayMs)
        }
    }

    private fun emitSteps(newSteps: Int) {
        val dm = dispatchMethod ?: return
        val snapshot = synchronized(queues) {
            queues.entries.map { (q, tq) -> q to tq.handles.entries.map { it.key to it.value } }
        }
        if (snapshot.isEmpty()) return
        val ts = SystemClock.elapsedRealtimeNanos()
        val total = counterTracker.onSynthetic(accumulator.totalSyntheticSteps)

        for ((queue, handles) in snapshot) {
            val targetHandler = queueHandlers[queue] ?: mainHandler
            targetHandler.post {
                val activeHandles = synchronized(queues) { queues[queue]?.handles } ?: return@post
                dispatchingSelf.set(true)
                try {
                    for ((handle, type) in handles) {
                        if (!activeHandles.containsKey(handle)) continue
                        when (type) {
                            Sensor.TYPE_STEP_DETECTOR -> repeat(newSteps) {
                                runCatching { dm.invoke(queue, handle, floatArrayOf(1.0f), SensorManager.SENSOR_STATUS_ACCURACY_HIGH, ts) }
                            }
                            Sensor.TYPE_STEP_COUNTER -> {
                                runCatching { dm.invoke(queue, handle, floatArrayOf(total), SensorManager.SENSOR_STATUS_ACCURACY_HIGH, ts) }
                            }
                        }
                    }
                } finally {
                    dispatchingSelf.remove()
                }
            }
        }
    }

    private fun queryGeoshift(nowMs: Long): MotionState {
        val ctx = currentContext() ?: return MotionState(false, false, 0.0, 0L, nowMs)
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return MotionState(false, false, 0.0, 0L, nowMs)
        val extras = Bundle().apply { putString("k", MOTION_KEY) }
        val ok = runCatching { lm.sendExtraCommand(COMMAND, "MOTION", extras) }.getOrDefault(false)
        if (!ok || !extras.getBoolean("active", false)) return MotionState(false, false, 0.0, 0L, nowMs)
        return MotionState(
            active = true,
            foot = extras.getBoolean("foot", true),
            speed = extras.getDouble("speed", 0.0),
            seed = extras.getLong("seed", 0L),
            atMs = nowMs,
        )
    }

    private fun currentContext(): Context? {
        appContext?.let { return it }
        return try {
            val at = Class.forName("android.app.ActivityThread")
            (at.getMethod("currentApplication").invoke(null) as? Context)?.also { appContext = it }
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        const val COMMAND = "geoshift"
        const val MOTION_KEY = "geoshift.motion.v1"
        const val SELF_GEOSHIFT = "app.geoshift"
        const val POLL_MS = 1000L
        const val STEP_TICK_MS = 100L
        const val IDLE_TICK_MS = 1000L
    }
}
