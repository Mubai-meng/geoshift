package app.geoshift.xposed.system

import android.location.Location
import app.geoshift.xposed.Reflect
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Reflection helpers for LocationProviderManager and its Registration subclasses across Android
 * 12–16 and OEM ROMs. Kept pure-reflection so JVM unit tests can exercise compatibility variants.
 */
internal object LocationCompat {
    private const val LOCATION_RESULT = "android.location.LocationResult"

    /** Registration classes nested in LocationProviderManager (Android 12–16). */
    val REGISTRATIONS = listOf(
        "\$Registration",
        "\$LocationRegistration",
        "\$LocationListenerRegistration",
        "\$LocationPendingIntentRegistration",
        "\$GetCurrentLocationListenerRegistration",
    )

    data class DiscoveredEntries(
        val reports: List<Method>,
        val cached: List<Method>,
        val deliveries: List<Method>,
    )

    data class IdentityInfo(
        val uid: Int?,
        val packageName: String?,
        val resolvedBy: String,
    )

    fun discoverEntries(lpm: Class<*>, loader: ClassLoader?): DiscoveredEntries {
        val reports = methods(lpm, nested = false) {
            it.name == "onReportLocation" && firstParam(it) == LOCATION_RESULT
        }
        val cached = discoverLastLocationMethods(lpm)
        val deliveries = discoverDeliveryMethods(lpm, loader)
        return DiscoveredEntries(reports, cached, deliveries)
    }

    fun discoverLastLocationMethods(lpm: Class<*>): List<Method> {
        // Primary: AOSP Android 12–14 getLastLocation returning Location.
        val exact = methods(lpm, nested = false) {
            it.name == "getLastLocation" && isLocationOrResult(it.returnType)
        }
        if (exact.isNotEmpty()) return exact

        // Fallback 1: Android 15+/OEM renamed or unlocked variants on LocationProviderManager.
        val variantNames = setOf("getLastLocationUnsafe", "getLastLocationLocked", "getLastKnownLocation")
        val variants = methods(lpm, nested = false) {
            it.name in variantNames && isLocationOrResult(it.returnType)
        }
        if (variants.isNotEmpty()) return variants

        // Fallback 2: Nested helper / delegate classes declaring getLastLocation.
        return methods(lpm, nested = true) {
            (it.name == "getLastLocation" || it.name in variantNames) && isLocationOrResult(it.returnType)
        }
    }

    fun discoverDeliveryMethods(lpm: Class<*>, loader: ClassLoader?): List<Method> {
        // Optimised system jars drop the inner-class metadata getDeclaredClasses() relies on, so the
        // registration classes are also looked up by name.
        val targets = LinkedHashSet<Method>()
        targets += methods(lpm, nested = true, ::isDelivery)
        for (suffix in REGISTRATIONS) {
            Reflect.classOrNull(lpm.name + suffix, loader)?.let { targets += methods(it, nested = true, ::isDelivery) }
        }
        return targets.toList()
    }

    fun isDelivery(m: Method): Boolean =
        m.name == "acceptLocationChange" && firstParam(m) == LOCATION_RESULT

    fun isLocationOrResult(type: Class<*>): Boolean =
        type == Location::class.java || type.name == LOCATION_RESULT

    /**
     * Resolves CallerIdentity / AttributionSource from a LocationProviderManager Registration
     * without polluting [Reflect.missing] during fallback probes.
     */
    fun resolveIdentity(registration: Any?): IdentityInfo? {
        if (registration == null) return null
        val identityMethods = listOf("getIdentity", "getCallerIdentity", "getAttributionSource")
        for (name in identityMethods) {
            val id = Reflect.callOrNull(registration, name) ?: continue
            extractFromIdentity(id, "method:$name")?.let { return it }
        }
        val identityFields = listOf("mIdentity", "mCallerIdentity", "mAttributionSource")
        for (name in identityFields) {
            val id = Reflect.getOrNull(registration, name) ?: continue
            extractFromIdentity(id, "field:$name")?.let { return it }
        }
        // Direct fallback on registration itself if an OEM flattened uid/packageName onto it.
        val uid = readUid(registration)
        val pkg = readPackageName(registration)
        return if (uid != null || pkg != null) IdentityInfo(uid, pkg, "direct") else null
    }

    private fun extractFromIdentity(identity: Any, source: String): IdentityInfo? {
        val uid = readUid(identity)
        val pkg = readPackageName(identity)
        return if (uid != null || pkg != null) IdentityInfo(uid, pkg, source) else null
    }

    private fun readUid(target: Any): Int? {
        (Reflect.callOrNull(target, "getUid") as? Number)?.toInt()?.let { return it }
        (Reflect.getOrNull(target, "mUid") as? Number)?.toInt()?.let { return it }
        return null
    }

    private fun readPackageName(target: Any): String? {
        (Reflect.callOrNull(target, "getPackageName") as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
        (Reflect.getOrNull(target, "mPackageName") as? String)?.takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    /** Compact summary of location-related candidate methods for diagnostics when hooks are missing. */
    fun describeLocationCandidates(type: Class<*>): String {
        val found = methods(type, nested = false) {
            it.name.contains("LastLocation", ignoreCase = true) ||
                it.name.contains("ReportLocation", ignoreCase = true)
        }
        if (found.isEmpty()) return "无候选"
        return found.take(8).joinToString(", ") { m ->
            val params = m.parameterTypes.joinToString(",") { it.simpleName }
            "${m.name}($params):${m.returnType.simpleName}"
        }
    }

    /** Compact summary of identity-related members on a registration class when resolution fails. */
    fun describeIdentityCandidates(type: Class<*>): String {
        val parts = ArrayList<String>()
        var c: Class<*>? = type
        while (c != null && c != Any::class.java && parts.size < 10) {
            for (m in c.declaredMethods) {
                if (m.name.contains("Identity", ignoreCase = true) ||
                    m.name.contains("Attribution", ignoreCase = true) ||
                    m.name.contains("Caller", ignoreCase = true) ||
                    m.name.equals("getUid", ignoreCase = true)
                ) {
                    parts.add("${c.simpleName}.${m.name}(${m.parameterTypes.size}):${m.returnType.simpleName}")
                }
            }
            for (f in c.declaredFields) {
                if (f.name.contains("Identity", ignoreCase = true) ||
                    f.name.contains("Attribution", ignoreCase = true) ||
                    f.name.contains("Caller", ignoreCase = true) ||
                    f.name.equals("mUid", ignoreCase = true)
                ) {
                    parts.add("${c.simpleName}#${f.name}:${f.type.simpleName}")
                }
            }
            c = c.superclass
        }
        return if (parts.isEmpty()) "无候选" else parts.take(10).joinToString(", ")
    }

    fun firstParam(m: Method): String? = m.parameterTypes.firstOrNull()?.name

    /** Concrete methods matching [predicate] in [type], its superclasses and (if [nested]) its declared nested classes. */
    fun methods(type: Class<*>, nested: Boolean, predicate: (Method) -> Boolean): List<Method> {
        val out = LinkedHashSet<Method>()
        val queue = ArrayDeque<Class<*>>().apply { add(type) }
        val seen = HashSet<Class<*>>()
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            if (t == Any::class.java || !seen.add(t)) continue
            if (nested) runCatching { queue.addAll(t.declaredClasses) }
            t.superclass?.let { queue.add(it) }
            for (m in t.declaredMethods) if (!Modifier.isAbstract(m.modifiers) && predicate(m)) out.add(m)
        }
        return out.toList()
    }
}
