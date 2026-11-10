package com.music.bitchord.data.sources.mornye

/**
 * The JavaScript prelude every Mornye add-on runs in.
 *
 * Copied verbatim from the Lokal / Mornye-desktop bootstrap so that the exact
 * same .sflx packages work in both apps without any JS-side changes. It turns
 * the single synchronous `bridge(method, json)` host function into the
 * `http`, `storage`, `credentials`, `file`, `auth` globals that add-on code
 * expects.
 *
 * The Kotlin side implements `bridge(method: String, argsJson: String): String`
 * — see [MorneyeHostBridge].
 */
internal const val MORNYE_BOOTSTRAP = """(function(bridge, config) {
  'use strict';
  const stringify = JSON.stringify, parse = JSON.parse;
  const callbacks = new Map(); let nextCallback = 0, extension;
  function pack(value, seen) {
    seen = seen || [];
    if (value == null) return null;
    if (typeof value === 'function') { const id = ++nextCallback; callbacks.set(id, value); return {__callback:id}; }
    if (value instanceof ArrayBuffer) return {__bytes:Array.from(new Uint8Array(value)),__buffer:true};
    if (ArrayBuffer.isView(value)) return {__bytes:Array.from(new Uint8Array(value.buffer,value.byteOffset,value.byteLength))};
    if (typeof value !== 'object') return value;
    if (seen.length > 100 || seen.indexOf(value) !== -1) throw Error('Cyclic or deeply nested value');
    if (Array.isArray(value)) return value.map(function(v){return pack(v,seen.concat([value]));});
    var out = Object.create(null);
    Object.keys(value).forEach(function(k){out[k]=pack(value[k],seen.concat([value]));});
    return out;
  }
  function unpack(value) {
    if (!value || typeof value !== 'object') return value;
    if (value.__bytes) { var bytes = new Uint8Array(value.__bytes); return value.__buffer ? bytes.buffer : bytes; }
    if (value.__callback) return function() { var args = Array.prototype.slice.call(arguments); return call('callback',[value.__callback,args]); };
    if (Array.isArray(value)) return value.map(unpack);
    var out = {};
    Object.keys(value).forEach(function(k){Object.defineProperty(out,k,{value:unpack(value[k]),writable:true,enumerable:true,configurable:true});});
    return out;
  }
  function call(method, args) {
    args = args || [];
    var result = parse(bridge(method, stringify(pack(args))));
    if (result.failure) throw Error(result.failure);
    return unpack(result.value);
  }
  function goJSON(value) {
    function convert(v, seen) {
      seen = seen || [];
      if (v == null) return null;
      if (v instanceof Uint8Array) return call('base64Bytes',[Array.from(v)]);
      if (typeof v === 'number' && !isFinite(v)) throw Error('Unsupported JSON number');
      if (typeof v !== 'object') {
        if (typeof v === 'function' || typeof v === 'bigint' || typeof v === 'symbol') throw Error('Unsupported JSON value');
        return v;
      }
      if (seen.indexOf(v) !== -1 || seen.length > 100) throw Error('Cyclic JSON');
      if (v instanceof Date) return v.toISOString();
      if (Array.isArray(v)) return v.map(function(x){return convert(x,seen.concat([v]));});
      var out = Object.create(null);
      Object.keys(v).sort().forEach(function(k){out[k]=convert(v[k],seen.concat([v]));});
      return out;
    }
    return stringify(convert(value)).replace(/[<>&\u2028\u2029]/g,function(c){return '\\u'+c.charCodeAt(0).toString(16).padStart(4,'0');});
  }
  globalThis.registerExtension = function(value) { extension = value; globalThis.extension = value; };
  globalThis.settings = unpack(config.settings || {});
  if (config.permissions.storage) {
    globalThis.storage = {
      get: function(key,fallback) { var r=call('storage.get',[String(key)]); return r.found ? r.value : fallback; },
      set: function(key,value) { return call('storage.set',[String(key),parse(goJSON(value))]).success; },
      remove: function(key) { return call('storage.remove',[String(key)]).success; },
      clear: function() { return call('storage.clear').success; },
    };
    globalThis.credentials = {
      get: function(key,fallback) { var r=call('credentials.get',[String(key)]); return r.found ? r.value : fallback; },
      store: function(key,value) { return call('credentials.set',[String(key),parse(goJSON(value))]); },
      remove: function(key) { return call('credentials.remove',[String(key)]).success; },
      has: function(key) { return call('credentials.get',[String(key)]).found; },
    };
  }
  function request(url,method,body,headers,asFetch) {
    body = body == null ? '' : typeof body === 'object' ? goJSON(body) : String(body);
    var r = call('http.request',[String(url),{method:method,body:body,headers:headers||{},asFetch:!!asFetch}]);
    if (!asFetch) return r;
    r.text = function() { return r.body || ''; };
    r.json = function() { try { return parse(r.body); } catch(e) {} };
    r.arrayBuffer = function() { return call('decodeBase64Bytes',[r.binary||'']); };
    return r;
  }
  if (config.permissions.network && config.permissions.network.length) {
    globalThis.http = {
      get: function(url,headers){return request(url,'GET',null,headers);},
      delete: function(url,headers){return request(url,'DELETE',null,headers);},
      post: function(url,body,headers){return request(url,'POST',body,headers);},
      put: function(url,body,headers){return request(url,'PUT',body,headers);},
      patch: function(url,body,headers){return request(url,'PATCH',body,headers);},
      request: function(url,opts){opts=opts||{};return request(url,opts.method||'GET',opts.body,opts.headers);},
      clearCookies: function(){return call('http.clearCookies');},
    };
    globalThis.fetch = function(url,opts){opts=opts||{};return request(url,opts.method||'GET',opts.body,opts.headers,true);};
    globalThis.auth = {};
  }
  globalThis.file = function(path) { return call('file',[String(path)]); };
  globalThis.crypto = {
    cipher: function(data,alg,mode,key,iv,pkcs7,decrypt) {
      return call('crypto.cipher',[data,alg,mode,key,iv,pkcs7,decrypt]);
    },
    hmac: function(data,key,alg) { return call('crypto.hmac',[data,key,alg||'SHA-256']); },
    hash: function(data,alg) { return call('crypto.hash',[data,alg||'SHA-256']); },
    randomBytes: function(n) { return call('crypto.random',[n]); },
    sign: function(data,key,alg) { return call('crypto.sign',[data,key,alg||'RSA-SHA256']); },
  };
  globalThis.console = {
    log: function() { call('log',Array.prototype.slice.call(arguments)); },
    warn: function() { call('log',Array.prototype.slice.call(arguments)); },
    error: function() { call('log',Array.prototype.slice.call(arguments)); },
    info: function() { call('log',Array.prototype.slice.call(arguments)); },
  };
  globalThis.__mornye_config = config;
})(bridge, __mornye_config);"""
