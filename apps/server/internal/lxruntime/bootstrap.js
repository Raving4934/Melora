(function (native, infoJSON) {
  'use strict';
  const parse = JSON.parse;
  const stringify = JSON.stringify;
  const PromiseImpl = Promise;
  // 在执行脚本前捕获原生 resolve；接纳同步值/thenable，且不受脚本替换全局 Promise 影响。
  const resolvePromise = PromiseImpl.resolve.bind(PromiseImpl);
  const rejectAPI = name => native.unsupported(name);
  class LXBuffer extends Uint8Array {
    toString(format = 'utf8') { return native.encode(this, format); }
  }
  const from = (value, format, length) => new LXBuffer(native.bufferFrom(value, format, length));
  const objectTag = Object.prototype.toString;
  const formEncode = value => {
    const parts = [];
    const visit = (value, key, depth) => {
      if (depth > 6 || parts.length >= 1024) return rejectAPI('lx.request.body');
      const tag = objectTag.call(value);
      if (key && value == null) {
        parts.push(key+'=');
      } else if (tag === '[object Array]') {
        // 固定 Needle fork 使用重复 []，包括稀疏数组中的空项，不使用数字下标。
        for (let i = 0; i < value.length; i++) visit(value[i], key ? key+'[]' : '', depth+1);
      } else if (tag === '[object Object]') {
        for (const child of Object.keys(value)) {
          const encoded = encodeURIComponent(child);
          visit(value[child], key ? key+'['+encoded+']' : encoded, depth+1);
        }
      } else if (tag === '[object Date]') {
        parts.push(value.toISOString());
      } else if (key) {
        parts.push(key+'='+encodeURIComponent(String(value)));
      } else {
        const text = String(value);
        if (!text.includes('=')) return rejectAPI('lx.request.body');
        parts.push(text);
      }
    };
    visit(value, '', 0);
    return parts.join('&');
  };
  const requestHeaders = value => {
    const headers = Object.create(null);
    for (const key of Object.keys(value || {})) {
      const item = value[key];
      if (!['string','number','boolean'].includes(typeof item)) return rejectAPI('lx.request.headers');
      headers[key.toLowerCase()] = String(item);
    }
    return headers;
  };
  const buffer = {
    from,
    bufToString(value, format = 'utf8') { return native.encode(value, format); },
  };
  const crypto = {
    md5(value) { return native.md5(value); },
    randomBytes(size) { return new LXBuffer(native.randomBytes(size)); },
    aesEncrypt(value, mode, key, iv) { return new LXBuffer(native.aesEncrypt(value, mode, key, iv)); },
    rsaEncrypt(value, key) { return new LXBuffer(native.rsaEncrypt(value, key)); },
  };
  const zlib = {
    inflate(value) { return PromiseImpl.resolve().then(() => new LXBuffer(native.inflate(value))); },
    deflate(value) { return PromiseImpl.resolve().then(() => new LXBuffer(native.deflate(value))); },
  };
  const guarded = (obj, api) => new Proxy(obj, {
    get(target, key, receiver) {
      if (typeof key !== 'string' || key in target) return Reflect.get(target, key, receiver);
      return (...args) => rejectAPI(api); // 只报宿主固定 API 类别，不输出任意属性名。
    },
  });
  globalThis.lx = {
    version: '2.0.0', // LX 桌面自定义源 SDK，不是应用版本。
    env: 'desktop',
    currentScriptInfo: parse(infoJSON),
    EVENT_NAMES: Object.freeze({request: 'request', inited: 'inited', updateAlert: 'updateAlert'}),
    on(event, handler) {
      native.on(event, handler);
      return PromiseImpl.resolve();
    },
    send(event, data) {
      if (event === 'updateAlert') return PromiseImpl.resolve(); // 安全策略：不弹窗、不打开或更新链接。
      if (event !== 'inited') return rejectAPI('lx.send');
      native.init(stringify(data));
      return PromiseImpl.resolve();
    },
    request(url, options, callback) {
      if (typeof options === 'function') { callback = options; options = {}; }
      options = options || {};
      if (typeof callback !== 'function') return rejectAPI('lx.request.callback');
      // 官方 request 只解构这六个字段；其它属性不进入 Broker，尤其不能设置代理/agent/TLS 策略。
      const request = {url: String(url), method: options.method || 'GET', headers: requestHeaders(options.headers), timeout: options.timeout || 0};
      let data;
      let json = false;
      let body;
      // 保留官方 truthy 选择：undefined/null/空字符串不得遮住后面的 form。
      if (options.body) {
        data = options.body;
        json = request.headers['content-type'] === 'application/json';
      } else if (options.form) {
        data = options.form;
      } else if (options.formData) {
        // 官方 preload 只设置 json:false，没有开启 Needle 的 multipart。
        data = options.formData;
      }
      if (data) {
        const binary = data instanceof Uint8Array || data instanceof ArrayBuffer;
        if (String(request.method).toUpperCase() === 'GET' && !json && !binary) {
          // 与固定 fork 一致：data 替换旧 query，不能并入旧签名参数。
          request.url = request.url.replace(/\?.*|$/, '?' + formEncode(data));
        } else {
          body = from(binary || typeof data === 'string' ? data : json ? stringify(data) : formEncode(data));
          if (!request.headers['content-type']) request.headers['content-type'] = 'application/x-www-form-urlencoded';
        }
      }
      if (body) request.body = body.toString('base64');
      const id = native.request(stringify(request), callback);
      return () => native.cancel(id);
    },
    utils: guarded({buffer: guarded(buffer,'lx.utils.buffer'), crypto: guarded(crypto,'lx.utils.crypto'), zlib: guarded(zlib,'lx.utils.zlib')},'lx.utils'),
  };
  globalThis.console = Object.freeze(Object.fromEntries(['log','info','warn','error','debug','trace','dir','table','time','timeEnd','clear','assert'].map(k => [k, () => {}])));
  globalThis.setTimeout = (fn, delay = 0, ...args) => native.timer(fn, delay, args);
  globalThis.clearTimeout = id => native.clearTimer(id);
  globalThis.atob = text => native.encode(from(text,'base64'),'latin1');
  globalThis.btoa = text => native.encode(from(text,'latin1'),'base64');
  globalThis.TextEncoder = class TextEncoder {
    get encoding() { return 'utf-8'; }
    encode(text = '') { return new Uint8Array(native.bufferFrom(String(text),'utf8')); }
  };
  globalThis.TextDecoder = class TextDecoder {
    constructor(format = 'utf-8') {
      if (!['utf8','utf-8'].includes(format.toLowerCase())) rejectAPI('lx.utils.buffer.encoding');
    }
    get encoding() { return 'utf-8'; }
    decode(value = new Uint8Array()) { return native.encode(value,'utf8'); }
  };
  return {parse, stringify, resolvePromise, rawBuffer: from};
})
