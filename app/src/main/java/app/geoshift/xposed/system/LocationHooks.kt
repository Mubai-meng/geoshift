package app.geoshift.xposed.system

import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import app.geoshift.core.Geo
import app.geoshift.core.LatLon
import app.geoshift.core.SimConfig
import app.geoshift.xposed.AppScope
import app.geoshift.xposed.CallbackOwners
import app.geoshift.xposed.Diag
import app.geoshift.xposed.Fakes
import app.geoshift.xposed.Hooker
import app.geoshift.xposed.Log
import app.geoshift.xposed.Reflect
import app.geoshift.xposed.Sim
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Location inside system_server (Android 12+): every app gets the simulated fix with no scope of its
 * own. Real provider reports are dropped while simulating and a pump reports the simulated fix through
 * each provider manager at the configured interval, exactly like a real fix. Deliveries to apps,
 * cached last fixes and replays after stopping are rewritten as well.
 */
class LocationHooks(private val hooks: Hooker, private val loader: ClassLoader) {
    private val providers = listOf("gps", "network", "fused")
    private class ManagerMeta(val name: String, @Volatile var provider: Any? = null, @Volatile var lock: Any? = null)
    private val managers: MutableSet<Any> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val managerMeta = Collections.synchronizedMap(WeakHashMap<Any, ManagerMeta>())
    @Volatile private var service = WeakReference<Any>(null)
    /** Last real fix per provider, for apps outside the chosen list and for after stopping. */
    private val lastReal = ConcurrentHashMap<String, Location>()
    /** After stopping, provider caches still hold the last simulated fix until a real one arrives. */
    private val staleProviders = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var pumpWasActive = false
    @Volatile private var pumpTick = 0L
    @Volatile private var lastFake: LatLon? = null
    @Volatile private var lastPumpAt = 0L
    /** True while the pump itself reports a simulated fix on this thread. */
    private val injecting = ThreadLocal.withInitial { false }
    private val pump by lazy { Handler(HandlerThread("geoshift-pump").apply { start() }.looper) }
    private val tick = Runnable { injectAll() }

    private val dropped = ConcurrentHashMap<String, AtomicLong>()
    private val pumped = ConcurrentHashMap<String, AtomicLong>()
    private val pumpFailed = ConcurrentHashMap<String, AtomicLong>()
    private val delivered = AtomicLong()
    @Volatile private var deliveryHooks = 0
    @Volatile private var lastTarget = "尚无"
    @Volatile private var lastPumpError = ""
    private var lastPumpReport = 0L
    @Volatile private var lastProgressAt = 0L

    private fun active(): SimConfig? = Sim.config.takeIf { it.locationOn }

    @Volatile private var identityMissLogged = false
    /** Location handlers and the pump stay idle until hook installation completes. */
    @Volatile private var installed = false

    fun install() {
        val lpm = Reflect.requireClass("com.android.server.location.provider.LocationProviderManager", loader, "系统定位")
            ?: error("LocationProviderManager")
        // Discover and validate all required entry points BEFORE installing any hooks, so a failure
        // never leaves real reports dropped while the simulation pump is unstarted.
        val entries = LocationCompat.discoverEntries(lpm, loader)
        val reportsCount = entries.reports.size
        val cachedCount = entries.cached.size
        check(reportsCount > 0 && cachedCount > 0) {
            "定位入口缺失：report=$reportsCount, last=$cachedCount；候选：${LocationCompat.describeLocationCandidates(lpm)}"
        }
        hooks.constructors(lpm) { c -> hooks.after(c) { managers.add(it.thisObject!!) } }
        for (m in entries.reports) hooks.before(m, ::onReport)
        for (m in entries.cached) {
            hooks.after(m, ::onCached)
            hooks.deoptimize(m)
        }
        if (entries.deliveries.isEmpty()) Reflect.recordMissing("LocationProviderManager.acceptLocationChange", "定位交付")
        for (m in entries.deliveries) hooks.before(m, ::onDelivery)
        deliveryHooks = entries.deliveries.size
        installed = true
        Sim.onChange { pumpTick = 0L; schedule(0) }
        Diag.report("location_system", true, "installed", "系统定位入口已安装（上报 $reportsCount · 缓存 $cachedCount · 交付 $deliveryHooks）")
        schedule(0)
    }

    // ---- Real reports -------------------------------------------------------------------------

