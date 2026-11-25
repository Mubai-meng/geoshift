package app.geoshift.data

import android.content.Context
import android.util.AtomicFile
import app.geoshift.core.SimConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import java.io.File
import java.util.concurrent.Executors

/** Single background thread for ordered disk writes so main-thread callers never block on file IO. */
private val diskWriter = Executors.newSingleThreadExecutor { r -> Thread(r, "geoshift-store").apply { isDaemon = true } }

/** A value kept as one JSON file, replaced atomically on every write. */
private class JsonFile<T>(dir: File, name: String, private val serializer: KSerializer<T>, private val default: () -> T) {
    val baseFile = File(dir, name)
    private val file = AtomicFile(baseFile)

    fun read(): T = try {
        if (!file.baseFile.exists()) default() else SimConfig.JSON.decodeFromString(serializer, String(file.readFully()))
    } catch (_: Throwable) {
        // Unreadable: keep it aside (the next save would replace it) and start from the default.
        runCatching { file.baseFile.copyTo(File(file.baseFile.path + ".unreadable"), overwrite = true) }
        default()
    }

    @Synchronized fun write(value: T) {
        baseFile.parentFile?.mkdirs()
        val out = file.startWrite()
        try {
            out.write(SimConfig.JSON.encodeToString(serializer, value).toByteArray())
            file.finishWrite(out)
        } catch (e: Throwable) {
            file.failWrite(out)
            throw e
        }
    }

    fun writeAsync(value: T) {
        diskWriter.execute { runCatching { write(value) } }
    }
}

/** Persistence strategy for a [RecordList]. All writes are ordered on [diskWriter]. */
internal interface RecordStorage<T : Record> {
    fun readAll(): List<T>
    fun writeAll(list: List<T>)
    fun writeOrder(list: List<T>) = writeAll(list)
    fun writeItem(list: List<T>, item: T) = writeAll(list)
    fun writeItems(list: List<T>, items: List<T>) = writeAll(list)
    fun removeItems(list: List<T>, removedIds: Set<String>) = writeAll(list)
}

private class SingleFileStorage<T : Record>(dir: File, name: String, serializer: KSerializer<T>) : RecordStorage<T> {
    private val file = JsonFile(dir, name, ListSerializer(serializer)) { emptyList() }
    override fun readAll(): List<T> = file.read()
    override fun writeAll(list: List<T>) = file.writeAsync(list)
}

@Serializable
private data class RouteMeta(
    val id: String,
    val name: String,
    val profile: RoadProfile = RoadProfile.STRAIGHT,
    val createdAt: Long = 0L,
)

/**
 * Splits routes into a tiny metadata index (`routes-meta.json`) plus one file per route
 * (`routes/<id>.json`). Reordering routes only rewrites the metadata index instead of
 * re-serialising megabytes of route coordinates; migrates legacy `routes.json` seamlessly.
 */
private class SplitRouteStorage(dir: File) : RecordStorage<SavedRoute> {
    private val legacyFile = JsonFile(dir, "routes.json", ListSerializer(SavedRoute.serializer())) { emptyList() }
    private val metaFile = JsonFile(dir, "routes-meta.json", ListSerializer(RouteMeta.serializer())) { emptyList() }
    private val routesDir = File(dir, "routes")

    private fun routeFile(id: String): JsonFile<SavedRoute?> {
        val safe = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return JsonFile(routesDir, "$safe.json", SavedRoute.serializer().nullable) { null }
    }

    private fun metaOf(list: List<SavedRoute>) = list.map { RouteMeta(it.id, it.name, it.profile, it.createdAt) }

    override fun readAll(): List<SavedRoute> {
        if (metaFile.baseFile.exists()) {
            val metas = metaFile.read()
            return metas.mapNotNull { m -> routeFile(m.id).read() }
        }
        if (!legacyFile.baseFile.exists()) return emptyList()
        val legacy = legacyFile.read()
        // Migrate legacy monolithic routes.json into split files on the same disk thread.
        routesDir.mkdirs()
        for (r in legacy) runCatching { routeFile(r.id).write(r) }
        runCatching { metaFile.write(metaOf(legacy)) }
        runCatching { legacyFile.baseFile.delete() }
        return legacy
    }

