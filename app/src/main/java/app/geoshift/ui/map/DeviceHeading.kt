package app.geoshift.ui.map

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The direction the top of the phone is pointing, in degrees clockwise from magnetic north
 * (0 = north, 90 = east), so a physical compass needle pointing to North rotates by `-heading`.
 * Uses TYPE_ROTATION_VECTOR when available, with automatic fallback to TYPE_GEOMAGNETIC_ROTATION_VECTOR
 * or Accelerometer + Magnetometer.
 */
@Composable
fun rememberDeviceHeading(): State<Float?> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val heading = remember { mutableStateOf<Float?>(null) }
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }

    DisposableEffect(lifecycle, sensorManager) {
        if (sensorManager == null) {
            heading.value = null
            return@DisposableEffect onDispose { }
        }
        val rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        val accelSensor = if (rotSensor == null) sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
        val magSensor = if (rotSensor == null) sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) else null

        if (rotSensor == null && (accelSensor == null || magSensor == null)) {
            heading.value = null
            return@DisposableEffect onDispose { }
        }

        val rotation = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        var hasGravity = false
        var hasMag = false
        var smoothSin = Float.NaN
        var smoothCos = Float.NaN
        val alpha = 0.16f

        fun publishFromMatrix() {
            val display = displayRotation(context)
            val (axisX, axisY) = when (display) {
                Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
            }
            SensorManager.remapCoordinateSystem(rotation, axisX, axisY, remapped)
            SensorManager.getOrientation(remapped, orientation)
            val azimuth = orientation[0]
            val s = sin(azimuth)
            val c = cos(azimuth)
            if (smoothSin.isNaN()) {
                smoothSin = s
                smoothCos = c
            } else {
                smoothSin += alpha * (s - smoothSin)
                smoothCos += alpha * (c - smoothCos)
            }
            val deg = Math.toDegrees(atan2(smoothSin, smoothCos).toDouble()).toFloat()
            heading.value = (deg + 360f) % 360f
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotation, event.values)
                        publishFromMatrix()
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        System.arraycopy(event.values, 0, gravity, 0, 3)
                        hasGravity = true
                        if (hasMag && SensorManager.getRotationMatrix(rotation, null, gravity, geomagnetic)) {
                            publishFromMatrix()
                        }
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(event.values, 0, geomagnetic, 0, 3)
                        hasMag = true
                        if (hasGravity && SensorManager.getRotationMatrix(rotation, null, gravity, geomagnetic)) {
                            publishFromMatrix()
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        var listening = false
        fun start() {
            if (!listening) {
                if (rotSensor != null) {
                    sensorManager.registerListener(listener, rotSensor, SensorManager.SENSOR_DELAY_UI)
                } else {
                    sensorManager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_UI)
                    sensorManager.registerListener(listener, magSensor, SensorManager.SENSOR_DELAY_UI)
                }
                listening = true
            }
        }
        fun stop() {
            if (listening) {
                sensorManager.unregisterListener(listener)
                listening = false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> start()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
        }
    }
    return heading
}

@Suppress("DEPRECATION")
private fun displayRotation(context: Context): Int =
    (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay?.rotation ?: Surface.ROTATION_0