    private fun onReport(call: app.geoshift.xposed.Call) {
        if (!installed) return
        val manager = call.thisObject ?: return
        managers.add(manager)
        if (injecting.get()) return // The pump's own report: already simulated.
        val packet = call.args.firstOrNull() ?: return
        val name = nameOf(manager)
        val c = active()
        if (c == null) {
            // A real fix after stopping: from here on the provider cache is real again.
            if (name in providers && staleProviders.remove(name)) {
                rememberReal(name, packet, null)
                staleProviders.remove("passive")
            }
            return
        }
        if (name !in providers && name != "passive") return
        if (c.selectedOnly) {
            // Real fixes keep flowing for apps not chosen; each delivery decides per app.
            if (name in providers) rememberReal(name, packet, c)
            return
        }
        if (name in providers) {
            rememberReal(name, packet, c)
            // Drop real reports; the pump delivers the simulated fix. Replacing them instead loops: a
            // fused provider listening passively sees the replaced fix as new input and reports again.
            call.drop()
            count(dropped, name)
            return
        }
        try {
            call.args[0] = Fakes.wrap(Fakes.location(c, if (name == "passive") "gps" else name))
        } catch (e: Throwable) {
            call.drop()
            Log.w("passive report", e)
        }
    }

    // ---- Cached last fix ----------------------------------------------------------------------

    private fun onCached(call: app.geoshift.xposed.Call) {
        if (!installed) return
        val manager = call.thisObject ?: return
        managers.add(manager)
        val name = nameOf(manager)
        if (name !in providers && name != "passive") return
        val provider = if (name == "passive") "gps" else name
        val returnsResult = call.returnType.name == "android.location.LocationResult"
        val c = active()
        val raw = call.result
        val result: Location? = when {
            raw is Location -> raw
            returnsResult && raw != null -> Fakes.lastOf(raw)
            else -> null
        }
        if (c == null) {
            if (staleSimulated(result, name)) {
                val restored = afterStop(provider)
                call.result = if (returnsResult) restored?.let { Fakes.wrap(it) } else restored
            }
            return
        }
        if (c.selectedOnly && !AppScope.covers(c, identityUid(call.args))) {
            // Not chosen: the real last fix, never a simulated one left in the provider cache.
            if (result != null && nearSimulated(c, result)) {
                val real = realFix(provider)
                call.result = if (returnsResult) real?.let { Fakes.wrap(it) } else real
            }
            return
        }
        try {
            val fake = Fakes.location(c, provider)
            call.result = if (returnsResult) Fakes.wrap(fake) else fake
        } catch (e: Throwable) {
            call.result = null
            Diag.report("location_delivery", false, "error", "缓存定位替换失败，已阻止返回真实数据：${e.javaClass.simpleName}", error = e)
        }
    }

    // ---- Deliveries to apps -------------------------------------------------------------------

    private fun onDelivery(call: app.geoshift.xposed.Call) {
        if (!installed) return
        val packet = call.args.firstOrNull() ?: return
        val c = active()
        if (c == null) {
            // Stopped: a cached simulated fix replayed to a new listener is dropped.
            if (staleProviders.isNotEmpty()) {
                val last = Fakes.lastOf(packet)
                if (staleSimulated(last, last?.provider)) call.drop()
            }
            return
        }
        if (c.selectedOnly && !AppScope.covers(c, registrationUid(call.thisObject))) {
            // Not chosen: drop the pump's simulated fixes and any replayed from the cache; real ones go through.
            if (injecting.get()) { call.drop(); return }
            val last = Fakes.lastOf(packet)
            if (last != null && nearSimulated(c, last)) call.drop()
            return
        }
        try {
            val provider = Fakes.lastOf(packet)?.provider ?: return
            if (provider !in providers && provider != "passive") return
            call.args[0] = Fakes.replacePacket(c, packet)
            delivered.incrementAndGet()
            progress(call.thisObject)
        } catch (e: Throwable) {
            call.drop()
            Diag.report("location_delivery", false, "error", "定位交付替换失败，已阻止返回真实数据：${e.javaClass.simpleName}", error = e)
            Log.w("delivery", e)
        }
    }

    private fun targetOf(registration: Any?): String {
        val info = LocationCompat.resolveIdentity(registration)
        if (info?.packageName != null) return info.packageName.take(100)
        logIdentityMissOnce(registration)
        return "包名不可用"
    }

