package com.music.bitchord.data.sources.mornye

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.Http
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Installs and manages Mornye addon packages for BitChord.
 *
 * A package is a ZIP archive (.sflx / .morneye-ext / .spotiflac-ext) holding:
 *  - `manifest.json` — name, version, type, permissions, settings schema.
 *  - One or more `.js` files — the addon's entry point (named in the manifest
 *    as `main`, defaulting to `index.js`).
 *  - Optional bundled assets readable via the `file()` host API.
 *
 * Installed packages live under `<files>/mornye/packages/<key>/` and their
 * metadata is persisted in [SharedPreferences] so they survive restarts.
 *
 * One [MorneyeRuntime] is kept per enabled addon, initialised lazily on first
 * search/download call.  Runtimes are torn down when the addon is removed or
 * when [release] is called.
 */
object MorneyePackageManager {

    private const val TAG = "BitChord/Mornye"
    private const val REGISTRY_URL =
        "https://raw.githubusercontent.com/mornyeapp/Mornye-Extension/refs/heads/main/registry.json"
    private const val COMPAT_VERSION = "5.3.0"
    private const val PREFS_KEY = "mornye_packages"
    private const val PREFS_REGISTRY = "mornye_registry"

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var packagesDir: File

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    private val _packages = MutableStateFlow<List<InstalledMorneyePackage>>(emptyList())
    val packages: StateFlow<List<InstalledMorneyePackage>> = _packages.asStateFlow()

    /** Active runtimes, keyed by addon key. Created lazily, closed on removal. */
    private val runtimes = mutableMapOf<String, MorneyeRuntime>()
    private val runtimeLock = Any()

    // ── Init ──────────────────────────────────────────────────────────────

    fun init(context: Context) {
        this.context = context.applicationContext
        this.packagesDir = File(context.filesDir, "mornye/packages").also { it.mkdirs() }
        this.prefs = context.getSharedPreferences("mornye_meta", Context.MODE_PRIVATE)
        _packages.value = loadPersistedPackages()
    }

    // ── Package installation ──────────────────────────────────────────────

    /**
     * Installs or updates a package from raw ZIP bytes.
     *
     * Validates the manifest, extracts the archive into the package directory,
     * and publishes the updated list. Throws [MorneyeInstallException] if the
     * archive is not a valid Mornye addon.
     */
    suspend fun installFromBytes(bytes: ByteArray): InstalledMorneyePackage =
        withContext(Dispatchers.IO) {
            val digest = sha256(bytes)
            val (manifest, jsCode, assets) = parseArchive(bytes)
            val name   = manifest.optString("name").trim()
            val dname  = manifest.optString("displayName").trim()
            val version= manifest.optString("version", "0.0.0")
            if (name.isEmpty()) throw MorneyeInstallException("manifest missing name")
            if (dname.isEmpty()) throw MorneyeInstallException("manifest missing displayName")

            validateManifest(manifest)

            val key = keyOf(name)
            val existing = _packages.value.firstOrNull { it.key == key }
            if (existing?.digest == digest) {
                TrackLog.d(TAG, "Package $name already installed at same digest")
                return@withContext existing
            }

            // Extract to package directory.
            val dir = File(packagesDir, key).also { it.mkdirs() }
            dir.listFiles()?.forEach { it.deleteRecursively() }

            // Write manifest.
            File(dir, "manifest.json").writeText(manifest.toString())
            // Write JS entry point.
            val mainEntry = manifest.optString("main", "index.js").trimStart('/')
            val jsFile = File(dir, mainEntry).also { it.parentFile?.mkdirs() }
            jsFile.writeText(jsCode)
            // Write other bundled assets.
            assets.forEach { (path, data) ->
                File(dir, path.trimStart('/')).also { it.parentFile?.mkdirs() }.writeBytes(data)
            }

            val installed = InstalledMorneyePackage(
                key          = key,
                name         = name,
                displayName  = dname,
                version      = version,
                description  = manifest.optString("description"),
                digest       = digest,
                installedAt  = System.currentTimeMillis(),
                manifestJson = manifest.toString(),
            )

            // Tear down any old runtime for this key.
            closeRuntime(key)

            val updated = _packages.value.filter { it.key != key } + installed
            persistPackages(updated)
            _packages.value = updated

            TrackLog.d(TAG, "Installed Mornye addon: $name v$version")
            installed
        }