    override fun writeAll(list: List<SavedRoute>) {
        val metas = metaOf(list)
        diskWriter.execute {
            runCatching {
                routesDir.mkdirs()
                val keepNames = HashSet<String>(list.size)
                for (r in list) {
                    val f = routeFile(r.id)
                    keepNames.add(f.baseFile.name)
                    f.write(r)
                }
                routesDir.listFiles()?.forEach { child ->
                    if (child.name !in keepNames) child.delete()
                }
                metaFile.write(metas)
            }
        }
    }

    override fun writeOrder(list: List<SavedRoute>) {
        metaFile.writeAsync(metaOf(list))
    }

    override fun writeItem(list: List<SavedRoute>, item: SavedRoute) {
        val metas = metaOf(list)
        diskWriter.execute {
            runCatching {
                routesDir.mkdirs()
                routeFile(item.id).write(item)
                metaFile.write(metas)
            }
        }
    }

    override fun writeItems(list: List<SavedRoute>, items: List<SavedRoute>) {
        val metas = metaOf(list)
        diskWriter.execute {
            runCatching {
                routesDir.mkdirs()
                for (item in items) routeFile(item.id).write(item)
                metaFile.write(metas)
            }
        }
    }

    override fun removeItems(list: List<SavedRoute>, removedIds: Set<String>) {
        val metas = metaOf(list)
        diskWriter.execute {
            runCatching {
                for (id in removedIds) routeFile(id).baseFile.delete()
                metaFile.write(metas)
            }
        }
    }
}

/** A list of records with ordered async persistence. */
class RecordList<T : Record> internal constructor(private val storage: RecordStorage<T>) {
    internal constructor(dir: File, name: String, serializer: KSerializer<T>) :
        this(SingleFileStorage(dir, name, serializer))

    private val flow = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = flow.asStateFlow()

    @Synchronized internal fun loadFromDisk() {
        flow.value = storage.readAll()
    }

    fun find(id: String): T = flow.value.firstOrNull { it.id == id } ?: fail("记录不存在，可能已经删除。")

    /** Adds or replaces (same id) a record at the top. */
    @Synchronized fun put(item: T) {
        val next = listOf(item) + flow.value.filter { it.id != item.id }
        if (next.size > Validate.MAX_RECORDS) fail("记录已达 ${Validate.MAX_RECORDS} 条，请先导出并清理。")
        flow.value = next
        storage.writeItem(next, item)
    }

    /** Replaces an existing record in place so custom ordering is preserved; prepends if absent. */
    @Synchronized fun replaceInPlace(item: T) {
        val current = flow.value
        val index = current.indexOfFirst { it.id == item.id }
        val next = if (index >= 0) current.toMutableList().apply { set(index, item) }
        else {
            if (current.size + 1 > Validate.MAX_RECORDS) fail("记录已达 ${Validate.MAX_RECORDS} 条，请先导出并清理。")
            listOf(item) + current
        }
        flow.value = next
        storage.writeItem(next, item)
    }

    /** Moves the record with [id] to the top (index 0). */
    @Synchronized fun moveToTop(id: String) {
        val current = flow.value
        val index = current.indexOfFirst { it.id == id }
        if (index <= 0) return
        val next = current.toMutableList()
        val item = next.removeAt(index)
        next.add(0, item)
        flow.value = next
        storage.writeOrder(next)
    }

    /** Moves the record with [id] by [delta] positions (-1 up, +1 down). */
    @Synchronized fun moveBy(id: String, delta: Int) {
        val current = flow.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        val target = (index + delta).coerceIn(0, current.lastIndex)
        if (target == index) return
        val next = current.toMutableList()
        val item = next.removeAt(index)
        next.add(target, item)
        flow.value = next
        storage.writeOrder(next)
    }

    /** Room left before [Validate.MAX_RECORDS] once [items] replace the records sharing their ids. */
    fun fits(items: List<T>): Boolean {
        val ids = items.mapTo(HashSet()) { it.id }
        return ids.size + flow.value.count { it.id !in ids } <= Validate.MAX_RECORDS
    }

    /** Adds or replaces many records with one write; [items] end up on top in the given order. */
    @Synchronized fun putAll(items: List<T>) {
        if (items.isEmpty()) return
        if (!fits(items)) fail("记录已达 ${Validate.MAX_RECORDS} 条，请先导出并清理。")
        val unique = items.distinctBy { it.id }
        val ids = unique.mapTo(HashSet()) { it.id }
        val next = unique + flow.value.filter { it.id !in ids }
        flow.value = next
        storage.writeItems(next, unique)
    }

    @Synchronized fun remove(ids: Set<String>) {
        if (ids.isEmpty()) return
        val next = flow.value.filter { it.id !in ids }
        flow.value = next
        storage.removeItems(next, ids)
    }

