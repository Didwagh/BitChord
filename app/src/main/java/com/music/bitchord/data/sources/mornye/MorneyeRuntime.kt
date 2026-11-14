package com.music.bitchord.data.sources.mornye

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
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
 * The runtime flow:
 *  1. The bootstrap JS is evaluated. It wires `bridge(method, json)` into the
 *     `http`, `storage`, `credentials`, `file`, `auth`, `crypto` globals.
 *  2. The addon's own JS is evaluated. It calls `registerExtension(...)` to
 *     expose its methods on `globalThis.extension`.
 *  3. Callers invoke named methods via [invoke], which serialises the args,
 *     calls the JS function, and returns the deserialised result.
 *
 * The sync bridge:
 *  Mornye's bootstrap calls `bridge(method, argsJson)` synchronously. We
 *  expose `bridge` as a property on a `__mornye_host` JS object (using
 *  [FunctionBinding] which is non-suspend), then alias it as a global via a
 *  one-liner. HTTP calls inside [MorneyeHostBridge.handle] use OkHttp's
 *  blocking `execute()` — safe on the IO dispatcher we assign the engine.
 */
internal class MorneyeRuntime(
    private val addonKey: String,
    private val jsCode: String,
    private val packageDir: File,
    /** Directory where the addon may write temporary and downloaded files. */
    private val cacheDir: File,
    private val storage: MorneyeAddonStorage,
    private val settingsJson: String = "{}",
    private val permissions: Set<String> = setOf("network", "storage"),
) : AutoCloseable {

    companion object {
        private const val TAG = "BitChord/Mornye"
    }

    private val mutex = Mutex()
    @Volatile private var qjs: QuickJs? = null
    @Volatile private var closed = false

    /** Methods the addon registered via `registerExtension({…})`. */
    var methods: List<String> = emptyList()
        private set

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Calls [method] on the addon JS and returns the deserialised result, or
     * null when the addon did not expose that method.
     */
    suspend fun invoke(method: String, args: List<Any?> = emptyList()): Any? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val engine = ensureEngine()
                val argsJson = packArgs(args)
                TrackLog.d(TAG, "[$addonKey] invoke $method")
                val resultJson = engine.evaluate<String>("""
                    (function(){
                        try {
                            var ext = globalThis.extension || {};
                            var fn = ext['$method'];
                            if (typeof fn !== 'function') return JSON.stringify({error:'no_method'});
                            var r = fn.apply(ext, $argsJson);
                            if (r && typeof r.then === 'function') {
                                return r.then(
                                    function(v){ return JSON.stringify({ok:true,value:v===undefined?null:v}); },
                                    function(e){ return JSON.stringify({error:e&&e.message?e.message:String(e)}); }
                                );
                            }
                            return JSON.stringify({ok:true,value:r===undefined?null:r});
                        } catch(e) {
                            return JSON.stringify({error:e&&e.message?e.message:String(e)});
                        }
                    })()
                """.trimIndent())
                unpackResult(method, resultJson)
            }
        }

    override fun close() {
        closed = true
        runCatching { qjs?.close() }
        qjs = null
    }

    // ── Engine lifecycle ────────────────────────────────────────────────

    private suspend fun ensureEngine(): QuickJs {
        qjs?.let { return it }
        return buildEngine().also { qjs = it }
    }

    private suspend fun buildEngine(): QuickJs {
        val bridge = MorneyeHostBridge(
            storage = storage,
            packageDir = packageDir,
            cacheDir = cacheDir.also { it.mkdirs() },
            invokeCallback = { _, _ -> """{"value":null}""" },
        )

        // Use Dispatchers.IO so blocking HTTP inside FunctionBinding is fine.
        val engine = QuickJs.create(Dispatchers.IO)
        engine.maxStackSize = 512 * 1024L

        try {
            // Expose the host bridge as __mornye_host.call(method, argsJson) so
            // FunctionBinding (which is sync/non-suspend) can run blocking HTTP.
            engine.define("__mornye_host") {
                function("call", object : FunctionBinding<String> {
                    override fun invoke(args: Array<Any?>): String {
                        val method   = args.getOrNull(0)?.toString() ?: return """{"value":null}"""
                        val argsJson = args.getOrNull(1)?.toString() ?: "[]"
                        return bridge.handle(method, argsJson)
                    }
                })
            }

            // Wire the global `bridge(m, a)` function the bootstrap expects.
            engine.evaluate<Unit>(
                "var bridge = function(m, a) { return __mornye_host.call(m, a); };"
            )

            // Inject the config object the bootstrap reads.
            engine.evaluate<Unit>("var __mornye_config = ${buildConfigJson()};")

            // Run the Mornye bootstrap to wire all the globals.
            engine.evaluate<Unit>(MORNYE_BOOTSTRAP)

            // Run the addon's own JS.
            engine.evaluate<Unit>(jsCode)

            // Discover what the addon exported.
            val methodsJson = runCatching {
                engine.evaluate<String>("""
                    (function(){
                        var ext = globalThis.extension || {};
                        return JSON.stringify(Object.keys(ext).filter(function(k){
                            return typeof ext[k] === 'function';
                        }));
                    })()
                """.trimIndent())
            }.getOrNull() ?: "[]"

            methods = runCatching {
                JSONArray(methodsJson).let { a -> (0 until a.length()).map { a.getString(it) } }
            }.getOrDefault(emptyList())

            TrackLog.d(TAG, "[$addonKey] engine ready, methods=${methods.joinToString()}")
            return engine
        } catch (e: Throwable) {
            engine.close()
            throw e
        }
    }

    // ── JSON helpers ────────────────────────────────────────────────────

    private fun buildConfigJson(): String = JSONObject().apply {
        put("settings", runCatching { JSONObject(settingsJson) }.getOrDefault(JSONObject()))
        put("permissions", JSONObject().apply {
            put("storage", "storage" in permissions)
            put("network", JSONArray(
                permissions.filter { it.startsWith("network") || it == "http" }
                    .ifEmpty { listOf("*") }
            ))
        })
    }.toString()

    private fun packArgs(args: List<Any?>): String =
        JSONArray(args.map { packValue(it) }).toString()

    private fun packValue(value: Any?): Any? = when (value) {
        null, is Boolean, is Number, is String -> value
        is Map<*, *> -> JSONObject(
            value.mapKeys { it.key.toString() }.mapValues { packValue(it.value) }
        )
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
            null
        }
    }
}

/** An error the addon JS explicitly threw. */
class MorneyeAddonException(message: String) : Exception(message)
