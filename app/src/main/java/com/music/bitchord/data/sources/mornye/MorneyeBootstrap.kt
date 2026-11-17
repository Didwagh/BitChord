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
  function adaptResponse(r){
    if(!r||typeof r!=='object')return r;
    if(typeof r.text==='function')return r;
    var status=typeof r.status==='number'?r.status:(typeof r.statusCode==='number'?r.statusCode:0);
    var body=r.body==null?'':String(r.body);
    r.status=status;
    r.statusCode=status;
    if(typeof r.ok!=='boolean')r.ok=status>=200&&status<300;
    r.text=function(){return body;};
    r.json=function(){return parse(body);};
    r.arrayBuffer=function(){
      var a=new Uint8Array(body.length);
      for(var i=0;i<body.length;i++)a[i]=body.charCodeAt(i)&255;
      return a.buffer;
    };
    return r;
  }
  function request(url,method,body,headers){
    body=body==null?'':typeof body==='object'?goJSON(body):String(body);
    return adaptResponse(call('http.request',[String(url),{method:method||'GET',body:body,headers:headers||{}}]));
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
  // QuickJS ships no DOM globals, so `URL` and `URLSearchParams` are absent.
  // The reference Mornye host provides them and addons rely on that: Amazon
  // builds every result deeplink with `new URL(path, base)`, and its
  // `extractAmazonDeeplinkInfo` swallows the ReferenceError, so a missing
  // `URL` turns a page full of songs into "Search returned 0 results" with an
  // HTTP 200 and no error in the log. This covers the standard surface those
  // addons read: parsing absolute and base-relative URLs, `pathname`,
  // `hostname`, `host`, `protocol`, `origin`, `href` and `searchParams`.
  function mornyeDecode(value){
    var text=String(value==null?'':value).replace(/\+/g,' ');
    try{return decodeURIComponent(text);}catch(e){return text;}
  }
  function MornyeURLSearchParams(init){
    var pairs=[];
    function push(name,value){pairs.push([String(name),String(value)]);}
    if(typeof init==='string'){
      var body=init.replace(/^\?/,'');
      if(body){
        var parts=body.split('&');
        for(var i=0;i<parts.length;i++){
          var part=parts[i];
          if(!part)continue;
          var eq=part.indexOf('=');
          push(mornyeDecode(eq<0?part:part.slice(0,eq)),mornyeDecode(eq<0?'':part.slice(eq+1)));
        }
      }
    }else if(init&&typeof init.length==='number'){
      for(var j=0;j<init.length;j++){
        var item=init[j]||[];
        push(item[0],item.length>1?item[1]:'');
      }
    }else if(init&&typeof init==='object'){
      var keys=Object.keys(init);
      for(var k=0;k<keys.length;k++)push(keys[k],init[keys[k]]);
    }
    this.append=function(name,value){push(name,value);};
    this.delete=function(name){
      var out=[];
      for(var i=0;i<pairs.length;i++)if(pairs[i][0]!==String(name))out.push(pairs[i]);
      pairs=out;
    };
    this.get=function(name){
      for(var i=0;i<pairs.length;i++)if(pairs[i][0]===String(name))return pairs[i][1];
      return null;
    };
    this.getAll=function(name){
      var out=[];
      for(var i=0;i<pairs.length;i++)if(pairs[i][0]===String(name))out.push(pairs[i][1]);
      return out;
    };
    this.has=function(name){
      for(var i=0;i<pairs.length;i++)if(pairs[i][0]===String(name))return true;
      return false;
    };
    this.set=function(name,value){
      var found=false,out=[];
      for(var i=0;i<pairs.length;i++){
        if(pairs[i][0]!==String(name))out.push(pairs[i]);
        else if(!found){out.push([String(name),String(value)]);found=true;}
      }
      if(!found)out.push([String(name),String(value)]);
      pairs=out;
    };
    this.sort=function(){pairs.sort(function(a,b){return a[0]<b[0]?-1:(a[0]>b[0]?1:0);});};
    this.keys=function(){return pairs.map(function(p){return p[0];});};
    this.values=function(){return pairs.map(function(p){return p[1];});};
    this.entries=function(){return pairs.map(function(p){return [p[0],p[1]];});};
    this.forEach=function(fn,thisArg){
      for(var i=0;i<pairs.length;i++)fn.call(thisArg,pairs[i][1],pairs[i][0],this);
    };
    this.toString=function(){
      var out=[];
      for(var i=0;i<pairs.length;i++)out.push(encodeURIComponent(pairs[i][0])+'='+encodeURIComponent(pairs[i][1]));
      return out.join('&');
    };
    if(typeof Symbol!=='undefined'&&Symbol.iterator){
      this[Symbol.iterator]=function(){return this.entries()[Symbol.iterator]();};
    }
  }
  function MornyeURL(href,base){
    var raw=String(href==null?'':href);
    var baseHref=base==null?null:String(base&&base.href?base.href:base);
    function parse(value){
      var m=/^([a-zA-Z][a-zA-Z0-9+.\-]*:)\/\/([^\/?#]*)([^?#]*)(\?[^#]*)?(#.*)?/.exec(value);
      if(!m)return null;
      var authority=m[2],path=m[3]||'';
      var at=authority.lastIndexOf('@');
      var userinfo='',hostport=authority;
      if(at>=0){userinfo=authority.slice(0,at+1);hostport=authority.slice(at+1);}
      var hostname=hostport,port='';
      var colon=hostport.lastIndexOf(':');
      if(colon>=0&&hostport.indexOf(']')<colon){hostname=hostport.slice(0,colon);port=hostport.slice(colon+1);}
      if(path.charAt(0)!=='/')path='/'+path;
      return {protocol:m[1],userinfo:userinfo,hostname:hostname,port:port,pathname:path,search:m[4]||'',hash:m[5]||''};
    }
    function originOf(p){return p.protocol+'//'+p.userinfo+p.hostname+(p.port?':'+p.port:'');}
    var parsed=parse(raw);
    if(!parsed&&baseHref!=null){
      var baseParsed=parse(baseHref);
      if(baseParsed){
        if(raw.slice(0,2)==='//')parsed=parse(baseParsed.protocol+raw);
        else if(raw.charAt(0)==='/')parsed=parse(originOf(baseParsed)+raw);
        else{
          var dir=baseParsed.pathname.slice(0,baseParsed.pathname.lastIndexOf('/')+1)||'/';
          parsed=parse(originOf(baseParsed)+dir+raw);
        }
      }
    }
    if(!parsed)throw new TypeError('Invalid URL: '+raw);
    this.protocol=parsed.protocol;
    this.username='';
    this.password='';
    this.hostname=parsed.hostname;
    this.port=parsed.port;
    this.host=parsed.hostname+(parsed.port?':'+parsed.port:'');
    this.pathname=parsed.pathname;
    this.search=parsed.search;
    this.hash=parsed.hash;
    this.origin=originOf(parsed);
    this.href=this.origin+this.pathname+this.search+this.hash;
    this.searchParams=new MornyeURLSearchParams(this.search);
  }
  MornyeURL.prototype.toString=function(){return this.href;};
  MornyeURL.prototype.toJSON=function(){return this.href;};
  MornyeURL.canParse=function(href,base){
    try{new MornyeURL(href,base);return true;}catch(e){return false;}
  };
  MornyeURL.parse=function(href,base){
    try{return new MornyeURL(href,base);}catch(e){return null;}
  };
  if(typeof globalThis.URLSearchParams!=='function')globalThis.URLSearchParams=MornyeURLSearchParams;
  if(typeof globalThis.URL!=='function')globalThis.URL=MornyeURL;
  // Signed-session providers (Tidal, Qobuz, Amazon, Deezer) read this global
  // before every request. It exists only when the addon's manifest declares a
  // `signedSession` block; the host owns the keys, the signing and the
  // verification URL, exactly as the Mornye desktop runtime does.
  if(config.sessionEnabled){
    globalThis.session={
      status:function(){return call('session.status');},
      clear:function(){return call('session.clear');},
      completeGrant:function(grant){
        return call('session.completeGrant',arguments.length?[String(grant)]:[]);
      },
      signedFetch:function(method,path,body,headers){
        if(arguments.length<2)return {ok:false,error:'signedFetch needs a method and a path'};
        var payload;
        try{payload=body==null?'':(typeof body==='object'?goJSON(body):String(body));}
        catch(e){return {ok:false,error:String(e&&e.message?e.message:e)};}
        return call('session.signedFetch',[
          String(method||'GET'),String(path||''),payload,headers||{}
        ]);
      }
    };
  }
  var _file=function(path){return call('file',[String(path)]);};
  _file.download=function(url,path,opts){return call('file.download',[String(url),String(path),opts||{}]);};
  _file.downloadSegments=function(urls,path,opts){return call('file.downloadSegments',[urls||[],String(path),opts||{}]);};
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
  // Host helpers the addons expect as `utils`. They are not optional: the
  // bundles read `utils` as a plain global and dereference it before their own
  // fallbacks (`utils && …`, `utils.randomUserAgent()`), so an undefined name
  // throws a ReferenceError on the first request.
  globalThis.utils=(function(){
    var APP_UA='SpotiFLAC-Mobile/1.0 (Android)';
    var DESKTOP_UA='Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36';
    function utf8Bytes(value){
      var s=String(value==null?'':value),a=[];
      for(var i=0;i<s.length;i++){
        var c=s.charCodeAt(i);
        if(c<0x80){a.push(c);}
        else if(c<0x800){a.push(0xC0|(c>>6),0x80|(c&0x3F));}
        else if(c>=0xD800&&c<=0xDBFF&&i+1<s.length){
          var d=s.charCodeAt(++i),cp=0x10000+((c-0xD800)<<10)+(d-0xDC00);
          a.push(0xF0|(cp>>18),0x80|((cp>>12)&0x3F),0x80|((cp>>6)&0x3F),0x80|(cp&0x3F));
        }
        else{a.push(0xE0|(c>>12),0x80|((c>>6)&0x3F),0x80|(c&0x3F));}
      }
      return new Uint8Array(a);
    }
    function utf8Text(bytes){
      var out='',i=0,n=bytes.length;
      while(i<n){
        var c=bytes[i++];
        if(c<0x80){out+=String.fromCharCode(c);}
        else if(c<0xE0&&i<n){out+=String.fromCharCode(((c&0x1F)<<6)|(bytes[i++]&0x3F));}
        else if(c<0xF0&&i+1<n){out+=String.fromCharCode(((c&0x0F)<<12)|((bytes[i++]&0x3F)<<6)|(bytes[i++]&0x3F));}
        else if(i+2<n){
          var cp=((c&0x07)<<18)|((bytes[i++]&0x3F)<<12)|((bytes[i++]&0x3F)<<6)|(bytes[i++]&0x3F);
          cp-=0x10000;out+=String.fromCharCode(0xD800+(cp>>10),0xDC00+(cp&0x3FF));
        }
      }
      return out;
    }
    function bytesOf(value){
      if(value instanceof Uint8Array)return value;
      if(value instanceof ArrayBuffer)return new Uint8Array(value);
      if(ArrayBuffer.isView(value))return new Uint8Array(value.buffer,value.byteOffset,value.byteLength);
      if(Array.isArray(value))return new Uint8Array(value);
      return utf8Bytes(value);
    }
    function hex(bytes){
      var s='';
      for(var i=0;i<bytes.length;i++){var h=bytes[i].toString(16);s+=h.length<2?'0'+h:h;}
      return s;
    }
    var B64='ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    function base64Bytes(text){
      var s=String(text||'').replace(/[\s=]/g,'').replace(/-/g,'+').replace(/_/g,'/');
      var out=[],bits=0,acc=0;
      for(var i=0;i<s.length;i++){
        var v=B64.indexOf(s.charAt(i));
        if(v<0)continue;
        acc=(acc<<6)|v;bits+=6;
        if(bits>=8){bits-=8;out.push((acc>>bits)&255);}
      }
      return new Uint8Array(out);
    }
    return {
      appUserAgent:function(){return APP_UA;},
      randomUserAgent:function(){return DESKTOP_UA;},
      appVersion:function(){return '1.0';},
      sleep:function(ms){return call('utils.sleep',[Number(ms)||0]);},
      isDownloadCancelled:function(){return false;},
      isRequestCancelled:function(){return false;},
      getResolutionRemainingMs:function(){return 120000;},
      sha256:function(value){return hex(call('crypto.hash',[bytesOf(value),'SHA-256']));},
      md5:function(value){return hex(call('crypto.hash',[bytesOf(value),'MD5']));},
      // key first, message second — see MorneyeCrypto.hmacSha1. Passing these
      // through crypto.hmac() reversed them, which made Spotify's TOTP wrong
      // and the token endpoint answer HTTP 400.
      hmacSHA1:function(key,data){return call('utils.hmacSHA1',[bytesOf(key),bytesOf(data)]);},
      base64Decode:function(value){return utf8Text(base64Bytes(String(value==null?'':value)));},
    };
  })();
  // The desktop host's `gobackend` object. Addons probe it with
  // `if (!gobackend || typeof gobackend.getAudioQuality !== 'function')`, and
  // an undeclared identifier throws on that first `!gobackend` even though the
  // `typeof` guard looks safe. Defining the object turns those probes into
  // "not available here" instead of aborting a download with a ReferenceError.
  globalThis.gobackend={
    getLocalTime:function(){
      var d=new Date();
      return {
        hour:d.getHours(),minute:d.getMinutes(),second:d.getSeconds(),
        year:d.getFullYear(),month:d.getMonth()+1,day:d.getDate(),
        weekday:d.getDay(),timestamp:d.getTime(),
      };
    },
    sanitizeFilename:function(name){
      return String(name==null?'':name).replace(/[\\/:*?"<>|]/g,'_').trim();
    },
    // No local audio-analysis index. Returning "unavailable" is honest: the
    // addon skips its duration/quality check rather than reading a fabricated
    // duration, and BitChord measures the codec from the bytes itself.
    getAudioQuality:function(){return null;},
    getLyricsLRC:function(){return {error:'lyrics lookup is not available'};},
    checkISRCExists:function(){return false;},
  };
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
})(bridge,(typeof __mornye_config!=='undefined'&&__mornye_config)?__mornye_config:{ settings:{},permissions:{storage:true,network:['*']} });
"""