    @Synchronized fun clear() {
        val removed = flow.value.mapTo(HashSet()) { it.id }
        flow.value = emptyList()
        storage.removeItems(emptyList(), removed)
    }
}

/**
 * All of the app's own state. Records and settings live in JSON files under filesDir; the runtime is
 * kept in memory and saved only on important changes (a playing route changes it every second).
 */
class Store(context: Context) {
    private val dir = File(context.filesDir, "store")

    private val settingsFile = JsonFile(dir, "settings.json", Settings.serializer()) { Settings() }
    private val runtimeFile = JsonFile(dir, "runtime.json", Runtime.serializer()) { Runtime() }
    private val logFile = JsonFile(dir, "log.json", ListSerializer(LogEntry.serializer())) { emptyList() }

    /** AMap Android (SDK) key: official map, search and routes. Kept apart from settings so it never ends up in an export. */
    private val sdkKeyFile = JsonFile(dir, "amap-sdk-key.json", kotlinx.serialization.serializer<String>()) { "" }

    /** Optional custom-map-style id made in the AMap console; applied to the official map when set. */
    private val sdkStyleIdFile = JsonFile(dir, "amap-style-id.json", kotlinx.serialization.serializer<String>()) { "" }

    private val _ready = MutableStateFlow(false)
    /** Becomes true once initial disk files have finished loading off the main thread. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    // Nothing keeps running across a process restart; presets stay selected but switched off.
    private val _runtime = MutableStateFlow(Runtime())
    val runtime: StateFlow<Runtime> = _runtime.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _sdkKey = MutableStateFlow("")
    val sdkKey: StateFlow<String> = _sdkKey.asStateFlow()

    private val _sdkStyleId = MutableStateFlow("")
    val sdkStyleId: StateFlow<String> = _sdkStyleId.asStateFlow()

    val places = RecordList(dir, "places.json", SavedPlace.serializer())
    val routes = RecordList(SplitRouteStorage(dir))
    val wifiPresets = RecordList(dir, "wifi.json", WifiPreset.serializer())
    val cellPresets = RecordList(dir, "cells.json", CellPreset.serializer())
    val environments = RecordList(dir, "environments.json", EnvironmentRecord.serializer())

    init {
        // Load all store files on the single ordered disk thread so Application.onCreate never blocks on IO.
        diskWriter.execute {
            dir.mkdirs()
            // The AMap Web service key (3.1.0 and earlier) is no longer used.
            File(dir, "map-key.json").delete()
            synchronized(this) {
                _settings.value = settingsFile.read()
                _runtime.value = runtimeFile.read().copy(
                    enabled = false, paused = false, joystick = false,
                    wifiEnabled = false, cellEnabled = false, error = "", speed = 0.0, anchor = null,
                )
                _logs.value = logFile.read()
                _sdkKey.value = sdkKeyFile.read()
                _sdkStyleId.value = sdkStyleIdFile.read()
            }
            places.loadFromDisk()
            routes.loadFromDisk()
            wifiPresets.loadFromDisk()
            cellPresets.loadFromDisk()
            environments.loadFromDisk()
            _ready.value = true
        }
    }

    @Synchronized fun updateSettings(change: (Settings) -> Settings): Settings {
        val next = change(_settings.value)
        if (next != _settings.value) {
            _settings.value = next
            settingsFile.writeAsync(next)
        }
        return next
    }

    /** [persist]: also save to disk (selection, presets, errors); moving positions are not saved every step. */
    @Synchronized fun updateRuntime(persist: Boolean = true, change: (Runtime) -> Runtime): Runtime {
        val next = change(_runtime.value)
        _runtime.value = next
        if (persist) runtimeFile.writeAsync(next)
        return next
    }

    @Synchronized fun setSdkKey(key: String) {
        _sdkKey.value = key
        sdkKeyFile.writeAsync(key)
    }

    @Synchronized fun setSdkStyleId(id: String) {
        val trimmed = id.trim()
        _sdkStyleId.value = trimmed
        sdkStyleIdFile.writeAsync(trimmed)
    }

    @Synchronized fun log(message: String) {
        val next = (listOf(LogEntry(System.currentTimeMillis(), message)) + _logs.value).take(100)
        _logs.value = next
        logFile.writeAsync(next)
    }

    @Synchronized fun clearLogs() {
        _logs.value = emptyList()
        logFile.writeAsync(emptyList())
    }
}
