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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The direction the top of the phone is pointing, in degrees clockwise from **magnetic** north
 * (0 = north, 90 = east), or `null` when the device has no rotation-vector sensor. Fused from the
 * accelerometer, magnetometer and gyroscope, so it is far steadier than a bare magnetometer. The
 * reading is screen-rotation compensated and low-pass filtered to stop the needle jittering.
 *
 * Only listens while the owning composable is in the STARTED state, so it costs nothing in the
 * background. Magnetic north is close enough for an on-screen compass; true north would need the
 * local declination, which is why this is not fed back into any simulated position.
 */
@Composable
fun rememberDeviceHeading(): State<Float?> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val heading = remember { mutableStateOf<Float?>(null) }
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    val sensor = remember { sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }

    DisposableEffect(lifecycle, sensorManager, sensor) {
        if (sensorManager == null || sensor == null) {
            heading.value = null
            return@DisposableEffect onDispose { }
        }
        val rotation = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        // Smooth on the unit circle so 359° → 1° averages through north, not through south.
        var smoothSin = Float.NaN
        var smoothCos = Float.NaN
        val alpha = 0.12f // lower = steadier, slower to settle

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotation, event.values)
                // Compensate for how the screen is turned relative to the device.
                val display = displayRotation(context)
                val (axisX, axisY) = when (display) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotation, axisX, axisY, remapped)
                SensorManager.getOrientation(remapped, orientation)
                val azimuth = orientation[0] // radians, -π..π
                val s = sin(azimuth)
                val c = cos(azimuth)
                if (smoothSin.isNaN()) {
                    smoothSin = s; smoothCos = c
                } else {
                    smoothSin += alpha * (s - smoothSin)
                    smoothCos += alpha * (c - smoothCos)
                }
                val deg = Math.toDegrees(atan2(smoothSin, smoothCos).toDouble()).toFloat()
                heading.value = (deg + 360f) % 360f
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        var listening = false
        fun start() {
            if (!listening) {
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
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
