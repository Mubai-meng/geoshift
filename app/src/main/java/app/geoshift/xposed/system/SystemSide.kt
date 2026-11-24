package app.geoshift.xposed.system

import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import app.geoshift.core.LatLon
import app.geoshift.xposed.AppScope
import app.geoshift.xposed.Bridge
import app.geoshift.xposed.Contexts
import app.geoshift.xposed.Diag
import app.geoshift.xposed.Fakes
import app.geoshift.xposed.Hooker
import app.geoshift.xposed.Log
import app.geoshift.xposed.Reflect
import app.geoshift.xposed.Sim
import org.json.JSONObject

/**
 * Everything installed in system_server. Commands arrive through
 * LocationManager.sendExtraCommand(provider = "geoshift", command, extras):
 *   PING   (app only)        -> extras: active, reports (JSON), config (what the hooks see)
 *   MOVE   (app only)        <- extras: lat, lon, speed, bearing (joystick steps)
 *   REPORT (system uids)     <- extras: feature, report (diagnostics from the phone / Bluetooth process)
 * Every other caller gets the stock behaviour, so no ordinary app can tell the module is there.
 */
class SystemSide(private val hooks: Hooker, private val loader: ClassLoader) {
    private val ownUidCache = java.util.concurrent.ConcurrentHashMap<Int, Pair<Boolean, Long>>()
    private val FEATURE = Regex("[a-z_]{1,30}")

    fun install() {
        Diag.isSystemServer = true
        Diag.processName = "system_server"
        val location = LocationHooks(hooks, loader)
        hookCommands(location)
        // Install callback-owner tracking independently of LocationHooks so CallbackHooks still
        // knows which app owns each Binder proxy even if LocationProviderManager hooks fail.
        step("callback_owners", "回调归属") { location.installRegistrations() }
        step("location_system", "系统定位") {
            check(Build.VERSION.SDK_INT >= 31) { "需要 Android 12 或更新版本" }
            location.install()
        }
        step("callbacks", "应用回调") { CallbackHooks(hooks, loader, location).install() }
        step("wifi", "Wi-Fi") { WifiHooks(hooks, loader, ::isOwnUid).install() }
    }

    private inline fun step(feature: String, label: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            Log.w("$label install", e)
            Diag.report(feature, false, "error", "${label}入口安装失败：${e.javaClass.simpleName} ${e.message.orEmpty()}".trim(), error = e)
        }
    }

    /**
     * Recognizes Geoshift across all Android users (primary User 0, Second Space / Work Profile /
     * Dual Apps where uid = userId * 100000 + appId).
     */
    private fun isOwnUid(uid: Int): Boolean {
        if (uid < 10000 || uid % 100000 < 10000) return false
        val now = SystemClock.elapsedRealtime()
        ownUidCache[uid]?.let { (hit, at) ->
            if (now - at < 60_000L) return hit
        }
        val pm = Contexts.get()?.packageManager ?: return false
        val matched = isSelfUid(
            uid = uid,
            packagesForUid = { targetUid -> runCatching { pm.getPackagesForUid(targetUid) }.getOrNull() },
            primarySelfUid = { runCatching { pm.getApplicationInfo(Bridge.SELF, 0).uid }.getOrNull() },
        )
        ownUidCache[uid] = matched to now
        return matched
    }

    internal companion object {
        const val PER_USER_RANGE = 100_000

        fun isSelfUid(
            uid: Int,
            packagesForUid: (Int) -> Array<String>?,
            primarySelfUid: () -> Int?,
        ): Boolean {
            if (uid < 10000) return false
            val appId = uid % PER_USER_RANGE
            if (appId < 10000) return false
            val exact = packagesForUid(uid)
            if (exact != null) return Bridge.SELF in exact
            val byAppId = packagesForUid(appId)
            if (byAppId != null) return Bridge.SELF in byAppId
            val primary = primarySelfUid() ?: return false
            return primary >= 10000 && primary % PER_USER_RANGE == appId
        }
    }

    private fun hookCommands(location: LocationHooks) {
        val service = Reflect.firstClass(listOf("com.android.server.location.LocationManagerService",
            "com.android.server.LocationManagerService"), loader, "命令通道") ?: return
        hooks.declared(service, "sendExtraCommand") { m ->
            hooks.before(m) { call ->
                if (call.args.size < 3 || call.args[0] != Bridge.COMMAND) return@before
                val cmd = call.args[1]?.toString().orEmpty()
                val extras = call.args[2] as? Bundle
                val uid = Binder.getCallingUid()
                // Read-only motion sync for the optional GeoshiftMotion companion module (only while
                // active for this app and carrying MOTION_KEY; ordinary apps get stock behaviour).
                if (cmd == "MOTION" && extras != null && extras.getString("k") == Bridge.MOTION_KEY) {
                    val c = Sim.config
                    if (!c.active || !AppScope.covers(c, uid)) return@before
                    val fix = runCatching { Fakes.fix(c, "gps") }.getOrNull() ?: return@before
                    extras.putBoolean("active", true)
                    extras.putBoolean("foot", c.footMotion)
                    extras.putDouble("speed", (fix.speed ?: 0f).toDouble())
                    extras.putLong("seed", c.noiseSeed)
                    call.result = if (call.returnType == java.lang.Boolean.TYPE) true else null
                    return@before
                }
                val own = isOwnUid(uid)
                if (!own && uid >= 10000) return@before // Stock behaviour for everyone else.
                call.thisObject?.let { location.attachService(it) }
                call.result = if (call.returnType == java.lang.Boolean.TYPE) true else null
                if (extras == null) return@before
                try {
                    handle(cmd, extras, own)
                    if (own && cmd == "MOVE") location.schedule(0)
                } catch (e: Throwable) {
                    extras.putString("error", "${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    private fun handle(command: String, extras: Bundle, own: Boolean) {
        when (command) {
            "PING" -> if (own) {
                extras.putBoolean("active", true)
                extras.putString("reports", Diag.snapshot().toString())
                extras.putString("config", Sim.config.encode())
                extras.putLong("session_started_at", Diag.sessionStartedAt)
            }
            "SET" -> if (own) extras.getString("config")?.let { Sim.accept(it) }
            "MOVE" -> if (own) Sim.moveTo(LatLon(extras.getDouble("lat"), extras.getDouble("lon")),
                extras.getDouble("speed"), extras.getDouble("bearing"))
            "REPORT" -> {
                val feature = extras.getString("feature").orEmpty()
                if (feature.matches(FEATURE)) runCatching { Diag.reports[feature] = JSONObject(extras.getString("report") ?: "{}") }
            }
        }
    }
}
