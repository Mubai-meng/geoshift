package app.geoshift.data

import android.content.Context
import android.util.AtomicFile
import app.geoshift.core.SimConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.concurrent.Executors

/** Single background thread for ordered disk writes so main-thread callers never block on file IO. */
private val diskWriter = Executors.newSingleThreadExecutor { r -> Thread(r, "geoshift-store").apply { isDaemon = true } }

/** A value kept as one JSON file, replaced atomically on every write. */
private class JsonFile<T>(dir: File, name: String, private val serializer: KSerializer<T>, private val default: () -> T) {
    private val file = AtomicFile(File(dir, name))

    fun read(): T = try {
        if (!file.baseFile.exists()) default() else SimConfig.JSON.decodeFromString(serializer, String(file.readFully()))
    } catch (_: Throwable) {
        // Unreadable: keep it aside (the next save would replace it) and start from the default.
        runCatching { file.baseFile.copyTo(File(file.baseFile.path + ".unreadable"), overwrite = true) }
        default()
    }

    @Synchronized fun write(value: T) {
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

/** A list of records with its file. */
class RecordList<T : Record> internal constructor(dir: File, name: String, serializer: KSerializer<T>) {
    private val file = JsonFile(dir, name, ListSerializer(serializer)) { emptyList() }
    private val flow = MutableStateFlow<List<T>>(emptyList())
    val items: StateFlow<List<T>> = flow.asStateFlow()

    @Synchronized internal fun loadFromDisk() {
        flow.value = file.read()
    }

    fun find(id: String): T = flow.value.firstOrNull { it.id == id } ?: fail("记录不存在，可能已经删除。")

    /** Adds or replaces (same id) a record at the top. */
    @Synchronized fun put(item: T) {
        val next = listOf(item) + flow.value.filter { it.id != item.id }
        if (next.size > Validate.MAX_RECORDS) fail("记录已达 ${Validate.MAX_RECORDS} 条，请先导出并清理。")
        save(next)
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
        save(next)
    }

    /** Moves the record with [id] to the top (index 0). */
    @Synchronized fun moveToTop(id: String) {
        val current = flow.value
        val index = current.indexOfFirst { it.id == id }
        if (index <= 0) return
        val next = current.toMutableList()
        val item = next.removeAt(index)
        next.add(0, item)
        save(next)
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
        save(next)
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
        save(unique + flow.value.filter { it.id !in ids })
    }

    @Synchronized fun remove(ids: Set<String>) = save(flow.value.filter { it.id !in ids })

    @Synchronized fun clear() = save(emptyList())

    private fun save(list: List<T>) {
        file.write(list)
        flow.value = list
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
    val routes = RecordList(dir, "routes.json", SavedRoute.serializer())
    val wifiPresets = RecordList(dir, "wifi.json", WifiPreset.serializer())
    val cellPresets = RecordList(dir, "cells.json", CellPreset.serializer())
    val environments = RecordList(dir, "environments.json", EnvironmentRecord.serializer())

    init {
        // Load all 10 JSON files on the single ordered disk thread so Application.onCreate never blocks on IO.
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