    /**
     * Installs a package from a registry URL.
     */
    suspend fun installFromUrl(downloadUrl: String, repositoryId: String? = null): InstalledMorneyePackage =
        withContext(Dispatchers.IO) {
            val bytes = httpFetch(downloadUrl)
            val pkg = installFromBytes(bytes)
            if (repositoryId != null) {
                val updated = _packages.value.map {
                    if (it.key == pkg.key) it.copy(repositoryId = repositoryId) else it
                }
                persistPackages(updated)
                _packages.value = updated
                updated.first { it.key == pkg.key }
            } else {
                pkg
            }
        }

    // ── Package management ────────────────────────────────────────────────

    fun setEnabled(key: String, enabled: Boolean) {
        val updated = _packages.value.map { if (it.key == key) it.copy(enabled = enabled) else it }
        persistPackages(updated)
        _packages.value = updated
        if (!enabled) closeRuntime(key)
    }

    fun remove(key: String) {
        closeRuntime(key)
        File(packagesDir, key).deleteRecursively()
        MorneyeAddonStorage(context, key).purge()
        val updated = _packages.value.filter { it.key != key }
        persistPackages(updated)
        _packages.value = updated
    }

    // ── Registry ──────────────────────────────────────────────────────────

    /**
     * Fetches the official Mornye registry and returns all entries, annotated
     * with whether each is already installed and whether an update is available.
     */
    suspend fun fetchRegistry(): List<MorneyeRegistryEntry> = withContext(Dispatchers.IO) {
        val bytes = httpFetch(REGISTRY_URL)
        val array = JSONArray(bytes.toString(Charsets.UTF_8))
        val installed = _packages.value.associateBy { it.name }
        (0 until array.length()).mapNotNull { i ->
            runCatching {
                val obj = array.getJSONObject(i)
                val name = obj.optString("name")
                val version = obj.optString("version", "0.0.0")
                val minApp = obj.optString("minAppVersion", "0.0.0")
                val compatible = compareVersions(minApp, COMPAT_VERSION) <= 0
                val inst = installed[name]
                MorneyeRegistryEntry(
                    id           = obj.optString("id", name),
                    name         = name,
                    displayName  = obj.optString("displayName", name),
                    version      = version,
                    description  = obj.optString("description"),
                    downloadUrl  = obj.optString("download_url"),
                    compatible   = compatible,
                    installed    = inst,
                    repositoryId = "official",
                    category     = obj.optString("category", "download"),
                )
            }.getOrNull()
        }
    }

    // ── Runtime access ────────────────────────────────────────────────────

    /**
     * Returns (or lazily creates) the [MorneyeRuntime] for [key].
     * Returns null when the addon is not installed or not enabled.
     */
    internal fun runtime(key: String): MorneyeRuntime? = synchronized(runtimeLock) {
        val pkg = _packages.value.firstOrNull { it.key == key && it.enabled } ?: return null
        runtimes.getOrPut(key) { buildRuntime(pkg) }
    }

    fun capabilities(key: String): MorneyeCapabilities? {
        val pkg = _packages.value.firstOrNull { it.key == key } ?: return null
        val manifest = runCatching { JSONObject(pkg.manifestJson) }.getOrDefault(JSONObject())
        return capabilitiesOf(manifest.toMap())
    }

    internal fun storage(key: String): MorneyeAddonStorage = MorneyeAddonStorage(context, key)

    fun currentSettings(key: String): String {
        // Settings are stored per-addon as a JSON object in encrypted prefs.
        return storage(key).get("__settings") ?: "{}"
    }

    fun saveSettings(key: String, settingsJson: String) {
        storage(key).set("__settings", settingsJson)
        // Restart runtime so it picks up the new settings on next initialize().
        closeRuntime(key)
    }

