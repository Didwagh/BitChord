package com.music.bitchord.data.sources.mornye

import android.content.Context
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.AsyncFunctionBinding
import com.dokar.quickjs.binding.FunctionBinding
import com.dokar.quickjs.binding.define
import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Sandboxed QuickJS host for Mornye extensions. Mirrors the proven engine pool
 * and two-evaluate call pattern of the legacy QuickJsExecutor, but loads the
 * Mornye prelude (registerExtension + host API) instead of the Spine wrapper.
 * One engine per extension, reused across search/stream so initialize() state
 * (tokens) survives. Storage is a per-extension JSON file under filesDir.
 */
internal object MornyeEngine {
    private const val TAG = "BitChord"
    lateinit var appContext: Context
        private set
    fun init(ctx: Context) { appContext = ctx }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val lock = Any()
    private val engines = LinkedHashMap<String, QuickJs>(16, 0.75f, true)
    private const val MAX = 12

    suspend fun load(ext: MornyePackage.Ext, settingsJson: String): Result<Unit> = withContext(Dispatchers.Default) {
        runCatching {
            synchronized(lock) {
                if (engines.containsKey(ext.id)) return@runCatching
                while (engines.size >= MAX) {
                    val k = engines.keys.first(); engines.remove(k)?.let { runCatching { it.close() } }
                }
            }
            val qjs = QuickJs.create(Dispatchers.Default)
            qjs.maxStackSize = 512 * 1024L
            try {
                bind(qjs, ext)
                qjs.evaluate<Unit>(PRELUDE)
                qjs.evaluate<Unit>("var __mornye_settings = $settingsJson; var settings = __mornye_settings;")
                qjs.evaluate<Unit>(ext.js)
                val registered = qjs.evaluate<String>("(__mornye_ext ? 'yes' : 'no')")
                if (registered != "yes") throw IllegalStateException("extension did not call registerExtension()")
                synchronized(lock) { engines[ext.id] = qjs }
            } catch (e: Throwable) { runCatching { qjs.close() }; throw e }
        }
    }

    suspend fun call(extId: String, fn: String, argsJson: String): Result<String> = withContext(Dispatchers.Default) {
        val qjs = synchronized(lock) { engines[extId] }
            ?: return@withContext Result.failure(IllegalStateException("extension $extId not loaded"))
        runCatching {
            val fnLit = JSONObject.quote(fn)
            val argsLit = JSONObject.quote(argsJson)
            qjs.evaluate<String>(
                """
                var __mornye_resolved_json = undefined;
                (async function() {
                    try {
                        var f = __mornye_ext && __mornye_ext[$fnLit];
                        if (typeof f !== 'function') { __mornye_resolved_json = JSON.stringify({success:false,error:'no '+$fnLit}); return; }
                        var r = await f.apply(__mornye_ext, JSON.parse($argsLit));
                        __mornye_resolved_json = (typeof r === 'string') ? r : JSON.stringify(r);
                    } catch(e) { __mornye_resolved_json = JSON.stringify({success:false,error:(e&&e.message)||String(e)}); }
                })();
                """.trimIndent()
            )
            qjs.evaluate<String>("__mornye_resolved_json")
        }
    }

    fun unload(extId: String) { synchronized(lock) { engines.remove(extId) }?.let { runCatching { it.close() } } }

    // ---- storage ----
    private val storeLock = Mutex()
    private fun storeFile(ext: MornyePackage.Ext) = File(ext.dir, "storage.json")
    private suspend fun readStore(ext: MornyePackage.Ext): JSONObject = storeLock.withLock {
        val f = storeFile(ext); if (!f.exists()) return JSONObject()
        runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
    }
    private suspend fun writeStore(ext: MornyePackage.Ext, o: JSONObject) = storeLock.withLock {
        storeFile(ext).writeText(o.toString())
    }

