package com.music.bitchord.data.sources.mornye

/**
 * JavaScript prelude evaluated before every Mornye addon.
 *
 * Implements the full Mornye host contract so the same .sflx packages
 * work here as in Lokal/Mornye-desktop — including:
 *   - `file` as a callable object with download/read/write/delete/exists/transformPatternedBlocks
 *   - `log` global (addons call log.debug / log.info / etc.)
 *   - `storage`, `credentials`, `http`, `fetch`, `crypto` globals
 */
internal const val MORNYE_BOOTSTRAP = """
(function(bridge, config) {
  'use strict';
  var stringify=JSON.stringify,parse=JSON.parse;
  var callbacks=new Map(),nextCallback=0;
  function pack(value,seen){
    seen=seen||[];
    if(value==null)return null;
    if(typeof value==='function'){var id=++nextCallback;callbacks.set(id,value);return{__callback:id};}
    if(value instanceof ArrayBuffer)return{__bytes:Array.from(new Uint8Array(value)),__buffer:true};
    if(ArrayBuffer.isView(value))return{__bytes:Array.from(new Uint8Array(value.buffer,value.byteOffset,value.byteLength))};
    if(typeof value!=='object')return value;
    if(seen.length>100||seen.indexOf(value)!==-1)throw Error('Cyclic');
    if(Array.isArray(value))return value.map(function(v){return pack(v,seen.concat([value]));});
    var out=Object.create(null);
    Object.keys(value).forEach(function(k){out[k]=pack(value[k],seen.concat([value]));});
    return out;
  }
  function unpack(value){
    if(!value||typeof value!=='object')return value;
    if(value.__bytes){var b=new Uint8Array(value.__bytes);return value.__buffer?b.buffer:b;}
    if(value.__callback)return function(){var a=Array.prototype.slice.call(arguments);return call('callback',[value.__callback,a]);};
    if(Array.isArray(value))return value.map(unpack);
    var out={};
    Object.keys(value).forEach(function(k){Object.defineProperty(out,k,{value:unpack(value[k]),writable:true,enumerable:true,configurable:true});});
    return out;
  }
  function call(method,args){
    args=args||[];
    var result=parse(bridge(method,stringify(pack(args))));
    if(result.failure)throw Error(result.failure);
    return unpack(result.value);
  }
  function goJSON(value){
    function convert(v,seen){
      seen=seen||[];
      if(v==null)return null;
      if(v instanceof Uint8Array)return call('base64Bytes',[Array.from(v)]);
      if(typeof v!=='object')return v;
      if(seen.indexOf(v)!==-1)throw Error('Cyclic');
      if(Array.isArray(v))return v.map(function(x){return convert(x,seen.concat([v]));});
      var out=Object.create(null);
      Object.keys(v).sort().forEach(function(k){out[k]=convert(v[k],seen.concat([v]));});
      return out;
    }
    return stringify(convert(value));
  }
  globalThis.registerExtension=function(value){globalThis.extension=value;};
  globalThis.settings=unpack(config.settings||{});
  globalThis.storage={
    get:function(k,fb){var r=call('storage.get',[String(k)]);return r.found?r.value:fb;},
    set:function(k,v){return call('storage.set',[String(k),parse(goJSON(v))]).success;},
    remove:function(k){return call('storage.remove',[String(k)]).success;},
    clear:function(){return call('storage.clear').success;},
  };
  globalThis.credentials={
    get:function(k,fb){var r=call('credentials.get',[String(k)]);return r.found?r.value:fb;},
    store:function(k,v){return call('credentials.set',[String(k),parse(goJSON(v))]);},
    remove:function(k){return call('credentials.remove',[String(k)]).success;},
    has:function(k){return call('credentials.get',[String(k)]).found;},
  };
  function request(url,method,body,headers){
    body=body==null?'':typeof body==='object'?goJSON(body):String(body);
    return call('http.request',[String(url),{method:method||'GET',body:body,headers:headers||{}}]);
  }
  globalThis.http={
    get:function(u,h){return request(u,'GET',null,h);},
    post:function(u,b,h){return request(u,'POST',b,h);},
    put:function(u,b,h){return request(u,'PUT',b,h);},
    patch:function(u,b,h){return request(u,'PATCH',b,h);},
    delete:function(u,h){return request(u,'DELETE',null,h);},
    request:function(u,o){o=o||{};return request(u,o.method,o.body,o.headers);},
    clearCookies:function(){return call('http.clearCookies');},
  };
  globalThis.fetch=function(u,o){o=o||{};return request(u,o.method,o.body,o.headers);};
  var _file=function(path){return call('file',[String(path)]);};
  _file.download=function(url,path,opts){return call('file.download',[String(url),String(path),opts||{}]);};
  _file.delete=function(path){return call('file.delete',[String(path)]);};
  _file.exists=function(path){return call('file.exists',[String(path)]);};
  _file.read=function(path){return call('file.read',[String(path)]);};
  _file.write=function(path,data){return call('file.write',[String(path),data]);};
  _file.transformPatternedBlocks=function(inp,out,opts,prog){return call('file.transformPatternedBlocks',[String(inp),String(out),opts||{},pack(prog)]);};
  globalThis.file=_file;
  var _crypto={
    cipher:function(d,a,m,k,iv,p,dec){return call('crypto.cipher',[d,a,m,k,iv,p,dec]);},
    hmac:function(d,k,a){return call('crypto.hmac',[d,k,a||'SHA-256']);},
    hash:function(d,a){return call('crypto.hash',[d,a||'SHA-256']);},
    randomBytes:function(n){return call('crypto.random',[n]);},
    sign:function(d,k,a){return call('crypto.sign',[d,k,a]);},
  };
  try{Object.defineProperty(globalThis,'crypto',{value:_crypto,writable:true,configurable:true});}catch(e){}
  globalThis.log={
    debug:function(){try{call('log',Array.prototype.slice.call(arguments));}catch(e){}},
    info:function(){try{call('log',Array.prototype.slice.call(arguments));}catch(e){}},
    warn:function(){try{call('log',Array.prototype.slice.call(arguments));}catch(e){}},
    error:function(){try{call('log',Array.prototype.slice.call(arguments));}catch(e){}},
  };
  globalThis.console={
    log:function(){try{call('log',Array.prototype.slice.call(arguments));}catch(e){}},
    warn:function(){try{call('log',Array.prototype.slice.call(arguments));}catch(e){}},
    error:function(){try{call('log',Array.prototype.slice.call(arguments));}catch(e){}},
    info:function(){try{call('log',Array.prototype.slice.call(arguments));}catch(e){}},
  };
})(bridge,{ settings:{},permissions:{storage:true,network:['*']} });
"""