    /** Drop all runtimes — called on app background to free memory. */
    fun release() = synchronized(runtimeLock) {
        runtimes.values.forEach { runCatching { it.close() } }
        runtimes.clear()
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private fun buildRuntime(pkg: InstalledMorneyePackage): MorneyeRuntime {
        val dir = File(packagesDir, pkg.key)
        val manifest = runCatching { JSONObject(pkg.manifestJson) }.getOrDefault(JSONObject())
        val mainEntry = manifest.optString("main", "index.js").trimStart('/')
        val jsCode = File(dir, mainEntry).readText()
        val permissions = runCatching {
            val perms = manifest.optJSONArray("permissions") ?: JSONArray()
            (0 until perms.length()).map { perms.getString(it) }.toSet()
        }.getOrDefault(setOf("network", "storage"))
        return MorneyeRuntime(
            addonKey    = pkg.key,
            jsCode      = jsCode,
            packageDir  = dir,
            storage     = MorneyeAddonStorage(context, pkg.key),
            settingsJson = currentSettings(pkg.key),
            permissions = permissions + setOf("network", "storage"),
        )
    }

    private fun closeRuntime(key: String) = synchronized(runtimeLock) {
        runtimes.remove(key)?.also { runCatching { it.close() } }
    }

    private fun parseArchive(bytes: ByteArray): Triple<JSONObject, String, Map<String, ByteArray>> {
        var manifest: JSONObject? = null
        var jsCode: String? = null
        val assets = mutableMapOf<String, ByteArray>()

        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name.trimStart('/')
                when {
                    name == "manifest.json" -> {
                        manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    }
                    name.endsWith(".js") && jsCode == null &&
                        name !in setOf("bootstrap.js") -> {
                        jsCode = zip.readBytes().toString(Charsets.UTF_8)
                        // Track which file this was so we can write it back.
                        assets[name] = jsCode!!.toByteArray()
                    }
                    name.isNotEmpty() && !entry.isDirectory &&
                        !name.startsWith("__MACOSX") -> {
                        assets[name] = zip.readBytes()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        // If the manifest names a specific main entry, prefer that JS file.
        val mf = manifest ?: throw MorneyeInstallException("Archive has no manifest.json")
        val mainEntry = mf.optString("main", "").trimStart('/')
        if (mainEntry.isNotEmpty() && mainEntry in assets) {
            jsCode = assets[mainEntry]!!.toString(Charsets.UTF_8)
        }

        val code = jsCode ?: throw MorneyeInstallException("Archive has no JavaScript entry point")
        return Triple(mf, code, assets)
    }

    private fun validateManifest(manifest: JSONObject) {
        val name = manifest.optString("name")
        if (!Regex("^[a-z0-9][a-z0-9_-]{0,99}$").matches(name))
            throw MorneyeInstallException("Invalid package name: $name")
        val version = manifest.optString("version", "")
        if (!Regex("^\\d+\\.\\d+\\.\\d+.*").matches(version))
            throw MorneyeInstallException("Invalid version: $version")
        val type = manifest.optJSONArray("type")
            ?: throw MorneyeInstallException("manifest missing type array")
        val types = (0 until type.length()).map { type.getString(it) }.toSet()
        if ("download_provider" !in types && "metadata_provider" !in types)
            throw MorneyeInstallException(
                "Only download_provider or metadata_provider addons can be installed"
            )
        val minApp = manifest.optString("minAppVersion", "0.0.0")
        if (compareVersions(minApp, COMPAT_VERSION) > 0)
            throw MorneyeInstallException(
                "Addon requires Mornye runtime $minApp (BitChord supports $COMPAT_VERSION)"
            )
    }

    private fun loadPersistedPackages(): List<InstalledMorneyePackage> {
        val raw = prefs.getString(PREFS_KEY, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(InstalledMorneyePackage.serializer()), raw)
        }.getOrDefault(emptyList())
    }

    private fun persistPackages(list: List<InstalledMorneyePackage>) {
        prefs.edit()
            .putString(PREFS_KEY, json.encodeToString(ListSerializer(InstalledMorneyePackage.serializer()), list))
            .apply()
    }

    private suspend fun httpFetch(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("User-Agent", "BitChord/Mornye")
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw MorneyeInstallException("HTTP ${response.code} from $url")
            response.body?.bytes() ?: throw MorneyeInstallException("Empty response from $url")
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────

    /** Stable per-addon storage key: SHA-1 of "spotiflac:<name>", first 10 hex chars. */
    fun keyOf(name: String): String {
        val bytes = MessageDigest.getInstance("SHA-1")
            .digest("spotiflac:$name".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(10)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.split(".", "-").take(3)
            .map { it.filter { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val la = parts(a); val lb = parts(b)
        for (i in 0 until maxOf(la.size, lb.size)) {
            val diff = (la.getOrElse(i) { 0 }) - (lb.getOrElse(i) { 0 })
            if (diff != 0) return diff.sign
        }
        return 0
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for (key in keys()) map[key] = opt(key)
        return map
    }

    private val Int.sign: Int get() = when { this > 0 -> 1; this < 0 -> -1; else -> 0 }
}

class MorneyeInstallException(message: String) : Exception(message)