    private fun registrationUid(registration: Any?): Int {
        val info = LocationCompat.resolveIdentity(registration)
        if (info?.uid != null) return info.uid
        if (registration != null) {
            val cls = registration.javaClass
            Reflect.recordMissing("${cls.name.substringAfterLast('.')}.identity", "定位归属")
            logIdentityMissOnce(registration)
        }
        return -1
    }

    private fun logIdentityMissOnce(registration: Any?) {
        if (registration == null || identityMissLogged) return
        identityMissLogged = true
        Log.w("registration identity unresolved on ${registration.javaClass.name}; candidates=${LocationCompat.describeIdentityCandidates(registration.javaClass)}")
    }

    /** Owner uid of a provider request / registration identity argument, or -1. */
    private fun identityUid(args: Array<Any?>): Int {
        for (a in args) if (a != null && a.javaClass.name.endsWith("CallerIdentity")) (Reflect.callOrNull(a, "getUid") as? Int)?.let { return it }
        return -1
    }

    // ---- New listeners get a fix at once ------------------------------------------------------

    /**
     * Records which app owns each location / GNSS / phone-state callback (for "selected" mode), and
     * runs the pump right after an app starts listening instead of at the next tick.
     */
    internal fun installRegistrations() {
        val lms = Reflect.firstClass(listOf("com.android.server.location.LocationManagerService",
            "com.android.server.LocationManagerService"), loader, "定位服务")
        if (lms != null) hooks.constructors(lms) { c -> hooks.after(c) { call -> call.thisObject?.let { attachService(it) } } }
        var n = CallbackOwners.watch(hooks, lms) { m, _ ->
            if (m.name.startsWith("registerLocation") || m.name == "getCurrentLocation" || m.name == "requestLocationUpdates") kick()
        }
        n += CallbackOwners.watch(hooks, Reflect.classOrNull("com.android.server.TelephonyRegistry", loader))
        Log.i("callback owner hooks: $n")
        Diag.report("callback_owners", n > 0, if (n > 0) "installed" else "error",
            if (n > 0) "已追踪 $n 个回调注册入口（「仅选中应用」模式按此判断归属）" else "未找到回调注册入口，「仅选中应用」模式无法判断回调归属")
    }

    /** Called by the command hook, which sees the LocationManagerService instance. */
    fun attachService(instance: Any) { service = WeakReference(instance) }

    private fun kick() {
        if (!installed || active() == null) return
        pumpTick = 0L
        val since = SystemClock.elapsedRealtime() - lastPumpAt
        schedule(if (since >= 200) 30 else 200 - since)
    }

    // ---- Pump ---------------------------------------------------------------------------------

    fun schedule(delay: Long) {
        if (!installed) return
        pump.removeCallbacks(tick)
        pump.postDelayed(tick, delay)
    }

    /**
     * Reports the simulated fix through each provider's own lock, exactly like a real fix, so apps
     * keep receiving updates even with no real GPS / network signal.
     */
    private fun injectAll() {
        lastPumpAt = SystemClock.elapsedRealtime()
        val c = active()
        if (c == null) {
            if (pumpWasActive) {
                pumpWasActive = false
                pumpTick = 0L
                staleProviders.addAll(providers)
                staleProviders.add("passive")
            }
            return
        }
        val firstTick = !pumpWasActive || pumpTick == 0L
        pumpWasActive = true
        val tickNo = pumpTick++
        staleProviders.clear()
        runCatching { lastFake = Fakes.fix(c, "gps").point }
        // Providers can exist before the constructor hook was installed: discover them from the service.
        service.get()?.let { s ->
            for (name in providers) runCatching { Reflect.call(s, "getLocationProviderManager", name)?.let { managers.add(it) } }
        }
        val snapshot = synchronized(managers) { managers.toList() }
        var ok = 0
        var failed = 0
        for (manager in snapshot) {
            val meta = metaOf(manager)
            val name = meta.name
            if (name !in providers) continue
            // Stagger network provider cadence (every 3rd cycle after initial kick) so gps & network
            // do not share identical lockstep timestamps.
            if (name == "network" && !firstTick && tickNo % 3L != 0L) continue
            try {
                if (active() == null) break
                val provider = meta.provider ?: Reflect.get(manager, "mProvider", "定位泵")!!.also { meta.provider = it }
                val lock = meta.lock ?: ownerLock(provider).also { meta.lock = it }
                val packet = Fakes.wrap(Fakes.location(c, name))
                synchronized(lock) {
                    injecting.set(true)
                    try { Reflect.call(manager, "onReportLocation", packet) } finally { injecting.set(false) }
                }
                count(pumped, name); ok++
            } catch (e: Throwable) {
                count(pumpFailed, name); failed++
                lastPumpError = "$name: ${e.javaClass.simpleName}"
                Log.w("inject $name", e)
            }
        }
        pumpReport(snapshot.size, ok, failed)
        schedule(c.interval.coerceIn(200, 5000).toLong())
    }