    private suspend fun bind(qjs: QuickJs, ext: MornyePackage.Ext) {
        qjs.define("__mornye") {
            function("log", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) { TrackLog.d(TAG, "[mornye:${ext.id}] ${args.joinToString(" ")}") }
            })
            function("http", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val method = args.getOrNull(0)?.toString() ?: "GET"
                    val url = args.getOrNull(1)?.toString() ?: ""
                    val body = args.getOrNull(2)?.toString()?.takeIf { it != "null" }
                    val headers = args.getOrNull(3)?.toString() ?: "{}"
                    return try {
                        val (code, text, hdr) = fetchSync(url, method, headers, body)
                        JSONObject().put("ok", code in 200..299).put("statusCode", code)
                            .put("body", text).put("url", url).put("headers", hdr).toString()
                    } catch (e: Exception) {
                        JSONObject().put("ok", false).put("statusCode", 0).put("body", "").put("error", e.message ?: "error").toString()
                    }
                }
            })
            asyncFunction("fetch", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    val url = args.getOrNull(0)?.toString() ?: ""
                    val method = args.getOrNull(1)?.toString() ?: "GET"
                    val headers = args.getOrNull(2)?.toString() ?: "{}"
                    val body = args.getOrNull(3)?.toString()?.takeIf { it != "null" }
                    val (code, text, _) = fetchSync(url, method, headers, body)
                    return JSONObject().put("status", code).put("ok", code in 200..299).put("body", text).toString()
                }
            })
            asyncFunction("setTimeout", object : AsyncFunctionBinding<String> {
                override suspend fun invoke(args: Array<Any?>): String {
                    kotlinx.coroutines.delay(args.getOrNull(1)?.toString()?.toLongOrNull() ?: 0L); return "0"
                }
            })
            function("storageGet", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val k = args.getOrNull(0)?.toString() ?: ""
                    val o = kotlinx.coroutines.runBlocking { readStore(ext) }
                    return if (o.has(k)) o.get(k).toString() else ""
                }
            })
            function("storageSet", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    val k = args.getOrNull(0)?.toString() ?: return
                    val v = args.getOrNull(1)?.toString() ?: "null"
                    kotlinx.coroutines.runBlocking { val o = readStore(ext); o.put(k, JSONObject.wrap(org.json.JSONTokener(v).nextValue())); writeStore(ext, o) }
                }
            })
            function("storageRemove", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    val k = args.getOrNull(0)?.toString() ?: return
                    kotlinx.coroutines.runBlocking { val o = readStore(ext); o.remove(k); writeStore(ext, o) }
                }
            })
            function("storageClear", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) { kotlinx.coroutines.runBlocking { writeStore(ext, JSONObject()) } }
            })
            function("readFile", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val rel = args.getOrNull(0)?.toString() ?: return ""
                    val f = File(ext.dir, rel); return if (f.exists()) f.readText() else ""
                }
            })
            function("writeFile", object : FunctionBinding<Unit> {
                override fun invoke(args: Array<Any?>) {
                    val rel = args.getOrNull(0)?.toString() ?: return
                    val data = args.getOrNull(1)?.toString() ?: ""
                    val f = File(ext.dir, rel); f.parentFile?.mkdirs(); f.writeText(data)
                }
            })
            function("exists", object : FunctionBinding<Boolean> {
                override fun invoke(args: Array<Any?>): Boolean = File(ext.dir, args.getOrNull(0)?.toString() ?: "").exists()
            })
            function("localTime", object : FunctionBinding<String> {
                override fun invoke(args: Array<Any?>): String {
                    val c = Calendar.getInstance()
                    return JSONObject().put("hour", c.get(Calendar.HOUR_OF_DAY))
                        .put("timezone", c.timeZone.id ?: "UTC").toString()
                }
            })
        }
    }

    private fun fetchSync(url: String, method: String, headersJson: String, body: String?): Triple<Int, String, JSONObject> {
        val b = Request.Builder().url(url)
        var ua = false
        runCatching {
            val h = JSONObject(headersJson)
            for (k in h.keys()) { val v = h.optString(k); b.header(k, v); if (k.equals("user-agent", true)) ua = true }
        }
        if (!ua) b.header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130 Mobile Safari/537.36")
        val mt = "application/json; charset=utf-8".toMediaType()
        when (method.uppercase(Locale.ROOT)) {
            "POST" -> b.post((body ?: "").toRequestBody(mt))
            "PUT" -> b.put((body ?: "").toRequestBody(mt))
            "DELETE" -> b.delete()
            "HEAD" -> b.head()
            else -> b.get()
        }
        http.newCall(b.build()).execute().use { r ->
            val hdr = JSONObject(); for (n in r.headers.names()) hdr.put(n, r.headers[n])
            return Triple(r.code, r.body?.string() ?: "", hdr)
        }
    }

    // ---- Mornye host prelude (no '$' so Kotlin raw-string interpolation is inert) ----
    private const val PRELUDE = """
var __mornye_ext = null;
function registerExtension(o){ __mornye_ext = o; }
var console = { log:function(){__mornye.log.apply(null,[].slice.call(arguments));},
  info:function(){__mornye.log.apply(null,[].slice.call(arguments));},
  debug:function(){__mornye.log.apply(null,[].slice.call(arguments));},
  warn:function(){__mornye.log.apply(null,[].slice.call(arguments));},
  error:function(){__mornye.log.apply(null,[].slice.call(arguments));} };
var log = console;
function __http(method,url,body,headers){
  var b = (body===undefined||body===null)?null:(typeof body==='string'?body:JSON.stringify(body));
  var h = headers||{};
  return JSON.parse(__mornye.http(method,url,b,JSON.stringify(h)));
}
var http = {
  get:function(u,h){return __http('GET',u,null,h);},
  post:function(u,b,h){return __http('POST',u,b,h);},
  put:function(u,b,h){return __http('PUT',u,b,h);},
  delete:function(u,h){return __http('DELETE',u,null,h);},
  request:function(o){return __http(o.method||'GET',o.url,o.body,o.headers);}
};
var fetch = async function(url,opts){
  opts=opts||{}; var method=opts.method||'GET';
  var headers=opts.headers?(typeof opts.headers==='string'?opts.headers:JSON.stringify(opts.headers)):'{}';
  var body=(opts.body===undefined||opts.body===null)?null:(typeof opts.body==='string'?opts.body:JSON.stringify(opts.body));
  var raw=JSON.parse(await __mornye.fetch(url,method,headers,body));
  var text=raw.body;
  return { ok:raw.ok, status:raw.status, statusText:raw.ok?'OK':'Error',
    json:function(){return JSON.parse(text);}, text:function(){return text;},
    headers:{get:function(){return null;}} };
};
var storage = {
  get:function(k){var s=__mornye.storageGet(k);return s===''?undefined:JSON.parse(s);},
  set:function(k,v){__mornye.storageSet(k,JSON.stringify(v));},
  remove:function(k){__mornye.storageRemove(k);},
  clear:function(){__mornye.storageClear();}
};
var credentials = { store:storage.set, get:storage.get, remove:storage.remove,
  has:function(k){return storage.get(k)!==undefined;} };
var file = {
  read:function(p){return __mornye.readFile(p);},
  write:function(p,d){__mornye.writeFile(p,typeof d==='string'?d:JSON.stringify(d));},
  exists:function(p){return __mornye.exists(p);},
  download:function(u,p,o){return {success:false,error:'file.download not used by BitChord; getDownloadUrl is streamed by the host'};}
};
var auth = {
  isAuthenticated:function(){return !!(settings&&(settings.access_token||settings.token||settings.arl));},
  getTokens:function(){var t=settings&&(settings.access_token||settings.token)||'';return {access_token:t,refresh_token:(settings&&settings.refresh_token)||'',is_expired:false,is_authenticated:!!t};},
  getAuthCode:function(){return (settings&&settings.auth_code)||'';},
  setAuthCode:function(t){if(t&&t.access_token)storage.set('access_token',t.access_token);},
  clearAuth:function(){storage.remove('access_token');},
  openAuthUrl:function(){}
};
var utils = {
  encrypt:function(){return {success:false,error:'crypto not supported in BitChord host'};},
  decrypt:function(){return {success:false,error:'crypto not supported in BitChord host'};},
  generateKey:function(){return {success:false,error:'crypto not supported in BitChord host'};}
};
var gobackend = { getLocalTime:function(){return JSON.parse(__mornye.localTime());} };
var setTimeout = async function(fn,ms){ await __mornye.setTimeout(null,ms||0); if(typeof fn==='function')fn(); return 0; };
var clearTimeout = function(){};
function btoa(s){var c='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';var o='';var i=0;s=String(s);while(i<s.length){var a=s.charCodeAt(i++),b=s.charCodeAt(i++),d=s.charCodeAt(i++);var n=(a<<16)|((isNaN(b)?0:b)<<8)|(isNaN(d)?0:d);o+=c[(n>>18)&63]+c[(n>>12)&63]+(isNaN(b)?'=':c[(n>>6)&63])+(isNaN(d)?'=':c[n&63]);}return o;}
function atob(s){var c='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';s=String(s).replace(/=+$/,'');var o='';var i=0;while(i<s.length){var n=(c.indexOf(s[i++])<<18)|(c.indexOf(s[i++])<<12)|((c.indexOf(s[i++])&63)<<6)|(c.indexOf(s[i++])&63);o+=String.fromCharCode((n>>16)&255);if(i-2<s.length+2)o+=String.fromCharCode((n>>8)&255);o+=String.fromCharCode(n&255);}return o;}
"""
}
