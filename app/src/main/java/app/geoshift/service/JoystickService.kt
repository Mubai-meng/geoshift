package app.geoshift.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import app.geoshift.core.Geo
import app.geoshift.data.Geoshift
import app.geoshift.data.SpeedMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Floating overlay joystick that moves the simulated point and can itself be dragged anywhere on screen:
 * - Hold & push the inner stick to move the simulated location continuously.
 * - Hold & drag the outer ring / top handle (or long-press the center knob still) to move the floating window.
 * - Tap top-left (−) to collapse into a compact draggable floating ball; tap the ball to expand.
 * - Tap bottom-right (■) while simulating / running a route to stop simulation immediately.
 * - Tap bottom-left (⏸/▶) while running a route to pause or resume route playback.
 * - Tap the bottom speed badge to cycle speed modes (步行 / 跑步 / 骑行 / 驾车 / 飞行) on the fly.
 */
class JoystickService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private val move = Runnable { step() }
    private lateinit var engine: Geoshift
    private var windows: WindowManager? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var stick: Stick? = null
    /** Deflection, each in [-1, 1]; (0, 0) is idle. */
    private var dx = 0f
    private var dy = 0f
    private var moving = false
    private var stepping = false
    private var collapsed = false

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("geoshift-joystick").apply { start() }
        worker = Handler(workerThread.looper)
        engine = Geoshift.get(this)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL, "摇杆悬浮窗", NotificationManager.IMPORTANCE_LOW))
        val close = PendingIntent.getService(this, 2, Intent(this, JoystickService::class.java).setAction(ACTION_CLOSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(NOTIFICATION_ID, Notification.Builder(this, CHANNEL)
            .setSmallIcon(app.geoshift.R.drawable.ic_stat_geoshift)
            .setContentTitle("Geoshift 摇杆")
            .setContentText("推内圈控制移动；右下角可启停模拟，路线中左下角可暂停/继续")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "关闭", close).build())
            .build())
        uiScope.launch {
            combine(engine.store.runtime, engine.store.settings) { r, s ->
                Triple(r.enabled, r.routeMode to r.paused, s.speedLabel)
            }.collect {
                stick?.invalidate()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        if (intent?.action == ACTION_CLOSE) { stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) {
            engine.store.updateRuntime(persist = false) { it.copy(joystick = false, error = "尚未授予悬浮窗权限。") }
            engine.store.log("悬浮窗：尚未授予悬浮窗权限。")
            stopSelf()
            return START_NOT_STICKY
        }
        worker.post {
            try {
                engine.ensureReady()
                handler.post {
                    if (stick == null) show()
                    engine.store.updateRuntime(persist = false) { it.copy(joystick = true) }
                }
            } catch (e: Exception) {
                engine.store.updateRuntime(persist = false) { it.copy(joystick = false, error = e.message.orEmpty()) }
                engine.store.log("悬浮窗：${e.message}")
                handler.post { stopSelf() }
            }
        }
        return START_NOT_STICKY
    }

    private fun windowSizePx(isCollapsed: Boolean): Int {
        val dp = if (isCollapsed) COLLAPSED_DP else EXPANDED_DP
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun show() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windows = wm
        val dm = resources.displayMetrics
        val size = windowSizePx(collapsed)
        val defaultX = (16 * dm.density).toInt()
        val defaultY = ((dm.heightPixels - size) / 2).coerceAtLeast(0)
        val maxX = (dm.widthPixels - size).coerceAtLeast(0)
        val maxY = (dm.heightPixels - size).coerceAtLeast(0)

        val p = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (savedX ?: defaultX).coerceIn(0, maxX)
            y = (savedY ?: defaultY).coerceIn(0, maxY)
        }
        windowParams = p
        val view = Stick(this)
        stick = view
        wm.addView(view, p)
    }

    private fun startStepping() {
        if (!stepping) {
            stepping = true
            handler.removeCallbacks(move)
            handler.post(move)
        }
    }

    private fun stopStepping() {
        dx = 0f
        dy = 0f
        if (stepping) {
            stepping = false
            handler.removeCallbacks(move)
        }
        if (moving) {
            moving = false
            worker.post { runCatching { engine.joystickStep(0.0, 0.0, 0.0) } }
        }
    }

    private fun setCollapsed(target: Boolean) {
        if (collapsed == target) return
        val wm = windows ?: return
        val v = stick ?: return
        val p = windowParams ?: return
        val oldSize = p.width
        val newSize = windowSizePx(target)
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - newSize).coerceAtLeast(0)
        val maxY = (dm.heightPixels - newSize).coerceAtLeast(0)
        // Keep the center anchored when toggling between expanded and collapsed ball
        p.x = (p.x + (oldSize - newSize) / 2).coerceIn(0, maxX)
        p.y = (p.y + (oldSize - newSize) / 2).coerceIn(0, maxY)
        p.width = newSize
        p.height = newSize
        collapsed = target
        stopStepping()
        savedX = p.x
        savedY = p.y
        runCatching { wm.updateViewLayout(v, p) }
        v.invalidate()
    }

    private fun cycleSpeedMode() {
        val modes = SpeedMode.entries
        val current = engine.store.settings.value.speedMode
        val next = modes[(current.ordinal + 1) % modes.size]
        engine.store.updateSettings { it.copy(speedMode = next, customSpeedKmh = 0.0) }
        stick?.invalidate()
    }

    private fun moveWindowTo(newX: Int, newY: Int) {
        val wm = windows ?: return
        val v = stick ?: return
        val p = windowParams ?: return
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - p.width).coerceAtLeast(0)
        val maxY = (dm.heightPixels - p.height).coerceAtLeast(0)
        p.x = newX.coerceIn(0, maxX)
        p.y = newY.coerceIn(0, maxY)
        savedX = p.x
        savedY = p.y
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** Moves the point by one step of travel in the joystick direction (runs only while deflected). */
    private fun step() {
        if (stick == null || !stepping) return
        val length = hypot(dx, dy).toDouble()
        if (length > 0.1) {
            val heading = Geo.normalizeBearing(Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())))
            val metres = engine.store.settings.value.speed * min(1.0, length) * STEP_MS / 1000.0
            val speed = metres * 1000.0 / STEP_MS
            moving = true
            // Report the speed actually travelled off the main thread so dragging never drops frames.
            worker.post {
                try { engine.joystickStep(metres, heading, speed) } catch (e: Exception) { engine.store.log("摇杆：${e.message}") }
            }
        } else if (moving) {
            moving = false
            worker.post {
                try { engine.joystickStep(0.0, 0.0, 0.0) } catch (e: Exception) { engine.store.log("摇杆：${e.message}") }
            }
        }
        if (stepping) handler.postDelayed(move, STEP_MS)
    }

    override fun onDestroy() {
        uiScope.cancel()
        handler.removeCallbacksAndMessages(null)
        worker.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        stick?.let { s -> runCatching { windows?.removeView(s) } }
        stick = null
        windowParams = null
        engine.store.updateRuntime { it.copy(joystick = false, speed = 0.0) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ViewConstructor")
    private inner class Stick(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }
        private val pillRect = RectF()
        private val playPath = android.graphics.Path()
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

        private var mode = MODE_NONE
        private var downX = 0f
        private var downY = 0f
        private var downRawX = 0f
        private var downRawY = 0f
        private var maxMoveFromDown = 0f
        private var startWinX = 0
        private var startWinY = 0

        private fun vibrate(constant: Int) {
            if (engine.store.settings.value.haptics) {
                runCatching { performHapticFeedback(constant) }
            }
        }

        private val longPressRunnable = Runnable {
            if (!collapsed && mode == MODE_STICK && maxMoveFromDown < touchSlop * 0.6f && hypot(dx, dy) < 0.14f) {
                mode = MODE_DRAG_WINDOW
                stopStepping()
                vibrate(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
        }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val dragging = mode == MODE_DRAG_WINDOW

            val r = engine.store.runtime.value

            if (collapsed) {
                val cx = w * 0.5f
                val cy = h * 0.5f
                val radius = w * 0.44f
                val accentColor = when {
                    dragging -> MINT
                    r.enabled && r.paused -> WARNING_COLOR
                    r.enabled -> MINT
                    else -> ACCENT
                }
                paint.style = Paint.Style.FILL
                paint.color = if (dragging) OUTER_FACE_DRAG else OUTER_FACE
                c.drawCircle(cx, cy, radius, paint)

                paint.style = Paint.Style.STROKE
                paint.strokeWidth = w * 0.04f
                paint.color = accentColor
                c.drawCircle(cx, cy, radius, paint)

                paint.style = Paint.Style.FILL
                paint.color = accentColor
                c.drawCircle(cx, cy, radius * 0.48f, paint)
                paint.color = 0xFFFFFFFF.toInt()
                c.drawCircle(cx, cy, radius * 0.18f, paint)
                return
            }

            val cx = w * 0.5f
            val cy = h * 0.50f
            val outerR = w * OUTER_RING
            val innerR = w * INNER_RING

            // 1. Outer floating glass disc (drag zone)
            paint.style = Paint.Style.FILL
            paint.color = if (dragging) OUTER_FACE_DRAG else OUTER_FACE
            c.drawCircle(cx, cy, outerR, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w * 0.015f
            paint.color = if (dragging) MINT else OUTER_BORDER
            c.drawCircle(cx, cy, outerR, paint)

            // 2. Top drag handle pill (visual cue for holding & dragging the window)
            val pillW = w * 0.22f
            val pillH = h * 0.030f
            val pillY = cy - outerR + h * 0.062f
            pillRect.set(cx - pillW / 2f, pillY - pillH / 2f, cx + pillW / 2f, pillY + pillH / 2f)
            paint.style = Paint.Style.FILL
            paint.color = if (dragging) MINT else HANDLE_COLOR
            c.drawRoundRect(pillRect, pillH / 2f, pillH / 2f, paint)

            // 3. Inner joystick track
            paint.style = Paint.Style.FILL
            paint.color = INNER_FACE
            c.drawCircle(cx, cy, innerR, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w * 0.012f
            paint.color = ACCENT_BORDER
            c.drawCircle(cx, cy, innerR, paint)

            // Cardinal direction ticks
            val tickLen = innerR * 0.12f
            paint.strokeWidth = w * 0.01f
            paint.color = TICK_COLOR
            c.drawLine(cx, cy - innerR + 2f, cx, cy - innerR + tickLen, paint)
            c.drawLine(cx, cy + innerR - 2f, cx, cy + innerR - tickLen, paint)
            c.drawLine(cx - innerR + 2f, cy, cx - innerR + tickLen, cy, paint)
            c.drawLine(cx + innerR - 2f, cy, cx + innerR - tickLen, cy, paint)

            // 4. Stick deflection tether & thumb knob
            val knobX = cx + dx * innerR * 0.58f
            val knobY = cy + dy * innerR * 0.58f
            val knobR = innerR * 0.34f
            if (hypot(dx, dy) > 0.05f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = w * 0.022f
                paint.color = ACCENT_GLOW
                c.drawLine(cx, cy, knobX, knobY, paint)
            }

            paint.style = Paint.Style.FILL
            paint.color = if (dragging) MINT else ACCENT
            c.drawCircle(knobX, knobY, knobR, paint)

            paint.color = 0xFFFFFFFF.toInt()
            c.drawCircle(knobX, knobY, knobR * 0.36f, paint)

            // 5. Top-left collapse button (−)
            val minX = w * 0.17f
            val minY = h * 0.16f
            val btnR = w * 0.085f
            paint.style = Paint.Style.FILL
            paint.color = CLOSE_BG
            c.drawCircle(minX, minY, btnR, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w * 0.014f
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = CLOSE_FG
            val md = btnR * 0.42f
            c.drawLine(minX - md, minY, minX + md, minY, paint)

            // 6. Top-right close button (×)
            val closeX = w * 0.83f
            val closeY = h * 0.16f
            paint.style = Paint.Style.FILL
            paint.color = CLOSE_BG
            c.drawCircle(closeX, closeY, btnR, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w * 0.014f
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = CLOSE_FG
            val cd = btnR * 0.36f
            c.drawLine(closeX - cd, closeY - cd, closeX + cd, closeY + cd, paint)
            c.drawLine(closeX + cd, closeY - cd, closeX - cd, closeY + cd, paint)

            // 6b. Bottom-left route pause / resume button (shown while a route is running)
            val routeRunning = r.enabled && r.routeMode
            if (routeRunning) {
                val pauseX = w * 0.17f
                val pauseY = h * 0.84f
                paint.style = Paint.Style.FILL
                paint.color = if (r.paused) MINT_BG else WARNING_BG
                c.drawCircle(pauseX, pauseY, btnR, paint)
                paint.color = 0xFFFFFFFF.toInt()
                if (r.paused) {
                    val s = btnR * 0.42f
                    playPath.reset()
                    playPath.moveTo(pauseX - s * 0.55f, pauseY - s)
                    playPath.lineTo(pauseX + s * 0.95f, pauseY)
                    playPath.lineTo(pauseX - s * 0.55f, pauseY + s)
                    playPath.close()
                    c.drawPath(playPath, paint)
                } else {
                    val barW = btnR * 0.24f
                    val barH = btnR * 0.78f
                    val gap = btnR * 0.16f
                    pillRect.set(pauseX - gap - barW, pauseY - barH / 2f, pauseX - gap, pauseY + barH / 2f)
                    c.drawRoundRect(pillRect, barW / 2f, barW / 2f, paint)
                    pillRect.set(pauseX + gap, pauseY - barH / 2f, pauseX + gap + barW, pauseY + barH / 2f)
                    c.drawRoundRect(pillRect, barW / 2f, barW / 2f, paint)
                }
            }

            // 6c. Bottom-right start / stop simulation button (always available: green ▶ to start, red ■ to stop)
            run {
                val toggleX = w * 0.83f
                val toggleY = h * 0.84f
                paint.style = Paint.Style.FILL
                paint.color = if (r.enabled) DANGER_BG else MINT_BG
                c.drawCircle(toggleX, toggleY, btnR, paint)
                paint.color = 0xFFFFFFFF.toInt()
                if (r.enabled) {
                    val sq = btnR * 0.38f
                    pillRect.set(toggleX - sq, toggleY - sq, toggleX + sq, toggleY + sq)
                    c.drawRoundRect(pillRect, sq * 0.3f, sq * 0.3f, paint)
                } else {
                    val s = btnR * 0.42f
                    playPath.reset()
                    playPath.moveTo(toggleX - s * 0.55f, toggleY - s)
                    playPath.lineTo(toggleX + s * 0.95f, toggleY)
                    playPath.lineTo(toggleX - s * 0.55f, toggleY + s)
                    playPath.close()
                    c.drawPath(playPath, paint)
                }
            }

            // 7. Bottom speed mode badge (tap to cycle speed mode on the fly)
            val speedLabel = engine.store.settings.value.speedLabel
            val badgeW = w * 0.34f
            val badgeH = h * 0.125f
            val badgeY = h * 0.89f
            pillRect.set(cx - badgeW / 2f, badgeY - badgeH / 2f, cx + badgeW / 2f, badgeY + badgeH / 2f)
            paint.style = Paint.Style.FILL
            paint.color = CLOSE_BG
            c.drawRoundRect(pillRect, badgeH / 2f, badgeH / 2f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = w * 0.008f
            paint.color = OUTER_BORDER
            c.drawRoundRect(pillRect, badgeH / 2f, badgeH / 2f, paint)

            paint.style = Paint.Style.FILL
            paint.color = CLOSE_FG
            paint.textSize = w * 0.072f
            paint.isFakeBoldText = true
            val textY = badgeY - (paint.descent() + paint.ascent()) / 2f
            c.drawText(speedLabel, cx, textY, paint)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val w = width.toFloat()
            val h = height.toFloat()

            if (collapsed) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = e.rawX
                        downRawY = e.rawY
                        maxMoveFromDown = 0f
                        startWinX = windowParams?.x ?: 0
                        startWinY = windowParams?.y ?: 0
                        mode = MODE_COLLAPSED_TAP
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val rawMoved = hypot(e.rawX - downRawX, e.rawY - downRawY)
                        if (rawMoved > maxMoveFromDown) maxMoveFromDown = rawMoved
                        if (rawMoved > touchSlop * 0.8f) {
                            mode = MODE_DRAG_WINDOW
                            moveWindowTo(
                                startWinX + (e.rawX - downRawX).toInt(),
                                startWinY + (e.rawY - downRawY).toInt(),
                            )
                            invalidate()
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (e.actionMasked == MotionEvent.ACTION_UP && mode == MODE_COLLAPSED_TAP) {
                            vibrate(HapticFeedbackConstants.CLOCK_TICK)
                            setCollapsed(false)
                            mode = MODE_NONE
                            return true
                        }
                        mode = MODE_NONE
                        invalidate()
                    }
                }
                return true
            }

            val cx = w * 0.5f
            val cy = h * 0.50f
            val innerR = w * INNER_RING
            val minX = w * 0.17f
            val minY = h * 0.16f
            val closeX = w * 0.83f
            val closeY = h * 0.16f
            val pauseX = w * 0.17f
            val pauseY = h * 0.84f
            val stopX = w * 0.83f
            val stopY = h * 0.84f
            val hitR = w * 0.115f
            val speedY = h * 0.89f
            val speedHitW = w * 0.20f
            val speedHitH = h * 0.08f
            val r = engine.store.runtime.value

            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacks(longPressRunnable)
                    downX = e.x
                    downY = e.y
                    downRawX = e.rawX
                    downRawY = e.rawY
                    maxMoveFromDown = 0f
                    startWinX = windowParams?.x ?: 0
                    startWinY = windowParams?.y ?: 0

                    val distClose = hypot(e.x - closeX, e.y - closeY)
                    val distMin = hypot(e.x - minX, e.y - minY)
                    val distPause = hypot(e.x - pauseX, e.y - pauseY)
                    val distStop = hypot(e.x - stopX, e.y - stopY)
                    val hitSpeed = kotlin.math.abs(e.x - cx) <= speedHitW && kotlin.math.abs(e.y - speedY) <= speedHitH
                    val distCenter = hypot(e.x - cx, e.y - cy)

                    when {
                        distClose <= hitR -> mode = MODE_CLOSE_CANDIDATE
                        distMin <= hitR -> mode = MODE_MINIMIZE_CANDIDATE
                        distStop <= hitR -> mode = MODE_STOP_CANDIDATE
                        r.enabled && r.routeMode && distPause <= hitR -> mode = MODE_PAUSE_CANDIDATE
                        hitSpeed -> mode = MODE_SPEED_CANDIDATE
                        // Outer ring or top handle bar: immediately drag the floating window
                        distCenter > innerR * 0.90f || e.y < cy - innerR * 0.75f -> {
                            mode = MODE_DRAG_WINDOW
                            dx = 0f
                            dy = 0f
                            vibrate(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        else -> {
                            mode = MODE_STICK
                            updateStickDeflection(e.x, e.y, cx, cy, innerR)
                            startStepping()
                            // Holding the center knob still without deflecting it enters window-drag mode
                            if (distCenter < innerR * 0.35f) {
                                handler.postDelayed(longPressRunnable, LONG_PRESS_DRAG_MS)
                            }
                        }
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    val rawMoved = hypot(e.rawX - downRawX, e.rawY - downRawY)
                    if (rawMoved > maxMoveFromDown) maxMoveFromDown = rawMoved
                    when (mode) {
                        MODE_CLOSE_CANDIDATE, MODE_MINIMIZE_CANDIDATE, MODE_SPEED_CANDIDATE,
                        MODE_STOP_CANDIDATE, MODE_PAUSE_CANDIDATE -> {
                            if (rawMoved > touchSlop) {
                                mode = MODE_DRAG_WINDOW
                                moveWindowTo(
                                    startWinX + (e.rawX - downRawX).toInt(),
                                    startWinY + (e.rawY - downRawY).toInt(),
                                )
                            }
                        }
                        MODE_DRAG_WINDOW -> {
                            dx = 0f
                            dy = 0f
                            moveWindowTo(
                                startWinX + (e.rawX - downRawX).toInt(),
                                startWinY + (e.rawY - downRawY).toInt(),
                            )
                        }
                        MODE_STICK -> {
                            if (hypot(e.x - downX, e.y - downY) > touchSlop * 0.9f) {
                                handler.removeCallbacks(longPressRunnable)
                            }
                            updateStickDeflection(e.x, e.y, cx, cy, innerR)
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        when (mode) {
                            MODE_CLOSE_CANDIDATE -> {
                                stopSelf()
                                return true
                            }
                            MODE_MINIMIZE_CANDIDATE -> {
                                vibrate(HapticFeedbackConstants.CLOCK_TICK)
                                mode = MODE_NONE
                                setCollapsed(true)
                                return true
                            }
                            MODE_SPEED_CANDIDATE -> {
                                vibrate(HapticFeedbackConstants.CLOCK_TICK)
                                cycleSpeedMode()
                                mode = MODE_NONE
                                invalidate()
                                return true
                            }
                            MODE_STOP_CANDIDATE -> {
                                vibrate(HapticFeedbackConstants.LONG_PRESS)
                                mode = MODE_NONE
                                if (engine.store.runtime.value.enabled) {
                                    stopStepping()
                                    worker.post { runCatching { engine.stop() } }
                                } else {
                                    worker.post {
                                        try {
                                            val cur = engine.store.runtime.value
                                            if (cur.routeMode && engine.plan(cur.routeId) != null) engine.startRoute()
                                            else engine.startPoint()
                                        } catch (ex: Exception) {
                                            engine.store.updateRuntime(persist = false) { it.copy(error = ex.message.orEmpty()) }
                                            engine.store.log("摇杆启动模拟：${ex.message}")
                                        }
                                    }
                                }
                                invalidate()
                                return true
                            }
                            MODE_PAUSE_CANDIDATE -> {
                                vibrate(HapticFeedbackConstants.CLOCK_TICK)
                                mode = MODE_NONE
                                val pause = !engine.store.runtime.value.paused
                                worker.post { runCatching { engine.pauseRoute(pause) } }
                                invalidate()
                                return true
                            }
                        }
                    }
                    mode = MODE_NONE
                    stopStepping()
                }
            }
            invalidate()
            return true
        }

        private fun updateStickDeflection(x: Float, y: Float, cx: Float, cy: Float, innerR: Float) {
            dx = (x - cx) / innerR
            dy = (y - cy) / innerR
            val n = hypot(dx, dy)
            if (n > 1f) {
                dx /= n
                dy /= n
            }
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 102
        private const val CHANNEL = "geoshift_joystick"
        private const val ACTION_CLOSE = "CLOSE"
        private const val STEP_MS = 100L
        private const val LONG_PRESS_DRAG_MS = 300L
        private const val EXPANDED_DP = 164
        private const val COLLAPSED_DP = 56

        private const val MODE_NONE = 0
        private const val MODE_STICK = 1
        private const val MODE_DRAG_WINDOW = 2
        private const val MODE_CLOSE_CANDIDATE = 3
        private const val MODE_MINIMIZE_CANDIDATE = 4
        private const val MODE_SPEED_CANDIDATE = 5
        private const val MODE_COLLAPSED_TAP = 6
        private const val MODE_STOP_CANDIDATE = 7
        private const val MODE_PAUSE_CANDIDATE = 8

        private const val OUTER_RING = 0.44f
        private const val INNER_RING = 0.29f

        private const val OUTER_FACE = 0xD91D1B2D.toInt()
        private const val OUTER_FACE_DRAG = 0xEE26233B.toInt()
        private const val OUTER_BORDER = 0x597C88FF.toInt()
        private const val HANDLE_COLOR = 0xB3A7A8BA.toInt()
        private const val INNER_FACE = 0xF2F7F8FC.toInt()
        private const val ACCENT = 0xFF5B67EA.toInt()
        private const val ACCENT_BORDER = 0x665B67EA.toInt()
        private const val ACCENT_GLOW = 0x805B67EA.toInt()
        private const val MINT = 0xFF00C897.toInt()
        private const val MINT_BG = 0xE600C897.toInt()
        private const val DANGER_BG = 0xE6FF5252.toInt()
        private const val WARNING_COLOR = 0xFFFF9F43.toInt()
        private const val WARNING_BG = 0xE6FF9F43.toInt()
        private const val TICK_COLOR = 0x55787A91.toInt()
        private const val CLOSE_BG = 0xE6353150.toInt()
        private const val CLOSE_FG = 0xFFEDEDF5.toInt()

        @Volatile private var savedX: Int? = null
        @Volatile private var savedY: Int? = null

        fun command(c: Context, enabled: Boolean) {
            val i = Intent(c, JoystickService::class.java)
            if (enabled) c.startForegroundService(i) else c.stopService(i)
        }
    }
}