    private fun ownerLock(provider: Any): Any {
        for (name in listOf("mOwnerLock", "mLock")) Reflect.getOrNull(provider, name)?.let { return it }
        Reflect.recordMissing("MockableLocationProvider.mOwnerLock", "定位泵")
        error("provider lock not found")
    }

    // ---- Real fixes ---------------------------------------------------------------------------

    /** Keeps the newest real fix. A chosen Play services app computes fixes from simulated input; those are not "real". */
    private fun rememberReal(provider: String, packet: Any, c: SimConfig?) {
        val last = Fakes.lastOf(packet) ?: return
        if (c != null && nearSimulated(c, last)) return
        lastReal[provider] = Location(last)
    }

    fun realFix(provider: String?): Location? {
        val l = lastReal[provider ?: "gps"] ?: lastReal["fused"] ?: lastReal["gps"] ?: lastReal["network"]
        return l?.let { Location(it) }
    }

    /** True when a fix is (close to) the simulated position, i.e. it must not reach an uncovered app. */
    fun nearSimulated(c: SimConfig, l: Location): Boolean = try {
        val ref = lastFake?.takeIf { SystemClock.elapsedRealtime() - lastPumpAt < 1500 } ?: Fakes.fix(c, "fused").point
        Geo.distance(ref, LatLon(l.latitude, l.longitude)) < 500
    } catch (_: Throwable) {
        false
    }

    private fun staleSimulated(l: Location?, provider: String?): Boolean {
        val fake = lastFake ?: return false
        if (l == null || (provider ?: "") !in staleProviders) return false
        return Geo.distance(fake, LatLon(l.latitude, l.longitude)) < 500
    }

    /** The provider's own real fix (never another provider's) instead of a cached simulated one. */
    private fun afterStop(provider: String): Location? {
        val real = lastReal[provider] ?: return null
        val fake = lastFake ?: return Location(real)
        return if (Geo.distance(fake, LatLon(real.latitude, real.longitude)) < 500) null else Location(real)
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private fun metaOf(manager: Any): ManagerMeta {
        managerMeta[manager]?.let { return it }
        val name = (Reflect.getOrNull(manager, "mName") as? String).orEmpty()
        return ManagerMeta(name).also { if (name.isNotEmpty()) managerMeta[manager] = it }
    }

    private fun nameOf(manager: Any): String = metaOf(manager).name

    private fun count(map: ConcurrentHashMap<String, AtomicLong>, key: String) {
        map.getOrPut(key) { AtomicLong() }.incrementAndGet()
    }

    private fun counts(map: ConcurrentHashMap<String, AtomicLong>) = providers.joinToString(" ") { "$it=${map[it]?.get() ?: 0}" }

    /** Deliveries are frequent: the package lookup and the report text are built at most every few seconds. */
    private fun progress(registration: Any?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressAt < 3000) return
        lastProgressAt = now
        lastTarget = targetOf(registration)
        Diag.report("location_delivery", true, "injected",
            "已改写交付 ${delivered.get()} 次（入口 $deliveryHooks）· 拦截真实上报 ${counts(dropped)} · 最近目标 $lastTarget")
    }

    @Synchronized private fun pumpReport(found: Int, ok: Int, failed: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPumpReport < 3000) return
        lastPumpReport = now
        val detail = "已发现提供器 $found · " + providers.joinToString(" ") { "$it=${pumped[it]?.get() ?: 0}/${pumpFailed[it]?.get() ?: 0}" } + "（成功/失败）"
        Diag.report("location_pump", failed == 0, if (failed > 0) "error" else if (ok > 0) "injected" else "waiting",
            if (failed > 0) "$detail；最近错误 $lastPumpError" else detail)
    }
}
