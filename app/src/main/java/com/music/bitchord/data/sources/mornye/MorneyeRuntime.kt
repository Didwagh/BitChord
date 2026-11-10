package com.music.bitchord.data.sources.mornye

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.evaluate
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One running Mornye addon, isolated in its own QuickJS engine.
 *
 * The engine is created lazily on first use and torn down on [close]. A
 * single instance serves one addon at a time (guarded by [mutex]) so that
 * consecutive `invoke()` calls within the same session share the in-memory
 * state the addon sets up during `initialize()`.
 *
 * The runtime flow mirrors what Lokal / Mornye-desktop does in
 * `electron/morneye/runtime.js`:
 *
 *  1. The bootstrap JS is evaluated. It wires `bridge(method, json)` into the
 *     `http`, `storage`, `credentials`, `file`, `auth`, `crypto` globals.
 *  2. The addon's own JS is evaluated. It calls `registerExtension(...)` to
 *     expose its methods.
 *  3. Callers invoke named methods via [invoke], which serialises the args,
 *     calls the JS function, and returns the deserialised result.
 *
 * @param addonKey  stable per-addon key used for storage namespacing.
 * @param jsCode    the addon's compiled JS (the archive's main entry point).
 * @param packageDir  directory containing files bundled in the archive.
 * @param storage   encrypted key-value store for this addon.
 * @param settingsJson  current user-visible settings, forwarded to the bootstrap.
 * @param permissions   the manifest's declared permission scopes.
 */
class MorneyeRuntime(
    private val addonKey: String,
    private val jsCode: String,
    private val packageDir: File,
    private val storage: MorneyeAddonStorage,
    private val settingsJson: String = "{}",
    private val permissions: Set<String> = setOf("network", "storage"),
) : AutoCloseable {

    companion object {
        private const val TAG = "BitChord/Mornye"
        /** Time JS is allowed to run per call before we give up. */
        private const val TIMEOUT_MS = 60_000L
    }

    private val mutex = Mutex()
    @Volatile private var qjs: QuickJs? = null
    @Volatile private var closed = false

    /** Methods the addon registered via `registerExtension({…})`. */
    var methods: List<String> = emptyList()
        private set

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Ensures the QuickJS engine is initialized and extension methods are discovered.
     */
    suspend fun ensureInitialized() {
        mutex.withLock {
            ensureEngine()
        }
    }

    /**
     * Calls [method] on the addon JS and returns the deserialised result, or
     * null when the addon did not expose that method.
     *
     * Runs on [Dispatchers.IO].
     */
    suspend fun invoke(method: String, args: List<Any?> = emptyList()): Any? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val engine = ensureEngine()
                val argsJson = packArgs(args)
                TrackLog.d(TAG, "[$addonKey] invoke $method args=${argsJson.take(120)}")
                engine.evaluate<Unit>(
                    """
                    var __mornye_resolved_json = undefined;
                    (async function(){
                        try {
                            var ext = globalThis.extension || {};
                            var fn = ext['$method'];
                            if (typeof fn !== 'function') {
                                __mornye_resolved_json = JSON.stringify({error:'no_method'});
                                return;
                            }
                            var r = await Promise.resolve(fn.apply(ext, $argsJson));
                            __mornye_resolved_json = JSON.stringify({ok: true, value: r === undefined ? null : r});
                        } catch(e) {
                            __mornye_resolved_json = JSON.stringify({error: e && e.message ? e.message : String(e)});
                        }
                    })();
                    """.trimIndent()
                )
                val resultJson = engine.evaluate<String>("__mornye_resolved_json || JSON.stringify({error:'no_result'})")
                unpackResult(method, resultJson)
            }
        }

    override fun close() {
        closed = true
        runCatching { qjs?.close() }
        qjs = null
    }

    // ── Engine lifecycle ──────────────────────────────────────────────────

    private suspend fun ensureEngine(): QuickJs {
        qjs?.let { return it }
        return buildEngine().also { qjs = it }
    }

    private suspend fun buildEngine(): QuickJs {
        val callbacks = mutableMapOf<Int, Any>()
        val bridge = MorneyeHostBridge(
            storage = storage,
            packageDir = packageDir,
            invokeCallback = { id, cbArgs ->
                callbacks[id] = cbArgs
                """{"value":null}"""
            },
        )

        val engine = QuickJs.create(Dispatchers.IO)
        engine.maxStackSize = 512 * 1024L

        // Wire in the synchronous host bridge via __mornye_host object binding.
        engine.define("__mornye_host") {
            function("bridge", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val method = args.getOrNull(0)?.toString() ?: return """{"value":null}"""
                    val argsJson = args.getOrNull(1)?.toString() ?: "[]"
                    return bridge.handle(method, argsJson)
                }
            })
        }

        // Inject the config and bridge wrapper that MORNYE_BOOTSTRAP expects.
        val configJson = buildConfigJson()
        engine.evaluate<Unit>(
            """
            var __mornye_config = $configJson;
            var bridge = function(method, argsJson) {
                return __mornye_host.bridge(method, argsJson);
            };
            """.trimIndent()
        )

        // Run the bootstrap to wire all the globals.
        engine.evaluate<Unit>(MORNYE_BOOTSTRAP)

        // Run the addon's own JS.
        engine.evaluate<Unit>(jsCode)

        // Discover what methods the addon registered.
        val methodsJson = runCatching {
            engine.evaluate<String>(
                """
                (function(){
                    var ext = globalThis.extension || {};
                    return JSON.stringify(Object.keys(ext).filter(function(k){
                        return typeof ext[k] === 'function';
                    }));
                })()
                """.trimIndent()
            )
        }.getOrNull() ?: "[]"

        methods = runCatching {
            JSONArray(methodsJson).let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
        }.getOrDefault(emptyList())

        TrackLog.d(TAG, "[$addonKey] engine built, methods=${methods.joinToString()}")
        return engine
    }

    // ── JSON helpers ──────────────────────────────────────────────────────

    private fun buildConfigJson(): String = JSONObject().apply {
        put("settings", runCatching { JSONObject(settingsJson) }.getOrDefault(JSONObject()))
        put("permissions", JSONObject().apply {
            put("storage", "storage" in permissions)
            put("network", JSONArray(permissions.filter { it.startsWith("network") || it == "http" }
                .ifEmpty { listOf("*") }))
        })
    }.toString()

    private fun packArgs(args: List<Any?>): String = JSONArray(args.map { packValue(it) }).toString()

    private fun packValue(value: Any?): Any? = when (value) {
        null, is Boolean, is Number, is String -> value
        is Map<*, *> -> JSONObject(value.mapKeys { it.key.toString() }
            .mapValues { packValue(it.value) })
        is List<*> -> JSONArray(value.map { packValue(it) })
        else -> value.toString()
    }

    private fun unpackResult(method: String, json: String?): Any? {
        if (json == null) return null
        return runCatching {
            val obj = JSONObject(json)
            when {
                obj.has("error") && obj.optString("error") != "no_method" -> {
                    val msg = obj.optString("error")
                    TrackLog.w(TAG, "[$addonKey] $method error: $msg")
                    throw MorneyeAddonException(msg)
                }
                obj.optBoolean("ok") -> obj.opt("value")
                else -> null
            }
        }.getOrElse { e ->
            if (e is MorneyeAddonException) throw e
            TrackLog.w(TAG, "[$addonKey] unpack failed: ${e.message}")
            null
        }
    }
}

/** An error the addon JS explicitly threw (not a host/engine failure). */
class MorneyeAddonException(message: String) : Exception(message)
