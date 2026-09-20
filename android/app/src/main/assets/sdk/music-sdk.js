(() => {
  var __create = Object.create;
  var __defProp = Object.defineProperty;
  var __getOwnPropDesc = Object.getOwnPropertyDescriptor;
  var __getOwnPropNames = Object.getOwnPropertyNames;
  var __getProtoOf = Object.getPrototypeOf;
  var __hasOwnProp = Object.prototype.hasOwnProperty;
  var __commonJS = (cb, mod) => function __require() {
    return mod || (0, cb[__getOwnPropNames(cb)[0]])((mod = { exports: {} }).exports, mod), mod.exports;
  };
  var __copyProps = (to, from, except, desc) => {
    if (from && typeof from === "object" || typeof from === "function") {
      for (let key of __getOwnPropNames(from))
        if (!__hasOwnProp.call(to, key) && key !== except)
          __defProp(to, key, { get: () => from[key], enumerable: !(desc = __getOwnPropDesc(from, key)) || desc.enumerable });
    }
    return to;
  };
  var __toESM = (mod, isNodeMode, target) => (target = mod != null ? __create(__getProtoOf(mod)) : {}, __copyProps(
    // If the importer is in node compatibility mode or this is not an ESM
    // file that has been converted to a CommonJS file using a Babel-
    // compatible transform (i.e. "__esModule" has not been set), then set
    // "default" to the CommonJS "module.exports" for node compatibility.
    isNodeMode || !mod || !mod.__esModule ? __defProp(target, "default", { value: mod, enumerable: true }) : target,
    mod
  ));

  // node_modules/base64-js/index.js
  var require_base64_js = __commonJS({
    "node_modules/base64-js/index.js"(exports) {
      "use strict";
      exports.byteLength = byteLength;
      exports.toByteArray = toByteArray;
      exports.fromByteArray = fromByteArray;
      var lookup = [];
      var revLookup = [];
      var Arr = typeof Uint8Array !== "undefined" ? Uint8Array : Array;
      var code = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
      for (i = 0, len = code.length; i < len; ++i) {
        lookup[i] = code[i];
        revLookup[code.charCodeAt(i)] = i;
      }
      var i;
      var len;
      revLookup["-".charCodeAt(0)] = 62;
      revLookup["_".charCodeAt(0)] = 63;
      function getLens(b64) {
        var len2 = b64.length;
        if (len2 % 4 > 0) {
          throw new Error("Invalid string. Length must be a multiple of 4");
        }
        var validLen = b64.indexOf("=");
        if (validLen === -1) validLen = len2;
        var placeHoldersLen = validLen === len2 ? 0 : 4 - validLen % 4;
        return [validLen, placeHoldersLen];
      }
      function byteLength(b64) {
        var lens = getLens(b64);
        var validLen = lens[0];
        var placeHoldersLen = lens[1];
        return (validLen + placeHoldersLen) * 3 / 4 - placeHoldersLen;
      }
      function _byteLength(b64, validLen, placeHoldersLen) {
        return (validLen + placeHoldersLen) * 3 / 4 - placeHoldersLen;
      }
      function toByteArray(b64) {
        var tmp;
        var lens = getLens(b64);
        var validLen = lens[0];
        var placeHoldersLen = lens[1];
        var arr = new Arr(_byteLength(b64, validLen, placeHoldersLen));
        var curByte = 0;
        var len2 = placeHoldersLen > 0 ? validLen - 4 : validLen;
        var i2;
        for (i2 = 0; i2 < len2; i2 += 4) {
          tmp = revLookup[b64.charCodeAt(i2)] << 18 | revLookup[b64.charCodeAt(i2 + 1)] << 12 | revLookup[b64.charCodeAt(i2 + 2)] << 6 | revLookup[b64.charCodeAt(i2 + 3)];
          arr[curByte++] = tmp >> 16 & 255;
          arr[curByte++] = tmp >> 8 & 255;
          arr[curByte++] = tmp & 255;
        }
        if (placeHoldersLen === 2) {
          tmp = revLookup[b64.charCodeAt(i2)] << 2 | revLookup[b64.charCodeAt(i2 + 1)] >> 4;
          arr[curByte++] = tmp & 255;
        }
        if (placeHoldersLen === 1) {
          tmp = revLookup[b64.charCodeAt(i2)] << 10 | revLookup[b64.charCodeAt(i2 + 1)] << 4 | revLookup[b64.charCodeAt(i2 + 2)] >> 2;
          arr[curByte++] = tmp >> 8 & 255;
          arr[curByte++] = tmp & 255;
        }
        return arr;
      }
      function tripletToBase64(num) {
        return lookup[num >> 18 & 63] + lookup[num >> 12 & 63] + lookup[num >> 6 & 63] + lookup[num & 63];
      }
      function encodeChunk(uint8, start, end) {
        var tmp;
        var output = [];
        for (var i2 = start; i2 < end; i2 += 3) {
          tmp = (uint8[i2] << 16 & 16711680) + (uint8[i2 + 1] << 8 & 65280) + (uint8[i2 + 2] & 255);
          output.push(tripletToBase64(tmp));
        }
        return output.join("");
      }
      function fromByteArray(uint8) {
        var tmp;
        var len2 = uint8.length;
        var extraBytes = len2 % 3;
        var parts = [];
        var maxChunkLength = 16383;
        for (var i2 = 0, len22 = len2 - extraBytes; i2 < len22; i2 += maxChunkLength) {
          parts.push(encodeChunk(uint8, i2, i2 + maxChunkLength > len22 ? len22 : i2 + maxChunkLength));
        }
        if (extraBytes === 1) {
          tmp = uint8[len2 - 1];
          parts.push(
            lookup[tmp >> 2] + lookup[tmp << 4 & 63] + "=="
          );
        } else if (extraBytes === 2) {
          tmp = (uint8[len2 - 2] << 8) + uint8[len2 - 1];
          parts.push(
            lookup[tmp >> 10] + lookup[tmp >> 4 & 63] + lookup[tmp << 2 & 63] + "="
          );
        }
        return parts.join("");
      }
    }
  });

  // node_modules/ieee754/index.js
  var require_ieee754 = __commonJS({
    "node_modules/ieee754/index.js"(exports) {
      exports.read = function(buffer, offset, isLE, mLen, nBytes) {
        var e, m;
        var eLen = nBytes * 8 - mLen - 1;
        var eMax = (1 << eLen) - 1;
        var eBias = eMax >> 1;
        var nBits = -7;
        var i = isLE ? nBytes - 1 : 0;
        var d = isLE ? -1 : 1;
        var s = buffer[offset + i];
        i += d;
        e = s & (1 << -nBits) - 1;
        s >>= -nBits;
        nBits += eLen;
        for (; nBits > 0; e = e * 256 + buffer[offset + i], i += d, nBits -= 8) {
        }
        m = e & (1 << -nBits) - 1;
        e >>= -nBits;
        nBits += mLen;
        for (; nBits > 0; m = m * 256 + buffer[offset + i], i += d, nBits -= 8) {
        }
        if (e === 0) {
          e = 1 - eBias;
        } else if (e === eMax) {
          return m ? NaN : (s ? -1 : 1) * Infinity;
        } else {
          m = m + Math.pow(2, mLen);
          e = e - eBias;
        }
        return (s ? -1 : 1) * m * Math.pow(2, e - mLen);
      };
      exports.write = function(buffer, value, offset, isLE, mLen, nBytes) {
        var e, m, c;
        var eLen = nBytes * 8 - mLen - 1;
        var eMax = (1 << eLen) - 1;
        var eBias = eMax >> 1;
        var rt = mLen === 23 ? Math.pow(2, -24) - Math.pow(2, -77) : 0;
        var i = isLE ? 0 : nBytes - 1;
        var d = isLE ? 1 : -1;
        var s = value < 0 || value === 0 && 1 / value < 0 ? 1 : 0;
        value = Math.abs(value);
        if (isNaN(value) || value === Infinity) {
          m = isNaN(value) ? 1 : 0;
          e = eMax;
        } else {
          e = Math.floor(Math.log(value) / Math.LN2);
          if (value * (c = Math.pow(2, -e)) < 1) {
            e--;
            c *= 2;
          }
          if (e + eBias >= 1) {
            value += rt / c;
          } else {
            value += rt * Math.pow(2, 1 - eBias);
          }
          if (value * c >= 2) {
            e++;
            c /= 2;
          }
          if (e + eBias >= eMax) {
            m = 0;
            e = eMax;
          } else if (e + eBias >= 1) {
            m = (value * c - 1) * Math.pow(2, mLen);
            e = e + eBias;
          } else {
            m = value * Math.pow(2, eBias - 1) * Math.pow(2, mLen);
            e = 0;
          }
        }
        for (; mLen >= 8; buffer[offset + i] = m & 255, i += d, m /= 256, mLen -= 8) {
        }
        e = e << mLen | m;
        eLen += mLen;
        for (; eLen > 0; buffer[offset + i] = e & 255, i += d, e /= 256, eLen -= 8) {
        }
        buffer[offset + i - d] |= s * 128;
      };
    }
  });

  // node_modules/buffer/index.js
  var require_buffer = __commonJS({
    "node_modules/buffer/index.js"(exports) {
      "use strict";
      var base64 = require_base64_js();
      var ieee754 = require_ieee754();
      var customInspectSymbol = typeof Symbol === "function" && typeof Symbol["for"] === "function" ? Symbol["for"]("nodejs.util.inspect.custom") : null;
      exports.Buffer = Buffer9;
      exports.SlowBuffer = SlowBuffer;
      exports.INSPECT_MAX_BYTES = 50;
      var K_MAX_LENGTH = 2147483647;
      exports.kMaxLength = K_MAX_LENGTH;
      Buffer9.TYPED_ARRAY_SUPPORT = typedArraySupport();
      if (!Buffer9.TYPED_ARRAY_SUPPORT && typeof console !== "undefined" && typeof console.error === "function") {
        console.error(
          "This browser lacks typed array (Uint8Array) support which is required by `buffer` v5.x. Use `buffer` v4.x if you require old browser support."
        );
      }
      function typedArraySupport() {
        try {
          const arr = new Uint8Array(1);
          const proto = { foo: function() {
            return 42;
          } };
          Object.setPrototypeOf(proto, Uint8Array.prototype);
          Object.setPrototypeOf(arr, proto);
          return arr.foo() === 42;
        } catch (e) {
          return false;
        }
      }
      Object.defineProperty(Buffer9.prototype, "parent", {
        enumerable: true,
        get: function() {
          if (!Buffer9.isBuffer(this)) return void 0;
          return this.buffer;
        }
      });
      Object.defineProperty(Buffer9.prototype, "offset", {
        enumerable: true,
        get: function() {
          if (!Buffer9.isBuffer(this)) return void 0;
          return this.byteOffset;
        }
      });
      function createBuffer(length) {
        if (length > K_MAX_LENGTH) {
          throw new RangeError('The value "' + length + '" is invalid for option "size"');
        }
        const buf = new Uint8Array(length);
        Object.setPrototypeOf(buf, Buffer9.prototype);
        return buf;
      }
      function Buffer9(arg, encodingOrOffset, length) {
        if (typeof arg === "number") {
          if (typeof encodingOrOffset === "string") {
            throw new TypeError(
              'The "string" argument must be of type string. Received type number'
            );
          }
          return allocUnsafe(arg);
        }
        return from(arg, encodingOrOffset, length);
      }
      Buffer9.poolSize = 8192;
      function from(value, encodingOrOffset, length) {
        if (typeof value === "string") {
          return fromString(value, encodingOrOffset);
        }
        if (ArrayBuffer.isView(value)) {
          return fromArrayView(value);
        }
        if (value == null) {
          throw new TypeError(
            "The first argument must be one of type string, Buffer, ArrayBuffer, Array, or Array-like Object. Received type " + typeof value
          );
        }
        if (isInstance(value, ArrayBuffer) || value && isInstance(value.buffer, ArrayBuffer)) {
          return fromArrayBuffer(value, encodingOrOffset, length);
        }
        if (typeof SharedArrayBuffer !== "undefined" && (isInstance(value, SharedArrayBuffer) || value && isInstance(value.buffer, SharedArrayBuffer))) {
          return fromArrayBuffer(value, encodingOrOffset, length);
        }
        if (typeof value === "number") {
          throw new TypeError(
            'The "value" argument must not be of type number. Received type number'
          );
        }
        const valueOf = value.valueOf && value.valueOf();
        if (valueOf != null && valueOf !== value) {
          return Buffer9.from(valueOf, encodingOrOffset, length);
        }
        const b = fromObject(value);
        if (b) return b;
        if (typeof Symbol !== "undefined" && Symbol.toPrimitive != null && typeof value[Symbol.toPrimitive] === "function") {
          return Buffer9.from(value[Symbol.toPrimitive]("string"), encodingOrOffset, length);
        }
        throw new TypeError(
          "The first argument must be one of type string, Buffer, ArrayBuffer, Array, or Array-like Object. Received type " + typeof value
        );
      }
      Buffer9.from = function(value, encodingOrOffset, length) {
        return from(value, encodingOrOffset, length);
      };
      Object.setPrototypeOf(Buffer9.prototype, Uint8Array.prototype);
      Object.setPrototypeOf(Buffer9, Uint8Array);
      function assertSize(size) {
        if (typeof size !== "number") {
          throw new TypeError('"size" argument must be of type number');
        } else if (size < 0) {
          throw new RangeError('The value "' + size + '" is invalid for option "size"');
        }
      }
      function alloc(size, fill, encoding) {
        assertSize(size);
        if (size <= 0) {
          return createBuffer(size);
        }
        if (fill !== void 0) {
          return typeof encoding === "string" ? createBuffer(size).fill(fill, encoding) : createBuffer(size).fill(fill);
        }
        return createBuffer(size);
      }
      Buffer9.alloc = function(size, fill, encoding) {
        return alloc(size, fill, encoding);
      };
      function allocUnsafe(size) {
        assertSize(size);
        return createBuffer(size < 0 ? 0 : checked(size) | 0);
      }
      Buffer9.allocUnsafe = function(size) {
        return allocUnsafe(size);
      };
      Buffer9.allocUnsafeSlow = function(size) {
        return allocUnsafe(size);
      };
      function fromString(string, encoding) {
        if (typeof encoding !== "string" || encoding === "") {
          encoding = "utf8";
        }
        if (!Buffer9.isEncoding(encoding)) {
          throw new TypeError("Unknown encoding: " + encoding);
        }
        const length = byteLength(string, encoding) | 0;
        let buf = createBuffer(length);
        const actual = buf.write(string, encoding);
        if (actual !== length) {
          buf = buf.slice(0, actual);
        }
        return buf;
      }
      function fromArrayLike(array) {
        const length = array.length < 0 ? 0 : checked(array.length) | 0;
        const buf = createBuffer(length);
        for (let i = 0; i < length; i += 1) {
          buf[i] = array[i] & 255;
        }
        return buf;
      }
      function fromArrayView(arrayView) {
        if (isInstance(arrayView, Uint8Array)) {
          const copy = new Uint8Array(arrayView);
          return fromArrayBuffer(copy.buffer, copy.byteOffset, copy.byteLength);
        }
        return fromArrayLike(arrayView);
      }
      function fromArrayBuffer(array, byteOffset, length) {
        if (byteOffset < 0 || array.byteLength < byteOffset) {
          throw new RangeError('"offset" is outside of buffer bounds');
        }
        if (array.byteLength < byteOffset + (length || 0)) {
          throw new RangeError('"length" is outside of buffer bounds');
        }
        let buf;
        if (byteOffset === void 0 && length === void 0) {
          buf = new Uint8Array(array);
        } else if (length === void 0) {
          buf = new Uint8Array(array, byteOffset);
        } else {
          buf = new Uint8Array(array, byteOffset, length);
        }
        Object.setPrototypeOf(buf, Buffer9.prototype);
        return buf;
      }
      function fromObject(obj) {
        if (Buffer9.isBuffer(obj)) {
          const len = checked(obj.length) | 0;
          const buf = createBuffer(len);
          if (buf.length === 0) {
            return buf;
          }
          obj.copy(buf, 0, 0, len);
          return buf;
        }
        if (obj.length !== void 0) {
          if (typeof obj.length !== "number" || numberIsNaN(obj.length)) {
            return createBuffer(0);
          }
          return fromArrayLike(obj);
        }
        if (obj.type === "Buffer" && Array.isArray(obj.data)) {
          return fromArrayLike(obj.data);
        }
      }
      function checked(length) {
        if (length >= K_MAX_LENGTH) {
          throw new RangeError("Attempt to allocate Buffer larger than maximum size: 0x" + K_MAX_LENGTH.toString(16) + " bytes");
        }
        return length | 0;
      }
      function SlowBuffer(length) {
        if (+length != length) {
          length = 0;
        }
        return Buffer9.alloc(+length);
      }
      Buffer9.isBuffer = function isBuffer(b) {
        return b != null && b._isBuffer === true && b !== Buffer9.prototype;
      };
      Buffer9.compare = function compare(a, b) {
        if (isInstance(a, Uint8Array)) a = Buffer9.from(a, a.offset, a.byteLength);
        if (isInstance(b, Uint8Array)) b = Buffer9.from(b, b.offset, b.byteLength);
        if (!Buffer9.isBuffer(a) || !Buffer9.isBuffer(b)) {
          throw new TypeError(
            'The "buf1", "buf2" arguments must be one of type Buffer or Uint8Array'
          );
        }
        if (a === b) return 0;
        let x = a.length;
        let y = b.length;
        for (let i = 0, len = Math.min(x, y); i < len; ++i) {
          if (a[i] !== b[i]) {
            x = a[i];
            y = b[i];
            break;
          }
        }
        if (x < y) return -1;
        if (y < x) return 1;
        return 0;
      };
      Buffer9.isEncoding = function isEncoding(encoding) {
        switch (String(encoding).toLowerCase()) {
          case "hex":
          case "utf8":
          case "utf-8":
          case "ascii":
          case "latin1":
          case "binary":
          case "base64":
          case "ucs2":
          case "ucs-2":
          case "utf16le":
          case "utf-16le":
            return true;
          default:
            return false;
        }
      };
      Buffer9.concat = function concat(list, length) {
        if (!Array.isArray(list)) {
          throw new TypeError('"list" argument must be an Array of Buffers');
        }
        if (list.length === 0) {
          return Buffer9.alloc(0);
        }
        let i;
        if (length === void 0) {
          length = 0;
          for (i = 0; i < list.length; ++i) {
            length += list[i].length;
          }
        }
        const buffer = Buffer9.allocUnsafe(length);
        let pos = 0;
        for (i = 0; i < list.length; ++i) {
          let buf = list[i];
          if (isInstance(buf, Uint8Array)) {
            if (pos + buf.length > buffer.length) {
              if (!Buffer9.isBuffer(buf)) buf = Buffer9.from(buf);
              buf.copy(buffer, pos);
            } else {
              Uint8Array.prototype.set.call(
                buffer,
                buf,
                pos
              );
            }
          } else if (!Buffer9.isBuffer(buf)) {
            throw new TypeError('"list" argument must be an Array of Buffers');
          } else {
            buf.copy(buffer, pos);
          }
          pos += buf.length;
        }
        return buffer;
      };
      function byteLength(string, encoding) {
        if (Buffer9.isBuffer(string)) {
          return string.length;
        }
        if (ArrayBuffer.isView(string) || isInstance(string, ArrayBuffer)) {
          return string.byteLength;
        }
        if (typeof string !== "string") {
          throw new TypeError(
            'The "string" argument must be one of type string, Buffer, or ArrayBuffer. Received type ' + typeof string
          );
        }
        const len = string.length;
        const mustMatch = arguments.length > 2 && arguments[2] === true;
        if (!mustMatch && len === 0) return 0;
        let loweredCase = false;
        for (; ; ) {
          switch (encoding) {
            case "ascii":
            case "latin1":
            case "binary":
              return len;
            case "utf8":
            case "utf-8":
              return utf8ToBytes(string).length;
            case "ucs2":
            case "ucs-2":
            case "utf16le":
            case "utf-16le":
              return len * 2;
            case "hex":
              return len >>> 1;
            case "base64":
              return base64ToBytes(string).length;
            default:
              if (loweredCase) {
                return mustMatch ? -1 : utf8ToBytes(string).length;
              }
              encoding = ("" + encoding).toLowerCase();
              loweredCase = true;
          }
        }
      }
      Buffer9.byteLength = byteLength;
      function slowToString(encoding, start, end) {
        let loweredCase = false;
        if (start === void 0 || start < 0) {
          start = 0;
        }
        if (start > this.length) {
          return "";
        }
        if (end === void 0 || end > this.length) {
          end = this.length;
        }
        if (end <= 0) {
          return "";
        }
        end >>>= 0;
        start >>>= 0;
        if (end <= start) {
          return "";
        }
        if (!encoding) encoding = "utf8";
        while (true) {
          switch (encoding) {
            case "hex":
              return hexSlice(this, start, end);
            case "utf8":
            case "utf-8":
              return utf8Slice(this, start, end);
            case "ascii":
              return asciiSlice(this, start, end);
            case "latin1":
            case "binary":
              return latin1Slice(this, start, end);
            case "base64":
              return base64Slice(this, start, end);
            case "ucs2":
            case "ucs-2":
            case "utf16le":
            case "utf-16le":
              return utf16leSlice(this, start, end);
            default:
              if (loweredCase) throw new TypeError("Unknown encoding: " + encoding);
              encoding = (encoding + "").toLowerCase();
              loweredCase = true;
          }
        }
      }
      Buffer9.prototype._isBuffer = true;
      function swap(b, n, m) {
        const i = b[n];
        b[n] = b[m];
        b[m] = i;
      }
      Buffer9.prototype.swap16 = function swap16() {
        const len = this.length;
        if (len % 2 !== 0) {
          throw new RangeError("Buffer size must be a multiple of 16-bits");
        }
        for (let i = 0; i < len; i += 2) {
          swap(this, i, i + 1);
        }
        return this;
      };
      Buffer9.prototype.swap32 = function swap32() {
        const len = this.length;
        if (len % 4 !== 0) {
          throw new RangeError("Buffer size must be a multiple of 32-bits");
        }
        for (let i = 0; i < len; i += 4) {
          swap(this, i, i + 3);
          swap(this, i + 1, i + 2);
        }
        return this;
      };
      Buffer9.prototype.swap64 = function swap64() {
        const len = this.length;
        if (len % 8 !== 0) {
          throw new RangeError("Buffer size must be a multiple of 64-bits");
        }
        for (let i = 0; i < len; i += 8) {
          swap(this, i, i + 7);
          swap(this, i + 1, i + 6);
          swap(this, i + 2, i + 5);
          swap(this, i + 3, i + 4);
        }
        return this;
      };
      Buffer9.prototype.toString = function toString2() {
        const length = this.length;
        if (length === 0) return "";
        if (arguments.length === 0) return utf8Slice(this, 0, length);
        return slowToString.apply(this, arguments);
      };
      Buffer9.prototype.toLocaleString = Buffer9.prototype.toString;
      Buffer9.prototype.equals = function equals(b) {
        if (!Buffer9.isBuffer(b)) throw new TypeError("Argument must be a Buffer");
        if (this === b) return true;
        return Buffer9.compare(this, b) === 0;
      };
      Buffer9.prototype.inspect = function inspect() {
        let str = "";
        const max = exports.INSPECT_MAX_BYTES;
        str = this.toString("hex", 0, max).replace(/(.{2})/g, "$1 ").trim();
        if (this.length > max) str += " ... ";
        return "<Buffer " + str + ">";
      };
      if (customInspectSymbol) {
        Buffer9.prototype[customInspectSymbol] = Buffer9.prototype.inspect;
      }
      Buffer9.prototype.compare = function compare(target, start, end, thisStart, thisEnd) {
        if (isInstance(target, Uint8Array)) {
          target = Buffer9.from(target, target.offset, target.byteLength);
        }
        if (!Buffer9.isBuffer(target)) {
          throw new TypeError(
            'The "target" argument must be one of type Buffer or Uint8Array. Received type ' + typeof target
          );
        }
        if (start === void 0) {
          start = 0;
        }
        if (end === void 0) {
          end = target ? target.length : 0;
        }
        if (thisStart === void 0) {
          thisStart = 0;
        }
        if (thisEnd === void 0) {
          thisEnd = this.length;
        }
        if (start < 0 || end > target.length || thisStart < 0 || thisEnd > this.length) {
          throw new RangeError("out of range index");
        }
        if (thisStart >= thisEnd && start >= end) {
          return 0;
        }
        if (thisStart >= thisEnd) {
          return -1;
        }
        if (start >= end) {
          return 1;
        }
        start >>>= 0;
        end >>>= 0;
        thisStart >>>= 0;
        thisEnd >>>= 0;
        if (this === target) return 0;
        let x = thisEnd - thisStart;
        let y = end - start;
        const len = Math.min(x, y);
        const thisCopy = this.slice(thisStart, thisEnd);
        const targetCopy = target.slice(start, end);
        for (let i = 0; i < len; ++i) {
          if (thisCopy[i] !== targetCopy[i]) {
            x = thisCopy[i];
            y = targetCopy[i];
            break;
          }
        }
        if (x < y) return -1;
        if (y < x) return 1;
        return 0;
      };
      function bidirectionalIndexOf(buffer, val, byteOffset, encoding, dir) {
        if (buffer.length === 0) return -1;
        if (typeof byteOffset === "string") {
          encoding = byteOffset;
          byteOffset = 0;
        } else if (byteOffset > 2147483647) {
          byteOffset = 2147483647;
        } else if (byteOffset < -2147483648) {
          byteOffset = -2147483648;
        }
        byteOffset = +byteOffset;
        if (numberIsNaN(byteOffset)) {
          byteOffset = dir ? 0 : buffer.length - 1;
        }
        if (byteOffset < 0) byteOffset = buffer.length + byteOffset;
        if (byteOffset >= buffer.length) {
          if (dir) return -1;
          else byteOffset = buffer.length - 1;
        } else if (byteOffset < 0) {
          if (dir) byteOffset = 0;
          else return -1;
        }
        if (typeof val === "string") {
          val = Buffer9.from(val, encoding);
        }
        if (Buffer9.isBuffer(val)) {
          if (val.length === 0) {
            return -1;
          }
          return arrayIndexOf(buffer, val, byteOffset, encoding, dir);
        } else if (typeof val === "number") {
          val = val & 255;
          if (typeof Uint8Array.prototype.indexOf === "function") {
            if (dir) {
              return Uint8Array.prototype.indexOf.call(buffer, val, byteOffset);
            } else {
              return Uint8Array.prototype.lastIndexOf.call(buffer, val, byteOffset);
            }
          }
          return arrayIndexOf(buffer, [val], byteOffset, encoding, dir);
        }
        throw new TypeError("val must be string, number or Buffer");
      }
      function arrayIndexOf(arr, val, byteOffset, encoding, dir) {
        let indexSize = 1;
        let arrLength = arr.length;
        let valLength = val.length;
        if (encoding !== void 0) {
          encoding = String(encoding).toLowerCase();
          if (encoding === "ucs2" || encoding === "ucs-2" || encoding === "utf16le" || encoding === "utf-16le") {
            if (arr.length < 2 || val.length < 2) {
              return -1;
            }
            indexSize = 2;
            arrLength /= 2;
            valLength /= 2;
            byteOffset /= 2;
          }
        }
        function read(buf, i2) {
          if (indexSize === 1) {
            return buf[i2];
          } else {
            return buf.readUInt16BE(i2 * indexSize);
          }
        }
        let i;
        if (dir) {
          let foundIndex = -1;
          for (i = byteOffset; i < arrLength; i++) {
            if (read(arr, i) === read(val, foundIndex === -1 ? 0 : i - foundIndex)) {
              if (foundIndex === -1) foundIndex = i;
              if (i - foundIndex + 1 === valLength) return foundIndex * indexSize;
            } else {
              if (foundIndex !== -1) i -= i - foundIndex;
              foundIndex = -1;
            }
          }
        } else {
          if (byteOffset + valLength > arrLength) byteOffset = arrLength - valLength;
          for (i = byteOffset; i >= 0; i--) {
            let found = true;
            for (let j = 0; j < valLength; j++) {
              if (read(arr, i + j) !== read(val, j)) {
                found = false;
                break;
              }
            }
            if (found) return i;
          }
        }
        return -1;
      }
      Buffer9.prototype.includes = function includes(val, byteOffset, encoding) {
        return this.indexOf(val, byteOffset, encoding) !== -1;
      };
      Buffer9.prototype.indexOf = function indexOf(val, byteOffset, encoding) {
        return bidirectionalIndexOf(this, val, byteOffset, encoding, true);
      };
      Buffer9.prototype.lastIndexOf = function lastIndexOf(val, byteOffset, encoding) {
        return bidirectionalIndexOf(this, val, byteOffset, encoding, false);
      };
      function hexWrite(buf, string, offset, length) {
        offset = Number(offset) || 0;
        const remaining = buf.length - offset;
        if (!length) {
          length = remaining;
        } else {
          length = Number(length);
          if (length > remaining) {
            length = remaining;
          }
        }
        const strLen = string.length;
        if (length > strLen / 2) {
          length = strLen / 2;
        }
        let i;
        for (i = 0; i < length; ++i) {
          const parsed = parseInt(string.substr(i * 2, 2), 16);
          if (numberIsNaN(parsed)) return i;
          buf[offset + i] = parsed;
        }
        return i;
      }
      function utf8Write(buf, string, offset, length) {
        return blitBuffer(utf8ToBytes(string, buf.length - offset), buf, offset, length);
      }
      function asciiWrite(buf, string, offset, length) {
        return blitBuffer(asciiToBytes(string), buf, offset, length);
      }
      function base64Write(buf, string, offset, length) {
        return blitBuffer(base64ToBytes(string), buf, offset, length);
      }
      function ucs2Write(buf, string, offset, length) {
        return blitBuffer(utf16leToBytes(string, buf.length - offset), buf, offset, length);
      }
      Buffer9.prototype.write = function write(string, offset, length, encoding) {
        if (offset === void 0) {
          encoding = "utf8";
          length = this.length;
          offset = 0;
        } else if (length === void 0 && typeof offset === "string") {
          encoding = offset;
          length = this.length;
          offset = 0;
        } else if (isFinite(offset)) {
          offset = offset >>> 0;
          if (isFinite(length)) {
            length = length >>> 0;
            if (encoding === void 0) encoding = "utf8";
          } else {
            encoding = length;
            length = void 0;
          }
        } else {
          throw new Error(
            "Buffer.write(string, encoding, offset[, length]) is no longer supported"
          );
        }
        const remaining = this.length - offset;
        if (length === void 0 || length > remaining) length = remaining;
        if (string.length > 0 && (length < 0 || offset < 0) || offset > this.length) {
          throw new RangeError("Attempt to write outside buffer bounds");
        }
        if (!encoding) encoding = "utf8";
        let loweredCase = false;
        for (; ; ) {
          switch (encoding) {
            case "hex":
              return hexWrite(this, string, offset, length);
            case "utf8":
            case "utf-8":
              return utf8Write(this, string, offset, length);
            case "ascii":
            case "latin1":
            case "binary":
              return asciiWrite(this, string, offset, length);
            case "base64":
              return base64Write(this, string, offset, length);
            case "ucs2":
            case "ucs-2":
            case "utf16le":
            case "utf-16le":
              return ucs2Write(this, string, offset, length);
            default:
              if (loweredCase) throw new TypeError("Unknown encoding: " + encoding);
              encoding = ("" + encoding).toLowerCase();
              loweredCase = true;
          }
        }
      };
      Buffer9.prototype.toJSON = function toJSON() {
        return {
          type: "Buffer",
          data: Array.prototype.slice.call(this._arr || this, 0)
        };
      };
      function base64Slice(buf, start, end) {
        if (start === 0 && end === buf.length) {
          return base64.fromByteArray(buf);
        } else {
          return base64.fromByteArray(buf.slice(start, end));
        }
      }
      function utf8Slice(buf, start, end) {
        end = Math.min(buf.length, end);
        const res = [];
        let i = start;
        while (i < end) {
          const firstByte = buf[i];
          let codePoint = null;
          let bytesPerSequence = firstByte > 239 ? 4 : firstByte > 223 ? 3 : firstByte > 191 ? 2 : 1;
          if (i + bytesPerSequence <= end) {
            let secondByte, thirdByte, fourthByte, tempCodePoint;
            switch (bytesPerSequence) {
              case 1:
                if (firstByte < 128) {
                  codePoint = firstByte;
                }
                break;
              case 2:
                secondByte = buf[i + 1];
                if ((secondByte & 192) === 128) {
                  tempCodePoint = (firstByte & 31) << 6 | secondByte & 63;
                  if (tempCodePoint > 127) {
                    codePoint = tempCodePoint;
                  }
                }
                break;
              case 3:
                secondByte = buf[i + 1];
                thirdByte = buf[i + 2];
                if ((secondByte & 192) === 128 && (thirdByte & 192) === 128) {
                  tempCodePoint = (firstByte & 15) << 12 | (secondByte & 63) << 6 | thirdByte & 63;
                  if (tempCodePoint > 2047 && (tempCodePoint < 55296 || tempCodePoint > 57343)) {
                    codePoint = tempCodePoint;
                  }
                }
                break;
              case 4:
                secondByte = buf[i + 1];
                thirdByte = buf[i + 2];
                fourthByte = buf[i + 3];
                if ((secondByte & 192) === 128 && (thirdByte & 192) === 128 && (fourthByte & 192) === 128) {
                  tempCodePoint = (firstByte & 15) << 18 | (secondByte & 63) << 12 | (thirdByte & 63) << 6 | fourthByte & 63;
                  if (tempCodePoint > 65535 && tempCodePoint < 1114112) {
                    codePoint = tempCodePoint;
                  }
                }
            }
          }
          if (codePoint === null) {
            codePoint = 65533;
            bytesPerSequence = 1;
          } else if (codePoint > 65535) {
            codePoint -= 65536;
            res.push(codePoint >>> 10 & 1023 | 55296);
            codePoint = 56320 | codePoint & 1023;
          }
          res.push(codePoint);
          i += bytesPerSequence;
        }
        return decodeCodePointsArray(res);
      }
      var MAX_ARGUMENTS_LENGTH = 4096;
      function decodeCodePointsArray(codePoints) {
        const len = codePoints.length;
        if (len <= MAX_ARGUMENTS_LENGTH) {
          return String.fromCharCode.apply(String, codePoints);
        }
        let res = "";
        let i = 0;
        while (i < len) {
          res += String.fromCharCode.apply(
            String,
            codePoints.slice(i, i += MAX_ARGUMENTS_LENGTH)
          );
        }
        return res;
      }
      function asciiSlice(buf, start, end) {
        let ret = "";
        end = Math.min(buf.length, end);
        for (let i = start; i < end; ++i) {
          ret += String.fromCharCode(buf[i] & 127);
        }
        return ret;
      }
      function latin1Slice(buf, start, end) {
        let ret = "";
        end = Math.min(buf.length, end);
        for (let i = start; i < end; ++i) {
          ret += String.fromCharCode(buf[i]);
        }
        return ret;
      }
      function hexSlice(buf, start, end) {
        const len = buf.length;
        if (!start || start < 0) start = 0;
        if (!end || end < 0 || end > len) end = len;
        let out = "";
        for (let i = start; i < end; ++i) {
          out += hexSliceLookupTable[buf[i]];
        }
        return out;
      }
      function utf16leSlice(buf, start, end) {
        const bytes = buf.slice(start, end);
        let res = "";
        for (let i = 0; i < bytes.length - 1; i += 2) {
          res += String.fromCharCode(bytes[i] + bytes[i + 1] * 256);
        }
        return res;
      }
      Buffer9.prototype.slice = function slice(start, end) {
        const len = this.length;
        start = ~~start;
        end = end === void 0 ? len : ~~end;
        if (start < 0) {
          start += len;
          if (start < 0) start = 0;
        } else if (start > len) {
          start = len;
        }
        if (end < 0) {
          end += len;
          if (end < 0) end = 0;
        } else if (end > len) {
          end = len;
        }
        if (end < start) end = start;
        const newBuf = this.subarray(start, end);
        Object.setPrototypeOf(newBuf, Buffer9.prototype);
        return newBuf;
      };
      function checkOffset(offset, ext, length) {
        if (offset % 1 !== 0 || offset < 0) throw new RangeError("offset is not uint");
        if (offset + ext > length) throw new RangeError("Trying to access beyond buffer length");
      }
      Buffer9.prototype.readUintLE = Buffer9.prototype.readUIntLE = function readUIntLE(offset, byteLength2, noAssert) {
        offset = offset >>> 0;
        byteLength2 = byteLength2 >>> 0;
        if (!noAssert) checkOffset(offset, byteLength2, this.length);
        let val = this[offset];
        let mul = 1;
        let i = 0;
        while (++i < byteLength2 && (mul *= 256)) {
          val += this[offset + i] * mul;
        }
        return val;
      };
      Buffer9.prototype.readUintBE = Buffer9.prototype.readUIntBE = function readUIntBE(offset, byteLength2, noAssert) {
        offset = offset >>> 0;
        byteLength2 = byteLength2 >>> 0;
        if (!noAssert) {
          checkOffset(offset, byteLength2, this.length);
        }
        let val = this[offset + --byteLength2];
        let mul = 1;
        while (byteLength2 > 0 && (mul *= 256)) {
          val += this[offset + --byteLength2] * mul;
        }
        return val;
      };
      Buffer9.prototype.readUint8 = Buffer9.prototype.readUInt8 = function readUInt8(offset, noAssert) {
        offset = offset >>> 0;
        if (!noAssert) checkOffset(offset, 1, this.length);
        return this[offset];
      };
      Buffer9.prototype.readUint16LE = Buffer9.prototype.readUInt16LE = function readUInt16LE(offset, noAssert) {
        offset = offset >>> 0;
        if (!noAssert) checkOffset(offset, 2, this.length);
        return this[offset] | this[offset + 1] << 8;
      };
      Buffer9.prototype.readUint16BE = Buffer9.prototype.readUInt16BE = function readUInt16BE(offset, noAssert) {
        offset = offset >>> 0;
        if (!noAssert) checkOffset(offset, 2, this.length);
        return this[offset] << 8 | this[offset + 1];
      };
      Buffer9.prototype.readUint32LE = Buffer9.prototype.readUInt32LE = function readUInt32LE(offset, noAssert) {
        offset = offset >>> 0;
        if (!noAssert) checkOffset(offset, 4, this.length);
        return (this[offset] | this[offset + 1] << 8 | this[offset + 2] << 16) + this[offset + 3] * 16777216;
      };
      Buffer9.prototype.readUint32BE = Buffer9.prototype.readUInt32BE = function readUInt32BE(offset, noAssert) {
        offset = offset >>> 0;
        if (!noAssert) checkOffset(offset, 4, this.length);
        return this[offset] * 16777216 + (this[offset + 1] << 16 | this[offset + 2] << 8 | this[offset + 3]);
      };
      Buffer9.prototype.readBigUInt64LE = defineBigIntMethod(function readBigUInt64LE(offset) {
        offset = offset >>> 0;
        validateNumber(offset, "offset");
        const first = this[offset];
        const last = this[offset + 7];
        if (first === void 0 || last === void 0) {
          boundsError(offset, this.length - 8);
        }
        const lo = first + this[++offset] * 2 ** 8 + this[++offset] * 2 ** 16 + this[++offset] * 2 ** 24;
        const hi = this[++offset] + this[++offset] * 2 ** 8 + this[++offset] * 2 ** 16 + last * 2 ** 24;
        return BigInt(lo) + (BigInt(hi) << BigInt(32));
      });
      Buffer9.prototype.readBigUInt64BE = defineBigIntMethod(function readBigUInt64BE(offset) {
        offset = offset >>> 0;
        validateNumber(offset, "offset");
        const first = this[offset];
        const last = this[offset + 7];
        if (first === void 0 || last === void 0) {
          boundsError(offset, this.length - 8);
        }
        const hi = first * 2 ** 24 + this[++offset] * 2 ** 16 + this[++offset] * 2 ** 8 + this[++offset];
        const lo = this[++offset] * 2 ** 24 + this[++offset] * 2 ** 16 + this[++offset] * 2 ** 8 + last;
        return (BigInt(hi) << BigInt(32)) + BigInt(lo);
      });
      Buffer9.prototype.readIntLE = function readIntLE(offset, byteLength2, noAssert) {
        offset = offset >>> 0;
        byteLength2 = byteLength2 >>> 0;
        if (!noAssert) checkOffset(offset, byteLength2, this.length);
        let val = this[offset];
        let mul = 1;
        let i = 0;
        while (++i < byteLength2 && (mul *= 256)) {
          val += this[offset + i] * mul;
        }
        mul *= 128;
        if (val >= mul) val -= Math.pow(2, 8 * byteLength2);
        return val;
      };
      Buffer9.prototype.readIntBE = function readIntBE(offset, byteLength2, noAssert) {
        offset = offset >>> 0;
        byteLength2 = byteLength2 >>> 0;
        if (!noAssert) checkOffset(offset, byteLength2, this.length);
        let i = byteLength2;
        let mul = 1;
        let val = this[offset + --i];
        while (i > 0 && (mul *= 256)) {
          val += this[offset + --i] * mul;
        }
        mul *= 128;
        if (val >= mul) val -= Math.pow(2, 8 * byteLength2);
        return val;
      };
      Buffer9.prototype.readInt8 = function readInt8(offset, noAssert) {
        offset = offset >>> 0;
        if (!noAssert) checkOffset(offset, 1, this.length);
        if (!(this[offset] & 128)) return this[offset];
        return (255 - this[offset] + 1) * -1;
      };
      Buffer9.prototype.readInt16LE = function readInt16LE(offset, noAssert) {
        offset = offset >>> 0;
        if (!noAssert) checkOffset(offset, 2, this.length);
        const val = this[offset] | this[offset + 1] << 8;
        return val & 32768 ? val | 4294901760 : val;
      };
      Buffer9.prototype.readInt16BE = function readInt16BE(offset, noAssert) {
        offset = offset >>> 0;
        if (!noAssert) checkOffset(offset, 2, this.length);
        const val = this[offset + 1] | this[offset] << 8;
        return val & 32768 ? val | 4294901760 : val;
      };
      Buffer9.prototype.readInt32LE = function readInt32LE(offset, noAssert) {
        offset = offset >>> 0;
        if (!noAssert) checkOffset(offset, 4, this.length);
        return this[offset] | this[offset + 1] << 8 | this[offset + 2] << 16 | this[offset + 3] << 24;
      };
      Buffer9.prototype.readInt32BE = function readInt32BE(offset, noAssert) {
        offset = offset >>> 0;
        if (!noAssert) checkOffset(offset, 4, this.length);
        return this[offset] << 24 | this[offset + 1] << 16 | this[offset + 2] << 8 | this[offset + 3];
      };
      Buffer9.prototype.readBigInt64LE = defineBigIntMethod(function readBigInt64LE(offset) {
        offset = offset >>> 0;
        validateNumber(offset, "offset");
        const first = this[offset];
        const last = this[offset + 7];
        if (first === void 0 || last === void 0) {
          boundsError(offset, this.length - 8);
        }
        const val = this[offset + 4] + this[offset + 5] * 2 ** 8 + this[offset + 6] * 2 ** 16 + (last << 24);
        return (BigInt(val) << BigInt(32)) + BigInt(first + this[++offset] * 2 ** 8 + this[++offset] * 2 ** 16 + this[++offset] * 2 ** 24);
      });
      Buffer9.prototype.readBigInt64BE = defineBigIntMethod(function readBigInt64BE(offset) {
        offset = offset >>> 0;
        validateNumber(offset, "offset");
        const first = this[offset];
        const last = this[offset + 7];
        if (first === void 0 || last === void 0) {
          boundsError(offset, this.length - 8);
        }
        const val = (first << 24) + // Overflow
        this[++offset] * 2 ** 16 + this[++offset] * 2 ** 8 + this[++offset];
        return (BigInt(val) << BigInt(32)) + BigInt(this[++offset] * 2 ** 24 + this[++offset] * 2 ** 16 + this[++offset] * 2 ** 8 + last);
      });
      Buffer9.prototype.readFloatLE = function readFloatLE(offset, noAssert) {
        offset = offset >>> 0;
        if (!noAssert) checkOffset(offset, 4, this.length);
        return ieee754.read(this, offset, true, 23, 4);
      };
      Buffer9.prototype.readFloatBE = function readFloatBE(offset, noAssert) {
        offset = offset >>> 0;
        if (!noAssert) checkOffset(offset, 4, this.length);
        return ieee754.read(this, offset, false, 23, 4);
      };
      Buffer9.prototype.readDoubleLE = function readDoubleLE(offset, noAssert) {
        offset = offset >>> 0;
        if (!noAssert) checkOffset(offset, 8, this.length);
        return ieee754.read(this, offset, true, 52, 8);
      };
      Buffer9.prototype.readDoubleBE = function readDoubleBE(offset, noAssert) {
        offset = offset >>> 0;
        if (!noAssert) checkOffset(offset, 8, this.length);
        return ieee754.read(this, offset, false, 52, 8);
      };
      function checkInt(buf, value, offset, ext, max, min) {
        if (!Buffer9.isBuffer(buf)) throw new TypeError('"buffer" argument must be a Buffer instance');
        if (value > max || value < min) throw new RangeError('"value" argument is out of bounds');
        if (offset + ext > buf.length) throw new RangeError("Index out of range");
      }
      Buffer9.prototype.writeUintLE = Buffer9.prototype.writeUIntLE = function writeUIntLE(value, offset, byteLength2, noAssert) {
        value = +value;
        offset = offset >>> 0;
        byteLength2 = byteLength2 >>> 0;
        if (!noAssert) {
          const maxBytes = Math.pow(2, 8 * byteLength2) - 1;
          checkInt(this, value, offset, byteLength2, maxBytes, 0);
        }
        let mul = 1;
        let i = 0;
        this[offset] = value & 255;
        while (++i < byteLength2 && (mul *= 256)) {
          this[offset + i] = value / mul & 255;
        }
        return offset + byteLength2;
      };
      Buffer9.prototype.writeUintBE = Buffer9.prototype.writeUIntBE = function writeUIntBE(value, offset, byteLength2, noAssert) {
        value = +value;
        offset = offset >>> 0;
        byteLength2 = byteLength2 >>> 0;
        if (!noAssert) {
          const maxBytes = Math.pow(2, 8 * byteLength2) - 1;
          checkInt(this, value, offset, byteLength2, maxBytes, 0);
        }
        let i = byteLength2 - 1;
        let mul = 1;
        this[offset + i] = value & 255;
        while (--i >= 0 && (mul *= 256)) {
          this[offset + i] = value / mul & 255;
        }
        return offset + byteLength2;
      };
      Buffer9.prototype.writeUint8 = Buffer9.prototype.writeUInt8 = function writeUInt8(value, offset, noAssert) {
        value = +value;
        offset = offset >>> 0;
        if (!noAssert) checkInt(this, value, offset, 1, 255, 0);
        this[offset] = value & 255;
        return offset + 1;
      };
      Buffer9.prototype.writeUint16LE = Buffer9.prototype.writeUInt16LE = function writeUInt16LE(value, offset, noAssert) {
        value = +value;
        offset = offset >>> 0;
        if (!noAssert) checkInt(this, value, offset, 2, 65535, 0);
        this[offset] = value & 255;
        this[offset + 1] = value >>> 8;
        return offset + 2;
      };
      Buffer9.prototype.writeUint16BE = Buffer9.prototype.writeUInt16BE = function writeUInt16BE(value, offset, noAssert) {
        value = +value;
        offset = offset >>> 0;
        if (!noAssert) checkInt(this, value, offset, 2, 65535, 0);
        this[offset] = value >>> 8;
        this[offset + 1] = value & 255;
        return offset + 2;
      };
      Buffer9.prototype.writeUint32LE = Buffer9.prototype.writeUInt32LE = function writeUInt32LE(value, offset, noAssert) {
        value = +value;
        offset = offset >>> 0;
        if (!noAssert) checkInt(this, value, offset, 4, 4294967295, 0);
        this[offset + 3] = value >>> 24;
        this[offset + 2] = value >>> 16;
        this[offset + 1] = value >>> 8;
        this[offset] = value & 255;
        return offset + 4;
      };
      Buffer9.prototype.writeUint32BE = Buffer9.prototype.writeUInt32BE = function writeUInt32BE(value, offset, noAssert) {
        value = +value;
        offset = offset >>> 0;
        if (!noAssert) checkInt(this, value, offset, 4, 4294967295, 0);
        this[offset] = value >>> 24;
        this[offset + 1] = value >>> 16;
        this[offset + 2] = value >>> 8;
        this[offset + 3] = value & 255;
        return offset + 4;
      };
      function wrtBigUInt64LE(buf, value, offset, min, max) {
        checkIntBI(value, min, max, buf, offset, 7);
        let lo = Number(value & BigInt(4294967295));
        buf[offset++] = lo;
        lo = lo >> 8;
        buf[offset++] = lo;
        lo = lo >> 8;
        buf[offset++] = lo;
        lo = lo >> 8;
        buf[offset++] = lo;
        let hi = Number(value >> BigInt(32) & BigInt(4294967295));
        buf[offset++] = hi;
        hi = hi >> 8;
        buf[offset++] = hi;
        hi = hi >> 8;
        buf[offset++] = hi;
        hi = hi >> 8;
        buf[offset++] = hi;
        return offset;
      }
      function wrtBigUInt64BE(buf, value, offset, min, max) {
        checkIntBI(value, min, max, buf, offset, 7);
        let lo = Number(value & BigInt(4294967295));
        buf[offset + 7] = lo;
        lo = lo >> 8;
        buf[offset + 6] = lo;
        lo = lo >> 8;
        buf[offset + 5] = lo;
        lo = lo >> 8;
        buf[offset + 4] = lo;
        let hi = Number(value >> BigInt(32) & BigInt(4294967295));
        buf[offset + 3] = hi;
        hi = hi >> 8;
        buf[offset + 2] = hi;
        hi = hi >> 8;
        buf[offset + 1] = hi;
        hi = hi >> 8;
        buf[offset] = hi;
        return offset + 8;
      }
      Buffer9.prototype.writeBigUInt64LE = defineBigIntMethod(function writeBigUInt64LE(value, offset = 0) {
        return wrtBigUInt64LE(this, value, offset, BigInt(0), BigInt("0xffffffffffffffff"));
      });
      Buffer9.prototype.writeBigUInt64BE = defineBigIntMethod(function writeBigUInt64BE(value, offset = 0) {
        return wrtBigUInt64BE(this, value, offset, BigInt(0), BigInt("0xffffffffffffffff"));
      });
      Buffer9.prototype.writeIntLE = function writeIntLE(value, offset, byteLength2, noAssert) {
        value = +value;
        offset = offset >>> 0;
        if (!noAssert) {
          const limit = Math.pow(2, 8 * byteLength2 - 1);
          checkInt(this, value, offset, byteLength2, limit - 1, -limit);
        }
        let i = 0;
        let mul = 1;
        let sub = 0;
        this[offset] = value & 255;
        while (++i < byteLength2 && (mul *= 256)) {
          if (value < 0 && sub === 0 && this[offset + i - 1] !== 0) {
            sub = 1;
          }
          this[offset + i] = (value / mul >> 0) - sub & 255;
        }
        return offset + byteLength2;
      };
      Buffer9.prototype.writeIntBE = function writeIntBE(value, offset, byteLength2, noAssert) {
        value = +value;
        offset = offset >>> 0;
        if (!noAssert) {
          const limit = Math.pow(2, 8 * byteLength2 - 1);
          checkInt(this, value, offset, byteLength2, limit - 1, -limit);
        }
        let i = byteLength2 - 1;
        let mul = 1;
        let sub = 0;
        this[offset + i] = value & 255;
        while (--i >= 0 && (mul *= 256)) {
          if (value < 0 && sub === 0 && this[offset + i + 1] !== 0) {
            sub = 1;
          }
          this[offset + i] = (value / mul >> 0) - sub & 255;
        }
        return offset + byteLength2;
      };
      Buffer9.prototype.writeInt8 = function writeInt8(value, offset, noAssert) {
        value = +value;
        offset = offset >>> 0;
        if (!noAssert) checkInt(this, value, offset, 1, 127, -128);
        if (value < 0) value = 255 + value + 1;
        this[offset] = value & 255;
        return offset + 1;
      };
      Buffer9.prototype.writeInt16LE = function writeInt16LE(value, offset, noAssert) {
        value = +value;
        offset = offset >>> 0;
        if (!noAssert) checkInt(this, value, offset, 2, 32767, -32768);
        this[offset] = value & 255;
        this[offset + 1] = value >>> 8;
        return offset + 2;
      };
      Buffer9.prototype.writeInt16BE = function writeInt16BE(value, offset, noAssert) {
        value = +value;
        offset = offset >>> 0;
        if (!noAssert) checkInt(this, value, offset, 2, 32767, -32768);
        this[offset] = value >>> 8;
        this[offset + 1] = value & 255;
        return offset + 2;
      };
      Buffer9.prototype.writeInt32LE = function writeInt32LE(value, offset, noAssert) {
        value = +value;
        offset = offset >>> 0;
        if (!noAssert) checkInt(this, value, offset, 4, 2147483647, -2147483648);
        this[offset] = value & 255;
        this[offset + 1] = value >>> 8;
        this[offset + 2] = value >>> 16;
        this[offset + 3] = value >>> 24;
        return offset + 4;
      };
      Buffer9.prototype.writeInt32BE = function writeInt32BE(value, offset, noAssert) {
        value = +value;
        offset = offset >>> 0;
        if (!noAssert) checkInt(this, value, offset, 4, 2147483647, -2147483648);
        if (value < 0) value = 4294967295 + value + 1;
        this[offset] = value >>> 24;
        this[offset + 1] = value >>> 16;
        this[offset + 2] = value >>> 8;
        this[offset + 3] = value & 255;
        return offset + 4;
      };
      Buffer9.prototype.writeBigInt64LE = defineBigIntMethod(function writeBigInt64LE(value, offset = 0) {
        return wrtBigUInt64LE(this, value, offset, -BigInt("0x8000000000000000"), BigInt("0x7fffffffffffffff"));
      });
      Buffer9.prototype.writeBigInt64BE = defineBigIntMethod(function writeBigInt64BE(value, offset = 0) {
        return wrtBigUInt64BE(this, value, offset, -BigInt("0x8000000000000000"), BigInt("0x7fffffffffffffff"));
      });
      function checkIEEE754(buf, value, offset, ext, max, min) {
        if (offset + ext > buf.length) throw new RangeError("Index out of range");
        if (offset < 0) throw new RangeError("Index out of range");
      }
      function writeFloat(buf, value, offset, littleEndian, noAssert) {
        value = +value;
        offset = offset >>> 0;
        if (!noAssert) {
          checkIEEE754(buf, value, offset, 4, 34028234663852886e22, -34028234663852886e22);
        }
        ieee754.write(buf, value, offset, littleEndian, 23, 4);
        return offset + 4;
      }
      Buffer9.prototype.writeFloatLE = function writeFloatLE(value, offset, noAssert) {
        return writeFloat(this, value, offset, true, noAssert);
      };
      Buffer9.prototype.writeFloatBE = function writeFloatBE(value, offset, noAssert) {
        return writeFloat(this, value, offset, false, noAssert);
      };
      function writeDouble(buf, value, offset, littleEndian, noAssert) {
        value = +value;
        offset = offset >>> 0;
        if (!noAssert) {
          checkIEEE754(buf, value, offset, 8, 17976931348623157e292, -17976931348623157e292);
        }
        ieee754.write(buf, value, offset, littleEndian, 52, 8);
        return offset + 8;
      }
      Buffer9.prototype.writeDoubleLE = function writeDoubleLE(value, offset, noAssert) {
        return writeDouble(this, value, offset, true, noAssert);
      };
      Buffer9.prototype.writeDoubleBE = function writeDoubleBE(value, offset, noAssert) {
        return writeDouble(this, value, offset, false, noAssert);
      };
      Buffer9.prototype.copy = function copy(target, targetStart, start, end) {
        if (!Buffer9.isBuffer(target)) throw new TypeError("argument should be a Buffer");
        if (!start) start = 0;
        if (!end && end !== 0) end = this.length;
        if (targetStart >= target.length) targetStart = target.length;
        if (!targetStart) targetStart = 0;
        if (end > 0 && end < start) end = start;
        if (end === start) return 0;
        if (target.length === 0 || this.length === 0) return 0;
        if (targetStart < 0) {
          throw new RangeError("targetStart out of bounds");
        }
        if (start < 0 || start >= this.length) throw new RangeError("Index out of range");
        if (end < 0) throw new RangeError("sourceEnd out of bounds");
        if (end > this.length) end = this.length;
        if (target.length - targetStart < end - start) {
          end = target.length - targetStart + start;
        }
        const len = end - start;
        if (this === target && typeof Uint8Array.prototype.copyWithin === "function") {
          this.copyWithin(targetStart, start, end);
        } else {
          Uint8Array.prototype.set.call(
            target,
            this.subarray(start, end),
            targetStart
          );
        }
        return len;
      };
      Buffer9.prototype.fill = function fill(val, start, end, encoding) {
        if (typeof val === "string") {
          if (typeof start === "string") {
            encoding = start;
            start = 0;
            end = this.length;
          } else if (typeof end === "string") {
            encoding = end;
            end = this.length;
          }
          if (encoding !== void 0 && typeof encoding !== "string") {
            throw new TypeError("encoding must be a string");
          }
          if (typeof encoding === "string" && !Buffer9.isEncoding(encoding)) {
            throw new TypeError("Unknown encoding: " + encoding);
          }
          if (val.length === 1) {
            const code = val.charCodeAt(0);
            if (encoding === "utf8" && code < 128 || encoding === "latin1") {
              val = code;
            }
          }
        } else if (typeof val === "number") {
          val = val & 255;
        } else if (typeof val === "boolean") {
          val = Number(val);
        }
        if (start < 0 || this.length < start || this.length < end) {
          throw new RangeError("Out of range index");
        }
        if (end <= start) {
          return this;
        }
        start = start >>> 0;
        end = end === void 0 ? this.length : end >>> 0;
        if (!val) val = 0;
        let i;
        if (typeof val === "number") {
          for (i = start; i < end; ++i) {
            this[i] = val;
          }
        } else {
          const bytes = Buffer9.isBuffer(val) ? val : Buffer9.from(val, encoding);
          const len = bytes.length;
          if (len === 0) {
            throw new TypeError('The value "' + val + '" is invalid for argument "value"');
          }
          for (i = 0; i < end - start; ++i) {
            this[i + start] = bytes[i % len];
          }
        }
        return this;
      };
      var errors = {};
      function E(sym, getMessage, Base) {
        errors[sym] = class NodeError extends Base {
          constructor() {
            super();
            Object.defineProperty(this, "message", {
              value: getMessage.apply(this, arguments),
              writable: true,
              configurable: true
            });
            this.name = `${this.name} [${sym}]`;
            this.stack;
            delete this.name;
          }
          get code() {
            return sym;
          }
          set code(value) {
            Object.defineProperty(this, "code", {
              configurable: true,
              enumerable: true,
              value,
              writable: true
            });
          }
          toString() {
            return `${this.name} [${sym}]: ${this.message}`;
          }
        };
      }
      E(
        "ERR_BUFFER_OUT_OF_BOUNDS",
        function(name) {
          if (name) {
            return `${name} is outside of buffer bounds`;
          }
          return "Attempt to access memory outside buffer bounds";
        },
        RangeError
      );
      E(
        "ERR_INVALID_ARG_TYPE",
        function(name, actual) {
          return `The "${name}" argument must be of type number. Received type ${typeof actual}`;
        },
        TypeError
      );
      E(
        "ERR_OUT_OF_RANGE",
        function(str, range, input) {
          let msg = `The value of "${str}" is out of range.`;
          let received = input;
          if (Number.isInteger(input) && Math.abs(input) > 2 ** 32) {
            received = addNumericalSeparator(String(input));
          } else if (typeof input === "bigint") {
            received = String(input);
            if (input > BigInt(2) ** BigInt(32) || input < -(BigInt(2) ** BigInt(32))) {
              received = addNumericalSeparator(received);
            }
            received += "n";
          }
          msg += ` It must be ${range}. Received ${received}`;
          return msg;
        },
        RangeError
      );
      function addNumericalSeparator(val) {
        let res = "";
        let i = val.length;
        const start = val[0] === "-" ? 1 : 0;
        for (; i >= start + 4; i -= 3) {
          res = `_${val.slice(i - 3, i)}${res}`;
        }
        return `${val.slice(0, i)}${res}`;
      }
      function checkBounds(buf, offset, byteLength2) {
        validateNumber(offset, "offset");
        if (buf[offset] === void 0 || buf[offset + byteLength2] === void 0) {
          boundsError(offset, buf.length - (byteLength2 + 1));
        }
      }
      function checkIntBI(value, min, max, buf, offset, byteLength2) {
        if (value > max || value < min) {
          const n = typeof min === "bigint" ? "n" : "";
          let range;
          if (byteLength2 > 3) {
            if (min === 0 || min === BigInt(0)) {
              range = `>= 0${n} and < 2${n} ** ${(byteLength2 + 1) * 8}${n}`;
            } else {
              range = `>= -(2${n} ** ${(byteLength2 + 1) * 8 - 1}${n}) and < 2 ** ${(byteLength2 + 1) * 8 - 1}${n}`;
            }
          } else {
            range = `>= ${min}${n} and <= ${max}${n}`;
          }
          throw new errors.ERR_OUT_OF_RANGE("value", range, value);
        }
        checkBounds(buf, offset, byteLength2);
      }
      function validateNumber(value, name) {
        if (typeof value !== "number") {
          throw new errors.ERR_INVALID_ARG_TYPE(name, "number", value);
        }
      }
      function boundsError(value, length, type) {
        if (Math.floor(value) !== value) {
          validateNumber(value, type);
          throw new errors.ERR_OUT_OF_RANGE(type || "offset", "an integer", value);
        }
        if (length < 0) {
          throw new errors.ERR_BUFFER_OUT_OF_BOUNDS();
        }
        throw new errors.ERR_OUT_OF_RANGE(
          type || "offset",
          `>= ${type ? 1 : 0} and <= ${length}`,
          value
        );
      }
      var INVALID_BASE64_RE = /[^+/0-9A-Za-z-_]/g;
      function base64clean(str) {
        str = str.split("=")[0];
        str = str.trim().replace(INVALID_BASE64_RE, "");
        if (str.length < 2) return "";
        while (str.length % 4 !== 0) {
          str = str + "=";
        }
        return str;
      }
      function utf8ToBytes(string, units) {
        units = units || Infinity;
        let codePoint;
        const length = string.length;
        let leadSurrogate = null;
        const bytes = [];
        for (let i = 0; i < length; ++i) {
          codePoint = string.charCodeAt(i);
          if (codePoint > 55295 && codePoint < 57344) {
            if (!leadSurrogate) {
              if (codePoint > 56319) {
                if ((units -= 3) > -1) bytes.push(239, 191, 189);
                continue;
              } else if (i + 1 === length) {
                if ((units -= 3) > -1) bytes.push(239, 191, 189);
                continue;
              }
              leadSurrogate = codePoint;
              continue;
            }
            if (codePoint < 56320) {
              if ((units -= 3) > -1) bytes.push(239, 191, 189);
              leadSurrogate = codePoint;
              continue;
            }
            codePoint = (leadSurrogate - 55296 << 10 | codePoint - 56320) + 65536;
          } else if (leadSurrogate) {
            if ((units -= 3) > -1) bytes.push(239, 191, 189);
          }
          leadSurrogate = null;
          if (codePoint < 128) {
            if ((units -= 1) < 0) break;
            bytes.push(codePoint);
          } else if (codePoint < 2048) {
            if ((units -= 2) < 0) break;
            bytes.push(
              codePoint >> 6 | 192,
              codePoint & 63 | 128
            );
          } else if (codePoint < 65536) {
            if ((units -= 3) < 0) break;
            bytes.push(
              codePoint >> 12 | 224,
              codePoint >> 6 & 63 | 128,
              codePoint & 63 | 128
            );
          } else if (codePoint < 1114112) {
            if ((units -= 4) < 0) break;
            bytes.push(
              codePoint >> 18 | 240,
              codePoint >> 12 & 63 | 128,
              codePoint >> 6 & 63 | 128,
              codePoint & 63 | 128
            );
          } else {
            throw new Error("Invalid code point");
          }
        }
        return bytes;
      }
      function asciiToBytes(str) {
        const byteArray = [];
        for (let i = 0; i < str.length; ++i) {
          byteArray.push(str.charCodeAt(i) & 255);
        }
        return byteArray;
      }
      function utf16leToBytes(str, units) {
        let c, hi, lo;
        const byteArray = [];
        for (let i = 0; i < str.length; ++i) {
          if ((units -= 2) < 0) break;
          c = str.charCodeAt(i);
          hi = c >> 8;
          lo = c % 256;
          byteArray.push(lo);
          byteArray.push(hi);
        }
        return byteArray;
      }
      function base64ToBytes(str) {
        return base64.toByteArray(base64clean(str));
      }
      function blitBuffer(src, dst, offset, length) {
        let i;
        for (i = 0; i < length; ++i) {
          if (i + offset >= dst.length || i >= src.length) break;
          dst[i + offset] = src[i];
        }
        return i;
      }
      function isInstance(obj, type) {
        return obj instanceof type || obj != null && obj.constructor != null && obj.constructor.name != null && obj.constructor.name === type.name;
      }
      function numberIsNaN(obj) {
        return obj !== obj;
      }
      var hexSliceLookupTable = (function() {
        const alphabet = "0123456789abcdef";
        const table = new Array(256);
        for (let i = 0; i < 16; ++i) {
          const i16 = i * 16;
          for (let j = 0; j < 16; ++j) {
            table[i16 + j] = alphabet[i] + alphabet[j];
          }
        }
        return table;
      })();
      function defineBigIntMethod(fn) {
        return typeof BigInt === "undefined" ? BufferBigIntNotDefined : fn;
      }
      function BufferBigIntNotDefined() {
        throw new Error("BigInt not supported");
      }
    }
  });

  // node_modules/he/he.js
  var require_he = __commonJS({
    "node_modules/he/he.js"(exports, module) {
      (function(root) {
        var freeExports = typeof exports == "object" && exports;
        var freeModule = typeof module == "object" && module && module.exports == freeExports && module;
        var freeGlobal = typeof global == "object" && global;
        if (freeGlobal.global === freeGlobal || freeGlobal.window === freeGlobal) {
          root = freeGlobal;
        }
        var regexAstralSymbols = /[\uD800-\uDBFF][\uDC00-\uDFFF]/g;
        var regexAsciiWhitelist = /[\x01-\x7F]/g;
        var regexBmpWhitelist = /[\x01-\t\x0B\f\x0E-\x1F\x7F\x81\x8D\x8F\x90\x9D\xA0-\uFFFF]/g;
        var regexEncodeNonAscii = /<\u20D2|=\u20E5|>\u20D2|\u205F\u200A|\u219D\u0338|\u2202\u0338|\u2220\u20D2|\u2229\uFE00|\u222A\uFE00|\u223C\u20D2|\u223D\u0331|\u223E\u0333|\u2242\u0338|\u224B\u0338|\u224D\u20D2|\u224E\u0338|\u224F\u0338|\u2250\u0338|\u2261\u20E5|\u2264\u20D2|\u2265\u20D2|\u2266\u0338|\u2267\u0338|\u2268\uFE00|\u2269\uFE00|\u226A\u0338|\u226A\u20D2|\u226B\u0338|\u226B\u20D2|\u227F\u0338|\u2282\u20D2|\u2283\u20D2|\u228A\uFE00|\u228B\uFE00|\u228F\u0338|\u2290\u0338|\u2293\uFE00|\u2294\uFE00|\u22B4\u20D2|\u22B5\u20D2|\u22D8\u0338|\u22D9\u0338|\u22DA\uFE00|\u22DB\uFE00|\u22F5\u0338|\u22F9\u0338|\u2933\u0338|\u29CF\u0338|\u29D0\u0338|\u2A6D\u0338|\u2A70\u0338|\u2A7D\u0338|\u2A7E\u0338|\u2AA1\u0338|\u2AA2\u0338|\u2AAC\uFE00|\u2AAD\uFE00|\u2AAF\u0338|\u2AB0\u0338|\u2AC5\u0338|\u2AC6\u0338|\u2ACB\uFE00|\u2ACC\uFE00|\u2AFD\u20E5|[\xA0-\u0113\u0116-\u0122\u0124-\u012B\u012E-\u014D\u0150-\u017E\u0192\u01B5\u01F5\u0237\u02C6\u02C7\u02D8-\u02DD\u0311\u0391-\u03A1\u03A3-\u03A9\u03B1-\u03C9\u03D1\u03D2\u03D5\u03D6\u03DC\u03DD\u03F0\u03F1\u03F5\u03F6\u0401-\u040C\u040E-\u044F\u0451-\u045C\u045E\u045F\u2002-\u2005\u2007-\u2010\u2013-\u2016\u2018-\u201A\u201C-\u201E\u2020-\u2022\u2025\u2026\u2030-\u2035\u2039\u203A\u203E\u2041\u2043\u2044\u204F\u2057\u205F-\u2063\u20AC\u20DB\u20DC\u2102\u2105\u210A-\u2113\u2115-\u211E\u2122\u2124\u2127-\u2129\u212C\u212D\u212F-\u2131\u2133-\u2138\u2145-\u2148\u2153-\u215E\u2190-\u219B\u219D-\u21A7\u21A9-\u21AE\u21B0-\u21B3\u21B5-\u21B7\u21BA-\u21DB\u21DD\u21E4\u21E5\u21F5\u21FD-\u2205\u2207-\u2209\u220B\u220C\u220F-\u2214\u2216-\u2218\u221A\u221D-\u2238\u223A-\u2257\u2259\u225A\u225C\u225F-\u2262\u2264-\u228B\u228D-\u229B\u229D-\u22A5\u22A7-\u22B0\u22B2-\u22BB\u22BD-\u22DB\u22DE-\u22E3\u22E6-\u22F7\u22F9-\u22FE\u2305\u2306\u2308-\u2310\u2312\u2313\u2315\u2316\u231C-\u231F\u2322\u2323\u232D\u232E\u2336\u233D\u233F\u237C\u23B0\u23B1\u23B4-\u23B6\u23DC-\u23DF\u23E2\u23E7\u2423\u24C8\u2500\u2502\u250C\u2510\u2514\u2518\u251C\u2524\u252C\u2534\u253C\u2550-\u256C\u2580\u2584\u2588\u2591-\u2593\u25A1\u25AA\u25AB\u25AD\u25AE\u25B1\u25B3-\u25B5\u25B8\u25B9\u25BD-\u25BF\u25C2\u25C3\u25CA\u25CB\u25EC\u25EF\u25F8-\u25FC\u2605\u2606\u260E\u2640\u2642\u2660\u2663\u2665\u2666\u266A\u266D-\u266F\u2713\u2717\u2720\u2736\u2758\u2772\u2773\u27C8\u27C9\u27E6-\u27ED\u27F5-\u27FA\u27FC\u27FF\u2902-\u2905\u290C-\u2913\u2916\u2919-\u2920\u2923-\u292A\u2933\u2935-\u2939\u293C\u293D\u2945\u2948-\u294B\u294E-\u2976\u2978\u2979\u297B-\u297F\u2985\u2986\u298B-\u2996\u299A\u299C\u299D\u29A4-\u29B7\u29B9\u29BB\u29BC\u29BE-\u29C5\u29C9\u29CD-\u29D0\u29DC-\u29DE\u29E3-\u29E5\u29EB\u29F4\u29F6\u2A00-\u2A02\u2A04\u2A06\u2A0C\u2A0D\u2A10-\u2A17\u2A22-\u2A27\u2A29\u2A2A\u2A2D-\u2A31\u2A33-\u2A3C\u2A3F\u2A40\u2A42-\u2A4D\u2A50\u2A53-\u2A58\u2A5A-\u2A5D\u2A5F\u2A66\u2A6A\u2A6D-\u2A75\u2A77-\u2A9A\u2A9D-\u2AA2\u2AA4-\u2AB0\u2AB3-\u2AC8\u2ACB\u2ACC\u2ACF-\u2ADB\u2AE4\u2AE6-\u2AE9\u2AEB-\u2AF3\u2AFD\uFB00-\uFB04]|\uD835[\uDC9C\uDC9E\uDC9F\uDCA2\uDCA5\uDCA6\uDCA9-\uDCAC\uDCAE-\uDCB9\uDCBB\uDCBD-\uDCC3\uDCC5-\uDCCF\uDD04\uDD05\uDD07-\uDD0A\uDD0D-\uDD14\uDD16-\uDD1C\uDD1E-\uDD39\uDD3B-\uDD3E\uDD40-\uDD44\uDD46\uDD4A-\uDD50\uDD52-\uDD6B]/g;
        var encodeMap = { "­": "shy", "‌": "zwnj", "‍": "zwj", "‎": "lrm", "⁣": "ic", "⁢": "it", "⁡": "af", "‏": "rlm", "​": "ZeroWidthSpace", "⁠": "NoBreak", "̑": "DownBreve", "⃛": "tdot", "⃜": "DotDot", "	": "Tab", "\n": "NewLine", " ": "puncsp", " ": "MediumSpace", " ": "thinsp", " ": "hairsp", " ": "emsp13", " ": "ensp", " ": "emsp14", " ": "emsp", " ": "numsp", " ": "nbsp", "  ": "ThickSpace", "‾": "oline", "_": "lowbar", "‐": "dash", "–": "ndash", "—": "mdash", "―": "horbar", ",": "comma", ";": "semi", "⁏": "bsemi", ":": "colon", "⩴": "Colone", "!": "excl", "¡": "iexcl", "?": "quest", "¿": "iquest", ".": "period", "‥": "nldr", "…": "mldr", "·": "middot", "'": "apos", "‘": "lsquo", "’": "rsquo", "‚": "sbquo", "‹": "lsaquo", "›": "rsaquo", '"': "quot", "“": "ldquo", "”": "rdquo", "„": "bdquo", "«": "laquo", "»": "raquo", "(": "lpar", ")": "rpar", "[": "lsqb", "]": "rsqb", "{": "lcub", "}": "rcub", "⌈": "lceil", "⌉": "rceil", "⌊": "lfloor", "⌋": "rfloor", "⦅": "lopar", "⦆": "ropar", "⦋": "lbrke", "⦌": "rbrke", "⦍": "lbrkslu", "⦎": "rbrksld", "⦏": "lbrksld", "⦐": "rbrkslu", "⦑": "langd", "⦒": "rangd", "⦓": "lparlt", "⦔": "rpargt", "⦕": "gtlPar", "⦖": "ltrPar", "⟦": "lobrk", "⟧": "robrk", "⟨": "lang", "⟩": "rang", "⟪": "Lang", "⟫": "Rang", "⟬": "loang", "⟭": "roang", "❲": "lbbrk", "❳": "rbbrk", "‖": "Vert", "§": "sect", "¶": "para", "@": "commat", "*": "ast", "/": "sol", "undefined": null, "&": "amp", "#": "num", "%": "percnt", "‰": "permil", "‱": "pertenk", "†": "dagger", "‡": "Dagger", "•": "bull", "⁃": "hybull", "′": "prime", "″": "Prime", "‴": "tprime", "⁗": "qprime", "‵": "bprime", "⁁": "caret", "`": "grave", "´": "acute", "˜": "tilde", "^": "Hat", "¯": "macr", "˘": "breve", "˙": "dot", "¨": "die", "˚": "ring", "˝": "dblac", "¸": "cedil", "˛": "ogon", "ˆ": "circ", "ˇ": "caron", "°": "deg", "©": "copy", "®": "reg", "℗": "copysr", "℘": "wp", "℞": "rx", "℧": "mho", "℩": "iiota", "←": "larr", "↚": "nlarr", "→": "rarr", "↛": "nrarr", "↑": "uarr", "↓": "darr", "↔": "harr", "↮": "nharr", "↕": "varr", "↖": "nwarr", "↗": "nearr", "↘": "searr", "↙": "swarr", "↝": "rarrw", "↝̸": "nrarrw", "↞": "Larr", "↟": "Uarr", "↠": "Rarr", "↡": "Darr", "↢": "larrtl", "↣": "rarrtl", "↤": "mapstoleft", "↥": "mapstoup", "↦": "map", "↧": "mapstodown", "↩": "larrhk", "↪": "rarrhk", "↫": "larrlp", "↬": "rarrlp", "↭": "harrw", "↰": "lsh", "↱": "rsh", "↲": "ldsh", "↳": "rdsh", "↵": "crarr", "↶": "cularr", "↷": "curarr", "↺": "olarr", "↻": "orarr", "↼": "lharu", "↽": "lhard", "↾": "uharr", "↿": "uharl", "⇀": "rharu", "⇁": "rhard", "⇂": "dharr", "⇃": "dharl", "⇄": "rlarr", "⇅": "udarr", "⇆": "lrarr", "⇇": "llarr", "⇈": "uuarr", "⇉": "rrarr", "⇊": "ddarr", "⇋": "lrhar", "⇌": "rlhar", "⇐": "lArr", "⇍": "nlArr", "⇑": "uArr", "⇒": "rArr", "⇏": "nrArr", "⇓": "dArr", "⇔": "iff", "⇎": "nhArr", "⇕": "vArr", "⇖": "nwArr", "⇗": "neArr", "⇘": "seArr", "⇙": "swArr", "⇚": "lAarr", "⇛": "rAarr", "⇝": "zigrarr", "⇤": "larrb", "⇥": "rarrb", "⇵": "duarr", "⇽": "loarr", "⇾": "roarr", "⇿": "hoarr", "∀": "forall", "∁": "comp", "∂": "part", "∂̸": "npart", "∃": "exist", "∄": "nexist", "∅": "empty", "∇": "Del", "∈": "in", "∉": "notin", "∋": "ni", "∌": "notni", "϶": "bepsi", "∏": "prod", "∐": "coprod", "∑": "sum", "+": "plus", "±": "pm", "÷": "div", "×": "times", "<": "lt", "≮": "nlt", "<⃒": "nvlt", "=": "equals", "≠": "ne", "=⃥": "bne", "⩵": "Equal", ">": "gt", "≯": "ngt", ">⃒": "nvgt", "¬": "not", "|": "vert", "¦": "brvbar", "−": "minus", "∓": "mp", "∔": "plusdo", "⁄": "frasl", "∖": "setmn", "∗": "lowast", "∘": "compfn", "√": "Sqrt", "∝": "prop", "∞": "infin", "∟": "angrt", "∠": "ang", "∠⃒": "nang", "∡": "angmsd", "∢": "angsph", "∣": "mid", "∤": "nmid", "∥": "par", "∦": "npar", "∧": "and", "∨": "or", "∩": "cap", "∩︀": "caps", "∪": "cup", "∪︀": "cups", "∫": "int", "∬": "Int", "∭": "tint", "⨌": "qint", "∮": "oint", "∯": "Conint", "∰": "Cconint", "∱": "cwint", "∲": "cwconint", "∳": "awconint", "∴": "there4", "∵": "becaus", "∶": "ratio", "∷": "Colon", "∸": "minusd", "∺": "mDDot", "∻": "homtht", "∼": "sim", "≁": "nsim", "∼⃒": "nvsim", "∽": "bsim", "∽̱": "race", "∾": "ac", "∾̳": "acE", "∿": "acd", "≀": "wr", "≂": "esim", "≂̸": "nesim", "≃": "sime", "≄": "nsime", "≅": "cong", "≇": "ncong", "≆": "simne", "≈": "ap", "≉": "nap", "≊": "ape", "≋": "apid", "≋̸": "napid", "≌": "bcong", "≍": "CupCap", "≭": "NotCupCap", "≍⃒": "nvap", "≎": "bump", "≎̸": "nbump", "≏": "bumpe", "≏̸": "nbumpe", "≐": "doteq", "≐̸": "nedot", "≑": "eDot", "≒": "efDot", "≓": "erDot", "≔": "colone", "≕": "ecolon", "≖": "ecir", "≗": "cire", "≙": "wedgeq", "≚": "veeeq", "≜": "trie", "≟": "equest", "≡": "equiv", "≢": "nequiv", "≡⃥": "bnequiv", "≤": "le", "≰": "nle", "≤⃒": "nvle", "≥": "ge", "≱": "nge", "≥⃒": "nvge", "≦": "lE", "≦̸": "nlE", "≧": "gE", "≧̸": "ngE", "≨︀": "lvnE", "≨": "lnE", "≩": "gnE", "≩︀": "gvnE", "≪": "ll", "≪̸": "nLtv", "≪⃒": "nLt", "≫": "gg", "≫̸": "nGtv", "≫⃒": "nGt", "≬": "twixt", "≲": "lsim", "≴": "nlsim", "≳": "gsim", "≵": "ngsim", "≶": "lg", "≸": "ntlg", "≷": "gl", "≹": "ntgl", "≺": "pr", "⊀": "npr", "≻": "sc", "⊁": "nsc", "≼": "prcue", "⋠": "nprcue", "≽": "sccue", "⋡": "nsccue", "≾": "prsim", "≿": "scsim", "≿̸": "NotSucceedsTilde", "⊂": "sub", "⊄": "nsub", "⊂⃒": "vnsub", "⊃": "sup", "⊅": "nsup", "⊃⃒": "vnsup", "⊆": "sube", "⊈": "nsube", "⊇": "supe", "⊉": "nsupe", "⊊︀": "vsubne", "⊊": "subne", "⊋︀": "vsupne", "⊋": "supne", "⊍": "cupdot", "⊎": "uplus", "⊏": "sqsub", "⊏̸": "NotSquareSubset", "⊐": "sqsup", "⊐̸": "NotSquareSuperset", "⊑": "sqsube", "⋢": "nsqsube", "⊒": "sqsupe", "⋣": "nsqsupe", "⊓": "sqcap", "⊓︀": "sqcaps", "⊔": "sqcup", "⊔︀": "sqcups", "⊕": "oplus", "⊖": "ominus", "⊗": "otimes", "⊘": "osol", "⊙": "odot", "⊚": "ocir", "⊛": "oast", "⊝": "odash", "⊞": "plusb", "⊟": "minusb", "⊠": "timesb", "⊡": "sdotb", "⊢": "vdash", "⊬": "nvdash", "⊣": "dashv", "⊤": "top", "⊥": "bot", "⊧": "models", "⊨": "vDash", "⊭": "nvDash", "⊩": "Vdash", "⊮": "nVdash", "⊪": "Vvdash", "⊫": "VDash", "⊯": "nVDash", "⊰": "prurel", "⊲": "vltri", "⋪": "nltri", "⊳": "vrtri", "⋫": "nrtri", "⊴": "ltrie", "⋬": "nltrie", "⊴⃒": "nvltrie", "⊵": "rtrie", "⋭": "nrtrie", "⊵⃒": "nvrtrie", "⊶": "origof", "⊷": "imof", "⊸": "mumap", "⊹": "hercon", "⊺": "intcal", "⊻": "veebar", "⊽": "barvee", "⊾": "angrtvb", "⊿": "lrtri", "⋀": "Wedge", "⋁": "Vee", "⋂": "xcap", "⋃": "xcup", "⋄": "diam", "⋅": "sdot", "⋆": "Star", "⋇": "divonx", "⋈": "bowtie", "⋉": "ltimes", "⋊": "rtimes", "⋋": "lthree", "⋌": "rthree", "⋍": "bsime", "⋎": "cuvee", "⋏": "cuwed", "⋐": "Sub", "⋑": "Sup", "⋒": "Cap", "⋓": "Cup", "⋔": "fork", "⋕": "epar", "⋖": "ltdot", "⋗": "gtdot", "⋘": "Ll", "⋘̸": "nLl", "⋙": "Gg", "⋙̸": "nGg", "⋚︀": "lesg", "⋚": "leg", "⋛": "gel", "⋛︀": "gesl", "⋞": "cuepr", "⋟": "cuesc", "⋦": "lnsim", "⋧": "gnsim", "⋨": "prnsim", "⋩": "scnsim", "⋮": "vellip", "⋯": "ctdot", "⋰": "utdot", "⋱": "dtdot", "⋲": "disin", "⋳": "isinsv", "⋴": "isins", "⋵": "isindot", "⋵̸": "notindot", "⋶": "notinvc", "⋷": "notinvb", "⋹": "isinE", "⋹̸": "notinE", "⋺": "nisd", "⋻": "xnis", "⋼": "nis", "⋽": "notnivc", "⋾": "notnivb", "⌅": "barwed", "⌆": "Barwed", "⌌": "drcrop", "⌍": "dlcrop", "⌎": "urcrop", "⌏": "ulcrop", "⌐": "bnot", "⌒": "profline", "⌓": "profsurf", "⌕": "telrec", "⌖": "target", "⌜": "ulcorn", "⌝": "urcorn", "⌞": "dlcorn", "⌟": "drcorn", "⌢": "frown", "⌣": "smile", "⌭": "cylcty", "⌮": "profalar", "⌶": "topbot", "⌽": "ovbar", "⌿": "solbar", "⍼": "angzarr", "⎰": "lmoust", "⎱": "rmoust", "⎴": "tbrk", "⎵": "bbrk", "⎶": "bbrktbrk", "⏜": "OverParenthesis", "⏝": "UnderParenthesis", "⏞": "OverBrace", "⏟": "UnderBrace", "⏢": "trpezium", "⏧": "elinters", "␣": "blank", "─": "boxh", "│": "boxv", "┌": "boxdr", "┐": "boxdl", "└": "boxur", "┘": "boxul", "├": "boxvr", "┤": "boxvl", "┬": "boxhd", "┴": "boxhu", "┼": "boxvh", "═": "boxH", "║": "boxV", "╒": "boxdR", "╓": "boxDr", "╔": "boxDR", "╕": "boxdL", "╖": "boxDl", "╗": "boxDL", "╘": "boxuR", "╙": "boxUr", "╚": "boxUR", "╛": "boxuL", "╜": "boxUl", "╝": "boxUL", "╞": "boxvR", "╟": "boxVr", "╠": "boxVR", "╡": "boxvL", "╢": "boxVl", "╣": "boxVL", "╤": "boxHd", "╥": "boxhD", "╦": "boxHD", "╧": "boxHu", "╨": "boxhU", "╩": "boxHU", "╪": "boxvH", "╫": "boxVh", "╬": "boxVH", "▀": "uhblk", "▄": "lhblk", "█": "block", "░": "blk14", "▒": "blk12", "▓": "blk34", "□": "squ", "▪": "squf", "▫": "EmptyVerySmallSquare", "▭": "rect", "▮": "marker", "▱": "fltns", "△": "xutri", "▴": "utrif", "▵": "utri", "▸": "rtrif", "▹": "rtri", "▽": "xdtri", "▾": "dtrif", "▿": "dtri", "◂": "ltrif", "◃": "ltri", "◊": "loz", "○": "cir", "◬": "tridot", "◯": "xcirc", "◸": "ultri", "◹": "urtri", "◺": "lltri", "◻": "EmptySmallSquare", "◼": "FilledSmallSquare", "★": "starf", "☆": "star", "☎": "phone", "♀": "female", "♂": "male", "♠": "spades", "♣": "clubs", "♥": "hearts", "♦": "diams", "♪": "sung", "✓": "check", "✗": "cross", "✠": "malt", "✶": "sext", "❘": "VerticalSeparator", "⟈": "bsolhsub", "⟉": "suphsol", "⟵": "xlarr", "⟶": "xrarr", "⟷": "xharr", "⟸": "xlArr", "⟹": "xrArr", "⟺": "xhArr", "⟼": "xmap", "⟿": "dzigrarr", "⤂": "nvlArr", "⤃": "nvrArr", "⤄": "nvHarr", "⤅": "Map", "⤌": "lbarr", "⤍": "rbarr", "⤎": "lBarr", "⤏": "rBarr", "⤐": "RBarr", "⤑": "DDotrahd", "⤒": "UpArrowBar", "⤓": "DownArrowBar", "⤖": "Rarrtl", "⤙": "latail", "⤚": "ratail", "⤛": "lAtail", "⤜": "rAtail", "⤝": "larrfs", "⤞": "rarrfs", "⤟": "larrbfs", "⤠": "rarrbfs", "⤣": "nwarhk", "⤤": "nearhk", "⤥": "searhk", "⤦": "swarhk", "⤧": "nwnear", "⤨": "toea", "⤩": "tosa", "⤪": "swnwar", "⤳": "rarrc", "⤳̸": "nrarrc", "⤵": "cudarrr", "⤶": "ldca", "⤷": "rdca", "⤸": "cudarrl", "⤹": "larrpl", "⤼": "curarrm", "⤽": "cularrp", "⥅": "rarrpl", "⥈": "harrcir", "⥉": "Uarrocir", "⥊": "lurdshar", "⥋": "ldrushar", "⥎": "LeftRightVector", "⥏": "RightUpDownVector", "⥐": "DownLeftRightVector", "⥑": "LeftUpDownVector", "⥒": "LeftVectorBar", "⥓": "RightVectorBar", "⥔": "RightUpVectorBar", "⥕": "RightDownVectorBar", "⥖": "DownLeftVectorBar", "⥗": "DownRightVectorBar", "⥘": "LeftUpVectorBar", "⥙": "LeftDownVectorBar", "⥚": "LeftTeeVector", "⥛": "RightTeeVector", "⥜": "RightUpTeeVector", "⥝": "RightDownTeeVector", "⥞": "DownLeftTeeVector", "⥟": "DownRightTeeVector", "⥠": "LeftUpTeeVector", "⥡": "LeftDownTeeVector", "⥢": "lHar", "⥣": "uHar", "⥤": "rHar", "⥥": "dHar", "⥦": "luruhar", "⥧": "ldrdhar", "⥨": "ruluhar", "⥩": "rdldhar", "⥪": "lharul", "⥫": "llhard", "⥬": "rharul", "⥭": "lrhard", "⥮": "udhar", "⥯": "duhar", "⥰": "RoundImplies", "⥱": "erarr", "⥲": "simrarr", "⥳": "larrsim", "⥴": "rarrsim", "⥵": "rarrap", "⥶": "ltlarr", "⥸": "gtrarr", "⥹": "subrarr", "⥻": "suplarr", "⥼": "lfisht", "⥽": "rfisht", "⥾": "ufisht", "⥿": "dfisht", "⦚": "vzigzag", "⦜": "vangrt", "⦝": "angrtvbd", "⦤": "ange", "⦥": "range", "⦦": "dwangle", "⦧": "uwangle", "⦨": "angmsdaa", "⦩": "angmsdab", "⦪": "angmsdac", "⦫": "angmsdad", "⦬": "angmsdae", "⦭": "angmsdaf", "⦮": "angmsdag", "⦯": "angmsdah", "⦰": "bemptyv", "⦱": "demptyv", "⦲": "cemptyv", "⦳": "raemptyv", "⦴": "laemptyv", "⦵": "ohbar", "⦶": "omid", "⦷": "opar", "⦹": "operp", "⦻": "olcross", "⦼": "odsold", "⦾": "olcir", "⦿": "ofcir", "⧀": "olt", "⧁": "ogt", "⧂": "cirscir", "⧃": "cirE", "⧄": "solb", "⧅": "bsolb", "⧉": "boxbox", "⧍": "trisb", "⧎": "rtriltri", "⧏": "LeftTriangleBar", "⧏̸": "NotLeftTriangleBar", "⧐": "RightTriangleBar", "⧐̸": "NotRightTriangleBar", "⧜": "iinfin", "⧝": "infintie", "⧞": "nvinfin", "⧣": "eparsl", "⧤": "smeparsl", "⧥": "eqvparsl", "⧫": "lozf", "⧴": "RuleDelayed", "⧶": "dsol", "⨀": "xodot", "⨁": "xoplus", "⨂": "xotime", "⨄": "xuplus", "⨆": "xsqcup", "⨍": "fpartint", "⨐": "cirfnint", "⨑": "awint", "⨒": "rppolint", "⨓": "scpolint", "⨔": "npolint", "⨕": "pointint", "⨖": "quatint", "⨗": "intlarhk", "⨢": "pluscir", "⨣": "plusacir", "⨤": "simplus", "⨥": "plusdu", "⨦": "plussim", "⨧": "plustwo", "⨩": "mcomma", "⨪": "minusdu", "⨭": "loplus", "⨮": "roplus", "⨯": "Cross", "⨰": "timesd", "⨱": "timesbar", "⨳": "smashp", "⨴": "lotimes", "⨵": "rotimes", "⨶": "otimesas", "⨷": "Otimes", "⨸": "odiv", "⨹": "triplus", "⨺": "triminus", "⨻": "tritime", "⨼": "iprod", "⨿": "amalg", "⩀": "capdot", "⩂": "ncup", "⩃": "ncap", "⩄": "capand", "⩅": "cupor", "⩆": "cupcap", "⩇": "capcup", "⩈": "cupbrcap", "⩉": "capbrcup", "⩊": "cupcup", "⩋": "capcap", "⩌": "ccups", "⩍": "ccaps", "⩐": "ccupssm", "⩓": "And", "⩔": "Or", "⩕": "andand", "⩖": "oror", "⩗": "orslope", "⩘": "andslope", "⩚": "andv", "⩛": "orv", "⩜": "andd", "⩝": "ord", "⩟": "wedbar", "⩦": "sdote", "⩪": "simdot", "⩭": "congdot", "⩭̸": "ncongdot", "⩮": "easter", "⩯": "apacir", "⩰": "apE", "⩰̸": "napE", "⩱": "eplus", "⩲": "pluse", "⩳": "Esim", "⩷": "eDDot", "⩸": "equivDD", "⩹": "ltcir", "⩺": "gtcir", "⩻": "ltquest", "⩼": "gtquest", "⩽": "les", "⩽̸": "nles", "⩾": "ges", "⩾̸": "nges", "⩿": "lesdot", "⪀": "gesdot", "⪁": "lesdoto", "⪂": "gesdoto", "⪃": "lesdotor", "⪄": "gesdotol", "⪅": "lap", "⪆": "gap", "⪇": "lne", "⪈": "gne", "⪉": "lnap", "⪊": "gnap", "⪋": "lEg", "⪌": "gEl", "⪍": "lsime", "⪎": "gsime", "⪏": "lsimg", "⪐": "gsiml", "⪑": "lgE", "⪒": "glE", "⪓": "lesges", "⪔": "gesles", "⪕": "els", "⪖": "egs", "⪗": "elsdot", "⪘": "egsdot", "⪙": "el", "⪚": "eg", "⪝": "siml", "⪞": "simg", "⪟": "simlE", "⪠": "simgE", "⪡": "LessLess", "⪡̸": "NotNestedLessLess", "⪢": "GreaterGreater", "⪢̸": "NotNestedGreaterGreater", "⪤": "glj", "⪥": "gla", "⪦": "ltcc", "⪧": "gtcc", "⪨": "lescc", "⪩": "gescc", "⪪": "smt", "⪫": "lat", "⪬": "smte", "⪬︀": "smtes", "⪭": "late", "⪭︀": "lates", "⪮": "bumpE", "⪯": "pre", "⪯̸": "npre", "⪰": "sce", "⪰̸": "nsce", "⪳": "prE", "⪴": "scE", "⪵": "prnE", "⪶": "scnE", "⪷": "prap", "⪸": "scap", "⪹": "prnap", "⪺": "scnap", "⪻": "Pr", "⪼": "Sc", "⪽": "subdot", "⪾": "supdot", "⪿": "subplus", "⫀": "supplus", "⫁": "submult", "⫂": "supmult", "⫃": "subedot", "⫄": "supedot", "⫅": "subE", "⫅̸": "nsubE", "⫆": "supE", "⫆̸": "nsupE", "⫇": "subsim", "⫈": "supsim", "⫋︀": "vsubnE", "⫋": "subnE", "⫌︀": "vsupnE", "⫌": "supnE", "⫏": "csub", "⫐": "csup", "⫑": "csube", "⫒": "csupe", "⫓": "subsup", "⫔": "supsub", "⫕": "subsub", "⫖": "supsup", "⫗": "suphsub", "⫘": "supdsub", "⫙": "forkv", "⫚": "topfork", "⫛": "mlcp", "⫤": "Dashv", "⫦": "Vdashl", "⫧": "Barv", "⫨": "vBar", "⫩": "vBarv", "⫫": "Vbar", "⫬": "Not", "⫭": "bNot", "⫮": "rnmid", "⫯": "cirmid", "⫰": "midcir", "⫱": "topcir", "⫲": "nhpar", "⫳": "parsim", "⫽": "parsl", "⫽⃥": "nparsl", "♭": "flat", "♮": "natur", "♯": "sharp", "¤": "curren", "¢": "cent", "$": "dollar", "£": "pound", "¥": "yen", "€": "euro", "¹": "sup1", "½": "half", "⅓": "frac13", "¼": "frac14", "⅕": "frac15", "⅙": "frac16", "⅛": "frac18", "²": "sup2", "⅔": "frac23", "⅖": "frac25", "³": "sup3", "¾": "frac34", "⅗": "frac35", "⅜": "frac38", "⅘": "frac45", "⅚": "frac56", "⅝": "frac58", "⅞": "frac78", "𝒶": "ascr", "𝕒": "aopf", "𝔞": "afr", "𝔸": "Aopf", "𝔄": "Afr", "𝒜": "Ascr", "ª": "ordf", "á": "aacute", "Á": "Aacute", "à": "agrave", "À": "Agrave", "ă": "abreve", "Ă": "Abreve", "â": "acirc", "Â": "Acirc", "å": "aring", "Å": "angst", "ä": "auml", "Ä": "Auml", "ã": "atilde", "Ã": "Atilde", "ą": "aogon", "Ą": "Aogon", "ā": "amacr", "Ā": "Amacr", "æ": "aelig", "Æ": "AElig", "𝒷": "bscr", "𝕓": "bopf", "𝔟": "bfr", "𝔹": "Bopf", "ℬ": "Bscr", "𝔅": "Bfr", "𝔠": "cfr", "𝒸": "cscr", "𝕔": "copf", "ℭ": "Cfr", "𝒞": "Cscr", "ℂ": "Copf", "ć": "cacute", "Ć": "Cacute", "ĉ": "ccirc", "Ĉ": "Ccirc", "č": "ccaron", "Č": "Ccaron", "ċ": "cdot", "Ċ": "Cdot", "ç": "ccedil", "Ç": "Ccedil", "℅": "incare", "𝔡": "dfr", "ⅆ": "dd", "𝕕": "dopf", "𝒹": "dscr", "𝒟": "Dscr", "𝔇": "Dfr", "ⅅ": "DD", "𝔻": "Dopf", "ď": "dcaron", "Ď": "Dcaron", "đ": "dstrok", "Đ": "Dstrok", "ð": "eth", "Ð": "ETH", "ⅇ": "ee", "ℯ": "escr", "𝔢": "efr", "𝕖": "eopf", "ℰ": "Escr", "𝔈": "Efr", "𝔼": "Eopf", "é": "eacute", "É": "Eacute", "è": "egrave", "È": "Egrave", "ê": "ecirc", "Ê": "Ecirc", "ě": "ecaron", "Ě": "Ecaron", "ë": "euml", "Ë": "Euml", "ė": "edot", "Ė": "Edot", "ę": "eogon", "Ę": "Eogon", "ē": "emacr", "Ē": "Emacr", "𝔣": "ffr", "𝕗": "fopf", "𝒻": "fscr", "𝔉": "Ffr", "𝔽": "Fopf", "ℱ": "Fscr", "ﬀ": "fflig", "ﬃ": "ffilig", "ﬄ": "ffllig", "ﬁ": "filig", "fj": "fjlig", "ﬂ": "fllig", "ƒ": "fnof", "ℊ": "gscr", "𝕘": "gopf", "𝔤": "gfr", "𝒢": "Gscr", "𝔾": "Gopf", "𝔊": "Gfr", "ǵ": "gacute", "ğ": "gbreve", "Ğ": "Gbreve", "ĝ": "gcirc", "Ĝ": "Gcirc", "ġ": "gdot", "Ġ": "Gdot", "Ģ": "Gcedil", "𝔥": "hfr", "ℎ": "planckh", "𝒽": "hscr", "𝕙": "hopf", "ℋ": "Hscr", "ℌ": "Hfr", "ℍ": "Hopf", "ĥ": "hcirc", "Ĥ": "Hcirc", "ℏ": "hbar", "ħ": "hstrok", "Ħ": "Hstrok", "𝕚": "iopf", "𝔦": "ifr", "𝒾": "iscr", "ⅈ": "ii", "𝕀": "Iopf", "ℐ": "Iscr", "ℑ": "Im", "í": "iacute", "Í": "Iacute", "ì": "igrave", "Ì": "Igrave", "î": "icirc", "Î": "Icirc", "ï": "iuml", "Ï": "Iuml", "ĩ": "itilde", "Ĩ": "Itilde", "İ": "Idot", "į": "iogon", "Į": "Iogon", "ī": "imacr", "Ī": "Imacr", "ĳ": "ijlig", "Ĳ": "IJlig", "ı": "imath", "𝒿": "jscr", "𝕛": "jopf", "𝔧": "jfr", "𝒥": "Jscr", "𝔍": "Jfr", "𝕁": "Jopf", "ĵ": "jcirc", "Ĵ": "Jcirc", "ȷ": "jmath", "𝕜": "kopf", "𝓀": "kscr", "𝔨": "kfr", "𝒦": "Kscr", "𝕂": "Kopf", "𝔎": "Kfr", "ķ": "kcedil", "Ķ": "Kcedil", "𝔩": "lfr", "𝓁": "lscr", "ℓ": "ell", "𝕝": "lopf", "ℒ": "Lscr", "𝔏": "Lfr", "𝕃": "Lopf", "ĺ": "lacute", "Ĺ": "Lacute", "ľ": "lcaron", "Ľ": "Lcaron", "ļ": "lcedil", "Ļ": "Lcedil", "ł": "lstrok", "Ł": "Lstrok", "ŀ": "lmidot", "Ŀ": "Lmidot", "𝔪": "mfr", "𝕞": "mopf", "𝓂": "mscr", "𝔐": "Mfr", "𝕄": "Mopf", "ℳ": "Mscr", "𝔫": "nfr", "𝕟": "nopf", "𝓃": "nscr", "ℕ": "Nopf", "𝒩": "Nscr", "𝔑": "Nfr", "ń": "nacute", "Ń": "Nacute", "ň": "ncaron", "Ň": "Ncaron", "ñ": "ntilde", "Ñ": "Ntilde", "ņ": "ncedil", "Ņ": "Ncedil", "№": "numero", "ŋ": "eng", "Ŋ": "ENG", "𝕠": "oopf", "𝔬": "ofr", "ℴ": "oscr", "𝒪": "Oscr", "𝔒": "Ofr", "𝕆": "Oopf", "º": "ordm", "ó": "oacute", "Ó": "Oacute", "ò": "ograve", "Ò": "Ograve", "ô": "ocirc", "Ô": "Ocirc", "ö": "ouml", "Ö": "Ouml", "ő": "odblac", "Ő": "Odblac", "õ": "otilde", "Õ": "Otilde", "ø": "oslash", "Ø": "Oslash", "ō": "omacr", "Ō": "Omacr", "œ": "oelig", "Œ": "OElig", "𝔭": "pfr", "𝓅": "pscr", "𝕡": "popf", "ℙ": "Popf", "𝔓": "Pfr", "𝒫": "Pscr", "𝕢": "qopf", "𝔮": "qfr", "𝓆": "qscr", "𝒬": "Qscr", "𝔔": "Qfr", "ℚ": "Qopf", "ĸ": "kgreen", "𝔯": "rfr", "𝕣": "ropf", "𝓇": "rscr", "ℛ": "Rscr", "ℜ": "Re", "ℝ": "Ropf", "ŕ": "racute", "Ŕ": "Racute", "ř": "rcaron", "Ř": "Rcaron", "ŗ": "rcedil", "Ŗ": "Rcedil", "𝕤": "sopf", "𝓈": "sscr", "𝔰": "sfr", "𝕊": "Sopf", "𝔖": "Sfr", "𝒮": "Sscr", "Ⓢ": "oS", "ś": "sacute", "Ś": "Sacute", "ŝ": "scirc", "Ŝ": "Scirc", "š": "scaron", "Š": "Scaron", "ş": "scedil", "Ş": "Scedil", "ß": "szlig", "𝔱": "tfr", "𝓉": "tscr", "𝕥": "topf", "𝒯": "Tscr", "𝔗": "Tfr", "𝕋": "Topf", "ť": "tcaron", "Ť": "Tcaron", "ţ": "tcedil", "Ţ": "Tcedil", "™": "trade", "ŧ": "tstrok", "Ŧ": "Tstrok", "𝓊": "uscr", "𝕦": "uopf", "𝔲": "ufr", "𝕌": "Uopf", "𝔘": "Ufr", "𝒰": "Uscr", "ú": "uacute", "Ú": "Uacute", "ù": "ugrave", "Ù": "Ugrave", "ŭ": "ubreve", "Ŭ": "Ubreve", "û": "ucirc", "Û": "Ucirc", "ů": "uring", "Ů": "Uring", "ü": "uuml", "Ü": "Uuml", "ű": "udblac", "Ű": "Udblac", "ũ": "utilde", "Ũ": "Utilde", "ų": "uogon", "Ų": "Uogon", "ū": "umacr", "Ū": "Umacr", "𝔳": "vfr", "𝕧": "vopf", "𝓋": "vscr", "𝔙": "Vfr", "𝕍": "Vopf", "𝒱": "Vscr", "𝕨": "wopf", "𝓌": "wscr", "𝔴": "wfr", "𝒲": "Wscr", "𝕎": "Wopf", "𝔚": "Wfr", "ŵ": "wcirc", "Ŵ": "Wcirc", "𝔵": "xfr", "𝓍": "xscr", "𝕩": "xopf", "𝕏": "Xopf", "𝔛": "Xfr", "𝒳": "Xscr", "𝔶": "yfr", "𝓎": "yscr", "𝕪": "yopf", "𝒴": "Yscr", "𝔜": "Yfr", "𝕐": "Yopf", "ý": "yacute", "Ý": "Yacute", "ŷ": "ycirc", "Ŷ": "Ycirc", "ÿ": "yuml", "Ÿ": "Yuml", "𝓏": "zscr", "𝔷": "zfr", "𝕫": "zopf", "ℨ": "Zfr", "ℤ": "Zopf", "𝒵": "Zscr", "ź": "zacute", "Ź": "Zacute", "ž": "zcaron", "Ž": "Zcaron", "ż": "zdot", "Ż": "Zdot", "Ƶ": "imped", "þ": "thorn", "Þ": "THORN", "ŉ": "napos", "α": "alpha", "Α": "Alpha", "β": "beta", "Β": "Beta", "γ": "gamma", "Γ": "Gamma", "δ": "delta", "Δ": "Delta", "ε": "epsi", "ϵ": "epsiv", "Ε": "Epsilon", "ϝ": "gammad", "Ϝ": "Gammad", "ζ": "zeta", "Ζ": "Zeta", "η": "eta", "Η": "Eta", "θ": "theta", "ϑ": "thetav", "Θ": "Theta", "ι": "iota", "Ι": "Iota", "κ": "kappa", "ϰ": "kappav", "Κ": "Kappa", "λ": "lambda", "Λ": "Lambda", "μ": "mu", "µ": "micro", "Μ": "Mu", "ν": "nu", "Ν": "Nu", "ξ": "xi", "Ξ": "Xi", "ο": "omicron", "Ο": "Omicron", "π": "pi", "ϖ": "piv", "Π": "Pi", "ρ": "rho", "ϱ": "rhov", "Ρ": "Rho", "σ": "sigma", "Σ": "Sigma", "ς": "sigmaf", "τ": "tau", "Τ": "Tau", "υ": "upsi", "Υ": "Upsilon", "ϒ": "Upsi", "φ": "phi", "ϕ": "phiv", "Φ": "Phi", "χ": "chi", "Χ": "Chi", "ψ": "psi", "Ψ": "Psi", "ω": "omega", "Ω": "ohm", "а": "acy", "А": "Acy", "б": "bcy", "Б": "Bcy", "в": "vcy", "В": "Vcy", "г": "gcy", "Г": "Gcy", "ѓ": "gjcy", "Ѓ": "GJcy", "д": "dcy", "Д": "Dcy", "ђ": "djcy", "Ђ": "DJcy", "е": "iecy", "Е": "IEcy", "ё": "iocy", "Ё": "IOcy", "є": "jukcy", "Є": "Jukcy", "ж": "zhcy", "Ж": "ZHcy", "з": "zcy", "З": "Zcy", "ѕ": "dscy", "Ѕ": "DScy", "и": "icy", "И": "Icy", "і": "iukcy", "І": "Iukcy", "ї": "yicy", "Ї": "YIcy", "й": "jcy", "Й": "Jcy", "ј": "jsercy", "Ј": "Jsercy", "к": "kcy", "К": "Kcy", "ќ": "kjcy", "Ќ": "KJcy", "л": "lcy", "Л": "Lcy", "љ": "ljcy", "Љ": "LJcy", "м": "mcy", "М": "Mcy", "н": "ncy", "Н": "Ncy", "њ": "njcy", "Њ": "NJcy", "о": "ocy", "О": "Ocy", "п": "pcy", "П": "Pcy", "р": "rcy", "Р": "Rcy", "с": "scy", "С": "Scy", "т": "tcy", "Т": "Tcy", "ћ": "tshcy", "Ћ": "TSHcy", "у": "ucy", "У": "Ucy", "ў": "ubrcy", "Ў": "Ubrcy", "ф": "fcy", "Ф": "Fcy", "х": "khcy", "Х": "KHcy", "ц": "tscy", "Ц": "TScy", "ч": "chcy", "Ч": "CHcy", "џ": "dzcy", "Џ": "DZcy", "ш": "shcy", "Ш": "SHcy", "щ": "shchcy", "Щ": "SHCHcy", "ъ": "hardcy", "Ъ": "HARDcy", "ы": "ycy", "Ы": "Ycy", "ь": "softcy", "Ь": "SOFTcy", "э": "ecy", "Э": "Ecy", "ю": "yucy", "Ю": "YUcy", "я": "yacy", "Я": "YAcy", "ℵ": "aleph", "ℶ": "beth", "ℷ": "gimel", "ℸ": "daleth" };
        var regexEscape = /["&'<>`]/g;
        var escapeMap = {
          '"': "&quot;",
          "&": "&amp;",
          "'": "&#x27;",
          "<": "&lt;",
          // See https://mathiasbynens.be/notes/ambiguous-ampersands: in HTML, the
          // following is not strictly necessary unless it’s part of a tag or an
          // unquoted attribute value. We’re only escaping it to support those
          // situations, and for XML support.
          ">": "&gt;",
          // In Internet Explorer ≤ 8, the backtick character can be used
          // to break out of (un)quoted attribute values or HTML comments.
          // See http://html5sec.org/#102, http://html5sec.org/#108, and
          // http://html5sec.org/#133.
          "`": "&#x60;"
        };
        var regexInvalidEntity = /&#(?:[xX][^a-fA-F0-9]|[^0-9xX])/;
        var regexInvalidRawCodePoint = /[\0-\x08\x0B\x0E-\x1F\x7F-\x9F\uFDD0-\uFDEF\uFFFE\uFFFF]|[\uD83F\uD87F\uD8BF\uD8FF\uD93F\uD97F\uD9BF\uD9FF\uDA3F\uDA7F\uDABF\uDAFF\uDB3F\uDB7F\uDBBF\uDBFF][\uDFFE\uDFFF]|[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?:[^\uD800-\uDBFF]|^)[\uDC00-\uDFFF]/;
        var regexDecode = /&(CounterClockwiseContourIntegral|DoubleLongLeftRightArrow|ClockwiseContourIntegral|NotNestedGreaterGreater|NotSquareSupersetEqual|DiacriticalDoubleAcute|NotRightTriangleEqual|NotSucceedsSlantEqual|NotPrecedesSlantEqual|CloseCurlyDoubleQuote|NegativeVeryThinSpace|DoubleContourIntegral|FilledVerySmallSquare|CapitalDifferentialD|OpenCurlyDoubleQuote|EmptyVerySmallSquare|NestedGreaterGreater|DoubleLongRightArrow|NotLeftTriangleEqual|NotGreaterSlantEqual|ReverseUpEquilibrium|DoubleLeftRightArrow|NotSquareSubsetEqual|NotDoubleVerticalBar|RightArrowLeftArrow|NotGreaterFullEqual|NotRightTriangleBar|SquareSupersetEqual|DownLeftRightVector|DoubleLongLeftArrow|leftrightsquigarrow|LeftArrowRightArrow|NegativeMediumSpace|blacktriangleright|RightDownVectorBar|PrecedesSlantEqual|RightDoubleBracket|SucceedsSlantEqual|NotLeftTriangleBar|RightTriangleEqual|SquareIntersection|RightDownTeeVector|ReverseEquilibrium|NegativeThickSpace|longleftrightarrow|Longleftrightarrow|LongLeftRightArrow|DownRightTeeVector|DownRightVectorBar|GreaterSlantEqual|SquareSubsetEqual|LeftDownVectorBar|LeftDoubleBracket|VerticalSeparator|rightleftharpoons|NotGreaterGreater|NotSquareSuperset|blacktriangleleft|blacktriangledown|NegativeThinSpace|LeftDownTeeVector|NotLessSlantEqual|leftrightharpoons|DoubleUpDownArrow|DoubleVerticalBar|LeftTriangleEqual|FilledSmallSquare|twoheadrightarrow|NotNestedLessLess|DownLeftTeeVector|DownLeftVectorBar|RightAngleBracket|NotTildeFullEqual|NotReverseElement|RightUpDownVector|DiacriticalTilde|NotSucceedsTilde|circlearrowright|NotPrecedesEqual|rightharpoondown|DoubleRightArrow|NotSucceedsEqual|NonBreakingSpace|NotRightTriangle|LessEqualGreater|RightUpTeeVector|LeftAngleBracket|GreaterFullEqual|DownArrowUpArrow|RightUpVectorBar|twoheadleftarrow|GreaterEqualLess|downharpoonright|RightTriangleBar|ntrianglerighteq|NotSupersetEqual|LeftUpDownVector|DiacriticalAcute|rightrightarrows|vartriangleright|UpArrowDownArrow|DiacriticalGrave|UnderParenthesis|EmptySmallSquare|LeftUpVectorBar|leftrightarrows|DownRightVector|downharpoonleft|trianglerighteq|ShortRightArrow|OverParenthesis|DoubleLeftArrow|DoubleDownArrow|NotSquareSubset|bigtriangledown|ntrianglelefteq|UpperRightArrow|curvearrowright|vartriangleleft|NotLeftTriangle|nleftrightarrow|LowerRightArrow|NotHumpDownHump|NotGreaterTilde|rightthreetimes|LeftUpTeeVector|NotGreaterEqual|straightepsilon|LeftTriangleBar|rightsquigarrow|ContourIntegral|rightleftarrows|CloseCurlyQuote|RightDownVector|LeftRightVector|nLeftrightarrow|leftharpoondown|circlearrowleft|SquareSuperset|OpenCurlyQuote|hookrightarrow|HorizontalLine|DiacriticalDot|NotLessGreater|ntriangleright|DoubleRightTee|InvisibleComma|InvisibleTimes|LowerLeftArrow|DownLeftVector|NotSubsetEqual|curvearrowleft|trianglelefteq|NotVerticalBar|TildeFullEqual|downdownarrows|NotGreaterLess|RightTeeVector|ZeroWidthSpace|looparrowright|LongRightArrow|doublebarwedge|ShortLeftArrow|ShortDownArrow|RightVectorBar|GreaterGreater|ReverseElement|rightharpoonup|LessSlantEqual|leftthreetimes|upharpoonright|rightarrowtail|LeftDownVector|Longrightarrow|NestedLessLess|UpperLeftArrow|nshortparallel|leftleftarrows|leftrightarrow|Leftrightarrow|LeftRightArrow|longrightarrow|upharpoonleft|RightArrowBar|ApplyFunction|LeftTeeVector|leftarrowtail|NotEqualTilde|varsubsetneqq|varsupsetneqq|RightTeeArrow|SucceedsEqual|SucceedsTilde|LeftVectorBar|SupersetEqual|hookleftarrow|DifferentialD|VerticalTilde|VeryThinSpace|blacktriangle|bigtriangleup|LessFullEqual|divideontimes|leftharpoonup|UpEquilibrium|ntriangleleft|RightTriangle|measuredangle|shortparallel|longleftarrow|Longleftarrow|LongLeftArrow|DoubleLeftTee|Poincareplane|PrecedesEqual|triangleright|DoubleUpArrow|RightUpVector|fallingdotseq|looparrowleft|PrecedesTilde|NotTildeEqual|NotTildeTilde|smallsetminus|Proportional|triangleleft|triangledown|UnderBracket|NotHumpEqual|exponentiale|ExponentialE|NotLessTilde|HilbertSpace|RightCeiling|blacklozenge|varsupsetneq|HumpDownHump|GreaterEqual|VerticalLine|LeftTeeArrow|NotLessEqual|DownTeeArrow|LeftTriangle|varsubsetneq|Intersection|NotCongruent|DownArrowBar|LeftUpVector|LeftArrowBar|risingdotseq|GreaterTilde|RoundImplies|SquareSubset|ShortUpArrow|NotSuperset|quaternions|precnapprox|backepsilon|preccurlyeq|OverBracket|blacksquare|MediumSpace|VerticalBar|circledcirc|circleddash|CircleMinus|CircleTimes|LessGreater|curlyeqprec|curlyeqsucc|diamondsuit|UpDownArrow|Updownarrow|RuleDelayed|Rrightarrow|updownarrow|RightVector|nRightarrow|nrightarrow|eqslantless|LeftCeiling|Equilibrium|SmallCircle|expectation|NotSucceeds|thickapprox|GreaterLess|SquareUnion|NotPrecedes|NotLessLess|straightphi|succnapprox|succcurlyeq|SubsetEqual|sqsupseteq|Proportion|Laplacetrf|ImaginaryI|supsetneqq|NotGreater|gtreqqless|NotElement|ThickSpace|TildeEqual|TildeTilde|Fouriertrf|rmoustache|EqualTilde|eqslantgtr|UnderBrace|LeftVector|UpArrowBar|nLeftarrow|nsubseteqq|subsetneqq|nsupseteqq|nleftarrow|succapprox|lessapprox|UpTeeArrow|upuparrows|curlywedge|lesseqqgtr|varepsilon|varnothing|RightFloor|complement|CirclePlus|sqsubseteq|Lleftarrow|circledast|RightArrow|Rightarrow|rightarrow|lmoustache|Bernoullis|precapprox|mapstoleft|mapstodown|longmapsto|dotsquare|downarrow|DoubleDot|nsubseteq|supsetneq|leftarrow|nsupseteq|subsetneq|ThinSpace|ngeqslant|subseteqq|HumpEqual|NotSubset|triangleq|NotCupCap|lesseqgtr|heartsuit|TripleDot|Leftarrow|Coproduct|Congruent|varpropto|complexes|gvertneqq|LeftArrow|LessTilde|supseteqq|MinusPlus|CircleDot|nleqslant|NotExists|gtreqless|nparallel|UnionPlus|LeftFloor|checkmark|CenterDot|centerdot|Mellintrf|gtrapprox|bigotimes|OverBrace|spadesuit|therefore|pitchfork|rationals|PlusMinus|Backslash|Therefore|DownBreve|backsimeq|backprime|DownArrow|nshortmid|Downarrow|lvertneqq|eqvparsl|imagline|imagpart|infintie|integers|Integral|intercal|LessLess|Uarrocir|intlarhk|sqsupset|angmsdaf|sqsubset|llcorner|vartheta|cupbrcap|lnapprox|Superset|SuchThat|succnsim|succneqq|angmsdag|biguplus|curlyvee|trpezium|Succeeds|NotTilde|bigwedge|angmsdah|angrtvbd|triminus|cwconint|fpartint|lrcorner|smeparsl|subseteq|urcorner|lurdshar|laemptyv|DDotrahd|approxeq|ldrushar|awconint|mapstoup|backcong|shortmid|triangle|geqslant|gesdotol|timesbar|circledR|circledS|setminus|multimap|naturals|scpolint|ncongdot|RightTee|boxminus|gnapprox|boxtimes|andslope|thicksim|angmsdaa|varsigma|cirfnint|rtriltri|angmsdab|rppolint|angmsdac|barwedge|drbkarow|clubsuit|thetasym|bsolhsub|capbrcup|dzigrarr|doteqdot|DotEqual|dotminus|UnderBar|NotEqual|realpart|otimesas|ulcorner|hksearow|hkswarow|parallel|PartialD|elinters|emptyset|plusacir|bbrktbrk|angmsdad|pointint|bigoplus|angmsdae|Precedes|bigsqcup|varkappa|notindot|supseteq|precneqq|precnsim|profalar|profline|profsurf|leqslant|lesdotor|raemptyv|subplus|notnivb|notnivc|subrarr|zigrarr|vzigzag|submult|subedot|Element|between|cirscir|larrbfs|larrsim|lotimes|lbrksld|lbrkslu|lozenge|ldrdhar|dbkarow|bigcirc|epsilon|simrarr|simplus|ltquest|Epsilon|luruhar|gtquest|maltese|npolint|eqcolon|npreceq|bigodot|ddagger|gtrless|bnequiv|harrcir|ddotseq|equivDD|backsim|demptyv|nsqsube|nsqsupe|Upsilon|nsubset|upsilon|minusdu|nsucceq|swarrow|nsupset|coloneq|searrow|boxplus|napprox|natural|asympeq|alefsym|congdot|nearrow|bigstar|diamond|supplus|tritime|LeftTee|nvinfin|triplus|NewLine|nvltrie|nvrtrie|nwarrow|nexists|Diamond|ruluhar|Implies|supmult|angzarr|suplarr|suphsub|questeq|because|digamma|Because|olcross|bemptyv|omicron|Omicron|rotimes|NoBreak|intprod|angrtvb|orderof|uwangle|suphsol|lesdoto|orslope|DownTee|realine|cudarrl|rdldhar|OverBar|supedot|lessdot|supdsub|topfork|succsim|rbrkslu|rbrksld|pertenk|cudarrr|isindot|planckh|lessgtr|pluscir|gesdoto|plussim|plustwo|lesssim|cularrp|rarrsim|Cayleys|notinva|notinvb|notinvc|UpArrow|Uparrow|uparrow|NotLess|dwangle|precsim|Product|curarrm|Cconint|dotplus|rarrbfs|ccupssm|Cedilla|cemptyv|notniva|quatint|frac35|frac38|frac45|frac56|frac58|frac78|tridot|xoplus|gacute|gammad|Gammad|lfisht|lfloor|bigcup|sqsupe|gbreve|Gbreve|lharul|sqsube|sqcups|Gcedil|apacir|llhard|lmidot|Lmidot|lmoust|andand|sqcaps|approx|Abreve|spades|circeq|tprime|divide|topcir|Assign|topbot|gesdot|divonx|xuplus|timesd|gesles|atilde|solbar|SOFTcy|loplus|timesb|lowast|lowbar|dlcorn|dlcrop|softcy|dollar|lparlt|thksim|lrhard|Atilde|lsaquo|smashp|bigvee|thinsp|wreath|bkarow|lsquor|lstrok|Lstrok|lthree|ltimes|ltlarr|DotDot|simdot|ltrPar|weierp|xsqcup|angmsd|sigmav|sigmaf|zeetrf|Zcaron|zcaron|mapsto|vsupne|thetav|cirmid|marker|mcomma|Zacute|vsubnE|there4|gtlPar|vsubne|bottom|gtrarr|SHCHcy|shchcy|midast|midcir|middot|minusb|minusd|gtrdot|bowtie|sfrown|mnplus|models|colone|seswar|Colone|mstpos|searhk|gtrsim|nacute|Nacute|boxbox|telrec|hairsp|Tcedil|nbumpe|scnsim|ncaron|Ncaron|ncedil|Ncedil|hamilt|Scedil|nearhk|hardcy|HARDcy|tcedil|Tcaron|commat|nequiv|nesear|tcaron|target|hearts|nexist|varrho|scedil|Scaron|scaron|hellip|Sacute|sacute|hercon|swnwar|compfn|rtimes|rthree|rsquor|rsaquo|zacute|wedgeq|homtht|barvee|barwed|Barwed|rpargt|horbar|conint|swarhk|roplus|nltrie|hslash|hstrok|Hstrok|rmoust|Conint|bprime|hybull|hyphen|iacute|Iacute|supsup|supsub|supsim|varphi|coprod|brvbar|agrave|Supset|supset|igrave|Igrave|notinE|Agrave|iiiint|iinfin|copysr|wedbar|Verbar|vangrt|becaus|incare|verbar|inodot|bullet|drcorn|intcal|drcrop|cularr|vellip|Utilde|bumpeq|cupcap|dstrok|Dstrok|CupCap|cupcup|cupdot|eacute|Eacute|supdot|iquest|easter|ecaron|Ecaron|ecolon|isinsv|utilde|itilde|Itilde|curarr|succeq|Bumpeq|cacute|ulcrop|nparsl|Cacute|nprcue|egrave|Egrave|nrarrc|nrarrw|subsup|subsub|nrtrie|jsercy|nsccue|Jsercy|kappav|kcedil|Kcedil|subsim|ulcorn|nsimeq|egsdot|veebar|kgreen|capand|elsdot|Subset|subset|curren|aacute|lacute|Lacute|emptyv|ntilde|Ntilde|lagran|lambda|Lambda|capcap|Ugrave|langle|subdot|emsp13|numero|emsp14|nvdash|nvDash|nVdash|nVDash|ugrave|ufisht|nvHarr|larrfs|nvlArr|larrhk|larrlp|larrpl|nvrArr|Udblac|nwarhk|larrtl|nwnear|oacute|Oacute|latail|lAtail|sstarf|lbrace|odblac|Odblac|lbrack|udblac|odsold|eparsl|lcaron|Lcaron|ograve|Ograve|lcedil|Lcedil|Aacute|ssmile|ssetmn|squarf|ldquor|capcup|ominus|cylcty|rharul|eqcirc|dagger|rfloor|rfisht|Dagger|daleth|equals|origof|capdot|equest|dcaron|Dcaron|rdquor|oslash|Oslash|otilde|Otilde|otimes|Otimes|urcrop|Ubreve|ubreve|Yacute|Uacute|uacute|Rcedil|rcedil|urcorn|parsim|Rcaron|Vdashl|rcaron|Tstrok|percnt|period|permil|Exists|yacute|rbrack|rbrace|phmmat|ccaron|Ccaron|planck|ccedil|plankv|tstrok|female|plusdo|plusdu|ffilig|plusmn|ffllig|Ccedil|rAtail|dfisht|bernou|ratail|Rarrtl|rarrtl|angsph|rarrpl|rarrlp|rarrhk|xwedge|xotime|forall|ForAll|Vvdash|vsupnE|preceq|bigcap|frac12|frac13|frac14|primes|rarrfs|prnsim|frac15|Square|frac16|square|lesdot|frac18|frac23|propto|prurel|rarrap|rangle|puncsp|frac25|Racute|qprime|racute|lesges|frac34|abreve|AElig|eqsim|utdot|setmn|urtri|Equal|Uring|seArr|uring|searr|dashv|Dashv|mumap|nabla|iogon|Iogon|sdote|sdotb|scsim|napid|napos|equiv|natur|Acirc|dblac|erarr|nbump|iprod|erDot|ucirc|awint|esdot|angrt|ncong|isinE|scnap|Scirc|scirc|ndash|isins|Ubrcy|nearr|neArr|isinv|nedot|ubrcy|acute|Ycirc|iukcy|Iukcy|xutri|nesim|caret|jcirc|Jcirc|caron|twixt|ddarr|sccue|exist|jmath|sbquo|ngeqq|angst|ccaps|lceil|ngsim|UpTee|delta|Delta|rtrif|nharr|nhArr|nhpar|rtrie|jukcy|Jukcy|kappa|rsquo|Kappa|nlarr|nlArr|TSHcy|rrarr|aogon|Aogon|fflig|xrarr|tshcy|ccirc|nleqq|filig|upsih|nless|dharl|nlsim|fjlig|ropar|nltri|dharr|robrk|roarr|fllig|fltns|roang|rnmid|subnE|subne|lAarr|trisb|Ccirc|acirc|ccups|blank|VDash|forkv|Vdash|langd|cedil|blk12|blk14|laquo|strns|diams|notin|vDash|larrb|blk34|block|disin|uplus|vdash|vBarv|aelig|starf|Wedge|check|xrArr|lates|lbarr|lBarr|notni|lbbrk|bcong|frasl|lbrke|frown|vrtri|vprop|vnsup|gamma|Gamma|wedge|xodot|bdquo|srarr|doteq|ldquo|boxdl|boxdL|gcirc|Gcirc|boxDl|boxDL|boxdr|boxdR|boxDr|TRADE|trade|rlhar|boxDR|vnsub|npart|vltri|rlarr|boxhd|boxhD|nprec|gescc|nrarr|nrArr|boxHd|boxHD|boxhu|boxhU|nrtri|boxHu|clubs|boxHU|times|colon|Colon|gimel|xlArr|Tilde|nsime|tilde|nsmid|nspar|THORN|thorn|xlarr|nsube|nsubE|thkap|xhArr|comma|nsucc|boxul|boxuL|nsupe|nsupE|gneqq|gnsim|boxUl|boxUL|grave|boxur|boxuR|boxUr|boxUR|lescc|angle|bepsi|boxvh|varpi|boxvH|numsp|Theta|gsime|gsiml|theta|boxVh|boxVH|boxvl|gtcir|gtdot|boxvL|boxVl|boxVL|crarr|cross|Cross|nvsim|boxvr|nwarr|nwArr|sqsup|dtdot|Uogon|lhard|lharu|dtrif|ocirc|Ocirc|lhblk|duarr|odash|sqsub|Hacek|sqcup|llarr|duhar|oelig|OElig|ofcir|boxvR|uogon|lltri|boxVr|csube|uuarr|ohbar|csupe|ctdot|olarr|olcir|harrw|oline|sqcap|omacr|Omacr|omega|Omega|boxVR|aleph|lneqq|lnsim|loang|loarr|rharu|lobrk|hcirc|operp|oplus|rhard|Hcirc|orarr|Union|order|ecirc|Ecirc|cuepr|szlig|cuesc|breve|reals|eDDot|Breve|hoarr|lopar|utrif|rdquo|Umacr|umacr|efDot|swArr|ultri|alpha|rceil|ovbar|swarr|Wcirc|wcirc|smtes|smile|bsemi|lrarr|aring|parsl|lrhar|bsime|uhblk|lrtri|cupor|Aring|uharr|uharl|slarr|rbrke|bsolb|lsime|rbbrk|RBarr|lsimg|phone|rBarr|rbarr|icirc|lsquo|Icirc|emacr|Emacr|ratio|simne|plusb|simlE|simgE|simeq|pluse|ltcir|ltdot|empty|xharr|xdtri|iexcl|Alpha|ltrie|rarrw|pound|ltrif|xcirc|bumpe|prcue|bumpE|asymp|amacr|cuvee|Sigma|sigma|iiint|udhar|iiota|ijlig|IJlig|supnE|imacr|Imacr|prime|Prime|image|prnap|eogon|Eogon|rarrc|mdash|mDDot|cuwed|imath|supne|imped|Amacr|udarr|prsim|micro|rarrb|cwint|raquo|infin|eplus|range|rangd|Ucirc|radic|minus|amalg|veeeq|rAarr|epsiv|ycirc|quest|sharp|quot|zwnj|Qscr|race|qscr|Qopf|qopf|qint|rang|Rang|Zscr|zscr|Zopf|zopf|rarr|rArr|Rarr|Pscr|pscr|prop|prod|prnE|prec|ZHcy|zhcy|prap|Zeta|zeta|Popf|popf|Zdot|plus|zdot|Yuml|yuml|phiv|YUcy|yucy|Yscr|yscr|perp|Yopf|yopf|part|para|YIcy|Ouml|rcub|yicy|YAcy|rdca|ouml|osol|Oscr|rdsh|yacy|real|oscr|xvee|andd|rect|andv|Xscr|oror|ordm|ordf|xscr|ange|aopf|Aopf|rHar|Xopf|opar|Oopf|xopf|xnis|rhov|oopf|omid|xmap|oint|apid|apos|ogon|ascr|Ascr|odot|odiv|xcup|xcap|ocir|oast|nvlt|nvle|nvgt|nvge|nvap|Wscr|wscr|auml|ntlg|ntgl|nsup|nsub|nsim|Nscr|nscr|nsce|Wopf|ring|npre|wopf|npar|Auml|Barv|bbrk|Nopf|nopf|nmid|nLtv|beta|ropf|Ropf|Beta|beth|nles|rpar|nleq|bnot|bNot|nldr|NJcy|rscr|Rscr|Vscr|vscr|rsqb|njcy|bopf|nisd|Bopf|rtri|Vopf|nGtv|ngtr|vopf|boxh|boxH|boxv|nges|ngeq|boxV|bscr|scap|Bscr|bsim|Vert|vert|bsol|bull|bump|caps|cdot|ncup|scnE|ncap|nbsp|napE|Cdot|cent|sdot|Vbar|nang|vBar|chcy|Mscr|mscr|sect|semi|CHcy|Mopf|mopf|sext|circ|cire|mldr|mlcp|cirE|comp|shcy|SHcy|vArr|varr|cong|copf|Copf|copy|COPY|malt|male|macr|lvnE|cscr|ltri|sime|ltcc|simg|Cscr|siml|csub|Uuml|lsqb|lsim|uuml|csup|Lscr|lscr|utri|smid|lpar|cups|smte|lozf|darr|Lopf|Uscr|solb|lopf|sopf|Sopf|lneq|uscr|spar|dArr|lnap|Darr|dash|Sqrt|LJcy|ljcy|lHar|dHar|Upsi|upsi|diam|lesg|djcy|DJcy|leqq|dopf|Dopf|dscr|Dscr|dscy|ldsh|ldca|squf|DScy|sscr|Sscr|dsol|lcub|late|star|Star|Uopf|Larr|lArr|larr|uopf|dtri|dzcy|sube|subE|Lang|lang|Kscr|kscr|Kopf|kopf|KJcy|kjcy|KHcy|khcy|DZcy|ecir|edot|eDot|Jscr|jscr|succ|Jopf|jopf|Edot|uHar|emsp|ensp|Iuml|iuml|eopf|isin|Iscr|iscr|Eopf|epar|sung|epsi|escr|sup1|sup2|sup3|Iota|iota|supe|supE|Iopf|iopf|IOcy|iocy|Escr|esim|Esim|imof|Uarr|QUOT|uArr|uarr|euml|IEcy|iecy|Idot|Euml|euro|excl|Hscr|hscr|Hopf|hopf|TScy|tscy|Tscr|hbar|tscr|flat|tbrk|fnof|hArr|harr|half|fopf|Fopf|tdot|gvnE|fork|trie|gtcc|fscr|Fscr|gdot|gsim|Gscr|gscr|Gopf|gopf|gneq|Gdot|tosa|gnap|Topf|topf|geqq|toea|GJcy|gjcy|tint|gesl|mid|Sfr|ggg|top|ges|gla|glE|glj|geq|gne|gEl|gel|gnE|Gcy|gcy|gap|Tfr|tfr|Tcy|tcy|Hat|Tau|Ffr|tau|Tab|hfr|Hfr|ffr|Fcy|fcy|icy|Icy|iff|ETH|eth|ifr|Ifr|Eta|eta|int|Int|Sup|sup|ucy|Ucy|Sum|sum|jcy|ENG|ufr|Ufr|eng|Jcy|jfr|els|ell|egs|Efr|efr|Jfr|uml|kcy|Kcy|Ecy|ecy|kfr|Kfr|lap|Sub|sub|lat|lcy|Lcy|leg|Dot|dot|lEg|leq|les|squ|div|die|lfr|Lfr|lgE|Dfr|dfr|Del|deg|Dcy|dcy|lne|lnE|sol|loz|smt|Cup|lrm|cup|lsh|Lsh|sim|shy|map|Map|mcy|Mcy|mfr|Mfr|mho|gfr|Gfr|sfr|cir|Chi|chi|nap|Cfr|vcy|Vcy|cfr|Scy|scy|ncy|Ncy|vee|Vee|Cap|cap|nfr|scE|sce|Nfr|nge|ngE|nGg|vfr|Vfr|ngt|bot|nGt|nis|niv|Rsh|rsh|nle|nlE|bne|Bfr|bfr|nLl|nlt|nLt|Bcy|bcy|not|Not|rlm|wfr|Wfr|npr|nsc|num|ocy|ast|Ocy|ofr|xfr|Xfr|Ofr|ogt|ohm|apE|olt|Rho|ape|rho|Rfr|rfr|ord|REG|ang|reg|orv|And|and|AMP|Rcy|amp|Afr|ycy|Ycy|yen|yfr|Yfr|rcy|par|pcy|Pcy|pfr|Pfr|phi|Phi|afr|Acy|acy|zcy|Zcy|piv|acE|acd|zfr|Zfr|pre|prE|psi|Psi|qfr|Qfr|zwj|Or|ge|Gg|gt|gg|el|oS|lt|Lt|LT|Re|lg|gl|eg|ne|Im|it|le|DD|wp|wr|nu|Nu|dd|lE|Sc|sc|pi|Pi|ee|af|ll|Ll|rx|gE|xi|pm|Xi|ic|pr|Pr|in|ni|mp|mu|ac|Mu|or|ap|Gt|GT|ii);|&(Aacute|Agrave|Atilde|Ccedil|Eacute|Egrave|Iacute|Igrave|Ntilde|Oacute|Ograve|Oslash|Otilde|Uacute|Ugrave|Yacute|aacute|agrave|atilde|brvbar|ccedil|curren|divide|eacute|egrave|frac12|frac14|frac34|iacute|igrave|iquest|middot|ntilde|oacute|ograve|oslash|otilde|plusmn|uacute|ugrave|yacute|AElig|Acirc|Aring|Ecirc|Icirc|Ocirc|THORN|Ucirc|acirc|acute|aelig|aring|cedil|ecirc|icirc|iexcl|laquo|micro|ocirc|pound|raquo|szlig|thorn|times|ucirc|Auml|COPY|Euml|Iuml|Ouml|QUOT|Uuml|auml|cent|copy|euml|iuml|macr|nbsp|ordf|ordm|ouml|para|quot|sect|sup1|sup2|sup3|uuml|yuml|AMP|ETH|REG|amp|deg|eth|not|reg|shy|uml|yen|GT|LT|gt|lt)(?!;)([=a-zA-Z0-9]?)|&#([0-9]+)(;?)|&#[xX]([a-fA-F0-9]+)(;?)|&([0-9a-zA-Z]+)/g;
        var decodeMap = { "aacute": "á", "Aacute": "Á", "abreve": "ă", "Abreve": "Ă", "ac": "∾", "acd": "∿", "acE": "∾̳", "acirc": "â", "Acirc": "Â", "acute": "´", "acy": "а", "Acy": "А", "aelig": "æ", "AElig": "Æ", "af": "⁡", "afr": "𝔞", "Afr": "𝔄", "agrave": "à", "Agrave": "À", "alefsym": "ℵ", "aleph": "ℵ", "alpha": "α", "Alpha": "Α", "amacr": "ā", "Amacr": "Ā", "amalg": "⨿", "amp": "&", "AMP": "&", "and": "∧", "And": "⩓", "andand": "⩕", "andd": "⩜", "andslope": "⩘", "andv": "⩚", "ang": "∠", "ange": "⦤", "angle": "∠", "angmsd": "∡", "angmsdaa": "⦨", "angmsdab": "⦩", "angmsdac": "⦪", "angmsdad": "⦫", "angmsdae": "⦬", "angmsdaf": "⦭", "angmsdag": "⦮", "angmsdah": "⦯", "angrt": "∟", "angrtvb": "⊾", "angrtvbd": "⦝", "angsph": "∢", "angst": "Å", "angzarr": "⍼", "aogon": "ą", "Aogon": "Ą", "aopf": "𝕒", "Aopf": "𝔸", "ap": "≈", "apacir": "⩯", "ape": "≊", "apE": "⩰", "apid": "≋", "apos": "'", "ApplyFunction": "⁡", "approx": "≈", "approxeq": "≊", "aring": "å", "Aring": "Å", "ascr": "𝒶", "Ascr": "𝒜", "Assign": "≔", "ast": "*", "asymp": "≈", "asympeq": "≍", "atilde": "ã", "Atilde": "Ã", "auml": "ä", "Auml": "Ä", "awconint": "∳", "awint": "⨑", "backcong": "≌", "backepsilon": "϶", "backprime": "‵", "backsim": "∽", "backsimeq": "⋍", "Backslash": "∖", "Barv": "⫧", "barvee": "⊽", "barwed": "⌅", "Barwed": "⌆", "barwedge": "⌅", "bbrk": "⎵", "bbrktbrk": "⎶", "bcong": "≌", "bcy": "б", "Bcy": "Б", "bdquo": "„", "becaus": "∵", "because": "∵", "Because": "∵", "bemptyv": "⦰", "bepsi": "϶", "bernou": "ℬ", "Bernoullis": "ℬ", "beta": "β", "Beta": "Β", "beth": "ℶ", "between": "≬", "bfr": "𝔟", "Bfr": "𝔅", "bigcap": "⋂", "bigcirc": "◯", "bigcup": "⋃", "bigodot": "⨀", "bigoplus": "⨁", "bigotimes": "⨂", "bigsqcup": "⨆", "bigstar": "★", "bigtriangledown": "▽", "bigtriangleup": "△", "biguplus": "⨄", "bigvee": "⋁", "bigwedge": "⋀", "bkarow": "⤍", "blacklozenge": "⧫", "blacksquare": "▪", "blacktriangle": "▴", "blacktriangledown": "▾", "blacktriangleleft": "◂", "blacktriangleright": "▸", "blank": "␣", "blk12": "▒", "blk14": "░", "blk34": "▓", "block": "█", "bne": "=⃥", "bnequiv": "≡⃥", "bnot": "⌐", "bNot": "⫭", "bopf": "𝕓", "Bopf": "𝔹", "bot": "⊥", "bottom": "⊥", "bowtie": "⋈", "boxbox": "⧉", "boxdl": "┐", "boxdL": "╕", "boxDl": "╖", "boxDL": "╗", "boxdr": "┌", "boxdR": "╒", "boxDr": "╓", "boxDR": "╔", "boxh": "─", "boxH": "═", "boxhd": "┬", "boxhD": "╥", "boxHd": "╤", "boxHD": "╦", "boxhu": "┴", "boxhU": "╨", "boxHu": "╧", "boxHU": "╩", "boxminus": "⊟", "boxplus": "⊞", "boxtimes": "⊠", "boxul": "┘", "boxuL": "╛", "boxUl": "╜", "boxUL": "╝", "boxur": "└", "boxuR": "╘", "boxUr": "╙", "boxUR": "╚", "boxv": "│", "boxV": "║", "boxvh": "┼", "boxvH": "╪", "boxVh": "╫", "boxVH": "╬", "boxvl": "┤", "boxvL": "╡", "boxVl": "╢", "boxVL": "╣", "boxvr": "├", "boxvR": "╞", "boxVr": "╟", "boxVR": "╠", "bprime": "‵", "breve": "˘", "Breve": "˘", "brvbar": "¦", "bscr": "𝒷", "Bscr": "ℬ", "bsemi": "⁏", "bsim": "∽", "bsime": "⋍", "bsol": "\\", "bsolb": "⧅", "bsolhsub": "⟈", "bull": "•", "bullet": "•", "bump": "≎", "bumpe": "≏", "bumpE": "⪮", "bumpeq": "≏", "Bumpeq": "≎", "cacute": "ć", "Cacute": "Ć", "cap": "∩", "Cap": "⋒", "capand": "⩄", "capbrcup": "⩉", "capcap": "⩋", "capcup": "⩇", "capdot": "⩀", "CapitalDifferentialD": "ⅅ", "caps": "∩︀", "caret": "⁁", "caron": "ˇ", "Cayleys": "ℭ", "ccaps": "⩍", "ccaron": "č", "Ccaron": "Č", "ccedil": "ç", "Ccedil": "Ç", "ccirc": "ĉ", "Ccirc": "Ĉ", "Cconint": "∰", "ccups": "⩌", "ccupssm": "⩐", "cdot": "ċ", "Cdot": "Ċ", "cedil": "¸", "Cedilla": "¸", "cemptyv": "⦲", "cent": "¢", "centerdot": "·", "CenterDot": "·", "cfr": "𝔠", "Cfr": "ℭ", "chcy": "ч", "CHcy": "Ч", "check": "✓", "checkmark": "✓", "chi": "χ", "Chi": "Χ", "cir": "○", "circ": "ˆ", "circeq": "≗", "circlearrowleft": "↺", "circlearrowright": "↻", "circledast": "⊛", "circledcirc": "⊚", "circleddash": "⊝", "CircleDot": "⊙", "circledR": "®", "circledS": "Ⓢ", "CircleMinus": "⊖", "CirclePlus": "⊕", "CircleTimes": "⊗", "cire": "≗", "cirE": "⧃", "cirfnint": "⨐", "cirmid": "⫯", "cirscir": "⧂", "ClockwiseContourIntegral": "∲", "CloseCurlyDoubleQuote": "”", "CloseCurlyQuote": "’", "clubs": "♣", "clubsuit": "♣", "colon": ":", "Colon": "∷", "colone": "≔", "Colone": "⩴", "coloneq": "≔", "comma": ",", "commat": "@", "comp": "∁", "compfn": "∘", "complement": "∁", "complexes": "ℂ", "cong": "≅", "congdot": "⩭", "Congruent": "≡", "conint": "∮", "Conint": "∯", "ContourIntegral": "∮", "copf": "𝕔", "Copf": "ℂ", "coprod": "∐", "Coproduct": "∐", "copy": "©", "COPY": "©", "copysr": "℗", "CounterClockwiseContourIntegral": "∳", "crarr": "↵", "cross": "✗", "Cross": "⨯", "cscr": "𝒸", "Cscr": "𝒞", "csub": "⫏", "csube": "⫑", "csup": "⫐", "csupe": "⫒", "ctdot": "⋯", "cudarrl": "⤸", "cudarrr": "⤵", "cuepr": "⋞", "cuesc": "⋟", "cularr": "↶", "cularrp": "⤽", "cup": "∪", "Cup": "⋓", "cupbrcap": "⩈", "cupcap": "⩆", "CupCap": "≍", "cupcup": "⩊", "cupdot": "⊍", "cupor": "⩅", "cups": "∪︀", "curarr": "↷", "curarrm": "⤼", "curlyeqprec": "⋞", "curlyeqsucc": "⋟", "curlyvee": "⋎", "curlywedge": "⋏", "curren": "¤", "curvearrowleft": "↶", "curvearrowright": "↷", "cuvee": "⋎", "cuwed": "⋏", "cwconint": "∲", "cwint": "∱", "cylcty": "⌭", "dagger": "†", "Dagger": "‡", "daleth": "ℸ", "darr": "↓", "dArr": "⇓", "Darr": "↡", "dash": "‐", "dashv": "⊣", "Dashv": "⫤", "dbkarow": "⤏", "dblac": "˝", "dcaron": "ď", "Dcaron": "Ď", "dcy": "д", "Dcy": "Д", "dd": "ⅆ", "DD": "ⅅ", "ddagger": "‡", "ddarr": "⇊", "DDotrahd": "⤑", "ddotseq": "⩷", "deg": "°", "Del": "∇", "delta": "δ", "Delta": "Δ", "demptyv": "⦱", "dfisht": "⥿", "dfr": "𝔡", "Dfr": "𝔇", "dHar": "⥥", "dharl": "⇃", "dharr": "⇂", "DiacriticalAcute": "´", "DiacriticalDot": "˙", "DiacriticalDoubleAcute": "˝", "DiacriticalGrave": "`", "DiacriticalTilde": "˜", "diam": "⋄", "diamond": "⋄", "Diamond": "⋄", "diamondsuit": "♦", "diams": "♦", "die": "¨", "DifferentialD": "ⅆ", "digamma": "ϝ", "disin": "⋲", "div": "÷", "divide": "÷", "divideontimes": "⋇", "divonx": "⋇", "djcy": "ђ", "DJcy": "Ђ", "dlcorn": "⌞", "dlcrop": "⌍", "dollar": "$", "dopf": "𝕕", "Dopf": "𝔻", "dot": "˙", "Dot": "¨", "DotDot": "⃜", "doteq": "≐", "doteqdot": "≑", "DotEqual": "≐", "dotminus": "∸", "dotplus": "∔", "dotsquare": "⊡", "doublebarwedge": "⌆", "DoubleContourIntegral": "∯", "DoubleDot": "¨", "DoubleDownArrow": "⇓", "DoubleLeftArrow": "⇐", "DoubleLeftRightArrow": "⇔", "DoubleLeftTee": "⫤", "DoubleLongLeftArrow": "⟸", "DoubleLongLeftRightArrow": "⟺", "DoubleLongRightArrow": "⟹", "DoubleRightArrow": "⇒", "DoubleRightTee": "⊨", "DoubleUpArrow": "⇑", "DoubleUpDownArrow": "⇕", "DoubleVerticalBar": "∥", "downarrow": "↓", "Downarrow": "⇓", "DownArrow": "↓", "DownArrowBar": "⤓", "DownArrowUpArrow": "⇵", "DownBreve": "̑", "downdownarrows": "⇊", "downharpoonleft": "⇃", "downharpoonright": "⇂", "DownLeftRightVector": "⥐", "DownLeftTeeVector": "⥞", "DownLeftVector": "↽", "DownLeftVectorBar": "⥖", "DownRightTeeVector": "⥟", "DownRightVector": "⇁", "DownRightVectorBar": "⥗", "DownTee": "⊤", "DownTeeArrow": "↧", "drbkarow": "⤐", "drcorn": "⌟", "drcrop": "⌌", "dscr": "𝒹", "Dscr": "𝒟", "dscy": "ѕ", "DScy": "Ѕ", "dsol": "⧶", "dstrok": "đ", "Dstrok": "Đ", "dtdot": "⋱", "dtri": "▿", "dtrif": "▾", "duarr": "⇵", "duhar": "⥯", "dwangle": "⦦", "dzcy": "џ", "DZcy": "Џ", "dzigrarr": "⟿", "eacute": "é", "Eacute": "É", "easter": "⩮", "ecaron": "ě", "Ecaron": "Ě", "ecir": "≖", "ecirc": "ê", "Ecirc": "Ê", "ecolon": "≕", "ecy": "э", "Ecy": "Э", "eDDot": "⩷", "edot": "ė", "eDot": "≑", "Edot": "Ė", "ee": "ⅇ", "efDot": "≒", "efr": "𝔢", "Efr": "𝔈", "eg": "⪚", "egrave": "è", "Egrave": "È", "egs": "⪖", "egsdot": "⪘", "el": "⪙", "Element": "∈", "elinters": "⏧", "ell": "ℓ", "els": "⪕", "elsdot": "⪗", "emacr": "ē", "Emacr": "Ē", "empty": "∅", "emptyset": "∅", "EmptySmallSquare": "◻", "emptyv": "∅", "EmptyVerySmallSquare": "▫", "emsp": " ", "emsp13": " ", "emsp14": " ", "eng": "ŋ", "ENG": "Ŋ", "ensp": " ", "eogon": "ę", "Eogon": "Ę", "eopf": "𝕖", "Eopf": "𝔼", "epar": "⋕", "eparsl": "⧣", "eplus": "⩱", "epsi": "ε", "epsilon": "ε", "Epsilon": "Ε", "epsiv": "ϵ", "eqcirc": "≖", "eqcolon": "≕", "eqsim": "≂", "eqslantgtr": "⪖", "eqslantless": "⪕", "Equal": "⩵", "equals": "=", "EqualTilde": "≂", "equest": "≟", "Equilibrium": "⇌", "equiv": "≡", "equivDD": "⩸", "eqvparsl": "⧥", "erarr": "⥱", "erDot": "≓", "escr": "ℯ", "Escr": "ℰ", "esdot": "≐", "esim": "≂", "Esim": "⩳", "eta": "η", "Eta": "Η", "eth": "ð", "ETH": "Ð", "euml": "ë", "Euml": "Ë", "euro": "€", "excl": "!", "exist": "∃", "Exists": "∃", "expectation": "ℰ", "exponentiale": "ⅇ", "ExponentialE": "ⅇ", "fallingdotseq": "≒", "fcy": "ф", "Fcy": "Ф", "female": "♀", "ffilig": "ﬃ", "fflig": "ﬀ", "ffllig": "ﬄ", "ffr": "𝔣", "Ffr": "𝔉", "filig": "ﬁ", "FilledSmallSquare": "◼", "FilledVerySmallSquare": "▪", "fjlig": "fj", "flat": "♭", "fllig": "ﬂ", "fltns": "▱", "fnof": "ƒ", "fopf": "𝕗", "Fopf": "𝔽", "forall": "∀", "ForAll": "∀", "fork": "⋔", "forkv": "⫙", "Fouriertrf": "ℱ", "fpartint": "⨍", "frac12": "½", "frac13": "⅓", "frac14": "¼", "frac15": "⅕", "frac16": "⅙", "frac18": "⅛", "frac23": "⅔", "frac25": "⅖", "frac34": "¾", "frac35": "⅗", "frac38": "⅜", "frac45": "⅘", "frac56": "⅚", "frac58": "⅝", "frac78": "⅞", "frasl": "⁄", "frown": "⌢", "fscr": "𝒻", "Fscr": "ℱ", "gacute": "ǵ", "gamma": "γ", "Gamma": "Γ", "gammad": "ϝ", "Gammad": "Ϝ", "gap": "⪆", "gbreve": "ğ", "Gbreve": "Ğ", "Gcedil": "Ģ", "gcirc": "ĝ", "Gcirc": "Ĝ", "gcy": "г", "Gcy": "Г", "gdot": "ġ", "Gdot": "Ġ", "ge": "≥", "gE": "≧", "gel": "⋛", "gEl": "⪌", "geq": "≥", "geqq": "≧", "geqslant": "⩾", "ges": "⩾", "gescc": "⪩", "gesdot": "⪀", "gesdoto": "⪂", "gesdotol": "⪄", "gesl": "⋛︀", "gesles": "⪔", "gfr": "𝔤", "Gfr": "𝔊", "gg": "≫", "Gg": "⋙", "ggg": "⋙", "gimel": "ℷ", "gjcy": "ѓ", "GJcy": "Ѓ", "gl": "≷", "gla": "⪥", "glE": "⪒", "glj": "⪤", "gnap": "⪊", "gnapprox": "⪊", "gne": "⪈", "gnE": "≩", "gneq": "⪈", "gneqq": "≩", "gnsim": "⋧", "gopf": "𝕘", "Gopf": "𝔾", "grave": "`", "GreaterEqual": "≥", "GreaterEqualLess": "⋛", "GreaterFullEqual": "≧", "GreaterGreater": "⪢", "GreaterLess": "≷", "GreaterSlantEqual": "⩾", "GreaterTilde": "≳", "gscr": "ℊ", "Gscr": "𝒢", "gsim": "≳", "gsime": "⪎", "gsiml": "⪐", "gt": ">", "Gt": "≫", "GT": ">", "gtcc": "⪧", "gtcir": "⩺", "gtdot": "⋗", "gtlPar": "⦕", "gtquest": "⩼", "gtrapprox": "⪆", "gtrarr": "⥸", "gtrdot": "⋗", "gtreqless": "⋛", "gtreqqless": "⪌", "gtrless": "≷", "gtrsim": "≳", "gvertneqq": "≩︀", "gvnE": "≩︀", "Hacek": "ˇ", "hairsp": " ", "half": "½", "hamilt": "ℋ", "hardcy": "ъ", "HARDcy": "Ъ", "harr": "↔", "hArr": "⇔", "harrcir": "⥈", "harrw": "↭", "Hat": "^", "hbar": "ℏ", "hcirc": "ĥ", "Hcirc": "Ĥ", "hearts": "♥", "heartsuit": "♥", "hellip": "…", "hercon": "⊹", "hfr": "𝔥", "Hfr": "ℌ", "HilbertSpace": "ℋ", "hksearow": "⤥", "hkswarow": "⤦", "hoarr": "⇿", "homtht": "∻", "hookleftarrow": "↩", "hookrightarrow": "↪", "hopf": "𝕙", "Hopf": "ℍ", "horbar": "―", "HorizontalLine": "─", "hscr": "𝒽", "Hscr": "ℋ", "hslash": "ℏ", "hstrok": "ħ", "Hstrok": "Ħ", "HumpDownHump": "≎", "HumpEqual": "≏", "hybull": "⁃", "hyphen": "‐", "iacute": "í", "Iacute": "Í", "ic": "⁣", "icirc": "î", "Icirc": "Î", "icy": "и", "Icy": "И", "Idot": "İ", "iecy": "е", "IEcy": "Е", "iexcl": "¡", "iff": "⇔", "ifr": "𝔦", "Ifr": "ℑ", "igrave": "ì", "Igrave": "Ì", "ii": "ⅈ", "iiiint": "⨌", "iiint": "∭", "iinfin": "⧜", "iiota": "℩", "ijlig": "ĳ", "IJlig": "Ĳ", "Im": "ℑ", "imacr": "ī", "Imacr": "Ī", "image": "ℑ", "ImaginaryI": "ⅈ", "imagline": "ℐ", "imagpart": "ℑ", "imath": "ı", "imof": "⊷", "imped": "Ƶ", "Implies": "⇒", "in": "∈", "incare": "℅", "infin": "∞", "infintie": "⧝", "inodot": "ı", "int": "∫", "Int": "∬", "intcal": "⊺", "integers": "ℤ", "Integral": "∫", "intercal": "⊺", "Intersection": "⋂", "intlarhk": "⨗", "intprod": "⨼", "InvisibleComma": "⁣", "InvisibleTimes": "⁢", "iocy": "ё", "IOcy": "Ё", "iogon": "į", "Iogon": "Į", "iopf": "𝕚", "Iopf": "𝕀", "iota": "ι", "Iota": "Ι", "iprod": "⨼", "iquest": "¿", "iscr": "𝒾", "Iscr": "ℐ", "isin": "∈", "isindot": "⋵", "isinE": "⋹", "isins": "⋴", "isinsv": "⋳", "isinv": "∈", "it": "⁢", "itilde": "ĩ", "Itilde": "Ĩ", "iukcy": "і", "Iukcy": "І", "iuml": "ï", "Iuml": "Ï", "jcirc": "ĵ", "Jcirc": "Ĵ", "jcy": "й", "Jcy": "Й", "jfr": "𝔧", "Jfr": "𝔍", "jmath": "ȷ", "jopf": "𝕛", "Jopf": "𝕁", "jscr": "𝒿", "Jscr": "𝒥", "jsercy": "ј", "Jsercy": "Ј", "jukcy": "є", "Jukcy": "Є", "kappa": "κ", "Kappa": "Κ", "kappav": "ϰ", "kcedil": "ķ", "Kcedil": "Ķ", "kcy": "к", "Kcy": "К", "kfr": "𝔨", "Kfr": "𝔎", "kgreen": "ĸ", "khcy": "х", "KHcy": "Х", "kjcy": "ќ", "KJcy": "Ќ", "kopf": "𝕜", "Kopf": "𝕂", "kscr": "𝓀", "Kscr": "𝒦", "lAarr": "⇚", "lacute": "ĺ", "Lacute": "Ĺ", "laemptyv": "⦴", "lagran": "ℒ", "lambda": "λ", "Lambda": "Λ", "lang": "⟨", "Lang": "⟪", "langd": "⦑", "langle": "⟨", "lap": "⪅", "Laplacetrf": "ℒ", "laquo": "«", "larr": "←", "lArr": "⇐", "Larr": "↞", "larrb": "⇤", "larrbfs": "⤟", "larrfs": "⤝", "larrhk": "↩", "larrlp": "↫", "larrpl": "⤹", "larrsim": "⥳", "larrtl": "↢", "lat": "⪫", "latail": "⤙", "lAtail": "⤛", "late": "⪭", "lates": "⪭︀", "lbarr": "⤌", "lBarr": "⤎", "lbbrk": "❲", "lbrace": "{", "lbrack": "[", "lbrke": "⦋", "lbrksld": "⦏", "lbrkslu": "⦍", "lcaron": "ľ", "Lcaron": "Ľ", "lcedil": "ļ", "Lcedil": "Ļ", "lceil": "⌈", "lcub": "{", "lcy": "л", "Lcy": "Л", "ldca": "⤶", "ldquo": "“", "ldquor": "„", "ldrdhar": "⥧", "ldrushar": "⥋", "ldsh": "↲", "le": "≤", "lE": "≦", "LeftAngleBracket": "⟨", "leftarrow": "←", "Leftarrow": "⇐", "LeftArrow": "←", "LeftArrowBar": "⇤", "LeftArrowRightArrow": "⇆", "leftarrowtail": "↢", "LeftCeiling": "⌈", "LeftDoubleBracket": "⟦", "LeftDownTeeVector": "⥡", "LeftDownVector": "⇃", "LeftDownVectorBar": "⥙", "LeftFloor": "⌊", "leftharpoondown": "↽", "leftharpoonup": "↼", "leftleftarrows": "⇇", "leftrightarrow": "↔", "Leftrightarrow": "⇔", "LeftRightArrow": "↔", "leftrightarrows": "⇆", "leftrightharpoons": "⇋", "leftrightsquigarrow": "↭", "LeftRightVector": "⥎", "LeftTee": "⊣", "LeftTeeArrow": "↤", "LeftTeeVector": "⥚", "leftthreetimes": "⋋", "LeftTriangle": "⊲", "LeftTriangleBar": "⧏", "LeftTriangleEqual": "⊴", "LeftUpDownVector": "⥑", "LeftUpTeeVector": "⥠", "LeftUpVector": "↿", "LeftUpVectorBar": "⥘", "LeftVector": "↼", "LeftVectorBar": "⥒", "leg": "⋚", "lEg": "⪋", "leq": "≤", "leqq": "≦", "leqslant": "⩽", "les": "⩽", "lescc": "⪨", "lesdot": "⩿", "lesdoto": "⪁", "lesdotor": "⪃", "lesg": "⋚︀", "lesges": "⪓", "lessapprox": "⪅", "lessdot": "⋖", "lesseqgtr": "⋚", "lesseqqgtr": "⪋", "LessEqualGreater": "⋚", "LessFullEqual": "≦", "LessGreater": "≶", "lessgtr": "≶", "LessLess": "⪡", "lesssim": "≲", "LessSlantEqual": "⩽", "LessTilde": "≲", "lfisht": "⥼", "lfloor": "⌊", "lfr": "𝔩", "Lfr": "𝔏", "lg": "≶", "lgE": "⪑", "lHar": "⥢", "lhard": "↽", "lharu": "↼", "lharul": "⥪", "lhblk": "▄", "ljcy": "љ", "LJcy": "Љ", "ll": "≪", "Ll": "⋘", "llarr": "⇇", "llcorner": "⌞", "Lleftarrow": "⇚", "llhard": "⥫", "lltri": "◺", "lmidot": "ŀ", "Lmidot": "Ŀ", "lmoust": "⎰", "lmoustache": "⎰", "lnap": "⪉", "lnapprox": "⪉", "lne": "⪇", "lnE": "≨", "lneq": "⪇", "lneqq": "≨", "lnsim": "⋦", "loang": "⟬", "loarr": "⇽", "lobrk": "⟦", "longleftarrow": "⟵", "Longleftarrow": "⟸", "LongLeftArrow": "⟵", "longleftrightarrow": "⟷", "Longleftrightarrow": "⟺", "LongLeftRightArrow": "⟷", "longmapsto": "⟼", "longrightarrow": "⟶", "Longrightarrow": "⟹", "LongRightArrow": "⟶", "looparrowleft": "↫", "looparrowright": "↬", "lopar": "⦅", "lopf": "𝕝", "Lopf": "𝕃", "loplus": "⨭", "lotimes": "⨴", "lowast": "∗", "lowbar": "_", "LowerLeftArrow": "↙", "LowerRightArrow": "↘", "loz": "◊", "lozenge": "◊", "lozf": "⧫", "lpar": "(", "lparlt": "⦓", "lrarr": "⇆", "lrcorner": "⌟", "lrhar": "⇋", "lrhard": "⥭", "lrm": "‎", "lrtri": "⊿", "lsaquo": "‹", "lscr": "𝓁", "Lscr": "ℒ", "lsh": "↰", "Lsh": "↰", "lsim": "≲", "lsime": "⪍", "lsimg": "⪏", "lsqb": "[", "lsquo": "‘", "lsquor": "‚", "lstrok": "ł", "Lstrok": "Ł", "lt": "<", "Lt": "≪", "LT": "<", "ltcc": "⪦", "ltcir": "⩹", "ltdot": "⋖", "lthree": "⋋", "ltimes": "⋉", "ltlarr": "⥶", "ltquest": "⩻", "ltri": "◃", "ltrie": "⊴", "ltrif": "◂", "ltrPar": "⦖", "lurdshar": "⥊", "luruhar": "⥦", "lvertneqq": "≨︀", "lvnE": "≨︀", "macr": "¯", "male": "♂", "malt": "✠", "maltese": "✠", "map": "↦", "Map": "⤅", "mapsto": "↦", "mapstodown": "↧", "mapstoleft": "↤", "mapstoup": "↥", "marker": "▮", "mcomma": "⨩", "mcy": "м", "Mcy": "М", "mdash": "—", "mDDot": "∺", "measuredangle": "∡", "MediumSpace": " ", "Mellintrf": "ℳ", "mfr": "𝔪", "Mfr": "𝔐", "mho": "℧", "micro": "µ", "mid": "∣", "midast": "*", "midcir": "⫰", "middot": "·", "minus": "−", "minusb": "⊟", "minusd": "∸", "minusdu": "⨪", "MinusPlus": "∓", "mlcp": "⫛", "mldr": "…", "mnplus": "∓", "models": "⊧", "mopf": "𝕞", "Mopf": "𝕄", "mp": "∓", "mscr": "𝓂", "Mscr": "ℳ", "mstpos": "∾", "mu": "μ", "Mu": "Μ", "multimap": "⊸", "mumap": "⊸", "nabla": "∇", "nacute": "ń", "Nacute": "Ń", "nang": "∠⃒", "nap": "≉", "napE": "⩰̸", "napid": "≋̸", "napos": "ŉ", "napprox": "≉", "natur": "♮", "natural": "♮", "naturals": "ℕ", "nbsp": " ", "nbump": "≎̸", "nbumpe": "≏̸", "ncap": "⩃", "ncaron": "ň", "Ncaron": "Ň", "ncedil": "ņ", "Ncedil": "Ņ", "ncong": "≇", "ncongdot": "⩭̸", "ncup": "⩂", "ncy": "н", "Ncy": "Н", "ndash": "–", "ne": "≠", "nearhk": "⤤", "nearr": "↗", "neArr": "⇗", "nearrow": "↗", "nedot": "≐̸", "NegativeMediumSpace": "​", "NegativeThickSpace": "​", "NegativeThinSpace": "​", "NegativeVeryThinSpace": "​", "nequiv": "≢", "nesear": "⤨", "nesim": "≂̸", "NestedGreaterGreater": "≫", "NestedLessLess": "≪", "NewLine": "\n", "nexist": "∄", "nexists": "∄", "nfr": "𝔫", "Nfr": "𝔑", "nge": "≱", "ngE": "≧̸", "ngeq": "≱", "ngeqq": "≧̸", "ngeqslant": "⩾̸", "nges": "⩾̸", "nGg": "⋙̸", "ngsim": "≵", "ngt": "≯", "nGt": "≫⃒", "ngtr": "≯", "nGtv": "≫̸", "nharr": "↮", "nhArr": "⇎", "nhpar": "⫲", "ni": "∋", "nis": "⋼", "nisd": "⋺", "niv": "∋", "njcy": "њ", "NJcy": "Њ", "nlarr": "↚", "nlArr": "⇍", "nldr": "‥", "nle": "≰", "nlE": "≦̸", "nleftarrow": "↚", "nLeftarrow": "⇍", "nleftrightarrow": "↮", "nLeftrightarrow": "⇎", "nleq": "≰", "nleqq": "≦̸", "nleqslant": "⩽̸", "nles": "⩽̸", "nless": "≮", "nLl": "⋘̸", "nlsim": "≴", "nlt": "≮", "nLt": "≪⃒", "nltri": "⋪", "nltrie": "⋬", "nLtv": "≪̸", "nmid": "∤", "NoBreak": "⁠", "NonBreakingSpace": " ", "nopf": "𝕟", "Nopf": "ℕ", "not": "¬", "Not": "⫬", "NotCongruent": "≢", "NotCupCap": "≭", "NotDoubleVerticalBar": "∦", "NotElement": "∉", "NotEqual": "≠", "NotEqualTilde": "≂̸", "NotExists": "∄", "NotGreater": "≯", "NotGreaterEqual": "≱", "NotGreaterFullEqual": "≧̸", "NotGreaterGreater": "≫̸", "NotGreaterLess": "≹", "NotGreaterSlantEqual": "⩾̸", "NotGreaterTilde": "≵", "NotHumpDownHump": "≎̸", "NotHumpEqual": "≏̸", "notin": "∉", "notindot": "⋵̸", "notinE": "⋹̸", "notinva": "∉", "notinvb": "⋷", "notinvc": "⋶", "NotLeftTriangle": "⋪", "NotLeftTriangleBar": "⧏̸", "NotLeftTriangleEqual": "⋬", "NotLess": "≮", "NotLessEqual": "≰", "NotLessGreater": "≸", "NotLessLess": "≪̸", "NotLessSlantEqual": "⩽̸", "NotLessTilde": "≴", "NotNestedGreaterGreater": "⪢̸", "NotNestedLessLess": "⪡̸", "notni": "∌", "notniva": "∌", "notnivb": "⋾", "notnivc": "⋽", "NotPrecedes": "⊀", "NotPrecedesEqual": "⪯̸", "NotPrecedesSlantEqual": "⋠", "NotReverseElement": "∌", "NotRightTriangle": "⋫", "NotRightTriangleBar": "⧐̸", "NotRightTriangleEqual": "⋭", "NotSquareSubset": "⊏̸", "NotSquareSubsetEqual": "⋢", "NotSquareSuperset": "⊐̸", "NotSquareSupersetEqual": "⋣", "NotSubset": "⊂⃒", "NotSubsetEqual": "⊈", "NotSucceeds": "⊁", "NotSucceedsEqual": "⪰̸", "NotSucceedsSlantEqual": "⋡", "NotSucceedsTilde": "≿̸", "NotSuperset": "⊃⃒", "NotSupersetEqual": "⊉", "NotTilde": "≁", "NotTildeEqual": "≄", "NotTildeFullEqual": "≇", "NotTildeTilde": "≉", "NotVerticalBar": "∤", "npar": "∦", "nparallel": "∦", "nparsl": "⫽⃥", "npart": "∂̸", "npolint": "⨔", "npr": "⊀", "nprcue": "⋠", "npre": "⪯̸", "nprec": "⊀", "npreceq": "⪯̸", "nrarr": "↛", "nrArr": "⇏", "nrarrc": "⤳̸", "nrarrw": "↝̸", "nrightarrow": "↛", "nRightarrow": "⇏", "nrtri": "⋫", "nrtrie": "⋭", "nsc": "⊁", "nsccue": "⋡", "nsce": "⪰̸", "nscr": "𝓃", "Nscr": "𝒩", "nshortmid": "∤", "nshortparallel": "∦", "nsim": "≁", "nsime": "≄", "nsimeq": "≄", "nsmid": "∤", "nspar": "∦", "nsqsube": "⋢", "nsqsupe": "⋣", "nsub": "⊄", "nsube": "⊈", "nsubE": "⫅̸", "nsubset": "⊂⃒", "nsubseteq": "⊈", "nsubseteqq": "⫅̸", "nsucc": "⊁", "nsucceq": "⪰̸", "nsup": "⊅", "nsupe": "⊉", "nsupE": "⫆̸", "nsupset": "⊃⃒", "nsupseteq": "⊉", "nsupseteqq": "⫆̸", "ntgl": "≹", "ntilde": "ñ", "Ntilde": "Ñ", "ntlg": "≸", "ntriangleleft": "⋪", "ntrianglelefteq": "⋬", "ntriangleright": "⋫", "ntrianglerighteq": "⋭", "nu": "ν", "Nu": "Ν", "num": "#", "numero": "№", "numsp": " ", "nvap": "≍⃒", "nvdash": "⊬", "nvDash": "⊭", "nVdash": "⊮", "nVDash": "⊯", "nvge": "≥⃒", "nvgt": ">⃒", "nvHarr": "⤄", "nvinfin": "⧞", "nvlArr": "⤂", "nvle": "≤⃒", "nvlt": "<⃒", "nvltrie": "⊴⃒", "nvrArr": "⤃", "nvrtrie": "⊵⃒", "nvsim": "∼⃒", "nwarhk": "⤣", "nwarr": "↖", "nwArr": "⇖", "nwarrow": "↖", "nwnear": "⤧", "oacute": "ó", "Oacute": "Ó", "oast": "⊛", "ocir": "⊚", "ocirc": "ô", "Ocirc": "Ô", "ocy": "о", "Ocy": "О", "odash": "⊝", "odblac": "ő", "Odblac": "Ő", "odiv": "⨸", "odot": "⊙", "odsold": "⦼", "oelig": "œ", "OElig": "Œ", "ofcir": "⦿", "ofr": "𝔬", "Ofr": "𝔒", "ogon": "˛", "ograve": "ò", "Ograve": "Ò", "ogt": "⧁", "ohbar": "⦵", "ohm": "Ω", "oint": "∮", "olarr": "↺", "olcir": "⦾", "olcross": "⦻", "oline": "‾", "olt": "⧀", "omacr": "ō", "Omacr": "Ō", "omega": "ω", "Omega": "Ω", "omicron": "ο", "Omicron": "Ο", "omid": "⦶", "ominus": "⊖", "oopf": "𝕠", "Oopf": "𝕆", "opar": "⦷", "OpenCurlyDoubleQuote": "“", "OpenCurlyQuote": "‘", "operp": "⦹", "oplus": "⊕", "or": "∨", "Or": "⩔", "orarr": "↻", "ord": "⩝", "order": "ℴ", "orderof": "ℴ", "ordf": "ª", "ordm": "º", "origof": "⊶", "oror": "⩖", "orslope": "⩗", "orv": "⩛", "oS": "Ⓢ", "oscr": "ℴ", "Oscr": "𝒪", "oslash": "ø", "Oslash": "Ø", "osol": "⊘", "otilde": "õ", "Otilde": "Õ", "otimes": "⊗", "Otimes": "⨷", "otimesas": "⨶", "ouml": "ö", "Ouml": "Ö", "ovbar": "⌽", "OverBar": "‾", "OverBrace": "⏞", "OverBracket": "⎴", "OverParenthesis": "⏜", "par": "∥", "para": "¶", "parallel": "∥", "parsim": "⫳", "parsl": "⫽", "part": "∂", "PartialD": "∂", "pcy": "п", "Pcy": "П", "percnt": "%", "period": ".", "permil": "‰", "perp": "⊥", "pertenk": "‱", "pfr": "𝔭", "Pfr": "𝔓", "phi": "φ", "Phi": "Φ", "phiv": "ϕ", "phmmat": "ℳ", "phone": "☎", "pi": "π", "Pi": "Π", "pitchfork": "⋔", "piv": "ϖ", "planck": "ℏ", "planckh": "ℎ", "plankv": "ℏ", "plus": "+", "plusacir": "⨣", "plusb": "⊞", "pluscir": "⨢", "plusdo": "∔", "plusdu": "⨥", "pluse": "⩲", "PlusMinus": "±", "plusmn": "±", "plussim": "⨦", "plustwo": "⨧", "pm": "±", "Poincareplane": "ℌ", "pointint": "⨕", "popf": "𝕡", "Popf": "ℙ", "pound": "£", "pr": "≺", "Pr": "⪻", "prap": "⪷", "prcue": "≼", "pre": "⪯", "prE": "⪳", "prec": "≺", "precapprox": "⪷", "preccurlyeq": "≼", "Precedes": "≺", "PrecedesEqual": "⪯", "PrecedesSlantEqual": "≼", "PrecedesTilde": "≾", "preceq": "⪯", "precnapprox": "⪹", "precneqq": "⪵", "precnsim": "⋨", "precsim": "≾", "prime": "′", "Prime": "″", "primes": "ℙ", "prnap": "⪹", "prnE": "⪵", "prnsim": "⋨", "prod": "∏", "Product": "∏", "profalar": "⌮", "profline": "⌒", "profsurf": "⌓", "prop": "∝", "Proportion": "∷", "Proportional": "∝", "propto": "∝", "prsim": "≾", "prurel": "⊰", "pscr": "𝓅", "Pscr": "𝒫", "psi": "ψ", "Psi": "Ψ", "puncsp": " ", "qfr": "𝔮", "Qfr": "𝔔", "qint": "⨌", "qopf": "𝕢", "Qopf": "ℚ", "qprime": "⁗", "qscr": "𝓆", "Qscr": "𝒬", "quaternions": "ℍ", "quatint": "⨖", "quest": "?", "questeq": "≟", "quot": '"', "QUOT": '"', "rAarr": "⇛", "race": "∽̱", "racute": "ŕ", "Racute": "Ŕ", "radic": "√", "raemptyv": "⦳", "rang": "⟩", "Rang": "⟫", "rangd": "⦒", "range": "⦥", "rangle": "⟩", "raquo": "»", "rarr": "→", "rArr": "⇒", "Rarr": "↠", "rarrap": "⥵", "rarrb": "⇥", "rarrbfs": "⤠", "rarrc": "⤳", "rarrfs": "⤞", "rarrhk": "↪", "rarrlp": "↬", "rarrpl": "⥅", "rarrsim": "⥴", "rarrtl": "↣", "Rarrtl": "⤖", "rarrw": "↝", "ratail": "⤚", "rAtail": "⤜", "ratio": "∶", "rationals": "ℚ", "rbarr": "⤍", "rBarr": "⤏", "RBarr": "⤐", "rbbrk": "❳", "rbrace": "}", "rbrack": "]", "rbrke": "⦌", "rbrksld": "⦎", "rbrkslu": "⦐", "rcaron": "ř", "Rcaron": "Ř", "rcedil": "ŗ", "Rcedil": "Ŗ", "rceil": "⌉", "rcub": "}", "rcy": "р", "Rcy": "Р", "rdca": "⤷", "rdldhar": "⥩", "rdquo": "”", "rdquor": "”", "rdsh": "↳", "Re": "ℜ", "real": "ℜ", "realine": "ℛ", "realpart": "ℜ", "reals": "ℝ", "rect": "▭", "reg": "®", "REG": "®", "ReverseElement": "∋", "ReverseEquilibrium": "⇋", "ReverseUpEquilibrium": "⥯", "rfisht": "⥽", "rfloor": "⌋", "rfr": "𝔯", "Rfr": "ℜ", "rHar": "⥤", "rhard": "⇁", "rharu": "⇀", "rharul": "⥬", "rho": "ρ", "Rho": "Ρ", "rhov": "ϱ", "RightAngleBracket": "⟩", "rightarrow": "→", "Rightarrow": "⇒", "RightArrow": "→", "RightArrowBar": "⇥", "RightArrowLeftArrow": "⇄", "rightarrowtail": "↣", "RightCeiling": "⌉", "RightDoubleBracket": "⟧", "RightDownTeeVector": "⥝", "RightDownVector": "⇂", "RightDownVectorBar": "⥕", "RightFloor": "⌋", "rightharpoondown": "⇁", "rightharpoonup": "⇀", "rightleftarrows": "⇄", "rightleftharpoons": "⇌", "rightrightarrows": "⇉", "rightsquigarrow": "↝", "RightTee": "⊢", "RightTeeArrow": "↦", "RightTeeVector": "⥛", "rightthreetimes": "⋌", "RightTriangle": "⊳", "RightTriangleBar": "⧐", "RightTriangleEqual": "⊵", "RightUpDownVector": "⥏", "RightUpTeeVector": "⥜", "RightUpVector": "↾", "RightUpVectorBar": "⥔", "RightVector": "⇀", "RightVectorBar": "⥓", "ring": "˚", "risingdotseq": "≓", "rlarr": "⇄", "rlhar": "⇌", "rlm": "‏", "rmoust": "⎱", "rmoustache": "⎱", "rnmid": "⫮", "roang": "⟭", "roarr": "⇾", "robrk": "⟧", "ropar": "⦆", "ropf": "𝕣", "Ropf": "ℝ", "roplus": "⨮", "rotimes": "⨵", "RoundImplies": "⥰", "rpar": ")", "rpargt": "⦔", "rppolint": "⨒", "rrarr": "⇉", "Rrightarrow": "⇛", "rsaquo": "›", "rscr": "𝓇", "Rscr": "ℛ", "rsh": "↱", "Rsh": "↱", "rsqb": "]", "rsquo": "’", "rsquor": "’", "rthree": "⋌", "rtimes": "⋊", "rtri": "▹", "rtrie": "⊵", "rtrif": "▸", "rtriltri": "⧎", "RuleDelayed": "⧴", "ruluhar": "⥨", "rx": "℞", "sacute": "ś", "Sacute": "Ś", "sbquo": "‚", "sc": "≻", "Sc": "⪼", "scap": "⪸", "scaron": "š", "Scaron": "Š", "sccue": "≽", "sce": "⪰", "scE": "⪴", "scedil": "ş", "Scedil": "Ş", "scirc": "ŝ", "Scirc": "Ŝ", "scnap": "⪺", "scnE": "⪶", "scnsim": "⋩", "scpolint": "⨓", "scsim": "≿", "scy": "с", "Scy": "С", "sdot": "⋅", "sdotb": "⊡", "sdote": "⩦", "searhk": "⤥", "searr": "↘", "seArr": "⇘", "searrow": "↘", "sect": "§", "semi": ";", "seswar": "⤩", "setminus": "∖", "setmn": "∖", "sext": "✶", "sfr": "𝔰", "Sfr": "𝔖", "sfrown": "⌢", "sharp": "♯", "shchcy": "щ", "SHCHcy": "Щ", "shcy": "ш", "SHcy": "Ш", "ShortDownArrow": "↓", "ShortLeftArrow": "←", "shortmid": "∣", "shortparallel": "∥", "ShortRightArrow": "→", "ShortUpArrow": "↑", "shy": "­", "sigma": "σ", "Sigma": "Σ", "sigmaf": "ς", "sigmav": "ς", "sim": "∼", "simdot": "⩪", "sime": "≃", "simeq": "≃", "simg": "⪞", "simgE": "⪠", "siml": "⪝", "simlE": "⪟", "simne": "≆", "simplus": "⨤", "simrarr": "⥲", "slarr": "←", "SmallCircle": "∘", "smallsetminus": "∖", "smashp": "⨳", "smeparsl": "⧤", "smid": "∣", "smile": "⌣", "smt": "⪪", "smte": "⪬", "smtes": "⪬︀", "softcy": "ь", "SOFTcy": "Ь", "sol": "/", "solb": "⧄", "solbar": "⌿", "sopf": "𝕤", "Sopf": "𝕊", "spades": "♠", "spadesuit": "♠", "spar": "∥", "sqcap": "⊓", "sqcaps": "⊓︀", "sqcup": "⊔", "sqcups": "⊔︀", "Sqrt": "√", "sqsub": "⊏", "sqsube": "⊑", "sqsubset": "⊏", "sqsubseteq": "⊑", "sqsup": "⊐", "sqsupe": "⊒", "sqsupset": "⊐", "sqsupseteq": "⊒", "squ": "□", "square": "□", "Square": "□", "SquareIntersection": "⊓", "SquareSubset": "⊏", "SquareSubsetEqual": "⊑", "SquareSuperset": "⊐", "SquareSupersetEqual": "⊒", "SquareUnion": "⊔", "squarf": "▪", "squf": "▪", "srarr": "→", "sscr": "𝓈", "Sscr": "𝒮", "ssetmn": "∖", "ssmile": "⌣", "sstarf": "⋆", "star": "☆", "Star": "⋆", "starf": "★", "straightepsilon": "ϵ", "straightphi": "ϕ", "strns": "¯", "sub": "⊂", "Sub": "⋐", "subdot": "⪽", "sube": "⊆", "subE": "⫅", "subedot": "⫃", "submult": "⫁", "subne": "⊊", "subnE": "⫋", "subplus": "⪿", "subrarr": "⥹", "subset": "⊂", "Subset": "⋐", "subseteq": "⊆", "subseteqq": "⫅", "SubsetEqual": "⊆", "subsetneq": "⊊", "subsetneqq": "⫋", "subsim": "⫇", "subsub": "⫕", "subsup": "⫓", "succ": "≻", "succapprox": "⪸", "succcurlyeq": "≽", "Succeeds": "≻", "SucceedsEqual": "⪰", "SucceedsSlantEqual": "≽", "SucceedsTilde": "≿", "succeq": "⪰", "succnapprox": "⪺", "succneqq": "⪶", "succnsim": "⋩", "succsim": "≿", "SuchThat": "∋", "sum": "∑", "Sum": "∑", "sung": "♪", "sup": "⊃", "Sup": "⋑", "sup1": "¹", "sup2": "²", "sup3": "³", "supdot": "⪾", "supdsub": "⫘", "supe": "⊇", "supE": "⫆", "supedot": "⫄", "Superset": "⊃", "SupersetEqual": "⊇", "suphsol": "⟉", "suphsub": "⫗", "suplarr": "⥻", "supmult": "⫂", "supne": "⊋", "supnE": "⫌", "supplus": "⫀", "supset": "⊃", "Supset": "⋑", "supseteq": "⊇", "supseteqq": "⫆", "supsetneq": "⊋", "supsetneqq": "⫌", "supsim": "⫈", "supsub": "⫔", "supsup": "⫖", "swarhk": "⤦", "swarr": "↙", "swArr": "⇙", "swarrow": "↙", "swnwar": "⤪", "szlig": "ß", "Tab": "	", "target": "⌖", "tau": "τ", "Tau": "Τ", "tbrk": "⎴", "tcaron": "ť", "Tcaron": "Ť", "tcedil": "ţ", "Tcedil": "Ţ", "tcy": "т", "Tcy": "Т", "tdot": "⃛", "telrec": "⌕", "tfr": "𝔱", "Tfr": "𝔗", "there4": "∴", "therefore": "∴", "Therefore": "∴", "theta": "θ", "Theta": "Θ", "thetasym": "ϑ", "thetav": "ϑ", "thickapprox": "≈", "thicksim": "∼", "ThickSpace": "  ", "thinsp": " ", "ThinSpace": " ", "thkap": "≈", "thksim": "∼", "thorn": "þ", "THORN": "Þ", "tilde": "˜", "Tilde": "∼", "TildeEqual": "≃", "TildeFullEqual": "≅", "TildeTilde": "≈", "times": "×", "timesb": "⊠", "timesbar": "⨱", "timesd": "⨰", "tint": "∭", "toea": "⤨", "top": "⊤", "topbot": "⌶", "topcir": "⫱", "topf": "𝕥", "Topf": "𝕋", "topfork": "⫚", "tosa": "⤩", "tprime": "‴", "trade": "™", "TRADE": "™", "triangle": "▵", "triangledown": "▿", "triangleleft": "◃", "trianglelefteq": "⊴", "triangleq": "≜", "triangleright": "▹", "trianglerighteq": "⊵", "tridot": "◬", "trie": "≜", "triminus": "⨺", "TripleDot": "⃛", "triplus": "⨹", "trisb": "⧍", "tritime": "⨻", "trpezium": "⏢", "tscr": "𝓉", "Tscr": "𝒯", "tscy": "ц", "TScy": "Ц", "tshcy": "ћ", "TSHcy": "Ћ", "tstrok": "ŧ", "Tstrok": "Ŧ", "twixt": "≬", "twoheadleftarrow": "↞", "twoheadrightarrow": "↠", "uacute": "ú", "Uacute": "Ú", "uarr": "↑", "uArr": "⇑", "Uarr": "↟", "Uarrocir": "⥉", "ubrcy": "ў", "Ubrcy": "Ў", "ubreve": "ŭ", "Ubreve": "Ŭ", "ucirc": "û", "Ucirc": "Û", "ucy": "у", "Ucy": "У", "udarr": "⇅", "udblac": "ű", "Udblac": "Ű", "udhar": "⥮", "ufisht": "⥾", "ufr": "𝔲", "Ufr": "𝔘", "ugrave": "ù", "Ugrave": "Ù", "uHar": "⥣", "uharl": "↿", "uharr": "↾", "uhblk": "▀", "ulcorn": "⌜", "ulcorner": "⌜", "ulcrop": "⌏", "ultri": "◸", "umacr": "ū", "Umacr": "Ū", "uml": "¨", "UnderBar": "_", "UnderBrace": "⏟", "UnderBracket": "⎵", "UnderParenthesis": "⏝", "Union": "⋃", "UnionPlus": "⊎", "uogon": "ų", "Uogon": "Ų", "uopf": "𝕦", "Uopf": "𝕌", "uparrow": "↑", "Uparrow": "⇑", "UpArrow": "↑", "UpArrowBar": "⤒", "UpArrowDownArrow": "⇅", "updownarrow": "↕", "Updownarrow": "⇕", "UpDownArrow": "↕", "UpEquilibrium": "⥮", "upharpoonleft": "↿", "upharpoonright": "↾", "uplus": "⊎", "UpperLeftArrow": "↖", "UpperRightArrow": "↗", "upsi": "υ", "Upsi": "ϒ", "upsih": "ϒ", "upsilon": "υ", "Upsilon": "Υ", "UpTee": "⊥", "UpTeeArrow": "↥", "upuparrows": "⇈", "urcorn": "⌝", "urcorner": "⌝", "urcrop": "⌎", "uring": "ů", "Uring": "Ů", "urtri": "◹", "uscr": "𝓊", "Uscr": "𝒰", "utdot": "⋰", "utilde": "ũ", "Utilde": "Ũ", "utri": "▵", "utrif": "▴", "uuarr": "⇈", "uuml": "ü", "Uuml": "Ü", "uwangle": "⦧", "vangrt": "⦜", "varepsilon": "ϵ", "varkappa": "ϰ", "varnothing": "∅", "varphi": "ϕ", "varpi": "ϖ", "varpropto": "∝", "varr": "↕", "vArr": "⇕", "varrho": "ϱ", "varsigma": "ς", "varsubsetneq": "⊊︀", "varsubsetneqq": "⫋︀", "varsupsetneq": "⊋︀", "varsupsetneqq": "⫌︀", "vartheta": "ϑ", "vartriangleleft": "⊲", "vartriangleright": "⊳", "vBar": "⫨", "Vbar": "⫫", "vBarv": "⫩", "vcy": "в", "Vcy": "В", "vdash": "⊢", "vDash": "⊨", "Vdash": "⊩", "VDash": "⊫", "Vdashl": "⫦", "vee": "∨", "Vee": "⋁", "veebar": "⊻", "veeeq": "≚", "vellip": "⋮", "verbar": "|", "Verbar": "‖", "vert": "|", "Vert": "‖", "VerticalBar": "∣", "VerticalLine": "|", "VerticalSeparator": "❘", "VerticalTilde": "≀", "VeryThinSpace": " ", "vfr": "𝔳", "Vfr": "𝔙", "vltri": "⊲", "vnsub": "⊂⃒", "vnsup": "⊃⃒", "vopf": "𝕧", "Vopf": "𝕍", "vprop": "∝", "vrtri": "⊳", "vscr": "𝓋", "Vscr": "𝒱", "vsubne": "⊊︀", "vsubnE": "⫋︀", "vsupne": "⊋︀", "vsupnE": "⫌︀", "Vvdash": "⊪", "vzigzag": "⦚", "wcirc": "ŵ", "Wcirc": "Ŵ", "wedbar": "⩟", "wedge": "∧", "Wedge": "⋀", "wedgeq": "≙", "weierp": "℘", "wfr": "𝔴", "Wfr": "𝔚", "wopf": "𝕨", "Wopf": "𝕎", "wp": "℘", "wr": "≀", "wreath": "≀", "wscr": "𝓌", "Wscr": "𝒲", "xcap": "⋂", "xcirc": "◯", "xcup": "⋃", "xdtri": "▽", "xfr": "𝔵", "Xfr": "𝔛", "xharr": "⟷", "xhArr": "⟺", "xi": "ξ", "Xi": "Ξ", "xlarr": "⟵", "xlArr": "⟸", "xmap": "⟼", "xnis": "⋻", "xodot": "⨀", "xopf": "𝕩", "Xopf": "𝕏", "xoplus": "⨁", "xotime": "⨂", "xrarr": "⟶", "xrArr": "⟹", "xscr": "𝓍", "Xscr": "𝒳", "xsqcup": "⨆", "xuplus": "⨄", "xutri": "△", "xvee": "⋁", "xwedge": "⋀", "yacute": "ý", "Yacute": "Ý", "yacy": "я", "YAcy": "Я", "ycirc": "ŷ", "Ycirc": "Ŷ", "ycy": "ы", "Ycy": "Ы", "yen": "¥", "yfr": "𝔶", "Yfr": "𝔜", "yicy": "ї", "YIcy": "Ї", "yopf": "𝕪", "Yopf": "𝕐", "yscr": "𝓎", "Yscr": "𝒴", "yucy": "ю", "YUcy": "Ю", "yuml": "ÿ", "Yuml": "Ÿ", "zacute": "ź", "Zacute": "Ź", "zcaron": "ž", "Zcaron": "Ž", "zcy": "з", "Zcy": "З", "zdot": "ż", "Zdot": "Ż", "zeetrf": "ℨ", "ZeroWidthSpace": "​", "zeta": "ζ", "Zeta": "Ζ", "zfr": "𝔷", "Zfr": "ℨ", "zhcy": "ж", "ZHcy": "Ж", "zigrarr": "⇝", "zopf": "𝕫", "Zopf": "ℤ", "zscr": "𝓏", "Zscr": "𝒵", "zwj": "‍", "zwnj": "‌" };
        var decodeMapLegacy = { "aacute": "á", "Aacute": "Á", "acirc": "â", "Acirc": "Â", "acute": "´", "aelig": "æ", "AElig": "Æ", "agrave": "à", "Agrave": "À", "amp": "&", "AMP": "&", "aring": "å", "Aring": "Å", "atilde": "ã", "Atilde": "Ã", "auml": "ä", "Auml": "Ä", "brvbar": "¦", "ccedil": "ç", "Ccedil": "Ç", "cedil": "¸", "cent": "¢", "copy": "©", "COPY": "©", "curren": "¤", "deg": "°", "divide": "÷", "eacute": "é", "Eacute": "É", "ecirc": "ê", "Ecirc": "Ê", "egrave": "è", "Egrave": "È", "eth": "ð", "ETH": "Ð", "euml": "ë", "Euml": "Ë", "frac12": "½", "frac14": "¼", "frac34": "¾", "gt": ">", "GT": ">", "iacute": "í", "Iacute": "Í", "icirc": "î", "Icirc": "Î", "iexcl": "¡", "igrave": "ì", "Igrave": "Ì", "iquest": "¿", "iuml": "ï", "Iuml": "Ï", "laquo": "«", "lt": "<", "LT": "<", "macr": "¯", "micro": "µ", "middot": "·", "nbsp": " ", "not": "¬", "ntilde": "ñ", "Ntilde": "Ñ", "oacute": "ó", "Oacute": "Ó", "ocirc": "ô", "Ocirc": "Ô", "ograve": "ò", "Ograve": "Ò", "ordf": "ª", "ordm": "º", "oslash": "ø", "Oslash": "Ø", "otilde": "õ", "Otilde": "Õ", "ouml": "ö", "Ouml": "Ö", "para": "¶", "plusmn": "±", "pound": "£", "quot": '"', "QUOT": '"', "raquo": "»", "reg": "®", "REG": "®", "sect": "§", "shy": "­", "sup1": "¹", "sup2": "²", "sup3": "³", "szlig": "ß", "thorn": "þ", "THORN": "Þ", "times": "×", "uacute": "ú", "Uacute": "Ú", "ucirc": "û", "Ucirc": "Û", "ugrave": "ù", "Ugrave": "Ù", "uml": "¨", "uuml": "ü", "Uuml": "Ü", "yacute": "ý", "Yacute": "Ý", "yen": "¥", "yuml": "ÿ" };
        var decodeMapNumeric = { "0": "�", "128": "€", "130": "‚", "131": "ƒ", "132": "„", "133": "…", "134": "†", "135": "‡", "136": "ˆ", "137": "‰", "138": "Š", "139": "‹", "140": "Œ", "142": "Ž", "145": "‘", "146": "’", "147": "“", "148": "”", "149": "•", "150": "–", "151": "—", "152": "˜", "153": "™", "154": "š", "155": "›", "156": "œ", "158": "ž", "159": "Ÿ" };
        var invalidReferenceCodePoints = [1, 2, 3, 4, 5, 6, 7, 8, 11, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 127, 128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 152, 153, 154, 155, 156, 157, 158, 159, 64976, 64977, 64978, 64979, 64980, 64981, 64982, 64983, 64984, 64985, 64986, 64987, 64988, 64989, 64990, 64991, 64992, 64993, 64994, 64995, 64996, 64997, 64998, 64999, 65e3, 65001, 65002, 65003, 65004, 65005, 65006, 65007, 65534, 65535, 131070, 131071, 196606, 196607, 262142, 262143, 327678, 327679, 393214, 393215, 458750, 458751, 524286, 524287, 589822, 589823, 655358, 655359, 720894, 720895, 786430, 786431, 851966, 851967, 917502, 917503, 983038, 983039, 1048574, 1048575, 1114110, 1114111];
        var stringFromCharCode = String.fromCharCode;
        var object = {};
        var hasOwnProperty = object.hasOwnProperty;
        var has = function(object2, propertyName) {
          return hasOwnProperty.call(object2, propertyName);
        };
        var contains = function(array, value) {
          var index = -1;
          var length = array.length;
          while (++index < length) {
            if (array[index] == value) {
              return true;
            }
          }
          return false;
        };
        var merge = function(options, defaults) {
          if (!options) {
            return defaults;
          }
          var result = {};
          var key2;
          for (key2 in defaults) {
            result[key2] = has(options, key2) ? options[key2] : defaults[key2];
          }
          return result;
        };
        var codePointToSymbol = function(codePoint, strict) {
          var output = "";
          if (codePoint >= 55296 && codePoint <= 57343 || codePoint > 1114111) {
            if (strict) {
              parseError("character reference outside the permissible Unicode range");
            }
            return "�";
          }
          if (has(decodeMapNumeric, codePoint)) {
            if (strict) {
              parseError("disallowed character reference");
            }
            return decodeMapNumeric[codePoint];
          }
          if (strict && contains(invalidReferenceCodePoints, codePoint)) {
            parseError("disallowed character reference");
          }
          if (codePoint > 65535) {
            codePoint -= 65536;
            output += stringFromCharCode(codePoint >>> 10 & 1023 | 55296);
            codePoint = 56320 | codePoint & 1023;
          }
          output += stringFromCharCode(codePoint);
          return output;
        };
        var hexEscape = function(codePoint) {
          return "&#x" + codePoint.toString(16).toUpperCase() + ";";
        };
        var decEscape = function(codePoint) {
          return "&#" + codePoint + ";";
        };
        var parseError = function(message) {
          throw Error("Parse error: " + message);
        };
        var encode = function(string, options) {
          options = merge(options, encode.options);
          var strict = options.strict;
          if (strict && regexInvalidRawCodePoint.test(string)) {
            parseError("forbidden code point");
          }
          var encodeEverything = options.encodeEverything;
          var useNamedReferences = options.useNamedReferences;
          var allowUnsafeSymbols = options.allowUnsafeSymbols;
          var escapeCodePoint = options.decimal ? decEscape : hexEscape;
          var escapeBmpSymbol = function(symbol) {
            return escapeCodePoint(symbol.charCodeAt(0));
          };
          if (encodeEverything) {
            string = string.replace(regexAsciiWhitelist, function(symbol) {
              if (useNamedReferences && has(encodeMap, symbol)) {
                return "&" + encodeMap[symbol] + ";";
              }
              return escapeBmpSymbol(symbol);
            });
            if (useNamedReferences) {
              string = string.replace(/&gt;\u20D2/g, "&nvgt;").replace(/&lt;\u20D2/g, "&nvlt;").replace(/&#x66;&#x6A;/g, "&fjlig;");
            }
            if (useNamedReferences) {
              string = string.replace(regexEncodeNonAscii, function(string2) {
                return "&" + encodeMap[string2] + ";";
              });
            }
          } else if (useNamedReferences) {
            if (!allowUnsafeSymbols) {
              string = string.replace(regexEscape, function(string2) {
                return "&" + encodeMap[string2] + ";";
              });
            }
            string = string.replace(/&gt;\u20D2/g, "&nvgt;").replace(/&lt;\u20D2/g, "&nvlt;");
            string = string.replace(regexEncodeNonAscii, function(string2) {
              return "&" + encodeMap[string2] + ";";
            });
          } else if (!allowUnsafeSymbols) {
            string = string.replace(regexEscape, escapeBmpSymbol);
          }
          return string.replace(regexAstralSymbols, function($0) {
            var high = $0.charCodeAt(0);
            var low = $0.charCodeAt(1);
            var codePoint = (high - 55296) * 1024 + low - 56320 + 65536;
            return escapeCodePoint(codePoint);
          }).replace(regexBmpWhitelist, escapeBmpSymbol);
        };
        encode.options = {
          "allowUnsafeSymbols": false,
          "encodeEverything": false,
          "strict": false,
          "useNamedReferences": false,
          "decimal": false
        };
        var decode = function(html, options) {
          options = merge(options, decode.options);
          var strict = options.strict;
          if (strict && regexInvalidEntity.test(html)) {
            parseError("malformed character reference");
          }
          return html.replace(regexDecode, function($0, $1, $2, $3, $4, $5, $6, $7, $8) {
            var codePoint;
            var semicolon;
            var decDigits;
            var hexDigits;
            var reference;
            var next;
            if ($1) {
              reference = $1;
              return decodeMap[reference];
            }
            if ($2) {
              reference = $2;
              next = $3;
              if (next && options.isAttributeValue) {
                if (strict && next == "=") {
                  parseError("`&` did not start a character reference");
                }
                return $0;
              } else {
                if (strict) {
                  parseError(
                    "named character reference was not terminated by a semicolon"
                  );
                }
                return decodeMapLegacy[reference] + (next || "");
              }
            }
            if ($4) {
              decDigits = $4;
              semicolon = $5;
              if (strict && !semicolon) {
                parseError("character reference was not terminated by a semicolon");
              }
              codePoint = parseInt(decDigits, 10);
              return codePointToSymbol(codePoint, strict);
            }
            if ($6) {
              hexDigits = $6;
              semicolon = $7;
              if (strict && !semicolon) {
                parseError("character reference was not terminated by a semicolon");
              }
              codePoint = parseInt(hexDigits, 16);
              return codePointToSymbol(codePoint, strict);
            }
            if (strict) {
              parseError(
                "named character reference was not terminated by a semicolon"
              );
            }
            return $0;
          });
        };
        decode.options = {
          "isAttributeValue": false,
          "strict": false
        };
        var escape2 = function(string) {
          return string.replace(regexEscape, function($0) {
            return escapeMap[$0];
          });
        };
        var he2 = {
          "version": "1.2.0",
          "encode": encode,
          "decode": decode,
          "escape": escape2,
          "unescape": decode
        };
        if (typeof define == "function" && typeof define.amd == "object" && define.amd) {
          define(function() {
            return he2;
          });
        } else if (freeExports && !freeExports.nodeType) {
          if (freeModule) {
            freeModule.exports = he2;
          } else {
            for (var key in he2) {
              has(he2, key) && (freeExports[key] = he2[key]);
            }
          }
        } else {
          root.he = he2;
        }
      })(exports);
    }
  });

  // node_modules/pako/lib/zlib/trees.js
  var require_trees = __commonJS({
    "node_modules/pako/lib/zlib/trees.js"(exports, module) {
      "use strict";
      var Z_FIXED2 = 4;
      var Z_BINARY2 = 0;
      var Z_TEXT2 = 1;
      var Z_UNKNOWN2 = 2;
      function zero2(buf) {
        let len = buf.length;
        while (--len >= 0) {
          buf[len] = 0;
        }
      }
      var STORED_BLOCK2 = 0;
      var STATIC_TREES2 = 1;
      var DYN_TREES2 = 2;
      var MIN_MATCH2 = 3;
      var MAX_MATCH2 = 258;
      var LENGTH_CODES2 = 29;
      var LITERALS2 = 256;
      var L_CODES2 = LITERALS2 + 1 + LENGTH_CODES2;
      var D_CODES2 = 30;
      var BL_CODES2 = 19;
      var HEAP_SIZE2 = 2 * L_CODES2 + 1;
      var MAX_BITS2 = 15;
      var Buf_size2 = 16;
      var MAX_BL_BITS2 = 7;
      var END_BLOCK2 = 256;
      var REP_3_62 = 16;
      var REPZ_3_102 = 17;
      var REPZ_11_1382 = 18;
      var extra_lbits2 = (
        /* extra bits for each length code */
        new Uint8Array([0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0])
      );
      var extra_dbits2 = (
        /* extra bits for each distance code */
        new Uint8Array([0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13])
      );
      var extra_blbits2 = (
        /* extra bits for each bit length code */
        new Uint8Array([0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 3, 7])
      );
      var bl_order2 = new Uint8Array([16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15]);
      var DIST_CODE_LEN2 = 512;
      var static_ltree2 = new Array((L_CODES2 + 2) * 2);
      zero2(static_ltree2);
      var static_dtree2 = new Array(D_CODES2 * 2);
      zero2(static_dtree2);
      var _dist_code2 = new Array(DIST_CODE_LEN2);
      zero2(_dist_code2);
      var _length_code2 = new Array(MAX_MATCH2 - MIN_MATCH2 + 1);
      zero2(_length_code2);
      var base_length2 = new Array(LENGTH_CODES2);
      zero2(base_length2);
      var base_dist2 = new Array(D_CODES2);
      zero2(base_dist2);
      function StaticTreeDesc2(static_tree, extra_bits, extra_base, elems, max_length) {
        this.static_tree = static_tree;
        this.extra_bits = extra_bits;
        this.extra_base = extra_base;
        this.elems = elems;
        this.max_length = max_length;
        this.has_stree = static_tree && static_tree.length;
      }
      var static_l_desc2;
      var static_d_desc2;
      var static_bl_desc2;
      function TreeDesc2(dyn_tree, stat_desc) {
        this.dyn_tree = dyn_tree;
        this.max_code = 0;
        this.stat_desc = stat_desc;
      }
      var d_code2 = (dist) => {
        return dist < 256 ? _dist_code2[dist] : _dist_code2[256 + (dist >>> 7)];
      };
      var put_short2 = (s, w) => {
        s.pending_buf[s.pending++] = w & 255;
        s.pending_buf[s.pending++] = w >>> 8 & 255;
      };
      var send_bits2 = (s, value, length) => {
        if (s.bi_valid > Buf_size2 - length) {
          s.bi_buf |= value << s.bi_valid & 65535;
          put_short2(s, s.bi_buf);
          s.bi_buf = value >> Buf_size2 - s.bi_valid;
          s.bi_valid += length - Buf_size2;
        } else {
          s.bi_buf |= value << s.bi_valid & 65535;
          s.bi_valid += length;
        }
      };
      var send_code2 = (s, c, tree) => {
        send_bits2(
          s,
          tree[c * 2],
          tree[c * 2 + 1]
          /*.Len*/
        );
      };
      var bi_reverse2 = (code, len) => {
        let res = 0;
        do {
          res |= code & 1;
          code >>>= 1;
          res <<= 1;
        } while (--len > 0);
        return res >>> 1;
      };
      var bi_flush2 = (s) => {
        if (s.bi_valid === 16) {
          put_short2(s, s.bi_buf);
          s.bi_buf = 0;
          s.bi_valid = 0;
        } else if (s.bi_valid >= 8) {
          s.pending_buf[s.pending++] = s.bi_buf & 255;
          s.bi_buf >>= 8;
          s.bi_valid -= 8;
        }
      };
      var gen_bitlen2 = (s, desc) => {
        const tree = desc.dyn_tree;
        const max_code = desc.max_code;
        const stree = desc.stat_desc.static_tree;
        const has_stree = desc.stat_desc.has_stree;
        const extra = desc.stat_desc.extra_bits;
        const base = desc.stat_desc.extra_base;
        const max_length = desc.stat_desc.max_length;
        let h;
        let n, m;
        let bits;
        let xbits;
        let f;
        let overflow = 0;
        for (bits = 0; bits <= MAX_BITS2; bits++) {
          s.bl_count[bits] = 0;
        }
        tree[s.heap[s.heap_max] * 2 + 1] = 0;
        for (h = s.heap_max + 1; h < HEAP_SIZE2; h++) {
          n = s.heap[h];
          bits = tree[tree[n * 2 + 1] * 2 + 1] + 1;
          if (bits > max_length) {
            bits = max_length;
            overflow++;
          }
          tree[n * 2 + 1] = bits;
          if (n > max_code) {
            continue;
          }
          s.bl_count[bits]++;
          xbits = 0;
          if (n >= base) {
            xbits = extra[n - base];
          }
          f = tree[n * 2];
          s.opt_len += f * (bits + xbits);
          if (has_stree) {
            s.static_len += f * (stree[n * 2 + 1] + xbits);
          }
        }
        if (overflow === 0) {
          return;
        }
        do {
          bits = max_length - 1;
          while (s.bl_count[bits] === 0) {
            bits--;
          }
          s.bl_count[bits]--;
          s.bl_count[bits + 1] += 2;
          s.bl_count[max_length]--;
          overflow -= 2;
        } while (overflow > 0);
        for (bits = max_length; bits !== 0; bits--) {
          n = s.bl_count[bits];
          while (n !== 0) {
            m = s.heap[--h];
            if (m > max_code) {
              continue;
            }
            if (tree[m * 2 + 1] !== bits) {
              s.opt_len += (bits - tree[m * 2 + 1]) * tree[m * 2];
              tree[m * 2 + 1] = bits;
            }
            n--;
          }
        }
      };
      var gen_codes2 = (tree, max_code, bl_count) => {
        const next_code = new Array(MAX_BITS2 + 1);
        let code = 0;
        let bits;
        let n;
        for (bits = 1; bits <= MAX_BITS2; bits++) {
          code = code + bl_count[bits - 1] << 1;
          next_code[bits] = code;
        }
        for (n = 0; n <= max_code; n++) {
          let len = tree[n * 2 + 1];
          if (len === 0) {
            continue;
          }
          tree[n * 2] = bi_reverse2(next_code[len]++, len);
        }
      };
      var tr_static_init2 = () => {
        let n;
        let bits;
        let length;
        let code;
        let dist;
        const bl_count = new Array(MAX_BITS2 + 1);
        length = 0;
        for (code = 0; code < LENGTH_CODES2 - 1; code++) {
          base_length2[code] = length;
          for (n = 0; n < 1 << extra_lbits2[code]; n++) {
            _length_code2[length++] = code;
          }
        }
        _length_code2[length - 1] = code;
        dist = 0;
        for (code = 0; code < 16; code++) {
          base_dist2[code] = dist;
          for (n = 0; n < 1 << extra_dbits2[code]; n++) {
            _dist_code2[dist++] = code;
          }
        }
        dist >>= 7;
        for (; code < D_CODES2; code++) {
          base_dist2[code] = dist << 7;
          for (n = 0; n < 1 << extra_dbits2[code] - 7; n++) {
            _dist_code2[256 + dist++] = code;
          }
        }
        for (bits = 0; bits <= MAX_BITS2; bits++) {
          bl_count[bits] = 0;
        }
        n = 0;
        while (n <= 143) {
          static_ltree2[n * 2 + 1] = 8;
          n++;
          bl_count[8]++;
        }
        while (n <= 255) {
          static_ltree2[n * 2 + 1] = 9;
          n++;
          bl_count[9]++;
        }
        while (n <= 279) {
          static_ltree2[n * 2 + 1] = 7;
          n++;
          bl_count[7]++;
        }
        while (n <= 287) {
          static_ltree2[n * 2 + 1] = 8;
          n++;
          bl_count[8]++;
        }
        gen_codes2(static_ltree2, L_CODES2 + 1, bl_count);
        for (n = 0; n < D_CODES2; n++) {
          static_dtree2[n * 2 + 1] = 5;
          static_dtree2[n * 2] = bi_reverse2(n, 5);
        }
        static_l_desc2 = new StaticTreeDesc2(static_ltree2, extra_lbits2, LITERALS2 + 1, L_CODES2, MAX_BITS2);
        static_d_desc2 = new StaticTreeDesc2(static_dtree2, extra_dbits2, 0, D_CODES2, MAX_BITS2);
        static_bl_desc2 = new StaticTreeDesc2(new Array(0), extra_blbits2, 0, BL_CODES2, MAX_BL_BITS2);
      };
      var init_block2 = (s) => {
        let n;
        for (n = 0; n < L_CODES2; n++) {
          s.dyn_ltree[n * 2] = 0;
        }
        for (n = 0; n < D_CODES2; n++) {
          s.dyn_dtree[n * 2] = 0;
        }
        for (n = 0; n < BL_CODES2; n++) {
          s.bl_tree[n * 2] = 0;
        }
        s.dyn_ltree[END_BLOCK2 * 2] = 1;
        s.opt_len = s.static_len = 0;
        s.sym_next = s.matches = 0;
      };
      var bi_windup2 = (s) => {
        if (s.bi_valid > 8) {
          put_short2(s, s.bi_buf);
        } else if (s.bi_valid > 0) {
          s.pending_buf[s.pending++] = s.bi_buf;
        }
        s.bi_buf = 0;
        s.bi_valid = 0;
      };
      var smaller2 = (tree, n, m, depth) => {
        const _n2 = n * 2;
        const _m2 = m * 2;
        return tree[_n2] < tree[_m2] || tree[_n2] === tree[_m2] && depth[n] <= depth[m];
      };
      var pqdownheap2 = (s, tree, k) => {
        const v = s.heap[k];
        let j = k << 1;
        while (j <= s.heap_len) {
          if (j < s.heap_len && smaller2(tree, s.heap[j + 1], s.heap[j], s.depth)) {
            j++;
          }
          if (smaller2(tree, v, s.heap[j], s.depth)) {
            break;
          }
          s.heap[k] = s.heap[j];
          k = j;
          j <<= 1;
        }
        s.heap[k] = v;
      };
      var compress_block2 = (s, ltree, dtree) => {
        let dist;
        let lc;
        let sx = 0;
        let code;
        let extra;
        if (s.sym_next !== 0) {
          do {
            dist = s.pending_buf[s.sym_buf + sx++] & 255;
            dist += (s.pending_buf[s.sym_buf + sx++] & 255) << 8;
            lc = s.pending_buf[s.sym_buf + sx++];
            if (dist === 0) {
              send_code2(s, lc, ltree);
            } else {
              code = _length_code2[lc];
              send_code2(s, code + LITERALS2 + 1, ltree);
              extra = extra_lbits2[code];
              if (extra !== 0) {
                lc -= base_length2[code];
                send_bits2(s, lc, extra);
              }
              dist--;
              code = d_code2(dist);
              send_code2(s, code, dtree);
              extra = extra_dbits2[code];
              if (extra !== 0) {
                dist -= base_dist2[code];
                send_bits2(s, dist, extra);
              }
            }
          } while (sx < s.sym_next);
        }
        send_code2(s, END_BLOCK2, ltree);
      };
      var build_tree2 = (s, desc) => {
        const tree = desc.dyn_tree;
        const stree = desc.stat_desc.static_tree;
        const has_stree = desc.stat_desc.has_stree;
        const elems = desc.stat_desc.elems;
        let n, m;
        let max_code = -1;
        let node;
        s.heap_len = 0;
        s.heap_max = HEAP_SIZE2;
        for (n = 0; n < elems; n++) {
          if (tree[n * 2] !== 0) {
            s.heap[++s.heap_len] = max_code = n;
            s.depth[n] = 0;
          } else {
            tree[n * 2 + 1] = 0;
          }
        }
        while (s.heap_len < 2) {
          node = s.heap[++s.heap_len] = max_code < 2 ? ++max_code : 0;
          tree[node * 2] = 1;
          s.depth[node] = 0;
          s.opt_len--;
          if (has_stree) {
            s.static_len -= stree[node * 2 + 1];
          }
        }
        desc.max_code = max_code;
        for (n = s.heap_len >> 1; n >= 1; n--) {
          pqdownheap2(s, tree, n);
        }
        node = elems;
        do {
          n = s.heap[
            1
            /*SMALLEST*/
          ];
          s.heap[
            1
            /*SMALLEST*/
          ] = s.heap[s.heap_len--];
          pqdownheap2(
            s,
            tree,
            1
            /*SMALLEST*/
          );
          m = s.heap[
            1
            /*SMALLEST*/
          ];
          s.heap[--s.heap_max] = n;
          s.heap[--s.heap_max] = m;
          tree[node * 2] = tree[n * 2] + tree[m * 2];
          s.depth[node] = (s.depth[n] >= s.depth[m] ? s.depth[n] : s.depth[m]) + 1;
          tree[n * 2 + 1] = tree[m * 2 + 1] = node;
          s.heap[
            1
            /*SMALLEST*/
          ] = node++;
          pqdownheap2(
            s,
            tree,
            1
            /*SMALLEST*/
          );
        } while (s.heap_len >= 2);
        s.heap[--s.heap_max] = s.heap[
          1
          /*SMALLEST*/
        ];
        gen_bitlen2(s, desc);
        gen_codes2(tree, max_code, s.bl_count);
      };
      var scan_tree2 = (s, tree, max_code) => {
        let n;
        let prevlen = -1;
        let curlen;
        let nextlen = tree[0 * 2 + 1];
        let count = 0;
        let max_count = 7;
        let min_count = 4;
        if (nextlen === 0) {
          max_count = 138;
          min_count = 3;
        }
        tree[(max_code + 1) * 2 + 1] = 65535;
        for (n = 0; n <= max_code; n++) {
          curlen = nextlen;
          nextlen = tree[(n + 1) * 2 + 1];
          if (++count < max_count && curlen === nextlen) {
            continue;
          } else if (count < min_count) {
            s.bl_tree[curlen * 2] += count;
          } else if (curlen !== 0) {
            if (curlen !== prevlen) {
              s.bl_tree[curlen * 2]++;
            }
            s.bl_tree[REP_3_62 * 2]++;
          } else if (count <= 10) {
            s.bl_tree[REPZ_3_102 * 2]++;
          } else {
            s.bl_tree[REPZ_11_1382 * 2]++;
          }
          count = 0;
          prevlen = curlen;
          if (nextlen === 0) {
            max_count = 138;
            min_count = 3;
          } else if (curlen === nextlen) {
            max_count = 6;
            min_count = 3;
          } else {
            max_count = 7;
            min_count = 4;
          }
        }
      };
      var send_tree2 = (s, tree, max_code) => {
        let n;
        let prevlen = -1;
        let curlen;
        let nextlen = tree[0 * 2 + 1];
        let count = 0;
        let max_count = 7;
        let min_count = 4;
        if (nextlen === 0) {
          max_count = 138;
          min_count = 3;
        }
        for (n = 0; n <= max_code; n++) {
          curlen = nextlen;
          nextlen = tree[(n + 1) * 2 + 1];
          if (++count < max_count && curlen === nextlen) {
            continue;
          } else if (count < min_count) {
            do {
              send_code2(s, curlen, s.bl_tree);
            } while (--count !== 0);
          } else if (curlen !== 0) {
            if (curlen !== prevlen) {
              send_code2(s, curlen, s.bl_tree);
              count--;
            }
            send_code2(s, REP_3_62, s.bl_tree);
            send_bits2(s, count - 3, 2);
          } else if (count <= 10) {
            send_code2(s, REPZ_3_102, s.bl_tree);
            send_bits2(s, count - 3, 3);
          } else {
            send_code2(s, REPZ_11_1382, s.bl_tree);
            send_bits2(s, count - 11, 7);
          }
          count = 0;
          prevlen = curlen;
          if (nextlen === 0) {
            max_count = 138;
            min_count = 3;
          } else if (curlen === nextlen) {
            max_count = 6;
            min_count = 3;
          } else {
            max_count = 7;
            min_count = 4;
          }
        }
      };
      var build_bl_tree2 = (s) => {
        let max_blindex;
        scan_tree2(s, s.dyn_ltree, s.l_desc.max_code);
        scan_tree2(s, s.dyn_dtree, s.d_desc.max_code);
        build_tree2(s, s.bl_desc);
        for (max_blindex = BL_CODES2 - 1; max_blindex >= 3; max_blindex--) {
          if (s.bl_tree[bl_order2[max_blindex] * 2 + 1] !== 0) {
            break;
          }
        }
        s.opt_len += 3 * (max_blindex + 1) + 5 + 5 + 4;
        return max_blindex;
      };
      var send_all_trees2 = (s, lcodes, dcodes, blcodes) => {
        let rank2;
        send_bits2(s, lcodes - 257, 5);
        send_bits2(s, dcodes - 1, 5);
        send_bits2(s, blcodes - 4, 4);
        for (rank2 = 0; rank2 < blcodes; rank2++) {
          send_bits2(s, s.bl_tree[bl_order2[rank2] * 2 + 1], 3);
        }
        send_tree2(s, s.dyn_ltree, lcodes - 1);
        send_tree2(s, s.dyn_dtree, dcodes - 1);
      };
      var detect_data_type2 = (s) => {
        let block_mask = 4093624447;
        let n;
        for (n = 0; n <= 31; n++, block_mask >>>= 1) {
          if (block_mask & 1 && s.dyn_ltree[n * 2] !== 0) {
            return Z_BINARY2;
          }
        }
        if (s.dyn_ltree[9 * 2] !== 0 || s.dyn_ltree[10 * 2] !== 0 || s.dyn_ltree[13 * 2] !== 0) {
          return Z_TEXT2;
        }
        for (n = 32; n < LITERALS2; n++) {
          if (s.dyn_ltree[n * 2] !== 0) {
            return Z_TEXT2;
          }
        }
        return Z_BINARY2;
      };
      var static_init_done2 = false;
      var _tr_init2 = (s) => {
        if (!static_init_done2) {
          tr_static_init2();
          static_init_done2 = true;
        }
        s.l_desc = new TreeDesc2(s.dyn_ltree, static_l_desc2);
        s.d_desc = new TreeDesc2(s.dyn_dtree, static_d_desc2);
        s.bl_desc = new TreeDesc2(s.bl_tree, static_bl_desc2);
        s.bi_buf = 0;
        s.bi_valid = 0;
        init_block2(s);
      };
      var _tr_stored_block2 = (s, buf, stored_len, last) => {
        send_bits2(s, (STORED_BLOCK2 << 1) + (last ? 1 : 0), 3);
        bi_windup2(s);
        put_short2(s, stored_len);
        put_short2(s, ~stored_len);
        if (stored_len) {
          s.pending_buf.set(s.window.subarray(buf, buf + stored_len), s.pending);
        }
        s.pending += stored_len;
      };
      var _tr_align2 = (s) => {
        send_bits2(s, STATIC_TREES2 << 1, 3);
        send_code2(s, END_BLOCK2, static_ltree2);
        bi_flush2(s);
      };
      var _tr_flush_block2 = (s, buf, stored_len, last) => {
        let opt_lenb, static_lenb;
        let max_blindex = 0;
        if (s.level > 0) {
          if (s.strm.data_type === Z_UNKNOWN2) {
            s.strm.data_type = detect_data_type2(s);
          }
          build_tree2(s, s.l_desc);
          build_tree2(s, s.d_desc);
          max_blindex = build_bl_tree2(s);
          opt_lenb = s.opt_len + 3 + 7 >>> 3;
          static_lenb = s.static_len + 3 + 7 >>> 3;
          if (static_lenb <= opt_lenb) {
            opt_lenb = static_lenb;
          }
        } else {
          opt_lenb = static_lenb = stored_len + 5;
        }
        if (stored_len + 4 <= opt_lenb && buf !== -1) {
          _tr_stored_block2(s, buf, stored_len, last);
        } else if (s.strategy === Z_FIXED2 || static_lenb === opt_lenb) {
          send_bits2(s, (STATIC_TREES2 << 1) + (last ? 1 : 0), 3);
          compress_block2(s, static_ltree2, static_dtree2);
        } else {
          send_bits2(s, (DYN_TREES2 << 1) + (last ? 1 : 0), 3);
          send_all_trees2(s, s.l_desc.max_code + 1, s.d_desc.max_code + 1, max_blindex + 1);
          compress_block2(s, s.dyn_ltree, s.dyn_dtree);
        }
        init_block2(s);
        if (last) {
          bi_windup2(s);
        }
      };
      var _tr_tally2 = (s, dist, lc) => {
        s.pending_buf[s.sym_buf + s.sym_next++] = dist;
        s.pending_buf[s.sym_buf + s.sym_next++] = dist >> 8;
        s.pending_buf[s.sym_buf + s.sym_next++] = lc;
        if (dist === 0) {
          s.dyn_ltree[lc * 2]++;
        } else {
          s.matches++;
          dist--;
          s.dyn_ltree[(_length_code2[lc] + LITERALS2 + 1) * 2]++;
          s.dyn_dtree[d_code2(dist) * 2]++;
        }
        return s.sym_next === s.sym_end;
      };
      module.exports._tr_init = _tr_init2;
      module.exports._tr_stored_block = _tr_stored_block2;
      module.exports._tr_flush_block = _tr_flush_block2;
      module.exports._tr_tally = _tr_tally2;
      module.exports._tr_align = _tr_align2;
    }
  });

  // node_modules/pako/lib/zlib/adler32.js
  var require_adler32 = __commonJS({
    "node_modules/pako/lib/zlib/adler32.js"(exports, module) {
      "use strict";
      var adler322 = (adler, buf, len, pos) => {
        let s1 = adler & 65535 | 0, s2 = adler >>> 16 & 65535 | 0, n = 0;
        while (len !== 0) {
          n = len > 2e3 ? 2e3 : len;
          len -= n;
          do {
            s1 = s1 + buf[pos++] | 0;
            s2 = s2 + s1 | 0;
          } while (--n);
          s1 %= 65521;
          s2 %= 65521;
        }
        return s1 | s2 << 16 | 0;
      };
      module.exports = adler322;
    }
  });

  // node_modules/pako/lib/zlib/crc32.js
  var require_crc32 = __commonJS({
    "node_modules/pako/lib/zlib/crc32.js"(exports, module) {
      "use strict";
      var makeTable2 = () => {
        let c, table = [];
        for (var n = 0; n < 256; n++) {
          c = n;
          for (var k = 0; k < 8; k++) {
            c = c & 1 ? 3988292384 ^ c >>> 1 : c >>> 1;
          }
          table[n] = c;
        }
        return table;
      };
      var crcTable2 = new Uint32Array(makeTable2());
      var crc322 = (crc, buf, len, pos) => {
        const t = crcTable2;
        const end = pos + len;
        crc ^= -1;
        for (let i = pos; i < end; i++) {
          crc = crc >>> 8 ^ t[(crc ^ buf[i]) & 255];
        }
        return crc ^ -1;
      };
      module.exports = crc322;
    }
  });

  // node_modules/pako/lib/zlib/messages.js
  var require_messages = __commonJS({
    "node_modules/pako/lib/zlib/messages.js"(exports, module) {
      "use strict";
      module.exports = {
        2: "need dictionary",
        /* Z_NEED_DICT       2  */
        1: "stream end",
        /* Z_STREAM_END      1  */
        0: "",
        /* Z_OK              0  */
        "-1": "file error",
        /* Z_ERRNO         (-1) */
        "-2": "stream error",
        /* Z_STREAM_ERROR  (-2) */
        "-3": "data error",
        /* Z_DATA_ERROR    (-3) */
        "-4": "insufficient memory",
        /* Z_MEM_ERROR     (-4) */
        "-5": "buffer error",
        /* Z_BUF_ERROR     (-5) */
        "-6": "incompatible version"
        /* Z_VERSION_ERROR (-6) */
      };
    }
  });

  // node_modules/pako/lib/zlib/constants.js
  var require_constants = __commonJS({
    "node_modules/pako/lib/zlib/constants.js"(exports, module) {
      "use strict";
      module.exports = {
        /* Allowed flush values; see deflate() and inflate() below for details */
        Z_NO_FLUSH: 0,
        Z_PARTIAL_FLUSH: 1,
        Z_SYNC_FLUSH: 2,
        Z_FULL_FLUSH: 3,
        Z_FINISH: 4,
        Z_BLOCK: 5,
        Z_TREES: 6,
        /* Return codes for the compression/decompression functions. Negative values
        * are errors, positive values are used for special but normal events.
        */
        Z_OK: 0,
        Z_STREAM_END: 1,
        Z_NEED_DICT: 2,
        Z_ERRNO: -1,
        Z_STREAM_ERROR: -2,
        Z_DATA_ERROR: -3,
        Z_MEM_ERROR: -4,
        Z_BUF_ERROR: -5,
        //Z_VERSION_ERROR: -6,
        /* compression levels */
        Z_NO_COMPRESSION: 0,
        Z_BEST_SPEED: 1,
        Z_BEST_COMPRESSION: 9,
        Z_DEFAULT_COMPRESSION: -1,
        Z_FILTERED: 1,
        Z_HUFFMAN_ONLY: 2,
        Z_RLE: 3,
        Z_FIXED: 4,
        Z_DEFAULT_STRATEGY: 0,
        /* Possible values of the data_type field (though see inflate()) */
        Z_BINARY: 0,
        Z_TEXT: 1,
        //Z_ASCII:                1, // = Z_TEXT (deprecated)
        Z_UNKNOWN: 2,
        /* The deflate compression method */
        Z_DEFLATED: 8
        //Z_NULL:                 null // Use -1 or null inline, depending on var type
      };
    }
  });

  // node_modules/pako/lib/zlib/deflate.js
  var require_deflate = __commonJS({
    "node_modules/pako/lib/zlib/deflate.js"(exports, module) {
      "use strict";
      var { _tr_init: _tr_init2, _tr_stored_block: _tr_stored_block2, _tr_flush_block: _tr_flush_block2, _tr_tally: _tr_tally2, _tr_align: _tr_align2 } = require_trees();
      var adler322 = require_adler32();
      var crc322 = require_crc32();
      var msg = require_messages();
      var {
        Z_NO_FLUSH: Z_NO_FLUSH2,
        Z_PARTIAL_FLUSH: Z_PARTIAL_FLUSH2,
        Z_FULL_FLUSH: Z_FULL_FLUSH2,
        Z_FINISH: Z_FINISH2,
        Z_BLOCK: Z_BLOCK2,
        Z_OK: Z_OK2,
        Z_STREAM_END: Z_STREAM_END2,
        Z_STREAM_ERROR: Z_STREAM_ERROR2,
        Z_DATA_ERROR: Z_DATA_ERROR2,
        Z_BUF_ERROR: Z_BUF_ERROR2,
        Z_DEFAULT_COMPRESSION: Z_DEFAULT_COMPRESSION2,
        Z_FILTERED: Z_FILTERED2,
        Z_HUFFMAN_ONLY: Z_HUFFMAN_ONLY2,
        Z_RLE: Z_RLE2,
        Z_FIXED: Z_FIXED2,
        Z_DEFAULT_STRATEGY: Z_DEFAULT_STRATEGY2,
        Z_UNKNOWN: Z_UNKNOWN2,
        Z_DEFLATED: Z_DEFLATED2
      } = require_constants();
      var MAX_MEM_LEVEL2 = 9;
      var MAX_WBITS2 = 15;
      var DEF_MEM_LEVEL2 = 8;
      var LENGTH_CODES2 = 29;
      var LITERALS2 = 256;
      var L_CODES2 = LITERALS2 + 1 + LENGTH_CODES2;
      var D_CODES2 = 30;
      var BL_CODES2 = 19;
      var HEAP_SIZE2 = 2 * L_CODES2 + 1;
      var MAX_BITS2 = 15;
      var MIN_MATCH2 = 3;
      var MAX_MATCH2 = 258;
      var MIN_LOOKAHEAD2 = MAX_MATCH2 + MIN_MATCH2 + 1;
      var PRESET_DICT2 = 32;
      var INIT_STATE2 = 42;
      var GZIP_STATE2 = 57;
      var EXTRA_STATE2 = 69;
      var NAME_STATE2 = 73;
      var COMMENT_STATE2 = 91;
      var HCRC_STATE2 = 103;
      var BUSY_STATE2 = 113;
      var FINISH_STATE2 = 666;
      var BS_NEED_MORE2 = 1;
      var BS_BLOCK_DONE2 = 2;
      var BS_FINISH_STARTED2 = 3;
      var BS_FINISH_DONE2 = 4;
      var OS_CODE2 = 3;
      var err2 = (strm, errorCode) => {
        strm.msg = msg[errorCode];
        return errorCode;
      };
      var rank2 = (f) => {
        return f * 2 - (f > 4 ? 9 : 0);
      };
      var zero2 = (buf) => {
        let len = buf.length;
        while (--len >= 0) {
          buf[len] = 0;
        }
      };
      var slide_hash2 = (s) => {
        let n, m;
        let p;
        let wsize = s.w_size;
        n = s.hash_size;
        p = n;
        do {
          m = s.head[--p];
          s.head[p] = m >= wsize ? m - wsize : 0;
        } while (--n);
        n = wsize;
        p = n;
        do {
          m = s.prev[--p];
          s.prev[p] = m >= wsize ? m - wsize : 0;
        } while (--n);
      };
      var HASH2 = (s, prev, data) => (prev << s.hash_shift ^ data) & s.hash_mask;
      var INSERT_STRING2 = (s, str) => {
        let h;
        if (s.legacy_hash) {
          h = s.ins_h = HASH2(s, s.ins_h, s.window[str + MIN_MATCH2 - 1]);
        } else {
          const w = s.window;
          const value = w[str] | w[str + 1] << 8 | w[str + 2] << 16 | w[str + 3] << 24;
          h = s.ins_h = Math.imul(value, 66521) + 66521 >>> 16 & s.hash_mask;
        }
        const hash_head = s.prev[str & s.w_mask] = s.head[h];
        s.head[h] = str;
        return hash_head;
      };
      var flush_pending2 = (strm) => {
        const s = strm.state;
        let len = s.pending;
        if (len > strm.avail_out) {
          len = strm.avail_out;
        }
        if (len === 0) {
          return;
        }
        strm.output.set(s.pending_buf.subarray(s.pending_out, s.pending_out + len), strm.next_out);
        strm.next_out += len;
        s.pending_out += len;
        strm.total_out += len;
        strm.avail_out -= len;
        s.pending -= len;
        if (s.pending === 0) {
          s.pending_out = 0;
        }
      };
      var flush_block_only2 = (s, last) => {
        _tr_flush_block2(s, s.block_start >= 0 ? s.block_start : -1, s.strstart - s.block_start, last);
        s.block_start = s.strstart;
        flush_pending2(s.strm);
      };
      var put_byte2 = (s, b) => {
        s.pending_buf[s.pending++] = b;
      };
      var putShortMSB2 = (s, b) => {
        s.pending_buf[s.pending++] = b >>> 8 & 255;
        s.pending_buf[s.pending++] = b & 255;
      };
      var read_buf2 = (strm, buf, start, size) => {
        let len = strm.avail_in;
        if (len > size) {
          len = size;
        }
        if (len === 0) {
          return 0;
        }
        strm.avail_in -= len;
        buf.set(strm.input.subarray(strm.next_in, strm.next_in + len), start);
        if (strm.state.wrap === 1) {
          strm.adler = adler322(strm.adler, buf, len, start);
        } else if (strm.state.wrap === 2) {
          strm.adler = crc322(strm.adler, buf, len, start);
        }
        strm.next_in += len;
        strm.total_in += len;
        return len;
      };
      var longest_match2 = (s, cur_match) => {
        let chain_length = s.max_chain_length;
        let scan = s.strstart;
        let match;
        let len;
        let best_len = s.prev_length;
        let nice_match = s.nice_match;
        const limit = s.strstart > s.w_size - MIN_LOOKAHEAD2 ? s.strstart - (s.w_size - MIN_LOOKAHEAD2) : 0;
        const _win = s.window;
        const wmask = s.w_mask;
        const prev = s.prev;
        const strend = s.strstart + MAX_MATCH2;
        let scan_end1 = _win[scan + best_len - 1];
        let scan_end = _win[scan + best_len];
        if (s.prev_length >= s.good_match) {
          chain_length >>= 2;
        }
        if (nice_match > s.lookahead) {
          nice_match = s.lookahead;
        }
        do {
          match = cur_match;
          if (_win[match + best_len] !== scan_end || _win[match + best_len - 1] !== scan_end1 || _win[match] !== _win[scan] || _win[++match] !== _win[scan + 1]) {
            continue;
          }
          scan += 2;
          match++;
          do {
          } while (_win[++scan] === _win[++match] && _win[++scan] === _win[++match] && _win[++scan] === _win[++match] && _win[++scan] === _win[++match] && _win[++scan] === _win[++match] && _win[++scan] === _win[++match] && _win[++scan] === _win[++match] && _win[++scan] === _win[++match] && scan < strend);
          len = MAX_MATCH2 - (strend - scan);
          scan = strend - MAX_MATCH2;
          if (len > best_len) {
            s.match_start = cur_match;
            best_len = len;
            if (len >= nice_match) {
              break;
            }
            scan_end1 = _win[scan + best_len - 1];
            scan_end = _win[scan + best_len];
          }
        } while ((cur_match = prev[cur_match & wmask]) > limit && --chain_length !== 0);
        if (best_len <= s.lookahead) {
          return best_len;
        }
        return s.lookahead;
      };
      var fill_window2 = (s) => {
        const _w_size = s.w_size;
        let n, more, str;
        do {
          more = s.window_size - s.lookahead - s.strstart;
          if (s.strstart >= _w_size + (_w_size - MIN_LOOKAHEAD2)) {
            s.window.set(s.window.subarray(_w_size, _w_size + _w_size - more), 0);
            s.match_start -= _w_size;
            s.strstart -= _w_size;
            s.block_start -= _w_size;
            if (s.insert > s.strstart) {
              s.insert = s.strstart;
            }
            slide_hash2(s);
            more += _w_size;
          }
          if (s.strm.avail_in === 0) {
            break;
          }
          n = read_buf2(s.strm, s.window, s.strstart + s.lookahead, more);
          s.lookahead += n;
          if (!s.legacy_hash) {
            if (s.lookahead + s.insert > MIN_MATCH2) {
              str = s.strstart - s.insert;
              while (s.insert) {
                INSERT_STRING2(s, str);
                str++;
                s.insert--;
                if (s.lookahead + s.insert <= MIN_MATCH2) {
                  break;
                }
              }
            }
          } else if (s.lookahead + s.insert >= MIN_MATCH2) {
            str = s.strstart - s.insert;
            s.ins_h = s.window[str];
            s.ins_h = HASH2(s, s.ins_h, s.window[str + 1]);
            while (s.insert) {
              INSERT_STRING2(s, str);
              str++;
              s.insert--;
              if (s.lookahead + s.insert < MIN_MATCH2) {
                break;
              }
            }
          }
        } while (s.lookahead < MIN_LOOKAHEAD2 && s.strm.avail_in !== 0);
      };
      var deflate_stored2 = (s, flush) => {
        let min_block = s.pending_buf_size - 5 > s.w_size ? s.w_size : s.pending_buf_size - 5;
        let len, left, have, last = 0;
        let used = s.strm.avail_in;
        do {
          len = 65535;
          have = s.bi_valid + 42 >> 3;
          if (s.strm.avail_out < have) {
            break;
          }
          have = s.strm.avail_out - have;
          left = s.strstart - s.block_start;
          if (len > left + s.strm.avail_in) {
            len = left + s.strm.avail_in;
          }
          if (len > have) {
            len = have;
          }
          if (len < min_block && (len === 0 && flush !== Z_FINISH2 || flush === Z_NO_FLUSH2 || len !== left + s.strm.avail_in)) {
            break;
          }
          last = flush === Z_FINISH2 && len === left + s.strm.avail_in ? 1 : 0;
          _tr_stored_block2(s, 0, 0, last);
          s.pending_buf[s.pending - 4] = len;
          s.pending_buf[s.pending - 3] = len >> 8;
          s.pending_buf[s.pending - 2] = ~len;
          s.pending_buf[s.pending - 1] = ~len >> 8;
          flush_pending2(s.strm);
          if (left) {
            if (left > len) {
              left = len;
            }
            s.strm.output.set(s.window.subarray(s.block_start, s.block_start + left), s.strm.next_out);
            s.strm.next_out += left;
            s.strm.avail_out -= left;
            s.strm.total_out += left;
            s.block_start += left;
            len -= left;
          }
          if (len) {
            read_buf2(s.strm, s.strm.output, s.strm.next_out, len);
            s.strm.next_out += len;
            s.strm.avail_out -= len;
            s.strm.total_out += len;
          }
        } while (last === 0);
        used -= s.strm.avail_in;
        if (used) {
          if (used >= s.w_size) {
            s.matches = 2;
            s.window.set(s.strm.input.subarray(s.strm.next_in - s.w_size, s.strm.next_in), 0);
            s.strstart = s.w_size;
            s.insert = s.strstart;
          } else {
            if (s.window_size - s.strstart <= used) {
              s.strstart -= s.w_size;
              s.window.set(s.window.subarray(s.w_size, s.w_size + s.strstart), 0);
              if (s.matches < 2) {
                s.matches++;
              }
              if (s.insert > s.strstart) {
                s.insert = s.strstart;
              }
            }
            s.window.set(s.strm.input.subarray(s.strm.next_in - used, s.strm.next_in), s.strstart);
            s.strstart += used;
            s.insert += used > s.w_size - s.insert ? s.w_size - s.insert : used;
          }
          s.block_start = s.strstart;
        }
        if (s.high_water < s.strstart) {
          s.high_water = s.strstart;
        }
        if (last) {
          return BS_FINISH_DONE2;
        }
        if (flush !== Z_NO_FLUSH2 && flush !== Z_FINISH2 && s.strm.avail_in === 0 && s.strstart === s.block_start) {
          return BS_BLOCK_DONE2;
        }
        have = s.window_size - s.strstart;
        if (s.strm.avail_in > have && s.block_start >= s.w_size) {
          s.block_start -= s.w_size;
          s.strstart -= s.w_size;
          s.window.set(s.window.subarray(s.w_size, s.w_size + s.strstart), 0);
          if (s.matches < 2) {
            s.matches++;
          }
          have += s.w_size;
          if (s.insert > s.strstart) {
            s.insert = s.strstart;
          }
        }
        if (have > s.strm.avail_in) {
          have = s.strm.avail_in;
        }
        if (have) {
          read_buf2(s.strm, s.window, s.strstart, have);
          s.strstart += have;
          s.insert += have > s.w_size - s.insert ? s.w_size - s.insert : have;
        }
        if (s.high_water < s.strstart) {
          s.high_water = s.strstart;
        }
        have = s.bi_valid + 42 >> 3;
        have = s.pending_buf_size - have > 65535 ? 65535 : s.pending_buf_size - have;
        min_block = have > s.w_size ? s.w_size : have;
        left = s.strstart - s.block_start;
        if (left >= min_block || (left || flush === Z_FINISH2) && flush !== Z_NO_FLUSH2 && s.strm.avail_in === 0 && left <= have) {
          len = left > have ? have : left;
          last = flush === Z_FINISH2 && s.strm.avail_in === 0 && len === left ? 1 : 0;
          _tr_stored_block2(s, s.block_start, len, last);
          s.block_start += len;
          flush_pending2(s.strm);
        }
        return last ? BS_FINISH_STARTED2 : BS_NEED_MORE2;
      };
      var deflate_fast2 = (s, flush) => {
        let hash_head;
        let bflush;
        for (; ; ) {
          if (s.lookahead < MIN_LOOKAHEAD2) {
            fill_window2(s);
            if (s.lookahead < MIN_LOOKAHEAD2 && flush === Z_NO_FLUSH2) {
              return BS_NEED_MORE2;
            }
            if (s.lookahead === 0) {
              break;
            }
          }
          hash_head = 0;
          if (s.lookahead >= MIN_MATCH2) {
            hash_head = INSERT_STRING2(s, s.strstart);
          }
          if (hash_head !== 0 && s.strstart - hash_head <= s.w_size - MIN_LOOKAHEAD2) {
            s.match_length = longest_match2(s, hash_head);
          }
          if (s.match_length >= MIN_MATCH2) {
            bflush = _tr_tally2(s, s.strstart - s.match_start, s.match_length - MIN_MATCH2);
            s.lookahead -= s.match_length;
            if (s.match_length <= s.max_lazy_match && s.lookahead >= MIN_MATCH2) {
              s.match_length--;
              do {
                s.strstart++;
                hash_head = INSERT_STRING2(s, s.strstart);
              } while (--s.match_length !== 0);
              s.strstart++;
            } else {
              s.strstart += s.match_length;
              s.match_length = 0;
              if (s.legacy_hash) {
                s.ins_h = s.window[s.strstart];
                s.ins_h = HASH2(s, s.ins_h, s.window[s.strstart + 1]);
              }
            }
          } else {
            bflush = _tr_tally2(s, 0, s.window[s.strstart]);
            s.lookahead--;
            s.strstart++;
          }
          if (bflush) {
            flush_block_only2(s, false);
            if (s.strm.avail_out === 0) {
              return BS_NEED_MORE2;
            }
          }
        }
        s.insert = s.strstart < MIN_MATCH2 - 1 ? s.strstart : MIN_MATCH2 - 1;
        if (flush === Z_FINISH2) {
          flush_block_only2(s, true);
          if (s.strm.avail_out === 0) {
            return BS_FINISH_STARTED2;
          }
          return BS_FINISH_DONE2;
        }
        if (s.sym_next) {
          flush_block_only2(s, false);
          if (s.strm.avail_out === 0) {
            return BS_NEED_MORE2;
          }
        }
        return BS_BLOCK_DONE2;
      };
      var deflate_slow2 = (s, flush) => {
        let hash_head;
        let bflush;
        let max_insert;
        for (; ; ) {
          if (s.lookahead < MIN_LOOKAHEAD2) {
            fill_window2(s);
            if (s.lookahead < MIN_LOOKAHEAD2 && flush === Z_NO_FLUSH2) {
              return BS_NEED_MORE2;
            }
            if (s.lookahead === 0) {
              break;
            }
          }
          hash_head = 0;
          if (s.lookahead >= MIN_MATCH2) {
            hash_head = INSERT_STRING2(s, s.strstart);
          }
          s.prev_length = s.match_length;
          s.prev_match = s.match_start;
          s.match_length = MIN_MATCH2 - 1;
          if (hash_head !== 0 && s.prev_length < s.max_lazy_match && s.strstart - hash_head <= s.w_size - MIN_LOOKAHEAD2) {
            s.match_length = longest_match2(s, hash_head);
            if (s.match_length <= 5 && (s.strategy === Z_FILTERED2 || s.match_length === MIN_MATCH2 && s.strstart - s.match_start > 4096)) {
              s.match_length = MIN_MATCH2 - 1;
            }
          }
          if (s.prev_length >= MIN_MATCH2 && s.match_length <= s.prev_length) {
            max_insert = s.strstart + s.lookahead - MIN_MATCH2;
            bflush = _tr_tally2(s, s.strstart - 1 - s.prev_match, s.prev_length - MIN_MATCH2);
            s.lookahead -= s.prev_length - 1;
            s.prev_length -= 2;
            do {
              if (++s.strstart <= max_insert) {
                hash_head = INSERT_STRING2(s, s.strstart);
              }
            } while (--s.prev_length !== 0);
            s.match_available = 0;
            s.match_length = MIN_MATCH2 - 1;
            s.strstart++;
            if (bflush) {
              flush_block_only2(s, false);
              if (s.strm.avail_out === 0) {
                return BS_NEED_MORE2;
              }
            }
          } else if (s.match_available) {
            bflush = _tr_tally2(s, 0, s.window[s.strstart - 1]);
            if (bflush) {
              flush_block_only2(s, false);
            }
            s.strstart++;
            s.lookahead--;
            if (s.strm.avail_out === 0) {
              return BS_NEED_MORE2;
            }
          } else {
            s.match_available = 1;
            s.strstart++;
            s.lookahead--;
          }
        }
        if (s.match_available) {
          bflush = _tr_tally2(s, 0, s.window[s.strstart - 1]);
          s.match_available = 0;
        }
        s.insert = s.strstart < MIN_MATCH2 - 1 ? s.strstart : MIN_MATCH2 - 1;
        if (flush === Z_FINISH2) {
          flush_block_only2(s, true);
          if (s.strm.avail_out === 0) {
            return BS_FINISH_STARTED2;
          }
          return BS_FINISH_DONE2;
        }
        if (s.sym_next) {
          flush_block_only2(s, false);
          if (s.strm.avail_out === 0) {
            return BS_NEED_MORE2;
          }
        }
        return BS_BLOCK_DONE2;
      };
      var deflate_rle2 = (s, flush) => {
        let bflush;
        let prev;
        let scan, strend;
        const _win = s.window;
        for (; ; ) {
          if (s.lookahead <= MAX_MATCH2) {
            fill_window2(s);
            if (s.lookahead <= MAX_MATCH2 && flush === Z_NO_FLUSH2) {
              return BS_NEED_MORE2;
            }
            if (s.lookahead === 0) {
              break;
            }
          }
          s.match_length = 0;
          if (s.lookahead >= MIN_MATCH2 && s.strstart > 0) {
            scan = s.strstart - 1;
            prev = _win[scan];
            if (prev === _win[++scan] && prev === _win[++scan] && prev === _win[++scan]) {
              strend = s.strstart + MAX_MATCH2;
              do {
              } while (prev === _win[++scan] && prev === _win[++scan] && prev === _win[++scan] && prev === _win[++scan] && prev === _win[++scan] && prev === _win[++scan] && prev === _win[++scan] && prev === _win[++scan] && scan < strend);
              s.match_length = MAX_MATCH2 - (strend - scan);
              if (s.match_length > s.lookahead) {
                s.match_length = s.lookahead;
              }
            }
          }
          if (s.match_length >= MIN_MATCH2) {
            bflush = _tr_tally2(s, 1, s.match_length - MIN_MATCH2);
            s.lookahead -= s.match_length;
            s.strstart += s.match_length;
            s.match_length = 0;
          } else {
            bflush = _tr_tally2(s, 0, s.window[s.strstart]);
            s.lookahead--;
            s.strstart++;
          }
          if (bflush) {
            flush_block_only2(s, false);
            if (s.strm.avail_out === 0) {
              return BS_NEED_MORE2;
            }
          }
        }
        s.insert = 0;
        if (flush === Z_FINISH2) {
          flush_block_only2(s, true);
          if (s.strm.avail_out === 0) {
            return BS_FINISH_STARTED2;
          }
          return BS_FINISH_DONE2;
        }
        if (s.sym_next) {
          flush_block_only2(s, false);
          if (s.strm.avail_out === 0) {
            return BS_NEED_MORE2;
          }
        }
        return BS_BLOCK_DONE2;
      };
      var deflate_huff2 = (s, flush) => {
        let bflush;
        for (; ; ) {
          if (s.lookahead === 0) {
            fill_window2(s);
            if (s.lookahead === 0) {
              if (flush === Z_NO_FLUSH2) {
                return BS_NEED_MORE2;
              }
              break;
            }
          }
          s.match_length = 0;
          bflush = _tr_tally2(s, 0, s.window[s.strstart]);
          s.lookahead--;
          s.strstart++;
          if (bflush) {
            flush_block_only2(s, false);
            if (s.strm.avail_out === 0) {
              return BS_NEED_MORE2;
            }
          }
        }
        s.insert = 0;
        if (flush === Z_FINISH2) {
          flush_block_only2(s, true);
          if (s.strm.avail_out === 0) {
            return BS_FINISH_STARTED2;
          }
          return BS_FINISH_DONE2;
        }
        if (s.sym_next) {
          flush_block_only2(s, false);
          if (s.strm.avail_out === 0) {
            return BS_NEED_MORE2;
          }
        }
        return BS_BLOCK_DONE2;
      };
      function Config2(good_length, max_lazy, nice_length, max_chain, func) {
        this.good_length = good_length;
        this.max_lazy = max_lazy;
        this.nice_length = nice_length;
        this.max_chain = max_chain;
        this.func = func;
      }
      var configuration_table2 = [
        /*      good lazy nice chain */
        new Config2(0, 0, 0, 0, deflate_stored2),
        /* 0 store only */
        new Config2(4, 4, 8, 4, deflate_fast2),
        /* 1 max speed, no lazy matches */
        new Config2(4, 5, 16, 8, deflate_fast2),
        /* 2 */
        new Config2(4, 6, 32, 32, deflate_fast2),
        /* 3 */
        new Config2(4, 4, 16, 16, deflate_slow2),
        /* 4 lazy matches */
        new Config2(8, 16, 32, 32, deflate_slow2),
        /* 5 */
        new Config2(8, 16, 128, 128, deflate_slow2),
        /* 6 */
        new Config2(8, 32, 128, 256, deflate_slow2),
        /* 7 */
        new Config2(32, 128, 258, 1024, deflate_slow2),
        /* 8 */
        new Config2(32, 258, 258, 4096, deflate_slow2)
        /* 9 max compression */
      ];
      var lm_init2 = (s) => {
        s.window_size = 2 * s.w_size;
        zero2(s.head);
        s.max_lazy_match = configuration_table2[s.level].max_lazy;
        s.good_match = configuration_table2[s.level].good_length;
        s.nice_match = configuration_table2[s.level].nice_length;
        s.max_chain_length = configuration_table2[s.level].max_chain;
        s.strstart = 0;
        s.block_start = 0;
        s.lookahead = 0;
        s.insert = 0;
        s.match_length = s.prev_length = MIN_MATCH2 - 1;
        s.match_available = 0;
        s.ins_h = 0;
      };
      function DeflateState2() {
        this.strm = null;
        this.status = 0;
        this.pending_buf = null;
        this.pending_buf_size = 0;
        this.pending_out = 0;
        this.pending = 0;
        this.wrap = 0;
        this.gzhead = null;
        this.gzindex = 0;
        this.method = Z_DEFLATED2;
        this.last_flush = -1;
        this.w_size = 0;
        this.w_bits = 0;
        this.w_mask = 0;
        this.window = null;
        this.window_size = 0;
        this.prev = null;
        this.head = null;
        this.ins_h = 0;
        this.legacy_hash = 0;
        this.hash_size = 0;
        this.hash_bits = 0;
        this.hash_mask = 0;
        this.hash_shift = 0;
        this.block_start = 0;
        this.match_length = 0;
        this.prev_match = 0;
        this.match_available = 0;
        this.strstart = 0;
        this.match_start = 0;
        this.lookahead = 0;
        this.prev_length = 0;
        this.max_chain_length = 0;
        this.max_lazy_match = 0;
        this.level = 0;
        this.strategy = 0;
        this.good_match = 0;
        this.nice_match = 0;
        this.dyn_ltree = new Uint16Array(HEAP_SIZE2 * 2);
        this.dyn_dtree = new Uint16Array((2 * D_CODES2 + 1) * 2);
        this.bl_tree = new Uint16Array((2 * BL_CODES2 + 1) * 2);
        zero2(this.dyn_ltree);
        zero2(this.dyn_dtree);
        zero2(this.bl_tree);
        this.l_desc = null;
        this.d_desc = null;
        this.bl_desc = null;
        this.bl_count = new Uint16Array(MAX_BITS2 + 1);
        this.heap = new Uint16Array(2 * L_CODES2 + 1);
        zero2(this.heap);
        this.heap_len = 0;
        this.heap_max = 0;
        this.depth = new Uint16Array(2 * L_CODES2 + 1);
        zero2(this.depth);
        this.sym_buf = 0;
        this.lit_bufsize = 0;
        this.sym_next = 0;
        this.sym_end = 0;
        this.opt_len = 0;
        this.static_len = 0;
        this.matches = 0;
        this.insert = 0;
        this.bi_buf = 0;
        this.bi_valid = 0;
      }
      var deflateStateCheck2 = (strm) => {
        if (!strm) {
          return 1;
        }
        const s = strm.state;
        if (!s || s.strm !== strm || s.status !== INIT_STATE2 && //#ifdef GZIP
        s.status !== GZIP_STATE2 && //#endif
        s.status !== EXTRA_STATE2 && s.status !== NAME_STATE2 && s.status !== COMMENT_STATE2 && s.status !== HCRC_STATE2 && s.status !== BUSY_STATE2 && s.status !== FINISH_STATE2) {
          return 1;
        }
        return 0;
      };
      var deflateResetKeep2 = (strm) => {
        if (deflateStateCheck2(strm)) {
          return err2(strm, Z_STREAM_ERROR2);
        }
        strm.total_in = strm.total_out = 0;
        strm.data_type = Z_UNKNOWN2;
        const s = strm.state;
        s.pending = 0;
        s.pending_out = 0;
        if (s.wrap < 0) {
          s.wrap = -s.wrap;
        }
        s.status = //#ifdef GZIP
        s.wrap === 2 ? GZIP_STATE2 : (
          //#endif
          s.wrap ? INIT_STATE2 : BUSY_STATE2
        );
        strm.adler = s.wrap === 2 ? 0 : 1;
        s.last_flush = -2;
        _tr_init2(s);
        return Z_OK2;
      };
      var deflateReset2 = (strm) => {
        const ret = deflateResetKeep2(strm);
        if (ret === Z_OK2) {
          lm_init2(strm.state);
        }
        return ret;
      };
      var deflateSetHeader2 = (strm, head) => {
        if (deflateStateCheck2(strm) || strm.state.wrap !== 2) {
          return Z_STREAM_ERROR2;
        }
        strm.state.gzhead = head;
        return Z_OK2;
      };
      var deflateInit22 = (strm, level, method, windowBits, memLevel, strategy, legacyHash) => {
        if (!strm) {
          return Z_STREAM_ERROR2;
        }
        let wrap = 1;
        if (level === Z_DEFAULT_COMPRESSION2) {
          level = 6;
        }
        if (windowBits < 0) {
          wrap = 0;
          windowBits = -windowBits;
        } else if (windowBits > 15) {
          wrap = 2;
          windowBits -= 16;
        }
        if (memLevel < 1 || memLevel > MAX_MEM_LEVEL2 || method !== Z_DEFLATED2 || windowBits < 8 || windowBits > 15 || level < 0 || level > 9 || strategy < 0 || strategy > Z_FIXED2 || windowBits === 8 && wrap !== 1) {
          return err2(strm, Z_STREAM_ERROR2);
        }
        if (windowBits === 8) {
          windowBits = 9;
        }
        const s = new DeflateState2();
        strm.state = s;
        s.strm = strm;
        s.status = INIT_STATE2;
        s.wrap = wrap;
        s.gzhead = null;
        s.w_bits = windowBits;
        s.w_size = 1 << s.w_bits;
        s.w_mask = s.w_size - 1;
        s.legacy_hash = legacyHash ? 1 : 0;
        s.hash_bits = memLevel + 7;
        if (!s.legacy_hash && s.hash_bits < 15) {
          s.hash_bits = 15;
        }
        s.hash_size = 1 << s.hash_bits;
        s.hash_mask = s.hash_size - 1;
        s.hash_shift = ~~((s.hash_bits + MIN_MATCH2 - 1) / MIN_MATCH2);
        s.window = new Uint8Array(s.w_size * 2);
        s.head = new Uint16Array(s.hash_size);
        s.prev = new Uint16Array(s.w_size);
        s.lit_bufsize = 1 << memLevel + 6;
        s.pending_buf_size = s.lit_bufsize * 4;
        s.pending_buf = new Uint8Array(s.pending_buf_size);
        s.sym_buf = s.lit_bufsize;
        s.sym_end = (s.lit_bufsize - 1) * 3;
        s.level = level;
        s.strategy = strategy;
        s.method = method;
        return deflateReset2(strm);
      };
      var deflateInit3 = (strm, level) => {
        return deflateInit22(strm, level, Z_DEFLATED2, MAX_WBITS2, DEF_MEM_LEVEL2, Z_DEFAULT_STRATEGY2);
      };
      var deflate2 = (strm, flush) => {
        if (deflateStateCheck2(strm) || flush > Z_BLOCK2 || flush < 0) {
          return strm ? err2(strm, Z_STREAM_ERROR2) : Z_STREAM_ERROR2;
        }
        const s = strm.state;
        if (!strm.output || strm.avail_in !== 0 && !strm.input || s.status === FINISH_STATE2 && flush !== Z_FINISH2) {
          return err2(strm, strm.avail_out === 0 ? Z_BUF_ERROR2 : Z_STREAM_ERROR2);
        }
        const old_flush = s.last_flush;
        s.last_flush = flush;
        if (s.pending !== 0) {
          flush_pending2(strm);
          if (strm.avail_out === 0) {
            s.last_flush = -1;
            return Z_OK2;
          }
        } else if (strm.avail_in === 0 && rank2(flush) <= rank2(old_flush) && flush !== Z_FINISH2) {
          return err2(strm, Z_BUF_ERROR2);
        }
        if (s.status === FINISH_STATE2 && strm.avail_in !== 0) {
          return err2(strm, Z_BUF_ERROR2);
        }
        if (s.status === INIT_STATE2 && s.wrap === 0) {
          s.status = BUSY_STATE2;
        }
        if (s.status === INIT_STATE2) {
          let header = Z_DEFLATED2 + (s.w_bits - 8 << 4) << 8;
          let level_flags = -1;
          if (s.strategy >= Z_HUFFMAN_ONLY2 || s.level < 2) {
            level_flags = 0;
          } else if (s.level < 6) {
            level_flags = 1;
          } else if (s.level === 6) {
            level_flags = 2;
          } else {
            level_flags = 3;
          }
          header |= level_flags << 6;
          if (s.strstart !== 0) {
            header |= PRESET_DICT2;
          }
          header += 31 - header % 31;
          putShortMSB2(s, header);
          if (s.strstart !== 0) {
            putShortMSB2(s, strm.adler >>> 16);
            putShortMSB2(s, strm.adler & 65535);
          }
          strm.adler = 1;
          s.status = BUSY_STATE2;
          flush_pending2(strm);
          if (s.pending !== 0) {
            s.last_flush = -1;
            return Z_OK2;
          }
        }
        if (s.status === GZIP_STATE2) {
          strm.adler = 0;
          put_byte2(s, 31);
          put_byte2(s, 139);
          put_byte2(s, 8);
          if (!s.gzhead) {
            put_byte2(s, 0);
            put_byte2(s, 0);
            put_byte2(s, 0);
            put_byte2(s, 0);
            put_byte2(s, 0);
            put_byte2(s, s.level === 9 ? 2 : s.strategy >= Z_HUFFMAN_ONLY2 || s.level < 2 ? 4 : 0);
            put_byte2(s, OS_CODE2);
            s.status = BUSY_STATE2;
            flush_pending2(strm);
            if (s.pending !== 0) {
              s.last_flush = -1;
              return Z_OK2;
            }
          } else {
            put_byte2(
              s,
              (s.gzhead.text ? 1 : 0) + (s.gzhead.hcrc ? 2 : 0) + (!s.gzhead.extra ? 0 : 4) + (!s.gzhead.name ? 0 : 8) + (!s.gzhead.comment ? 0 : 16)
            );
            put_byte2(s, s.gzhead.time & 255);
            put_byte2(s, s.gzhead.time >> 8 & 255);
            put_byte2(s, s.gzhead.time >> 16 & 255);
            put_byte2(s, s.gzhead.time >> 24 & 255);
            put_byte2(s, s.level === 9 ? 2 : s.strategy >= Z_HUFFMAN_ONLY2 || s.level < 2 ? 4 : 0);
            put_byte2(s, s.gzhead.os & 255);
            if (s.gzhead.extra && s.gzhead.extra.length) {
              put_byte2(s, s.gzhead.extra.length & 255);
              put_byte2(s, s.gzhead.extra.length >> 8 & 255);
            }
            if (s.gzhead.hcrc) {
              strm.adler = crc322(strm.adler, s.pending_buf, s.pending, 0);
            }
            s.gzindex = 0;
            s.status = EXTRA_STATE2;
          }
        }
        if (s.status === EXTRA_STATE2) {
          if (s.gzhead.extra) {
            let beg = s.pending;
            let left = (s.gzhead.extra.length & 65535) - s.gzindex;
            while (s.pending + left > s.pending_buf_size) {
              let copy = s.pending_buf_size - s.pending;
              s.pending_buf.set(s.gzhead.extra.subarray(s.gzindex, s.gzindex + copy), s.pending);
              s.pending = s.pending_buf_size;
              if (s.gzhead.hcrc && s.pending > beg) {
                strm.adler = crc322(strm.adler, s.pending_buf, s.pending - beg, beg);
              }
              s.gzindex += copy;
              flush_pending2(strm);
              if (s.pending !== 0) {
                s.last_flush = -1;
                return Z_OK2;
              }
              beg = 0;
              left -= copy;
            }
            let gzhead_extra = new Uint8Array(s.gzhead.extra);
            s.pending_buf.set(gzhead_extra.subarray(s.gzindex, s.gzindex + left), s.pending);
            s.pending += left;
            if (s.gzhead.hcrc && s.pending > beg) {
              strm.adler = crc322(strm.adler, s.pending_buf, s.pending - beg, beg);
            }
            s.gzindex = 0;
          }
          s.status = NAME_STATE2;
        }
        if (s.status === NAME_STATE2) {
          if (s.gzhead.name) {
            let beg = s.pending;
            let val;
            do {
              if (s.pending === s.pending_buf_size) {
                if (s.gzhead.hcrc && s.pending > beg) {
                  strm.adler = crc322(strm.adler, s.pending_buf, s.pending - beg, beg);
                }
                flush_pending2(strm);
                if (s.pending !== 0) {
                  s.last_flush = -1;
                  return Z_OK2;
                }
                beg = 0;
              }
              if (s.gzindex < s.gzhead.name.length) {
                val = s.gzhead.name.charCodeAt(s.gzindex++) & 255;
              } else {
                val = 0;
              }
              put_byte2(s, val);
            } while (val !== 0);
            if (s.gzhead.hcrc && s.pending > beg) {
              strm.adler = crc322(strm.adler, s.pending_buf, s.pending - beg, beg);
            }
            s.gzindex = 0;
          }
          s.status = COMMENT_STATE2;
        }
        if (s.status === COMMENT_STATE2) {
          if (s.gzhead.comment) {
            let beg = s.pending;
            let val;
            do {
              if (s.pending === s.pending_buf_size) {
                if (s.gzhead.hcrc && s.pending > beg) {
                  strm.adler = crc322(strm.adler, s.pending_buf, s.pending - beg, beg);
                }
                flush_pending2(strm);
                if (s.pending !== 0) {
                  s.last_flush = -1;
                  return Z_OK2;
                }
                beg = 0;
              }
              if (s.gzindex < s.gzhead.comment.length) {
                val = s.gzhead.comment.charCodeAt(s.gzindex++) & 255;
              } else {
                val = 0;
              }
              put_byte2(s, val);
            } while (val !== 0);
            if (s.gzhead.hcrc && s.pending > beg) {
              strm.adler = crc322(strm.adler, s.pending_buf, s.pending - beg, beg);
            }
          }
          s.status = HCRC_STATE2;
        }
        if (s.status === HCRC_STATE2) {
          if (s.gzhead.hcrc) {
            if (s.pending + 2 > s.pending_buf_size) {
              flush_pending2(strm);
              if (s.pending !== 0) {
                s.last_flush = -1;
                return Z_OK2;
              }
            }
            put_byte2(s, strm.adler & 255);
            put_byte2(s, strm.adler >> 8 & 255);
            strm.adler = 0;
          }
          s.status = BUSY_STATE2;
          flush_pending2(strm);
          if (s.pending !== 0) {
            s.last_flush = -1;
            return Z_OK2;
          }
        }
        if (strm.avail_in !== 0 || s.lookahead !== 0 || flush !== Z_NO_FLUSH2 && s.status !== FINISH_STATE2) {
          let bstate = s.level === 0 ? deflate_stored2(s, flush) : s.strategy === Z_HUFFMAN_ONLY2 ? deflate_huff2(s, flush) : s.strategy === Z_RLE2 ? deflate_rle2(s, flush) : configuration_table2[s.level].func(s, flush);
          if (bstate === BS_FINISH_STARTED2 || bstate === BS_FINISH_DONE2) {
            s.status = FINISH_STATE2;
          }
          if (bstate === BS_NEED_MORE2 || bstate === BS_FINISH_STARTED2) {
            if (strm.avail_out === 0) {
              s.last_flush = -1;
            }
            return Z_OK2;
          }
          if (bstate === BS_BLOCK_DONE2) {
            if (flush === Z_PARTIAL_FLUSH2) {
              _tr_align2(s);
            } else if (flush !== Z_BLOCK2) {
              _tr_stored_block2(s, 0, 0, false);
              if (flush === Z_FULL_FLUSH2) {
                zero2(s.head);
                if (s.lookahead === 0) {
                  s.strstart = 0;
                  s.block_start = 0;
                  s.insert = 0;
                }
              }
            }
            flush_pending2(strm);
            if (strm.avail_out === 0) {
              s.last_flush = -1;
              return Z_OK2;
            }
          }
        }
        if (flush !== Z_FINISH2) {
          return Z_OK2;
        }
        if (s.wrap <= 0) {
          return Z_STREAM_END2;
        }
        if (s.wrap === 2) {
          put_byte2(s, strm.adler & 255);
          put_byte2(s, strm.adler >> 8 & 255);
          put_byte2(s, strm.adler >> 16 & 255);
          put_byte2(s, strm.adler >> 24 & 255);
          put_byte2(s, strm.total_in & 255);
          put_byte2(s, strm.total_in >> 8 & 255);
          put_byte2(s, strm.total_in >> 16 & 255);
          put_byte2(s, strm.total_in >> 24 & 255);
        } else {
          putShortMSB2(s, strm.adler >>> 16);
          putShortMSB2(s, strm.adler & 65535);
        }
        flush_pending2(strm);
        if (s.wrap > 0) {
          s.wrap = -s.wrap;
        }
        return s.pending !== 0 ? Z_OK2 : Z_STREAM_END2;
      };
      var deflateEnd2 = (strm) => {
        if (deflateStateCheck2(strm)) {
          return Z_STREAM_ERROR2;
        }
        const status = strm.state.status;
        strm.state = null;
        return status === BUSY_STATE2 ? err2(strm, Z_DATA_ERROR2) : Z_OK2;
      };
      var deflateSetDictionary2 = (strm, dictionary) => {
        let dictLength = dictionary.length;
        if (deflateStateCheck2(strm)) {
          return Z_STREAM_ERROR2;
        }
        const s = strm.state;
        const wrap = s.wrap;
        if (wrap === 2 || wrap === 1 && s.status !== INIT_STATE2 || s.lookahead) {
          return Z_STREAM_ERROR2;
        }
        if (wrap === 1) {
          strm.adler = adler322(strm.adler, dictionary, dictLength, 0);
        }
        s.wrap = 0;
        if (dictLength >= s.w_size) {
          if (wrap === 0) {
            zero2(s.head);
            s.strstart = 0;
            s.block_start = 0;
            s.insert = 0;
          }
          let tmpDict = new Uint8Array(s.w_size);
          tmpDict.set(dictionary.subarray(dictLength - s.w_size, dictLength), 0);
          dictionary = tmpDict;
          dictLength = s.w_size;
        }
        const avail = strm.avail_in;
        const next = strm.next_in;
        const input = strm.input;
        strm.avail_in = dictLength;
        strm.next_in = 0;
        strm.input = dictionary;
        fill_window2(s);
        while (s.lookahead >= MIN_MATCH2) {
          let str = s.strstart;
          let n = s.lookahead - (MIN_MATCH2 - 1);
          do {
            INSERT_STRING2(s, str);
            str++;
          } while (--n);
          s.strstart = str;
          s.lookahead = MIN_MATCH2 - 1;
          fill_window2(s);
        }
        s.strstart += s.lookahead;
        s.block_start = s.strstart;
        s.insert = s.lookahead;
        s.lookahead = 0;
        s.match_length = s.prev_length = MIN_MATCH2 - 1;
        s.match_available = 0;
        strm.next_in = next;
        strm.input = input;
        strm.avail_in = avail;
        s.wrap = wrap;
        return Z_OK2;
      };
      module.exports.deflateInit = deflateInit3;
      module.exports.deflateInit2 = deflateInit22;
      module.exports.deflateReset = deflateReset2;
      module.exports.deflateResetKeep = deflateResetKeep2;
      module.exports.deflateSetHeader = deflateSetHeader2;
      module.exports.deflate = deflate2;
      module.exports.deflateEnd = deflateEnd2;
      module.exports.deflateSetDictionary = deflateSetDictionary2;
      module.exports.deflateInfo = "pako deflate (from Nodeca project)";
    }
  });

  // node_modules/pako/lib/utils/common.js
  var require_common = __commonJS({
    "node_modules/pako/lib/utils/common.js"(exports, module) {
      "use strict";
      var _has2 = (obj, key) => {
        return Object.prototype.hasOwnProperty.call(obj, key);
      };
      module.exports.assign = function(obj) {
        const sources = Array.prototype.slice.call(arguments, 1);
        while (sources.length) {
          const source = sources.shift();
          if (!source) {
            continue;
          }
          if (typeof source !== "object") {
            throw new TypeError(source + "must be non-object");
          }
          for (const p in source) {
            if (_has2(source, p)) {
              obj[p] = source[p];
            }
          }
        }
        return obj;
      };
      module.exports.flattenChunks = (chunks) => {
        let len = 0;
        for (let i = 0, l = chunks.length; i < l; i++) {
          len += chunks[i].length;
        }
        const result = new Uint8Array(len);
        for (let i = 0, pos = 0, l = chunks.length; i < l; i++) {
          let chunk = chunks[i];
          result.set(chunk, pos);
          pos += chunk.length;
        }
        return result;
      };
    }
  });

  // node_modules/pako/lib/utils/strings.js
  var require_strings = __commonJS({
    "node_modules/pako/lib/utils/strings.js"(exports, module) {
      "use strict";
      var STR_APPLY_UIA_OK2 = true;
      try {
        String.fromCharCode.apply(null, new Uint8Array(1));
      } catch (__) {
        STR_APPLY_UIA_OK2 = false;
      }
      var _utf8len2 = new Uint8Array(256);
      for (let q = 0; q < 256; q++) {
        _utf8len2[q] = q >= 252 ? 6 : q >= 248 ? 5 : q >= 240 ? 4 : q >= 224 ? 3 : q >= 192 ? 2 : 1;
      }
      _utf8len2[254] = _utf8len2[255] = 1;
      module.exports.string2buf = (str) => {
        if (typeof TextEncoder === "function" && TextEncoder.prototype.encode) {
          return new TextEncoder().encode(str);
        }
        let buf, c, c2, m_pos, i, str_len = str.length, buf_len = 0;
        for (m_pos = 0; m_pos < str_len; m_pos++) {
          c = str.charCodeAt(m_pos);
          if ((c & 64512) === 55296 && m_pos + 1 < str_len) {
            c2 = str.charCodeAt(m_pos + 1);
            if ((c2 & 64512) === 56320) {
              c = 65536 + (c - 55296 << 10) + (c2 - 56320);
              m_pos++;
            }
          }
          buf_len += c < 128 ? 1 : c < 2048 ? 2 : c < 65536 ? 3 : 4;
        }
        buf = new Uint8Array(buf_len);
        for (i = 0, m_pos = 0; i < buf_len; m_pos++) {
          c = str.charCodeAt(m_pos);
          if ((c & 64512) === 55296 && m_pos + 1 < str_len) {
            c2 = str.charCodeAt(m_pos + 1);
            if ((c2 & 64512) === 56320) {
              c = 65536 + (c - 55296 << 10) + (c2 - 56320);
              m_pos++;
            }
          }
          if (c < 128) {
            buf[i++] = c;
          } else if (c < 2048) {
            buf[i++] = 192 | c >>> 6;
            buf[i++] = 128 | c & 63;
          } else if (c < 65536) {
            buf[i++] = 224 | c >>> 12;
            buf[i++] = 128 | c >>> 6 & 63;
            buf[i++] = 128 | c & 63;
          } else {
            buf[i++] = 240 | c >>> 18;
            buf[i++] = 128 | c >>> 12 & 63;
            buf[i++] = 128 | c >>> 6 & 63;
            buf[i++] = 128 | c & 63;
          }
        }
        return buf;
      };
      var buf2binstring2 = (buf, len) => {
        if (len < 65534) {
          if (buf.subarray && STR_APPLY_UIA_OK2) {
            return String.fromCharCode.apply(null, buf.length === len ? buf : buf.subarray(0, len));
          }
        }
        let result = "";
        for (let i = 0; i < len; i++) {
          result += String.fromCharCode(buf[i]);
        }
        return result;
      };
      module.exports.buf2string = (buf, max) => {
        const len = max || buf.length;
        if (typeof TextDecoder === "function" && TextDecoder.prototype.decode) {
          return new TextDecoder().decode(buf.subarray(0, max));
        }
        let i, out;
        const utf16buf = new Array(len * 2);
        for (out = 0, i = 0; i < len; ) {
          let c = buf[i++];
          if (c < 128) {
            utf16buf[out++] = c;
            continue;
          }
          let c_len = _utf8len2[c];
          if (c_len > 4) {
            utf16buf[out++] = 65533;
            i += c_len - 1;
            continue;
          }
          c &= c_len === 2 ? 31 : c_len === 3 ? 15 : 7;
          while (c_len > 1 && i < len) {
            c = c << 6 | buf[i++] & 63;
            c_len--;
          }
          if (c_len > 1) {
            utf16buf[out++] = 65533;
            continue;
          }
          if (c < 65536) {
            utf16buf[out++] = c;
          } else {
            c -= 65536;
            utf16buf[out++] = 55296 | c >> 10 & 1023;
            utf16buf[out++] = 56320 | c & 1023;
          }
        }
        return buf2binstring2(utf16buf, out);
      };
      module.exports.utf8border = (buf, max) => {
        max = max || buf.length;
        if (max > buf.length) {
          max = buf.length;
        }
        let pos = max - 1;
        while (pos >= 0 && (buf[pos] & 192) === 128) {
          pos--;
        }
        if (pos < 0) {
          return max;
        }
        if (pos === 0) {
          return max;
        }
        return pos + _utf8len2[buf[pos]] > max ? pos : max;
      };
    }
  });

  // node_modules/pako/lib/zlib/zstream.js
  var require_zstream = __commonJS({
    "node_modules/pako/lib/zlib/zstream.js"(exports, module) {
      "use strict";
      function ZStream2() {
        this.input = null;
        this.next_in = 0;
        this.avail_in = 0;
        this.total_in = 0;
        this.output = null;
        this.next_out = 0;
        this.avail_out = 0;
        this.total_out = 0;
        this.msg = "";
        this.state = null;
        this.data_type = 2;
        this.adler = 0;
      }
      module.exports = ZStream2;
    }
  });

  // node_modules/pako/lib/deflate.js
  var require_deflate2 = __commonJS({
    "node_modules/pako/lib/deflate.js"(exports, module) {
      "use strict";
      var zlib_deflate = require_deflate();
      var utils = require_common();
      var strings2 = require_strings();
      var msg = require_messages();
      var ZStream2 = require_zstream();
      var toString2 = Object.prototype.toString;
      var {
        Z_NO_FLUSH: Z_NO_FLUSH2,
        Z_SYNC_FLUSH: Z_SYNC_FLUSH2,
        Z_FULL_FLUSH: Z_FULL_FLUSH2,
        Z_FINISH: Z_FINISH2,
        Z_OK: Z_OK2,
        Z_STREAM_END: Z_STREAM_END2,
        Z_DEFAULT_COMPRESSION: Z_DEFAULT_COMPRESSION2,
        Z_DEFAULT_STRATEGY: Z_DEFAULT_STRATEGY2,
        Z_DEFLATED: Z_DEFLATED2
      } = require_constants();
      var defaultOptions2 = {
        level: Z_DEFAULT_COMPRESSION2,
        method: Z_DEFLATED2,
        chunkSize: 16384,
        windowBits: 15,
        memLevel: 8,
        strategy: Z_DEFAULT_STRATEGY2,
        legacyHash: true
      };
      function Deflate2(options) {
        this.options = utils.assign({}, defaultOptions2, options || {});
        let opt = this.options;
        if (opt.raw && opt.windowBits > 0) {
          opt.windowBits = -opt.windowBits;
        } else if (opt.gzip && opt.windowBits > 0 && opt.windowBits < 16) {
          opt.windowBits += 16;
        }
        this.err = 0;
        this.msg = "";
        this.ended = false;
        this.chunks = [];
        this.strm = new ZStream2();
        this.strm.avail_out = 0;
        let status = zlib_deflate.deflateInit2(
          this.strm,
          opt.level,
          opt.method,
          opt.windowBits,
          opt.memLevel,
          opt.strategy,
          opt.legacyHash
        );
        if (status !== Z_OK2) {
          throw new Error(msg[status]);
        }
        if (opt.header) {
          zlib_deflate.deflateSetHeader(this.strm, opt.header);
        }
        if (opt.dictionary) {
          let dict;
          if (typeof opt.dictionary === "string") {
            dict = strings2.string2buf(opt.dictionary);
          } else if (toString2.call(opt.dictionary) === "[object ArrayBuffer]") {
            dict = new Uint8Array(opt.dictionary);
          } else {
            dict = opt.dictionary;
          }
          status = zlib_deflate.deflateSetDictionary(this.strm, dict);
          if (status !== Z_OK2) {
            throw new Error(msg[status]);
          }
          this._dict_set = true;
        }
      }
      Deflate2.prototype.push = function(data, flush_mode) {
        const strm = this.strm;
        const chunkSize = this.options.chunkSize;
        let status, _flush_mode;
        if (this.ended) {
          return false;
        }
        if (flush_mode === ~~flush_mode) _flush_mode = flush_mode;
        else _flush_mode = flush_mode === true ? Z_FINISH2 : Z_NO_FLUSH2;
        if (typeof data === "string") {
          strm.input = strings2.string2buf(data);
        } else if (toString2.call(data) === "[object ArrayBuffer]") {
          strm.input = new Uint8Array(data);
        } else {
          strm.input = data;
        }
        strm.next_in = 0;
        strm.avail_in = strm.input.length;
        for (; ; ) {
          if (strm.avail_out === 0) {
            strm.output = new Uint8Array(chunkSize);
            strm.next_out = 0;
            strm.avail_out = chunkSize;
          }
          if ((_flush_mode === Z_SYNC_FLUSH2 || _flush_mode === Z_FULL_FLUSH2) && strm.avail_out <= 6) {
            this.onData(strm.output.subarray(0, strm.next_out));
            strm.avail_out = 0;
            continue;
          }
          status = zlib_deflate.deflate(strm, _flush_mode);
          if (status === Z_STREAM_END2) {
            if (strm.next_out > 0) {
              this.onData(strm.output.subarray(0, strm.next_out));
            }
            status = zlib_deflate.deflateEnd(this.strm);
            this.onEnd(status);
            this.ended = true;
            return status === Z_OK2;
          }
          if (strm.avail_out === 0) {
            this.onData(strm.output);
            continue;
          }
          if (_flush_mode > 0 && strm.next_out > 0) {
            this.onData(strm.output.subarray(0, strm.next_out));
            strm.avail_out = 0;
            continue;
          }
          if (strm.avail_in === 0) break;
        }
        return true;
      };
      Deflate2.prototype.onData = function(chunk) {
        this.chunks.push(chunk);
      };
      Deflate2.prototype.onEnd = function(status) {
        if (status === Z_OK2) {
          this.result = utils.flattenChunks(this.chunks);
        }
        this.chunks = [];
        this.err = status;
        this.msg = this.strm.msg;
      };
      function deflate2(input, options) {
        const deflator = new Deflate2(options);
        deflator.push(input, true);
        if (deflator.err) {
          throw deflator.msg || msg[deflator.err];
        }
        return deflator.result;
      }
      function deflateRaw2(input, options) {
        options = options || {};
        options.raw = true;
        return deflate2(input, options);
      }
      function gzip2(input, options) {
        options = options || {};
        options.gzip = true;
        return deflate2(input, options);
      }
      module.exports.Deflate = Deflate2;
      module.exports.deflate = deflate2;
      module.exports.deflateRaw = deflateRaw2;
      module.exports.gzip = gzip2;
      module.exports.constants = require_constants();
    }
  });

  // node_modules/pako/lib/zlib/inffast.js
  var require_inffast = __commonJS({
    "node_modules/pako/lib/zlib/inffast.js"(exports, module) {
      "use strict";
      var BAD2 = 16209;
      var TYPE2 = 16191;
      module.exports = function inflate_fast2(strm, start) {
        let _in;
        let last;
        let _out;
        let beg;
        let end;
        let dmax;
        let wsize;
        let whave;
        let wnext;
        let s_window;
        let hold;
        let bits;
        let lcode;
        let dcode;
        let lmask;
        let dmask;
        let here;
        let op;
        let len;
        let dist;
        let from;
        let from_source;
        let input, output;
        const state = strm.state;
        _in = strm.next_in;
        input = strm.input;
        last = _in + (strm.avail_in - 5);
        _out = strm.next_out;
        output = strm.output;
        beg = _out - (start - strm.avail_out);
        end = _out + (strm.avail_out - 257);
        dmax = state.dmax;
        wsize = state.wsize;
        whave = state.whave;
        wnext = state.wnext;
        s_window = state.window;
        hold = state.hold;
        bits = state.bits;
        lcode = state.lencode;
        dcode = state.distcode;
        lmask = (1 << state.lenbits) - 1;
        dmask = (1 << state.distbits) - 1;
        top:
          do {
            if (bits < 15) {
              hold += input[_in++] << bits;
              bits += 8;
              hold += input[_in++] << bits;
              bits += 8;
            }
            here = lcode[hold & lmask];
            dolen:
              for (; ; ) {
                op = here >>> 24;
                hold >>>= op;
                bits -= op;
                op = here >>> 16 & 255;
                if (op === 0) {
                  output[_out++] = here & 65535;
                } else if (op & 16) {
                  len = here & 65535;
                  op &= 15;
                  if (op) {
                    if (bits < op) {
                      hold += input[_in++] << bits;
                      bits += 8;
                    }
                    len += hold & (1 << op) - 1;
                    hold >>>= op;
                    bits -= op;
                  }
                  if (bits < 15) {
                    hold += input[_in++] << bits;
                    bits += 8;
                    hold += input[_in++] << bits;
                    bits += 8;
                  }
                  here = dcode[hold & dmask];
                  dodist:
                    for (; ; ) {
                      op = here >>> 24;
                      hold >>>= op;
                      bits -= op;
                      op = here >>> 16 & 255;
                      if (op & 16) {
                        dist = here & 65535;
                        op &= 15;
                        if (bits < op) {
                          hold += input[_in++] << bits;
                          bits += 8;
                          if (bits < op) {
                            hold += input[_in++] << bits;
                            bits += 8;
                          }
                        }
                        dist += hold & (1 << op) - 1;
                        if (dist > dmax) {
                          strm.msg = "invalid distance too far back";
                          state.mode = BAD2;
                          break top;
                        }
                        hold >>>= op;
                        bits -= op;
                        op = _out - beg;
                        if (dist > op) {
                          op = dist - op;
                          if (op > whave) {
                            if (state.sane) {
                              strm.msg = "invalid distance too far back";
                              state.mode = BAD2;
                              break top;
                            }
                          }
                          from = 0;
                          from_source = s_window;
                          if (wnext === 0) {
                            from += wsize - op;
                            if (op < len) {
                              len -= op;
                              do {
                                output[_out++] = s_window[from++];
                              } while (--op);
                              from = _out - dist;
                              from_source = output;
                            }
                          } else if (wnext < op) {
                            from += wsize + wnext - op;
                            op -= wnext;
                            if (op < len) {
                              len -= op;
                              do {
                                output[_out++] = s_window[from++];
                              } while (--op);
                              from = 0;
                              if (wnext < len) {
                                op = wnext;
                                len -= op;
                                do {
                                  output[_out++] = s_window[from++];
                                } while (--op);
                                from = _out - dist;
                                from_source = output;
                              }
                            }
                          } else {
                            from += wnext - op;
                            if (op < len) {
                              len -= op;
                              do {
                                output[_out++] = s_window[from++];
                              } while (--op);
                              from = _out - dist;
                              from_source = output;
                            }
                          }
                          while (len > 2) {
                            output[_out++] = from_source[from++];
                            output[_out++] = from_source[from++];
                            output[_out++] = from_source[from++];
                            len -= 3;
                          }
                          if (len) {
                            output[_out++] = from_source[from++];
                            if (len > 1) {
                              output[_out++] = from_source[from++];
                            }
                          }
                        } else {
                          from = _out - dist;
                          do {
                            output[_out++] = output[from++];
                            output[_out++] = output[from++];
                            output[_out++] = output[from++];
                            len -= 3;
                          } while (len > 2);
                          if (len) {
                            output[_out++] = output[from++];
                            if (len > 1) {
                              output[_out++] = output[from++];
                            }
                          }
                        }
                      } else if ((op & 64) === 0) {
                        here = dcode[(here & 65535) + (hold & (1 << op) - 1)];
                        continue dodist;
                      } else {
                        strm.msg = "invalid distance code";
                        state.mode = BAD2;
                        break top;
                      }
                      break;
                    }
                } else if ((op & 64) === 0) {
                  here = lcode[(here & 65535) + (hold & (1 << op) - 1)];
                  continue dolen;
                } else if (op & 32) {
                  state.mode = TYPE2;
                  break top;
                } else {
                  strm.msg = "invalid literal/length code";
                  state.mode = BAD2;
                  break top;
                }
                break;
              }
          } while (_in < last && _out < end);
        len = bits >> 3;
        _in -= len;
        bits -= len << 3;
        hold &= (1 << bits) - 1;
        strm.next_in = _in;
        strm.next_out = _out;
        strm.avail_in = _in < last ? 5 + (last - _in) : 5 - (_in - last);
        strm.avail_out = _out < end ? 257 + (end - _out) : 257 - (_out - end);
        state.hold = hold;
        state.bits = bits;
        return;
      };
    }
  });

  // node_modules/pako/lib/zlib/inftrees.js
  var require_inftrees = __commonJS({
    "node_modules/pako/lib/zlib/inftrees.js"(exports, module) {
      "use strict";
      var MAXBITS2 = 15;
      var ENOUGH_LENS2 = 852;
      var ENOUGH_DISTS2 = 592;
      var CODES2 = 0;
      var LENS2 = 1;
      var DISTS2 = 2;
      var lbase2 = new Uint16Array([
        /* Length codes 257..285 base */
        3,
        4,
        5,
        6,
        7,
        8,
        9,
        10,
        11,
        13,
        15,
        17,
        19,
        23,
        27,
        31,
        35,
        43,
        51,
        59,
        67,
        83,
        99,
        115,
        131,
        163,
        195,
        227,
        258,
        0,
        0
      ]);
      var lext2 = new Uint8Array([
        /* Length codes 257..285 extra */
        16,
        16,
        16,
        16,
        16,
        16,
        16,
        16,
        17,
        17,
        17,
        17,
        18,
        18,
        18,
        18,
        19,
        19,
        19,
        19,
        20,
        20,
        20,
        20,
        21,
        21,
        21,
        21,
        16,
        199,
        75
      ]);
      var dbase2 = new Uint16Array([
        /* Distance codes 0..29 base */
        1,
        2,
        3,
        4,
        5,
        7,
        9,
        13,
        17,
        25,
        33,
        49,
        65,
        97,
        129,
        193,
        257,
        385,
        513,
        769,
        1025,
        1537,
        2049,
        3073,
        4097,
        6145,
        8193,
        12289,
        16385,
        24577,
        0,
        0
      ]);
      var dext2 = new Uint8Array([
        /* Distance codes 0..29 extra */
        16,
        16,
        16,
        16,
        17,
        17,
        18,
        18,
        19,
        19,
        20,
        20,
        21,
        21,
        22,
        22,
        23,
        23,
        24,
        24,
        25,
        25,
        26,
        26,
        27,
        27,
        28,
        28,
        29,
        29,
        64,
        64
      ]);
      var inflate_table2 = (type, lens, lens_index, codes, table, table_index, work, opts) => {
        const bits = opts.bits;
        let len = 0;
        let sym = 0;
        let min = 0, max = 0;
        let root = 0;
        let curr = 0;
        let drop = 0;
        let left = 0;
        let used = 0;
        let huff = 0;
        let incr;
        let fill;
        let low;
        let mask;
        let next;
        let base = null;
        let match;
        const count = new Uint16Array(MAXBITS2 + 1);
        const offs = new Uint16Array(MAXBITS2 + 1);
        let extra = null;
        let here_bits, here_op, here_val;
        for (len = 0; len <= MAXBITS2; len++) {
          count[len] = 0;
        }
        for (sym = 0; sym < codes; sym++) {
          count[lens[lens_index + sym]]++;
        }
        root = bits;
        for (max = MAXBITS2; max >= 1; max--) {
          if (count[max] !== 0) {
            break;
          }
        }
        if (root > max) {
          root = max;
        }
        if (max === 0) {
          table[table_index++] = 1 << 24 | 64 << 16 | 0;
          table[table_index++] = 1 << 24 | 64 << 16 | 0;
          opts.bits = 1;
          return 0;
        }
        for (min = 1; min < max; min++) {
          if (count[min] !== 0) {
            break;
          }
        }
        if (root < min) {
          root = min;
        }
        left = 1;
        for (len = 1; len <= MAXBITS2; len++) {
          left <<= 1;
          left -= count[len];
          if (left < 0) {
            return -1;
          }
        }
        if (left > 0 && (type === CODES2 || max !== 1)) {
          return -1;
        }
        offs[1] = 0;
        for (len = 1; len < MAXBITS2; len++) {
          offs[len + 1] = offs[len] + count[len];
        }
        for (sym = 0; sym < codes; sym++) {
          if (lens[lens_index + sym] !== 0) {
            work[offs[lens[lens_index + sym]]++] = sym;
          }
        }
        if (type === CODES2) {
          base = extra = work;
          match = 20;
        } else if (type === LENS2) {
          base = lbase2;
          extra = lext2;
          match = 257;
        } else {
          base = dbase2;
          extra = dext2;
          match = 0;
        }
        huff = 0;
        sym = 0;
        len = min;
        next = table_index;
        curr = root;
        drop = 0;
        low = -1;
        used = 1 << root;
        mask = used - 1;
        if (type === LENS2 && used > ENOUGH_LENS2 || type === DISTS2 && used > ENOUGH_DISTS2) {
          return 1;
        }
        for (; ; ) {
          here_bits = len - drop;
          if (work[sym] + 1 < match) {
            here_op = 0;
            here_val = work[sym];
          } else if (work[sym] >= match) {
            here_op = extra[work[sym] - match];
            here_val = base[work[sym] - match];
          } else {
            here_op = 32 + 64;
            here_val = 0;
          }
          incr = 1 << len - drop;
          fill = 1 << curr;
          min = fill;
          do {
            fill -= incr;
            table[next + (huff >> drop) + fill] = here_bits << 24 | here_op << 16 | here_val | 0;
          } while (fill !== 0);
          incr = 1 << len - 1;
          while (huff & incr) {
            incr >>= 1;
          }
          if (incr !== 0) {
            huff &= incr - 1;
            huff += incr;
          } else {
            huff = 0;
          }
          sym++;
          if (--count[len] === 0) {
            if (len === max) {
              break;
            }
            len = lens[lens_index + work[sym]];
          }
          if (len > root && (huff & mask) !== low) {
            if (drop === 0) {
              drop = root;
            }
            next += min;
            curr = len - drop;
            left = 1 << curr;
            while (curr + drop < max) {
              left -= count[curr + drop];
              if (left <= 0) {
                break;
              }
              curr++;
              left <<= 1;
            }
            used += 1 << curr;
            if (type === LENS2 && used > ENOUGH_LENS2 || type === DISTS2 && used > ENOUGH_DISTS2) {
              return 1;
            }
            low = huff & mask;
            table[low] = root << 24 | curr << 16 | next - table_index | 0;
          }
        }
        if (huff !== 0) {
          table[next + huff] = len - drop << 24 | 64 << 16 | 0;
        }
        opts.bits = root;
        return 0;
      };
      module.exports = inflate_table2;
    }
  });

  // node_modules/pako/lib/zlib/inflate.js
  var require_inflate = __commonJS({
    "node_modules/pako/lib/zlib/inflate.js"(exports, module) {
      "use strict";
      var adler322 = require_adler32();
      var crc322 = require_crc32();
      var inflate_fast2 = require_inffast();
      var inflate_table2 = require_inftrees();
      var CODES2 = 0;
      var LENS2 = 1;
      var DISTS2 = 2;
      var {
        Z_FINISH: Z_FINISH2,
        Z_BLOCK: Z_BLOCK2,
        Z_TREES: Z_TREES2,
        Z_OK: Z_OK2,
        Z_STREAM_END: Z_STREAM_END2,
        Z_NEED_DICT: Z_NEED_DICT2,
        Z_STREAM_ERROR: Z_STREAM_ERROR2,
        Z_DATA_ERROR: Z_DATA_ERROR2,
        Z_MEM_ERROR: Z_MEM_ERROR2,
        Z_BUF_ERROR: Z_BUF_ERROR2,
        Z_DEFLATED: Z_DEFLATED2
      } = require_constants();
      var HEAD2 = 16180;
      var FLAGS2 = 16181;
      var TIME2 = 16182;
      var OS2 = 16183;
      var EXLEN2 = 16184;
      var EXTRA2 = 16185;
      var NAME2 = 16186;
      var COMMENT2 = 16187;
      var HCRC2 = 16188;
      var DICTID2 = 16189;
      var DICT2 = 16190;
      var TYPE2 = 16191;
      var TYPEDO2 = 16192;
      var STORED2 = 16193;
      var COPY_2 = 16194;
      var COPY2 = 16195;
      var TABLE2 = 16196;
      var LENLENS2 = 16197;
      var CODELENS2 = 16198;
      var LEN_2 = 16199;
      var LEN2 = 16200;
      var LENEXT2 = 16201;
      var DIST2 = 16202;
      var DISTEXT2 = 16203;
      var MATCH2 = 16204;
      var LIT2 = 16205;
      var CHECK2 = 16206;
      var LENGTH2 = 16207;
      var DONE2 = 16208;
      var BAD2 = 16209;
      var MEM2 = 16210;
      var SYNC2 = 16211;
      var ENOUGH_LENS2 = 852;
      var ENOUGH_DISTS2 = 592;
      var MAX_WBITS2 = 15;
      var DEF_WBITS2 = MAX_WBITS2;
      var zswap322 = (q) => {
        return (q >>> 24 & 255) + (q >>> 8 & 65280) + ((q & 65280) << 8) + ((q & 255) << 24);
      };
      function InflateState2() {
        this.strm = null;
        this.mode = 0;
        this.last = false;
        this.wrap = 0;
        this.havedict = false;
        this.flags = 0;
        this.dmax = 0;
        this.check = 0;
        this.total = 0;
        this.head = null;
        this.wbits = 0;
        this.wsize = 0;
        this.whave = 0;
        this.wnext = 0;
        this.window = null;
        this.hold = 0;
        this.bits = 0;
        this.length = 0;
        this.offset = 0;
        this.extra = 0;
        this.lencode = null;
        this.distcode = null;
        this.lenbits = 0;
        this.distbits = 0;
        this.ncode = 0;
        this.nlen = 0;
        this.ndist = 0;
        this.have = 0;
        this.next = null;
        this.lens = new Uint16Array(320);
        this.work = new Uint16Array(288);
        this.lendyn = null;
        this.distdyn = null;
        this.sane = 0;
        this.back = 0;
        this.was = 0;
      }
      var inflateStateCheck2 = (strm) => {
        if (!strm) {
          return 1;
        }
        const state = strm.state;
        if (!state || state.strm !== strm || state.mode < HEAD2 || state.mode > SYNC2) {
          return 1;
        }
        return 0;
      };
      var inflateResetKeep2 = (strm) => {
        if (inflateStateCheck2(strm)) {
          return Z_STREAM_ERROR2;
        }
        const state = strm.state;
        strm.total_in = strm.total_out = state.total = 0;
        strm.msg = "";
        if (state.wrap) {
          strm.adler = state.wrap & 1;
        }
        state.mode = HEAD2;
        state.last = 0;
        state.havedict = 0;
        state.flags = -1;
        state.dmax = 32768;
        state.head = null;
        state.hold = 0;
        state.bits = 0;
        state.lencode = state.lendyn = new Int32Array(ENOUGH_LENS2);
        state.distcode = state.distdyn = new Int32Array(ENOUGH_DISTS2);
        state.sane = 1;
        state.back = -1;
        return Z_OK2;
      };
      var inflateReset3 = (strm) => {
        if (inflateStateCheck2(strm)) {
          return Z_STREAM_ERROR2;
        }
        const state = strm.state;
        state.wsize = 0;
        state.whave = 0;
        state.wnext = 0;
        return inflateResetKeep2(strm);
      };
      var inflateReset22 = (strm, windowBits) => {
        let wrap;
        if (inflateStateCheck2(strm)) {
          return Z_STREAM_ERROR2;
        }
        const state = strm.state;
        if (windowBits < 0) {
          wrap = 0;
          windowBits = -windowBits;
        } else {
          wrap = (windowBits >> 4) + 5;
          if (windowBits < 48) {
            windowBits &= 15;
          }
        }
        if (windowBits && (windowBits < 8 || windowBits > 15)) {
          return Z_STREAM_ERROR2;
        }
        if (state.window !== null && state.wbits !== windowBits) {
          state.window = null;
        }
        state.wrap = wrap;
        state.wbits = windowBits;
        return inflateReset3(strm);
      };
      var inflateInit22 = (strm, windowBits) => {
        if (!strm) {
          return Z_STREAM_ERROR2;
        }
        const state = new InflateState2();
        strm.state = state;
        state.strm = strm;
        state.window = null;
        state.mode = HEAD2;
        const ret = inflateReset22(strm, windowBits);
        if (ret !== Z_OK2) {
          strm.state = null;
        }
        return ret;
      };
      var inflateInit3 = (strm) => {
        return inflateInit22(strm, DEF_WBITS2);
      };
      var virgin2 = true;
      var lenfix2;
      var distfix2;
      var fixedtables2 = (state) => {
        if (virgin2) {
          lenfix2 = new Int32Array(512);
          distfix2 = new Int32Array(32);
          let sym = 0;
          while (sym < 144) {
            state.lens[sym++] = 8;
          }
          while (sym < 256) {
            state.lens[sym++] = 9;
          }
          while (sym < 280) {
            state.lens[sym++] = 7;
          }
          while (sym < 288) {
            state.lens[sym++] = 8;
          }
          inflate_table2(LENS2, state.lens, 0, 288, lenfix2, 0, state.work, { bits: 9 });
          sym = 0;
          while (sym < 32) {
            state.lens[sym++] = 5;
          }
          inflate_table2(DISTS2, state.lens, 0, 32, distfix2, 0, state.work, { bits: 5 });
          virgin2 = false;
        }
        state.lencode = lenfix2;
        state.lenbits = 9;
        state.distcode = distfix2;
        state.distbits = 5;
      };
      var updatewindow2 = (strm, src, end, copy) => {
        let dist;
        const state = strm.state;
        if (state.window === null) {
          state.window = new Uint8Array(1 << state.wbits);
        }
        if (state.wsize === 0) {
          state.wsize = 1 << state.wbits;
          state.wnext = 0;
          state.whave = 0;
        }
        if (copy >= state.wsize) {
          state.window.set(src.subarray(end - state.wsize, end), 0);
          state.wnext = 0;
          state.whave = state.wsize;
        } else {
          dist = state.wsize - state.wnext;
          if (dist > copy) {
            dist = copy;
          }
          state.window.set(src.subarray(end - copy, end - copy + dist), state.wnext);
          copy -= dist;
          if (copy) {
            state.window.set(src.subarray(end - copy, end), 0);
            state.wnext = copy;
            state.whave = state.wsize;
          } else {
            state.wnext += dist;
            if (state.wnext === state.wsize) {
              state.wnext = 0;
            }
            if (state.whave < state.wsize) {
              state.whave += dist;
            }
          }
        }
        return 0;
      };
      var inflate3 = (strm, flush) => {
        let state;
        let input, output;
        let next;
        let put;
        let have, left;
        let hold;
        let bits;
        let _in, _out;
        let copy;
        let from;
        let from_source;
        let here = 0;
        let here_bits, here_op, here_val;
        let last_bits, last_op, last_val;
        let len;
        let ret;
        const hbuf = new Uint8Array(4);
        let opts;
        let n;
        const order = (
          /* permutation of code lengths */
          new Uint8Array([16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15])
        );
        if (inflateStateCheck2(strm) || !strm.output || !strm.input && strm.avail_in !== 0) {
          return Z_STREAM_ERROR2;
        }
        state = strm.state;
        if (state.mode === TYPE2) {
          state.mode = TYPEDO2;
        }
        put = strm.next_out;
        output = strm.output;
        left = strm.avail_out;
        next = strm.next_in;
        input = strm.input;
        have = strm.avail_in;
        hold = state.hold;
        bits = state.bits;
        _in = have;
        _out = left;
        ret = Z_OK2;
        inf_leave:
          for (; ; ) {
            switch (state.mode) {
              case HEAD2:
                if (state.wrap === 0) {
                  state.mode = TYPEDO2;
                  break;
                }
                while (bits < 16) {
                  if (have === 0) {
                    break inf_leave;
                  }
                  have--;
                  hold += input[next++] << bits;
                  bits += 8;
                }
                if (state.wrap & 2 && hold === 35615) {
                  if (state.wbits === 0) {
                    state.wbits = 15;
                  }
                  state.check = 0;
                  hbuf[0] = hold & 255;
                  hbuf[1] = hold >>> 8 & 255;
                  state.check = crc322(state.check, hbuf, 2, 0);
                  hold = 0;
                  bits = 0;
                  state.mode = FLAGS2;
                  break;
                }
                if (state.head) {
                  state.head.done = false;
                }
                if (!(state.wrap & 1) || /* check if zlib header allowed */
                (((hold & 255) << 8) + (hold >> 8)) % 31) {
                  strm.msg = "incorrect header check";
                  state.mode = BAD2;
                  break;
                }
                if ((hold & 15) !== Z_DEFLATED2) {
                  strm.msg = "unknown compression method";
                  state.mode = BAD2;
                  break;
                }
                hold >>>= 4;
                bits -= 4;
                len = (hold & 15) + 8;
                if (state.wbits === 0) {
                  state.wbits = len;
                }
                if (len > 15 || len > state.wbits) {
                  strm.msg = "invalid window size";
                  state.mode = BAD2;
                  break;
                }
                state.dmax = 1 << state.wbits;
                state.flags = 0;
                strm.adler = state.check = 1;
                state.mode = hold & 512 ? DICTID2 : TYPE2;
                hold = 0;
                bits = 0;
                break;
              case FLAGS2:
                while (bits < 16) {
                  if (have === 0) {
                    break inf_leave;
                  }
                  have--;
                  hold += input[next++] << bits;
                  bits += 8;
                }
                state.flags = hold;
                if ((state.flags & 255) !== Z_DEFLATED2) {
                  strm.msg = "unknown compression method";
                  state.mode = BAD2;
                  break;
                }
                if (state.flags & 57344) {
                  strm.msg = "unknown header flags set";
                  state.mode = BAD2;
                  break;
                }
                if (state.head) {
                  state.head.text = hold >> 8 & 1;
                }
                if (state.flags & 512 && state.wrap & 4) {
                  hbuf[0] = hold & 255;
                  hbuf[1] = hold >>> 8 & 255;
                  state.check = crc322(state.check, hbuf, 2, 0);
                }
                hold = 0;
                bits = 0;
                state.mode = TIME2;
              /* falls through */
              case TIME2:
                while (bits < 32) {
                  if (have === 0) {
                    break inf_leave;
                  }
                  have--;
                  hold += input[next++] << bits;
                  bits += 8;
                }
                if (state.head) {
                  state.head.time = hold;
                }
                if (state.flags & 512 && state.wrap & 4) {
                  hbuf[0] = hold & 255;
                  hbuf[1] = hold >>> 8 & 255;
                  hbuf[2] = hold >>> 16 & 255;
                  hbuf[3] = hold >>> 24 & 255;
                  state.check = crc322(state.check, hbuf, 4, 0);
                }
                hold = 0;
                bits = 0;
                state.mode = OS2;
              /* falls through */
              case OS2:
                while (bits < 16) {
                  if (have === 0) {
                    break inf_leave;
                  }
                  have--;
                  hold += input[next++] << bits;
                  bits += 8;
                }
                if (state.head) {
                  state.head.xflags = hold & 255;
                  state.head.os = hold >> 8;
                }
                if (state.flags & 512 && state.wrap & 4) {
                  hbuf[0] = hold & 255;
                  hbuf[1] = hold >>> 8 & 255;
                  state.check = crc322(state.check, hbuf, 2, 0);
                }
                hold = 0;
                bits = 0;
                state.mode = EXLEN2;
              /* falls through */
              case EXLEN2:
                if (state.flags & 1024) {
                  while (bits < 16) {
                    if (have === 0) {
                      break inf_leave;
                    }
                    have--;
                    hold += input[next++] << bits;
                    bits += 8;
                  }
                  state.length = hold;
                  if (state.head) {
                    state.head.extra_len = hold;
                  }
                  if (state.flags & 512 && state.wrap & 4) {
                    hbuf[0] = hold & 255;
                    hbuf[1] = hold >>> 8 & 255;
                    state.check = crc322(state.check, hbuf, 2, 0);
                  }
                  hold = 0;
                  bits = 0;
                } else if (state.head) {
                  state.head.extra = null;
                }
                state.mode = EXTRA2;
              /* falls through */
              case EXTRA2:
                if (state.flags & 1024) {
                  copy = state.length;
                  if (copy > have) {
                    copy = have;
                  }
                  if (copy) {
                    if (state.head) {
                      len = state.head.extra_len - state.length;
                      if (!state.head.extra) {
                        state.head.extra = new Uint8Array(state.head.extra_len);
                      }
                      state.head.extra.set(
                        input.subarray(
                          next,
                          // extra field is limited to 65536 bytes
                          // - no need for additional size check
                          next + copy
                        ),
                        /*len + copy > state.head.extra_max - len ? state.head.extra_max : copy,*/
                        len
                      );
                    }
                    if (state.flags & 512 && state.wrap & 4) {
                      state.check = crc322(state.check, input, copy, next);
                    }
                    have -= copy;
                    next += copy;
                    state.length -= copy;
                  }
                  if (state.length) {
                    break inf_leave;
                  }
                }
                state.length = 0;
                state.mode = NAME2;
              /* falls through */
              case NAME2:
                if (state.flags & 2048) {
                  if (have === 0) {
                    break inf_leave;
                  }
                  copy = 0;
                  do {
                    len = input[next + copy++];
                    if (state.head && len && state.length < 65536) {
                      state.head.name += String.fromCharCode(len);
                    }
                  } while (len && copy < have);
                  if (state.flags & 512 && state.wrap & 4) {
                    state.check = crc322(state.check, input, copy, next);
                  }
                  have -= copy;
                  next += copy;
                  if (len) {
                    break inf_leave;
                  }
                } else if (state.head) {
                  state.head.name = null;
                }
                state.length = 0;
                state.mode = COMMENT2;
              /* falls through */
              case COMMENT2:
                if (state.flags & 4096) {
                  if (have === 0) {
                    break inf_leave;
                  }
                  copy = 0;
                  do {
                    len = input[next + copy++];
                    if (state.head && len && state.length < 65536) {
                      state.head.comment += String.fromCharCode(len);
                    }
                  } while (len && copy < have);
                  if (state.flags & 512 && state.wrap & 4) {
                    state.check = crc322(state.check, input, copy, next);
                  }
                  have -= copy;
                  next += copy;
                  if (len) {
                    break inf_leave;
                  }
                } else if (state.head) {
                  state.head.comment = null;
                }
                state.mode = HCRC2;
              /* falls through */
              case HCRC2:
                if (state.flags & 512) {
                  while (bits < 16) {
                    if (have === 0) {
                      break inf_leave;
                    }
                    have--;
                    hold += input[next++] << bits;
                    bits += 8;
                  }
                  if (state.wrap & 4 && hold !== (state.check & 65535)) {
                    strm.msg = "header crc mismatch";
                    state.mode = BAD2;
                    break;
                  }
                  hold = 0;
                  bits = 0;
                }
                if (state.head) {
                  state.head.hcrc = state.flags >> 9 & 1;
                  state.head.done = true;
                }
                strm.adler = state.check = 0;
                state.mode = TYPE2;
                break;
              case DICTID2:
                while (bits < 32) {
                  if (have === 0) {
                    break inf_leave;
                  }
                  have--;
                  hold += input[next++] << bits;
                  bits += 8;
                }
                strm.adler = state.check = zswap322(hold);
                hold = 0;
                bits = 0;
                state.mode = DICT2;
              /* falls through */
              case DICT2:
                if (state.havedict === 0) {
                  strm.next_out = put;
                  strm.avail_out = left;
                  strm.next_in = next;
                  strm.avail_in = have;
                  state.hold = hold;
                  state.bits = bits;
                  return Z_NEED_DICT2;
                }
                strm.adler = state.check = 1;
                state.mode = TYPE2;
              /* falls through */
              case TYPE2:
                if (flush === Z_BLOCK2 || flush === Z_TREES2) {
                  break inf_leave;
                }
              /* falls through */
              case TYPEDO2:
                if (state.last) {
                  hold >>>= bits & 7;
                  bits -= bits & 7;
                  state.mode = CHECK2;
                  break;
                }
                while (bits < 3) {
                  if (have === 0) {
                    break inf_leave;
                  }
                  have--;
                  hold += input[next++] << bits;
                  bits += 8;
                }
                state.last = hold & 1;
                hold >>>= 1;
                bits -= 1;
                switch (hold & 3) {
                  case 0:
                    state.mode = STORED2;
                    break;
                  case 1:
                    fixedtables2(state);
                    state.mode = LEN_2;
                    if (flush === Z_TREES2) {
                      hold >>>= 2;
                      bits -= 2;
                      break inf_leave;
                    }
                    break;
                  case 2:
                    state.mode = TABLE2;
                    break;
                  case 3:
                    strm.msg = "invalid block type";
                    state.mode = BAD2;
                }
                hold >>>= 2;
                bits -= 2;
                break;
              case STORED2:
                hold >>>= bits & 7;
                bits -= bits & 7;
                while (bits < 32) {
                  if (have === 0) {
                    break inf_leave;
                  }
                  have--;
                  hold += input[next++] << bits;
                  bits += 8;
                }
                if ((hold & 65535) !== (hold >>> 16 ^ 65535)) {
                  strm.msg = "invalid stored block lengths";
                  state.mode = BAD2;
                  break;
                }
                state.length = hold & 65535;
                hold = 0;
                bits = 0;
                state.mode = COPY_2;
                if (flush === Z_TREES2) {
                  break inf_leave;
                }
              /* falls through */
              case COPY_2:
                state.mode = COPY2;
              /* falls through */
              case COPY2:
                copy = state.length;
                if (copy) {
                  if (copy > have) {
                    copy = have;
                  }
                  if (copy > left) {
                    copy = left;
                  }
                  if (copy === 0) {
                    break inf_leave;
                  }
                  output.set(input.subarray(next, next + copy), put);
                  have -= copy;
                  next += copy;
                  left -= copy;
                  put += copy;
                  state.length -= copy;
                  break;
                }
                state.mode = TYPE2;
                break;
              case TABLE2:
                while (bits < 14) {
                  if (have === 0) {
                    break inf_leave;
                  }
                  have--;
                  hold += input[next++] << bits;
                  bits += 8;
                }
                state.nlen = (hold & 31) + 257;
                hold >>>= 5;
                bits -= 5;
                state.ndist = (hold & 31) + 1;
                hold >>>= 5;
                bits -= 5;
                state.ncode = (hold & 15) + 4;
                hold >>>= 4;
                bits -= 4;
                if (state.nlen > 286 || state.ndist > 30) {
                  strm.msg = "too many length or distance symbols";
                  state.mode = BAD2;
                  break;
                }
                state.have = 0;
                state.mode = LENLENS2;
              /* falls through */
              case LENLENS2:
                while (state.have < state.ncode) {
                  while (bits < 3) {
                    if (have === 0) {
                      break inf_leave;
                    }
                    have--;
                    hold += input[next++] << bits;
                    bits += 8;
                  }
                  state.lens[order[state.have++]] = hold & 7;
                  hold >>>= 3;
                  bits -= 3;
                }
                while (state.have < 19) {
                  state.lens[order[state.have++]] = 0;
                }
                state.lencode = state.lendyn;
                state.lenbits = 7;
                opts = { bits: state.lenbits };
                ret = inflate_table2(CODES2, state.lens, 0, 19, state.lencode, 0, state.work, opts);
                state.lenbits = opts.bits;
                if (ret) {
                  strm.msg = "invalid code lengths set";
                  state.mode = BAD2;
                  break;
                }
                state.have = 0;
                state.mode = CODELENS2;
              /* falls through */
              case CODELENS2:
                while (state.have < state.nlen + state.ndist) {
                  for (; ; ) {
                    here = state.lencode[hold & (1 << state.lenbits) - 1];
                    here_bits = here >>> 24;
                    here_op = here >>> 16 & 255;
                    here_val = here & 65535;
                    if (here_bits <= bits) {
                      break;
                    }
                    if (have === 0) {
                      break inf_leave;
                    }
                    have--;
                    hold += input[next++] << bits;
                    bits += 8;
                  }
                  if (here_val < 16) {
                    hold >>>= here_bits;
                    bits -= here_bits;
                    state.lens[state.have++] = here_val;
                  } else {
                    if (here_val === 16) {
                      n = here_bits + 2;
                      while (bits < n) {
                        if (have === 0) {
                          break inf_leave;
                        }
                        have--;
                        hold += input[next++] << bits;
                        bits += 8;
                      }
                      hold >>>= here_bits;
                      bits -= here_bits;
                      if (state.have === 0) {
                        strm.msg = "invalid bit length repeat";
                        state.mode = BAD2;
                        break;
                      }
                      len = state.lens[state.have - 1];
                      copy = 3 + (hold & 3);
                      hold >>>= 2;
                      bits -= 2;
                    } else if (here_val === 17) {
                      n = here_bits + 3;
                      while (bits < n) {
                        if (have === 0) {
                          break inf_leave;
                        }
                        have--;
                        hold += input[next++] << bits;
                        bits += 8;
                      }
                      hold >>>= here_bits;
                      bits -= here_bits;
                      len = 0;
                      copy = 3 + (hold & 7);
                      hold >>>= 3;
                      bits -= 3;
                    } else {
                      n = here_bits + 7;
                      while (bits < n) {
                        if (have === 0) {
                          break inf_leave;
                        }
                        have--;
                        hold += input[next++] << bits;
                        bits += 8;
                      }
                      hold >>>= here_bits;
                      bits -= here_bits;
                      len = 0;
                      copy = 11 + (hold & 127);
                      hold >>>= 7;
                      bits -= 7;
                    }
                    if (state.have + copy > state.nlen + state.ndist) {
                      strm.msg = "invalid bit length repeat";
                      state.mode = BAD2;
                      break;
                    }
                    while (copy--) {
                      state.lens[state.have++] = len;
                    }
                  }
                }
                if (state.mode === BAD2) {
                  break;
                }
                if (state.lens[256] === 0) {
                  strm.msg = "invalid code -- missing end-of-block";
                  state.mode = BAD2;
                  break;
                }
                state.lenbits = 9;
                opts = { bits: state.lenbits };
                ret = inflate_table2(LENS2, state.lens, 0, state.nlen, state.lencode, 0, state.work, opts);
                state.lenbits = opts.bits;
                if (ret) {
                  strm.msg = "invalid literal/lengths set";
                  state.mode = BAD2;
                  break;
                }
                state.distbits = 6;
                state.distcode = state.distdyn;
                opts = { bits: state.distbits };
                ret = inflate_table2(DISTS2, state.lens, state.nlen, state.ndist, state.distcode, 0, state.work, opts);
                state.distbits = opts.bits;
                if (ret) {
                  strm.msg = "invalid distances set";
                  state.mode = BAD2;
                  break;
                }
                state.mode = LEN_2;
                if (flush === Z_TREES2) {
                  break inf_leave;
                }
              /* falls through */
              case LEN_2:
                state.mode = LEN2;
              /* falls through */
              case LEN2:
                if (have >= 6 && left >= 258) {
                  strm.next_out = put;
                  strm.avail_out = left;
                  strm.next_in = next;
                  strm.avail_in = have;
                  state.hold = hold;
                  state.bits = bits;
                  inflate_fast2(strm, _out);
                  put = strm.next_out;
                  output = strm.output;
                  left = strm.avail_out;
                  next = strm.next_in;
                  input = strm.input;
                  have = strm.avail_in;
                  hold = state.hold;
                  bits = state.bits;
                  if (state.mode === TYPE2) {
                    state.back = -1;
                  }
                  break;
                }
                state.back = 0;
                for (; ; ) {
                  here = state.lencode[hold & (1 << state.lenbits) - 1];
                  here_bits = here >>> 24;
                  here_op = here >>> 16 & 255;
                  here_val = here & 65535;
                  if (here_bits <= bits) {
                    break;
                  }
                  if (have === 0) {
                    break inf_leave;
                  }
                  have--;
                  hold += input[next++] << bits;
                  bits += 8;
                }
                if (here_op && (here_op & 240) === 0) {
                  last_bits = here_bits;
                  last_op = here_op;
                  last_val = here_val;
                  for (; ; ) {
                    here = state.lencode[last_val + ((hold & (1 << last_bits + last_op) - 1) >> last_bits)];
                    here_bits = here >>> 24;
                    here_op = here >>> 16 & 255;
                    here_val = here & 65535;
                    if (last_bits + here_bits <= bits) {
                      break;
                    }
                    if (have === 0) {
                      break inf_leave;
                    }
                    have--;
                    hold += input[next++] << bits;
                    bits += 8;
                  }
                  hold >>>= last_bits;
                  bits -= last_bits;
                  state.back += last_bits;
                }
                hold >>>= here_bits;
                bits -= here_bits;
                state.back += here_bits;
                state.length = here_val;
                if (here_op === 0) {
                  state.mode = LIT2;
                  break;
                }
                if (here_op & 32) {
                  state.back = -1;
                  state.mode = TYPE2;
                  break;
                }
                if (here_op & 64) {
                  strm.msg = "invalid literal/length code";
                  state.mode = BAD2;
                  break;
                }
                state.extra = here_op & 15;
                state.mode = LENEXT2;
              /* falls through */
              case LENEXT2:
                if (state.extra) {
                  n = state.extra;
                  while (bits < n) {
                    if (have === 0) {
                      break inf_leave;
                    }
                    have--;
                    hold += input[next++] << bits;
                    bits += 8;
                  }
                  state.length += hold & (1 << state.extra) - 1;
                  hold >>>= state.extra;
                  bits -= state.extra;
                  state.back += state.extra;
                }
                state.was = state.length;
                state.mode = DIST2;
              /* falls through */
              case DIST2:
                for (; ; ) {
                  here = state.distcode[hold & (1 << state.distbits) - 1];
                  here_bits = here >>> 24;
                  here_op = here >>> 16 & 255;
                  here_val = here & 65535;
                  if (here_bits <= bits) {
                    break;
                  }
                  if (have === 0) {
                    break inf_leave;
                  }
                  have--;
                  hold += input[next++] << bits;
                  bits += 8;
                }
                if ((here_op & 240) === 0) {
                  last_bits = here_bits;
                  last_op = here_op;
                  last_val = here_val;
                  for (; ; ) {
                    here = state.distcode[last_val + ((hold & (1 << last_bits + last_op) - 1) >> last_bits)];
                    here_bits = here >>> 24;
                    here_op = here >>> 16 & 255;
                    here_val = here & 65535;
                    if (last_bits + here_bits <= bits) {
                      break;
                    }
                    if (have === 0) {
                      break inf_leave;
                    }
                    have--;
                    hold += input[next++] << bits;
                    bits += 8;
                  }
                  hold >>>= last_bits;
                  bits -= last_bits;
                  state.back += last_bits;
                }
                hold >>>= here_bits;
                bits -= here_bits;
                state.back += here_bits;
                if (here_op & 64) {
                  strm.msg = "invalid distance code";
                  state.mode = BAD2;
                  break;
                }
                state.offset = here_val;
                state.extra = here_op & 15;
                state.mode = DISTEXT2;
              /* falls through */
              case DISTEXT2:
                if (state.extra) {
                  n = state.extra;
                  while (bits < n) {
                    if (have === 0) {
                      break inf_leave;
                    }
                    have--;
                    hold += input[next++] << bits;
                    bits += 8;
                  }
                  state.offset += hold & (1 << state.extra) - 1;
                  hold >>>= state.extra;
                  bits -= state.extra;
                  state.back += state.extra;
                }
                if (state.offset > state.dmax) {
                  strm.msg = "invalid distance too far back";
                  state.mode = BAD2;
                  break;
                }
                state.mode = MATCH2;
              /* falls through */
              case MATCH2:
                if (left === 0) {
                  break inf_leave;
                }
                copy = _out - left;
                if (state.offset > copy) {
                  copy = state.offset - copy;
                  if (copy > state.whave) {
                    if (state.sane) {
                      strm.msg = "invalid distance too far back";
                      state.mode = BAD2;
                      break;
                    }
                  }
                  if (copy > state.wnext) {
                    copy -= state.wnext;
                    from = state.wsize - copy;
                  } else {
                    from = state.wnext - copy;
                  }
                  if (copy > state.length) {
                    copy = state.length;
                  }
                  from_source = state.window;
                } else {
                  from_source = output;
                  from = put - state.offset;
                  copy = state.length;
                }
                if (copy > left) {
                  copy = left;
                }
                left -= copy;
                state.length -= copy;
                do {
                  output[put++] = from_source[from++];
                } while (--copy);
                if (state.length === 0) {
                  state.mode = LEN2;
                }
                break;
              case LIT2:
                if (left === 0) {
                  break inf_leave;
                }
                output[put++] = state.length;
                left--;
                state.mode = LEN2;
                break;
              case CHECK2:
                if (state.wrap) {
                  while (bits < 32) {
                    if (have === 0) {
                      break inf_leave;
                    }
                    have--;
                    hold |= input[next++] << bits;
                    bits += 8;
                  }
                  _out -= left;
                  strm.total_out += _out;
                  state.total += _out;
                  if (state.wrap & 4 && _out) {
                    strm.adler = state.check = /*UPDATE_CHECK(state.check, put - _out, _out);*/
                    state.flags ? crc322(state.check, output, _out, put - _out) : adler322(state.check, output, _out, put - _out);
                  }
                  _out = left;
                  if (state.wrap & 4 && (state.flags ? hold : zswap322(hold)) !== state.check) {
                    strm.msg = "incorrect data check";
                    state.mode = BAD2;
                    break;
                  }
                  hold = 0;
                  bits = 0;
                }
                state.mode = LENGTH2;
              /* falls through */
              case LENGTH2:
                if (state.wrap && state.flags) {
                  while (bits < 32) {
                    if (have === 0) {
                      break inf_leave;
                    }
                    have--;
                    hold += input[next++] << bits;
                    bits += 8;
                  }
                  if (state.wrap & 4 && hold !== (state.total & 4294967295)) {
                    strm.msg = "incorrect length check";
                    state.mode = BAD2;
                    break;
                  }
                  hold = 0;
                  bits = 0;
                }
                state.mode = DONE2;
              /* falls through */
              case DONE2:
                ret = Z_STREAM_END2;
                break inf_leave;
              case BAD2:
                ret = Z_DATA_ERROR2;
                break inf_leave;
              case MEM2:
                return Z_MEM_ERROR2;
              case SYNC2:
              /* falls through */
              default:
                return Z_STREAM_ERROR2;
            }
          }
        strm.next_out = put;
        strm.avail_out = left;
        strm.next_in = next;
        strm.avail_in = have;
        state.hold = hold;
        state.bits = bits;
        if (state.wsize || _out !== strm.avail_out && state.mode < BAD2 && (state.mode < CHECK2 || flush !== Z_FINISH2)) {
          if (updatewindow2(strm, strm.output, strm.next_out, _out - strm.avail_out)) {
            state.mode = MEM2;
            return Z_MEM_ERROR2;
          }
        }
        _in -= strm.avail_in;
        _out -= strm.avail_out;
        strm.total_in += _in;
        strm.total_out += _out;
        state.total += _out;
        if (state.wrap & 4 && _out) {
          strm.adler = state.check = /*UPDATE_CHECK(state.check, strm.next_out - _out, _out);*/
          state.flags ? crc322(state.check, output, _out, strm.next_out - _out) : adler322(state.check, output, _out, strm.next_out - _out);
        }
        strm.data_type = state.bits + (state.last ? 64 : 0) + (state.mode === TYPE2 ? 128 : 0) + (state.mode === LEN_2 || state.mode === COPY_2 ? 256 : 0);
        if ((_in === 0 && _out === 0 || flush === Z_FINISH2) && ret === Z_OK2) {
          ret = Z_BUF_ERROR2;
        }
        return ret;
      };
      var inflateEnd2 = (strm) => {
        if (inflateStateCheck2(strm)) {
          return Z_STREAM_ERROR2;
        }
        let state = strm.state;
        if (state.window) {
          state.window = null;
        }
        strm.state = null;
        return Z_OK2;
      };
      var inflateGetHeader2 = (strm, head) => {
        if (inflateStateCheck2(strm)) {
          return Z_STREAM_ERROR2;
        }
        const state = strm.state;
        if ((state.wrap & 2) === 0) {
          return Z_STREAM_ERROR2;
        }
        state.head = head;
        head.done = false;
        return Z_OK2;
      };
      var inflateSetDictionary2 = (strm, dictionary) => {
        const dictLength = dictionary.length;
        let state;
        let dictid;
        let ret;
        if (inflateStateCheck2(strm)) {
          return Z_STREAM_ERROR2;
        }
        state = strm.state;
        if (state.wrap !== 0 && state.mode !== DICT2) {
          return Z_STREAM_ERROR2;
        }
        if (state.mode === DICT2) {
          dictid = 1;
          dictid = adler322(dictid, dictionary, dictLength, 0);
          if (dictid !== state.check) {
            return Z_DATA_ERROR2;
          }
        }
        ret = updatewindow2(strm, dictionary, dictLength, dictLength);
        if (ret) {
          state.mode = MEM2;
          return Z_MEM_ERROR2;
        }
        state.havedict = 1;
        return Z_OK2;
      };
      module.exports.inflateReset = inflateReset3;
      module.exports.inflateReset2 = inflateReset22;
      module.exports.inflateResetKeep = inflateResetKeep2;
      module.exports.inflateInit = inflateInit3;
      module.exports.inflateInit2 = inflateInit22;
      module.exports.inflate = inflate3;
      module.exports.inflateEnd = inflateEnd2;
      module.exports.inflateGetHeader = inflateGetHeader2;
      module.exports.inflateSetDictionary = inflateSetDictionary2;
      module.exports.inflateInfo = "pako inflate (from Nodeca project)";
    }
  });

  // node_modules/pako/lib/zlib/gzheader.js
  var require_gzheader = __commonJS({
    "node_modules/pako/lib/zlib/gzheader.js"(exports, module) {
      "use strict";
      function GZheader2() {
        this.text = 0;
        this.time = 0;
        this.xflags = 0;
        this.os = 0;
        this.extra = null;
        this.extra_len = 0;
        this.name = "";
        this.comment = "";
        this.hcrc = 0;
        this.done = false;
      }
      module.exports = GZheader2;
    }
  });

  // node_modules/pako/lib/inflate.js
  var require_inflate2 = __commonJS({
    "node_modules/pako/lib/inflate.js"(exports, module) {
      "use strict";
      var zlib_inflate = require_inflate();
      var utils = require_common();
      var strings2 = require_strings();
      var msg = require_messages();
      var ZStream2 = require_zstream();
      var GZheader2 = require_gzheader();
      var toString2 = Object.prototype.toString;
      var {
        Z_NO_FLUSH: Z_NO_FLUSH2,
        Z_FINISH: Z_FINISH2,
        Z_OK: Z_OK2,
        Z_STREAM_END: Z_STREAM_END2,
        Z_NEED_DICT: Z_NEED_DICT2,
        Z_STREAM_ERROR: Z_STREAM_ERROR2,
        Z_DATA_ERROR: Z_DATA_ERROR2,
        Z_MEM_ERROR: Z_MEM_ERROR2,
        Z_BUF_ERROR: Z_BUF_ERROR2
      } = require_constants();
      var defaultOptions2 = {
        chunkSize: 1024 * 64,
        windowBits: 15,
        to: ""
      };
      function Inflate2(options) {
        this.options = utils.assign({}, defaultOptions2, options || {});
        const opt = this.options;
        if (opt.raw && opt.windowBits >= 0 && opt.windowBits < 16) {
          opt.windowBits = -opt.windowBits;
          if (opt.windowBits === 0) {
            opt.windowBits = -15;
          }
        }
        if (opt.windowBits >= 0 && opt.windowBits < 16 && !(options && options.windowBits)) {
          opt.windowBits += 32;
        }
        if (opt.windowBits > 15 && opt.windowBits < 48) {
          if ((opt.windowBits & 15) === 0) {
            opt.windowBits |= 15;
          }
        }
        this.err = 0;
        this.msg = "";
        this.ended = false;
        this.chunks = [];
        this.strm = new ZStream2();
        this.strm.avail_out = 0;
        let status = zlib_inflate.inflateInit2(
          this.strm,
          opt.windowBits
        );
        if (status !== Z_OK2) {
          throw new Error(msg[status]);
        }
        this.header = new GZheader2();
        zlib_inflate.inflateGetHeader(this.strm, this.header);
        if (opt.dictionary) {
          if (typeof opt.dictionary === "string") {
            opt.dictionary = strings2.string2buf(opt.dictionary);
          } else if (toString2.call(opt.dictionary) === "[object ArrayBuffer]") {
            opt.dictionary = new Uint8Array(opt.dictionary);
          }
          if (opt.raw) {
            status = zlib_inflate.inflateSetDictionary(this.strm, opt.dictionary);
            if (status !== Z_OK2) {
              throw new Error(msg[status]);
            }
          }
        }
      }
      Inflate2.prototype.push = function(data, flush_mode) {
        const strm = this.strm;
        const chunkSize = this.options.chunkSize;
        const dictionary = this.options.dictionary;
        let status, _flush_mode, last_avail_out;
        if (this.ended) return false;
        if (flush_mode === ~~flush_mode) _flush_mode = flush_mode;
        else _flush_mode = flush_mode === true ? Z_FINISH2 : Z_NO_FLUSH2;
        if (toString2.call(data) === "[object ArrayBuffer]") {
          strm.input = new Uint8Array(data);
        } else {
          strm.input = data;
        }
        strm.next_in = 0;
        strm.avail_in = strm.input.length;
        for (; ; ) {
          if (strm.avail_out === 0) {
            strm.output = new Uint8Array(chunkSize);
            strm.next_out = 0;
            strm.avail_out = chunkSize;
          }
          status = zlib_inflate.inflate(strm, _flush_mode);
          if (status === Z_NEED_DICT2 && dictionary) {
            status = zlib_inflate.inflateSetDictionary(strm, dictionary);
            if (status === Z_OK2) {
              status = zlib_inflate.inflate(strm, _flush_mode);
            } else if (status === Z_DATA_ERROR2) {
              status = Z_NEED_DICT2;
            }
          }
          while (strm.avail_in > 0 && status === Z_STREAM_END2 && strm.state.wrap & 2 && strm.state.flags !== 0 && strm.input[strm.next_in] !== 0) {
            zlib_inflate.inflateReset(strm);
            status = zlib_inflate.inflate(strm, _flush_mode);
          }
          switch (status) {
            case Z_STREAM_ERROR2:
            case Z_DATA_ERROR2:
            case Z_NEED_DICT2:
            case Z_MEM_ERROR2:
              this.onEnd(status);
              this.ended = true;
              return false;
          }
          last_avail_out = strm.avail_out;
          if (strm.next_out) {
            if (strm.avail_out === 0 || status === Z_STREAM_END2 || _flush_mode > 0) {
              if (this.options.to === "string") {
                let next_out_utf8 = strings2.utf8border(strm.output, strm.next_out);
                let tail = strm.next_out - next_out_utf8;
                let utf8str = strings2.buf2string(strm.output, next_out_utf8);
                strm.next_out = tail;
                strm.avail_out = chunkSize - tail;
                if (tail) strm.output.set(strm.output.subarray(next_out_utf8, next_out_utf8 + tail), 0);
                this.onData(utf8str);
              } else {
                this.onData(strm.output.length === strm.next_out ? strm.output : strm.output.subarray(0, strm.next_out));
                strm.avail_out = 0;
                strm.next_out = 0;
              }
            }
          }
          if ((status === Z_OK2 || status === Z_BUF_ERROR2) && last_avail_out === 0) continue;
          if (status === Z_STREAM_END2) {
            status = zlib_inflate.inflateEnd(this.strm);
            this.onEnd(status);
            this.ended = true;
            return true;
          }
          if (strm.avail_in === 0) {
            if (_flush_mode === Z_FINISH2) {
              status = zlib_inflate.inflateEnd(this.strm);
              this.onEnd(status === Z_OK2 ? Z_BUF_ERROR2 : status);
              this.ended = true;
              return false;
            }
            break;
          }
        }
        return true;
      };
      Inflate2.prototype.onData = function(chunk) {
        this.chunks.push(chunk);
      };
      Inflate2.prototype.onEnd = function(status) {
        if (status === Z_OK2) {
          if (this.options.to === "string") {
            this.result = this.chunks.join("");
          } else {
            this.result = utils.flattenChunks(this.chunks);
          }
        }
        this.chunks = [];
        this.err = status;
        this.msg = this.strm.msg;
      };
      function inflate3(input, options) {
        const inflator = new Inflate2(options);
        inflator.push(input, true);
        if (inflator.err) throw inflator.msg || msg[inflator.err];
        return inflator.result;
      }
      function inflateRaw2(input, options) {
        options = options || {};
        options.raw = true;
        return inflate3(input, options);
      }
      module.exports.Inflate = Inflate2;
      module.exports.inflate = inflate3;
      module.exports.inflateRaw = inflateRaw2;
      module.exports.ungzip = inflate3;
      module.exports.constants = require_constants();
    }
  });

  // node_modules/pako/index.js
  var require_pako = __commonJS({
    "node_modules/pako/index.js"(exports, module) {
      "use strict";
      var { Deflate: Deflate2, deflate: deflate2, deflateRaw: deflateRaw2, gzip: gzip2 } = require_deflate2();
      var { Inflate: Inflate2, inflate: inflate3, inflateRaw: inflateRaw2, ungzip: ungzip2 } = require_inflate2();
      var constants2 = require_constants();
      module.exports.Deflate = Deflate2;
      module.exports.deflate = deflate2;
      module.exports.deflateRaw = deflateRaw2;
      module.exports.gzip = gzip2;
      module.exports.Inflate = Inflate2;
      module.exports.inflate = inflate3;
      module.exports.inflateRaw = inflateRaw2;
      module.exports.ungzip = ungzip2;
      module.exports.constants = constants2;
    }
  });

  // src/musicSdk/kg/vendors/infSign.min.cjs
  var require_infSign_min = __commonJS({
    "src/musicSdk/kg/vendors/infSign.min.cjs"(exports, module) {
      !(function(t, n) {
        "object" == typeof exports && "undefined" != typeof module ? module.exports = n() : "function" == typeof define && define.amd ? define(n) : (t = t || self, t.infSign = n());
      })(exports, function() {
        "use strict";
        function t(t2, n2, r2) {
          return n2 in t2 ? Object.defineProperty(t2, n2, { value: r2, enumerable: true, configurable: true, writable: true }) : t2[n2] = r2, t2;
        }
        function n(t2, n2) {
          var r2 = Object.keys(t2);
          if (Object.getOwnPropertySymbols) {
            var e2 = Object.getOwnPropertySymbols(t2);
            n2 && (e2 = e2.filter(function(n3) {
              return Object.getOwnPropertyDescriptor(t2, n3).enumerable;
            })), r2.push.apply(r2, e2);
          }
          return r2;
        }
        function r(r2) {
          for (var e2 = 1; e2 < arguments.length; e2++) {
            var o2 = null != arguments[e2] ? arguments[e2] : {};
            e2 % 2 ? n(o2, true).forEach(function(n2) {
              t(r2, n2, o2[n2]);
            }) : Object.getOwnPropertyDescriptors ? Object.defineProperties(r2, Object.getOwnPropertyDescriptors(o2)) : n(o2).forEach(function(t2) {
              Object.defineProperty(r2, t2, Object.getOwnPropertyDescriptor(o2, t2));
            });
          }
          return r2;
        }
        function e(t2, n2) {
          return n2 = { exports: {} }, t2(n2, n2.exports), n2.exports;
        }
        function o(t2) {
          return !!t2.constructor && "function" == typeof t2.constructor.isBuffer && t2.constructor.isBuffer(t2);
        }
        function i(t2) {
          return "function" == typeof t2.readFloatLE && "function" == typeof t2.slice && o(t2.slice(0, 0));
        }
        function c() {
          var t2, n2 = arguments.length > 0 && void 0 !== arguments[0] ? arguments[0] : {}, e2 = arguments.length > 1 && void 0 !== arguments[1] ? arguments[1] : "", o2 = arguments.length > 2 && void 0 !== arguments[2] ? arguments[2] : {}, i2 = false, c2 = false, a2 = "json", l2 = r({}, n2), u2 = s.isInClient();
          "function" == typeof o2 ? t2 = o2 : (t2 = o2.callback, i2 = o2.useH5 || false, a2 = o2.postType || "json", c2 = o2.isCDN || false), e2 && ("[object Object]" != Object.prototype.toString.call(e2) ? u2 = false : "urlencoded" == a2 && (u2 = false));
          var f2 = function() {
            var n3 = (/* @__PURE__ */ new Date()).getTime(), i3 = [], s2 = [], u3 = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt", f3 = { srcappid: "2919", clientver: "20000", clienttime: n3, mid: n3, uuid: n3, dfid: "-" };
            c2 && (delete f3.clienttime, delete f3.mid, delete f3.uuid, delete f3.dfid), l2 = r({}, f3, {}, l2);
            for (var g2 in l2) i3.push(g2);
            if (i3.sort(), i3.forEach(function(t3) {
              s2.push(t3 + "=" + l2[t3]);
            }), e2) if ("[object Object]" == Object.prototype.toString.call(e2)) if ("json" == a2) s2.push(JSON.stringify(e2));
            else {
              var b = [];
              for (var g2 in e2) b.push(g2 + "=" + e2[g2]);
              s2.push(b.join("&"));
            }
            else s2.push(e2);
            s2.unshift(u3), s2.push(u3), l2.signature = d(s2.join("")), o2.log && (console.log("H5签名前参数", s2), console.log("H5签名后返回", l2)), e2 ? t2 && t2(l2, "[object Object]" == Object.prototype.toString.call(e2) && "json" == a2 ? JSON.stringify(e2) : e2) : t2 && t2(l2);
          };
          if (u2 && !i2) {
            var g = false;
            s.mobileCall(764, { get: l2, post: e2 }, function(n3) {
              return !g && (g = true, n3 && n3.status ? (delete n3.status, o2.log && (console.log("客户端签名前参数", { get: l2, post: e2 }), console.log("客户端签名后返回", r({}, l2, {}, n3))), l2 = r({}, l2, {}, n3), e2 ? t2 && t2(l2, "[object Object]" == Object.prototype.toString.call(e2) && "json" == a2 ? JSON.stringify(e2) : e2) : t2 && t2(l2), false) : (u2 = false, void f2()));
            });
          } else u2 = false, f2();
        }
        "undefined" != typeof globalThis ? globalThis : "undefined" != typeof window ? window : "undefined" != typeof global ? global : "undefined" != typeof self && self;
        var s = e(function(t2, n2) {
          !(function(n3, r2) {
            t2.exports = (function() {
              var t3 = { str2Json: function(t4) {
                var n4 = {};
                if ("[object String]" === Object.prototype.toString.call(t4)) try {
                  n4 = JSON.parse(t4);
                } catch (t5) {
                  n4 = {};
                }
                return n4;
              }, json2Str: function(t4) {
                var n4 = t4;
                if ("string" != typeof t4) try {
                  n4 = JSON.stringify(t4);
                } catch (t5) {
                  n4 = "";
                }
                return n4;
              }, _extend: function(t4, n4) {
                if (n4) for (var r3 in t4) n4.hasOwnProperty(r3) || (n4[r3] = t4[r3]);
                return n4;
              }, formatURL: { browser: "", url: "" }, formatSong: { filename: "", filesize: "", hash: "", bitrate: "", extname: "", duration: "", mvhash: "", m4afilesize: "", "320hash": "", "320filesize": "", sqhash: "", sqfilesize: 0, feetype: 0, isfirst: 0 }, formatMV: { filename: "", singername: "", hash: "", imgurl: "" }, formatShare: { shareName: "", topicName: "", hash: "", listID: "", type: "", suid: "", slid: "", imgUrl: "", filename: "", duration: "", shareData: { linkUrl: "", picUrl: "", content: "", title: "" } }, cbNum: 0, isIOS: false, isKugouAndroid: false, isAndroid: true, loadUrl: function(t4) {
                var n4 = document.createElement("iframe");
                n4.setAttribute("src", t4), n4.setAttribute("style", "display:none;"), n4.setAttribute("height", "0px"), n4.setAttribute("width", "0px"), n4.setAttribute("frameborder", "0"), document.body.appendChild(n4), n4.parentNode.removeChild(n4), n4 = null;
              }, callCmd: function(n4) {
                var r3 = t3;
                if (r3.isKugouAndroid) {
                  var e2 = {}, o2 = "";
                  if (n4.cmd && (e2.cmd = n4.cmd), n4.jsonStr && (e2.jsonStr = n4.jsonStr), n4.callback && (o2 = "kgandroidmobilecall" + ++r3.cbNum + Math.random().toString().substr(2, 9), e2.callback = o2, window[o2] = function(t4, e3) {
                    void 0 !== t4 && ("[object String]" === Object.prototype.toString.call(t4) ? (t4 = "#" === e3 ? decodeURIComponent(t4) : decodeURIComponent(decodeURIComponent(t4)), n4.callback(r3.str2Json(t4))) : n4.callback(t4));
                  }), n4.AndroidCallback) {
                    var i2 = r3.str2Json(n4.jsonStr);
                    i2.AndroidCallback = o2, n4.jsonStr = r3.json2Str(i2), n4.jsonStr && (e2.jsonStr = n4.jsonStr);
                  }
                  var c2 = encodeURIComponent(JSON.stringify(e2)), s2 = "kugoujsbridge://start.kugou_jsbridge/?".concat(c2);
                  r3.loadUrl(s2);
                } else if (r3.isAndroid) {
                  var a2 = "", l2 = "";
                  if (n4.jsonStr) {
                    if (n4.callback && "" !== n4.callback && true === n4.AndroidCallback) {
                      l2 = "kgmobilecall" + ++r3.cbNum + Math.random().toString().substr(2, 9), window[l2] = function(t4, e3) {
                        void 0 !== t4 && ("[object String]" === Object.prototype.toString.call(t4) ? (t4 = "#" === e3 ? decodeURIComponent(t4) : decodeURIComponent(decodeURIComponent(t4)), n4.callback(r3.str2Json(t4))) : n4.callback(t4));
                      };
                      var u2 = r3.str2Json(n4.jsonStr);
                      u2.AndroidCallback = l2, n4.jsonStr = r3.json2Str(u2);
                    }
                    try {
                      a2 = external.superCall(n4.cmd, n4.jsonStr);
                    } catch (t4) {
                    }
                  } else try {
                    a2 = external.superCall(n4.cmd);
                  } catch (t4) {
                  }
                  n4.callback && "" !== n4.callback && "AndroidCallback" != a2 && (a2 = r3.str2Json(a2), n4.callback(a2));
                } else {
                  var f2 = "", d2 = "";
                  n4.callback && (d2 = "kgmobilecall" + ++r3.cbNum + Math.random().toString().substr(2, 9), window[d2] = function(t4) {
                    void 0 !== t4 && n4.callback && ("[object String]" === Object.prototype.toString.call(t4) ? n4.callback(r3.str2Json(t4)) : n4.callback(t4));
                  }), d2 && "" != d2 && n4.jsonStr && (f2 = 'kugouurl://start.music/?{"cmd":' + n4.cmd + ', "jsonStr":' + n4.jsonStr + ', "callback":"' + d2 + '"}'), d2 && "" != d2 && !n4.jsonStr && (f2 = 'kugouurl://start.music/?{"cmd":' + n4.cmd + ', "callback":"' + d2 + '"}'), "" == d2 && n4.jsonStr && (f2 = 'kugouurl://start.music/?{"cmd":' + n4.cmd + ', "jsonStr":' + n4.jsonStr + "}"), "" != d2 || n4.jsonStr || (f2 = 'kugouurl://start.music/?{"cmd":' + n4.cmd + "}"), r3.loadUrl(f2);
                }
              }, formartData: function(n4, r3) {
                n4 && 123 == n4 && r3 && (r3 = t3._extend(t3.formatURL, r3)), n4 && 123 == n4 && r3 && (r3 = t3._extend(t3.formatURL, r3));
              } };
              return { isIOS: t3.isIOS, isKugouAndroid: t3.isKugouAndroid, isAndroid: t3.isAndroid, isInClient: function() {
                return !(!t3.isAndroid && !t3.isKugouAndroid && !t3.isIOS);
              }, mobileCall: function(n4, r3, e2) {
                var o2 = "";
                if (r3 && (o2 = t3.json2Str(r3)), !n4) return console.error("请输入命令号！"), false;
                var i2 = {};
                n4 && (i2.cmd = n4), "" != o2 && (i2.jsonStr = o2), e2 && (i2.callback = e2), n4 && 186 == n4 && e2 && (i2.AndroidCallback = true), t3.callCmd(i2);
              }, KgWebMobileCall: function(t4, n4) {
                if (t4) try {
                  var r3 = t4.split(".");
                  r3.reduce(function(e2, o2) {
                    if (e2[o2]) {
                      if (o2 === r3[r3.length - 1]) {
                        var i2 = e2[o2];
                        return "function" == typeof i2 ? (e2[o2] = function(t5) {
                          i2 && i2(t5), n4 && n4(t5);
                        }, e2[o2]) : (console.error("请检查，当前环境变量已注册了对象：" + t4 + "，且该对象不是方法"), null);
                      }
                      return e2[o2];
                    }
                    return o2 === r3[r3.length - 1] ? e2[o2] = function(t5) {
                      n4 && n4(t5);
                    } : e2[o2] = new Object(), e2[o2];
                  }, window);
                } catch (t5) {
                }
              } };
            })();
          })();
        }), a = e(function(t2) {
          !(function() {
            var n2 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/", r2 = { rotl: function(t3, n3) {
              return t3 << n3 | t3 >>> 32 - n3;
            }, rotr: function(t3, n3) {
              return t3 << 32 - n3 | t3 >>> n3;
            }, endian: function(t3) {
              if (t3.constructor == Number) return 16711935 & r2.rotl(t3, 8) | 4278255360 & r2.rotl(t3, 24);
              for (var n3 = 0; n3 < t3.length; n3++) t3[n3] = r2.endian(t3[n3]);
              return t3;
            }, randomBytes: function(t3) {
              for (var n3 = []; t3 > 0; t3--) n3.push(Math.floor(256 * Math.random()));
              return n3;
            }, bytesToWords: function(t3) {
              for (var n3 = [], r3 = 0, e2 = 0; r3 < t3.length; r3++, e2 += 8) n3[e2 >>> 5] |= t3[r3] << 24 - e2 % 32;
              return n3;
            }, wordsToBytes: function(t3) {
              for (var n3 = [], r3 = 0; r3 < 32 * t3.length; r3 += 8) n3.push(t3[r3 >>> 5] >>> 24 - r3 % 32 & 255);
              return n3;
            }, bytesToHex: function(t3) {
              for (var n3 = [], r3 = 0; r3 < t3.length; r3++) n3.push((t3[r3] >>> 4).toString(16)), n3.push((15 & t3[r3]).toString(16));
              return n3.join("");
            }, hexToBytes: function(t3) {
              for (var n3 = [], r3 = 0; r3 < t3.length; r3 += 2) n3.push(parseInt(t3.substr(r3, 2), 16));
              return n3;
            }, bytesToBase64: function(t3) {
              for (var r3 = [], e2 = 0; e2 < t3.length; e2 += 3) for (var o2 = t3[e2] << 16 | t3[e2 + 1] << 8 | t3[e2 + 2], i2 = 0; i2 < 4; i2++) 8 * e2 + 6 * i2 <= 8 * t3.length ? r3.push(n2.charAt(o2 >>> 6 * (3 - i2) & 63)) : r3.push("=");
              return r3.join("");
            }, base64ToBytes: function(t3) {
              t3 = t3.replace(/[^A-Z0-9+\/]/gi, "");
              for (var r3 = [], e2 = 0, o2 = 0; e2 < t3.length; o2 = ++e2 % 4) 0 != o2 && r3.push((n2.indexOf(t3.charAt(e2 - 1)) & Math.pow(2, -2 * o2 + 8) - 1) << 2 * o2 | n2.indexOf(t3.charAt(e2)) >>> 6 - 2 * o2);
              return r3;
            } };
            t2.exports = r2;
          })();
        }), l = { utf8: { stringToBytes: function(t2) {
          return l.bin.stringToBytes(unescape(encodeURIComponent(t2)));
        }, bytesToString: function(t2) {
          return decodeURIComponent(escape(l.bin.bytesToString(t2)));
        } }, bin: { stringToBytes: function(t2) {
          for (var n2 = [], r2 = 0; r2 < t2.length; r2++) n2.push(255 & t2.charCodeAt(r2));
          return n2;
        }, bytesToString: function(t2) {
          for (var n2 = [], r2 = 0; r2 < t2.length; r2++) n2.push(String.fromCharCode(t2[r2]));
          return n2.join("");
        } } }, u = l, f = function(t2) {
          return null != t2 && (o(t2) || i(t2) || !!t2._isBuffer);
        }, d = e(function(t2) {
          !(function() {
            var n2 = a, r2 = u.utf8, e2 = f, o2 = u.bin, i2 = function(t3, c2) {
              t3.constructor == String ? t3 = c2 && "binary" === c2.encoding ? o2.stringToBytes(t3) : r2.stringToBytes(t3) : e2(t3) ? t3 = Array.prototype.slice.call(t3, 0) : Array.isArray(t3) || (t3 = t3.toString());
              for (var s2 = n2.bytesToWords(t3), a2 = 8 * t3.length, l2 = 1732584193, u2 = -271733879, f2 = -1732584194, d2 = 271733878, g = 0; g < s2.length; g++) s2[g] = 16711935 & (s2[g] << 8 | s2[g] >>> 24) | 4278255360 & (s2[g] << 24 | s2[g] >>> 8);
              s2[a2 >>> 5] |= 128 << a2 % 32, s2[14 + (a2 + 64 >>> 9 << 4)] = a2;
              for (var b = i2._ff, p = i2._gg, h = i2._hh, m = i2._ii, g = 0; g < s2.length; g += 16) {
                var y = l2, j = u2, S = f2, v = d2;
                u2 = m(u2 = m(u2 = m(u2 = m(u2 = h(u2 = h(u2 = h(u2 = h(u2 = p(u2 = p(u2 = p(u2 = p(u2 = b(u2 = b(u2 = b(u2 = b(u2, f2 = b(f2, d2 = b(d2, l2 = b(l2, u2, f2, d2, s2[g + 0], 7, -680876936), u2, f2, s2[g + 1], 12, -389564586), l2, u2, s2[g + 2], 17, 606105819), d2, l2, s2[g + 3], 22, -1044525330), f2 = b(f2, d2 = b(d2, l2 = b(l2, u2, f2, d2, s2[g + 4], 7, -176418897), u2, f2, s2[g + 5], 12, 1200080426), l2, u2, s2[g + 6], 17, -1473231341), d2, l2, s2[g + 7], 22, -45705983), f2 = b(f2, d2 = b(d2, l2 = b(l2, u2, f2, d2, s2[g + 8], 7, 1770035416), u2, f2, s2[g + 9], 12, -1958414417), l2, u2, s2[g + 10], 17, -42063), d2, l2, s2[g + 11], 22, -1990404162), f2 = b(f2, d2 = b(d2, l2 = b(l2, u2, f2, d2, s2[g + 12], 7, 1804603682), u2, f2, s2[g + 13], 12, -40341101), l2, u2, s2[g + 14], 17, -1502002290), d2, l2, s2[g + 15], 22, 1236535329), f2 = p(f2, d2 = p(d2, l2 = p(l2, u2, f2, d2, s2[g + 1], 5, -165796510), u2, f2, s2[g + 6], 9, -1069501632), l2, u2, s2[g + 11], 14, 643717713), d2, l2, s2[g + 0], 20, -373897302), f2 = p(f2, d2 = p(d2, l2 = p(l2, u2, f2, d2, s2[g + 5], 5, -701558691), u2, f2, s2[g + 10], 9, 38016083), l2, u2, s2[g + 15], 14, -660478335), d2, l2, s2[g + 4], 20, -405537848), f2 = p(f2, d2 = p(d2, l2 = p(l2, u2, f2, d2, s2[g + 9], 5, 568446438), u2, f2, s2[g + 14], 9, -1019803690), l2, u2, s2[g + 3], 14, -187363961), d2, l2, s2[g + 8], 20, 1163531501), f2 = p(f2, d2 = p(d2, l2 = p(l2, u2, f2, d2, s2[g + 13], 5, -1444681467), u2, f2, s2[g + 2], 9, -51403784), l2, u2, s2[g + 7], 14, 1735328473), d2, l2, s2[g + 12], 20, -1926607734), f2 = h(f2, d2 = h(d2, l2 = h(l2, u2, f2, d2, s2[g + 5], 4, -378558), u2, f2, s2[g + 8], 11, -2022574463), l2, u2, s2[g + 11], 16, 1839030562), d2, l2, s2[g + 14], 23, -35309556), f2 = h(f2, d2 = h(d2, l2 = h(l2, u2, f2, d2, s2[g + 1], 4, -1530992060), u2, f2, s2[g + 4], 11, 1272893353), l2, u2, s2[g + 7], 16, -155497632), d2, l2, s2[g + 10], 23, -1094730640), f2 = h(f2, d2 = h(d2, l2 = h(l2, u2, f2, d2, s2[g + 13], 4, 681279174), u2, f2, s2[g + 0], 11, -358537222), l2, u2, s2[g + 3], 16, -722521979), d2, l2, s2[g + 6], 23, 76029189), f2 = h(f2, d2 = h(d2, l2 = h(l2, u2, f2, d2, s2[g + 9], 4, -640364487), u2, f2, s2[g + 12], 11, -421815835), l2, u2, s2[g + 15], 16, 530742520), d2, l2, s2[g + 2], 23, -995338651), f2 = m(f2, d2 = m(d2, l2 = m(l2, u2, f2, d2, s2[g + 0], 6, -198630844), u2, f2, s2[g + 7], 10, 1126891415), l2, u2, s2[g + 14], 15, -1416354905), d2, l2, s2[g + 5], 21, -57434055), f2 = m(f2, d2 = m(d2, l2 = m(l2, u2, f2, d2, s2[g + 12], 6, 1700485571), u2, f2, s2[g + 3], 10, -1894986606), l2, u2, s2[g + 10], 15, -1051523), d2, l2, s2[g + 1], 21, -2054922799), f2 = m(f2, d2 = m(d2, l2 = m(l2, u2, f2, d2, s2[g + 8], 6, 1873313359), u2, f2, s2[g + 15], 10, -30611744), l2, u2, s2[g + 6], 15, -1560198380), d2, l2, s2[g + 13], 21, 1309151649), f2 = m(f2, d2 = m(d2, l2 = m(l2, u2, f2, d2, s2[g + 4], 6, -145523070), u2, f2, s2[g + 11], 10, -1120210379), l2, u2, s2[g + 2], 15, 718787259), d2, l2, s2[g + 9], 21, -343485551), l2 = l2 + y >>> 0, u2 = u2 + j >>> 0, f2 = f2 + S >>> 0, d2 = d2 + v >>> 0;
              }
              return n2.endian([l2, u2, f2, d2]);
            };
            i2._ff = function(t3, n3, r3, e3, o3, i3, c2) {
              var s2 = t3 + (n3 & r3 | ~n3 & e3) + (o3 >>> 0) + c2;
              return (s2 << i3 | s2 >>> 32 - i3) + n3;
            }, i2._gg = function(t3, n3, r3, e3, o3, i3, c2) {
              var s2 = t3 + (n3 & e3 | r3 & ~e3) + (o3 >>> 0) + c2;
              return (s2 << i3 | s2 >>> 32 - i3) + n3;
            }, i2._hh = function(t3, n3, r3, e3, o3, i3, c2) {
              var s2 = t3 + (n3 ^ r3 ^ e3) + (o3 >>> 0) + c2;
              return (s2 << i3 | s2 >>> 32 - i3) + n3;
            }, i2._ii = function(t3, n3, r3, e3, o3, i3, c2) {
              var s2 = t3 + (r3 ^ (n3 | ~e3)) + (o3 >>> 0) + c2;
              return (s2 << i3 | s2 >>> 32 - i3) + n3;
            }, i2._blocksize = 16, i2._digestsize = 16, t2.exports = function(t3, r3) {
              if (void 0 === t3 || null === t3) throw new Error("Illegal argument " + t3);
              var e3 = n2.wordsToBytes(i2(t3, r3));
              return r3 && r3.asBytes ? e3 : r3 && r3.asString ? o2.bytesToString(e3) : n2.bytesToHex(e3);
            };
          })();
        });
        return c;
      });
    }
  });

  // bootstrap.js
  var import_buffer = __toESM(require_buffer(), 1);
  globalThis.Buffer = globalThis.Buffer || import_buffer.Buffer;
  globalThis.global = globalThis.global || globalThis;
  globalThis.process = globalThis.process || { env: {}, versions: { app: "2.12.2" } };
  if (typeof globalThis.console === "undefined") {
    globalThis.console = { log() {
    }, warn() {
    }, error() {
    }, info() {
    }, debug() {
    } };
  }

  // src/musicSdk/channel.js
  var current = "auto";
  var setDataChannel = (value) => {
    current = value === "app" || value === "web" ? value : "auto";
  };
  var requestByDataChannel = (appRequest, webRequest) => {
    const [primary, fallback] = current === "web" ? [webRequest, appRequest] : [appRequest, webRequest];
    return Promise.resolve().then(primary).catch(() => Promise.resolve().then(fallback));
  };

  // src/request.js
  var import_buffer2 = __toESM(require_buffer(), 1);
  var native = globalThis.__lxNative;
  var DEFAULT_HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36"
  };
  var toBase64 = (text) => import_buffer2.Buffer.from(text, "utf8").toString("base64");
  var doRequest = (url, options = {}) => {
    const method = String(options.method || "get").toUpperCase();
    const headers = Object.assign({}, DEFAULT_HEADERS, options.headers || {});
    let body = null;
    if (options.form) {
      const parts = [];
      for (const [key, value] of Object.entries(options.form)) {
        parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(value)}`);
      }
      body = toBase64(parts.join("&"));
      if (!headers["Content-Type"]) headers["Content-Type"] = "application/x-www-form-urlencoded";
    } else if (options.body != null) {
      const text2 = typeof options.body === "string" ? options.body : JSON.stringify(options.body);
      if (!headers["Content-Type"]) headers["Content-Type"] = "application/json";
      body = toBase64(text2);
    }
    const payload = {
      url: String(url),
      method,
      headers,
      timeout: Number(options.timeout) || 15e3,
      responseType: "text"
    };
    if (body != null) payload.body = body;
    const raw = JSON.parse(native.http(JSON.stringify(payload)));
    if (raw.error) throw new Error(raw.error);
    const responseUrl = raw.finalUrl || String(url);
    if (options.binary) {
      return {
        statusCode: raw.statusCode,
        statusMessage: raw.statusMessage || "",
        headers: raw.headers || {},
        body: import_buffer2.Buffer.from(raw.raw || "", "base64"),
        url: responseUrl
      };
    }
    const text = import_buffer2.Buffer.from(raw.raw || "", "base64").toString("utf8");
    let parsed = text;
    try {
      parsed = JSON.parse(text);
    } catch (_) {
    }
    return {
      statusCode: raw.statusCode,
      statusMessage: raw.statusMessage || "",
      headers: raw.headers || {},
      body: parsed,
      url: responseUrl
    };
  };
  var httpFetch = (url, options = { method: "get" }) => new Promise((resolve, reject) => {
    try {
      resolve(doRequest(url, options));
    } catch (error) {
      reject(error);
    }
  });

  // src/index.js
  var import_he = __toESM(require_he(), 1);
  var import_buffer3 = __toESM(require_buffer(), 1);
  var native2 = globalThis.__lxNative;
  var numFix = (n) => n < 10 ? `0${n}` : String(n);
  var sizeFormate = (size) => {
    if (!size) return "0 B";
    const units = ["B", "KiB", "MiB", "GiB", "TiB"];
    const number = Math.floor(Math.log(size) / Math.log(1024));
    return `${(size / Math.pow(1024, Math.floor(number))).toFixed(2)} ${units[number]}`;
  };
  var toDateObj = (date) => {
    if (!date) return "";
    switch (typeof date) {
      case "string":
        if (!date.includes("T")) date = date.split(".")[0].replace(/-/g, "/");
      // fallthrough
      case "number":
        date = new Date(date);
      // fallthrough
      case "object":
        break;
      default:
        return "";
    }
    return date;
  };
  var dateFormat = (date, format = "Y-M-D h:m:s") => {
    const value = toDateObj(date);
    if (!value) return "";
    return format.replace("Y", value.getFullYear().toString()).replace("M", numFix(value.getMonth() + 1)).replace("D", numFix(value.getDate())).replace("h", numFix(value.getHours())).replace("m", numFix(value.getMinutes())).replace("s", numFix(value.getSeconds()));
  };
  var formatPlayTime = (time) => {
    const m = Math.trunc(time / 60);
    const s = Math.trunc(time % 60);
    return m === 0 && s === 0 ? "--/--" : `${numFix(m)}:${numFix(s)}`;
  };
  var formatPlayCount = (num) => {
    if (num > 1e8) return `${Math.trunc(num / 1e7) / 10}亿`;
    if (num > 1e4) return `${Math.trunc(num / 1e3) / 10}万`;
    return String(num);
  };
  var decodeName = (str) => {
    if (!str) return "";
    return import_he.default.decode(str);
  };

  // compat/quick-md5.js
  var import_buffer4 = __toESM(require_buffer(), 1);
  var native3 = globalThis.__lxNative;
  var stringMd5 = (text) => native3.hash("md5", import_buffer4.Buffer.from(String(text), "utf8").toString("base64"));

  // src/musicSdk/utils.js
  var toMD5 = (str) => stringMd5(str);
  var formatSingerName = (singers, nameKey = "name", join = "、") => {
    if (Array.isArray(singers)) {
      const singer = [];
      singers.forEach((item) => {
        let name = item[nameKey];
        if (!name) return;
        singer.push(name);
      });
      return decodeName(singer.join(join));
    }
    return decodeName(String(singers ?? ""));
  };

  // compat/native-crypto.js
  var import_buffer5 = __toESM(require_buffer());
  var native4 = globalThis.__lxNative;
  var RSA_PADDING = {
    OAEPWithSHA1AndMGF1Padding: "RSA/ECB/OAEPWithSHA1AndMGF1Padding",
    NoPadding: "RSA/ECB/NoPadding"
  };
  var AES_MODE = {
    CBC_128_PKCS7Padding: "AES/CBC/PKCS7Padding",
    ECB_128_NoPadding: "AES"
  };
  var modeToNative = (mode) => {
    if (mode === AES_MODE.CBC_128_PKCS7Padding) return "aes-128-cbc";
    if (mode === AES_MODE.ECB_128_NoPadding || mode === "AES") return "aes-128-ecb";
    return String(mode);
  };
  var aesEncryptSync = (textBase64, key, iv2, mode) => native4.aes(
    "encrypt",
    modeToNative(mode),
    key.toString("base64"),
    iv2 ? iv2.toString("base64") : "",
    textBase64
  );
  var aesDecryptSync = (textBase64, key, iv2, mode) => import_buffer5.Buffer.from(
    native4.aes(
      "decrypt",
      modeToNative(mode),
      key.toString("base64"),
      iv2 ? iv2.toString("base64") : "",
      textBase64
    ),
    "base64"
  ).toString("utf8");
  var rsaEncryptSync = (textBase64, keyPem, padding) => native4.rsaEncrypt(textBase64, keyPem, padding || RSA_PADDING.NoPadding);
  var hashSHA1 = (text) => native4.hash("sha1", import_buffer5.Buffer.from(String(text), "utf8").toString("base64"));

  // src/musicSdk/kw/decodeLyric.js
  var { inflate } = require_pako();
  var handleInflate = (data) => new Promise((resolve, reject) => {
    resolve(Buffer.from(inflate(data)));
  });
  var buf_key = Buffer.from("yeelion");
  var buf_key_len = buf_key.length;
  var decodeLyric = async (rawData, isGetLyricx) => {
    const buf = Buffer.isBuffer(rawData) ? rawData : Buffer.from(rawData);
    if (buf.toString("utf8", 0, 10).toLowerCase() !== "tp=content") return "";
    const lrcData = await handleInflate(buf.subarray(buf.indexOf("\r\n\r\n") + 4));
    if (!isGetLyricx) return lrcData.toString("utf8");
    const buf_str = Buffer.from(lrcData.toString(), "base64");
    const buf_str_len = buf_str.length;
    const output = new Uint8Array(buf_str_len);
    let i = 0;
    while (i < buf_str_len) {
      let j = 0;
      while (j < buf_key_len && i < buf_str_len) {
        output[i] = buf_str[i] ^ buf_key[j];
        i++;
        j++;
      }
    }
    return Buffer.from(output).toString("utf8");
  };
  var decodeLyric_default = async ({ lrcBuffer, isGetLyricx }) => {
    const lrc = await decodeLyric(lrcBuffer, isGetLyricx);
    return lrc;
  };

  // src/musicSdk/kw/util.js
  var objStr2JSON = (str) => {
    return JSON.parse(str.replace(/('(?=(,\s*')))|('(?=:))|((?<=([:,]\s*))')|((?<={)')|('(?=}))/g, '"'));
  };
  var formatSinger = (rawData) => rawData.replace(/&/g, "、");
  var lrcTools = {
    rxps: {
      wordLine: /^(\[\d{1,2}:.*\d{1,4}\])\s*(\S+(?:\s+\S+)*)?\s*/,
      tagLine: /\[(ver|ti|ar|al|offset|by|kuwo):\s*(\S+(?:\s+\S+)*)\s*\]/,
      wordTimeAll: /<(-?\d+),(-?\d+)(?:,-?\d+)?>/g,
      wordTime: /<(-?\d+),(-?\d+)(?:,-?\d+)?>/
    },
    offset: 1,
    offset2: 1,
    isOK: false,
    lines: [],
    tags: [],
    getWordInfo(str, str2, prevWord) {
      const offset = parseInt(str);
      const offset2 = parseInt(str2);
      let startTime = Math.trunc(Math.abs((offset + offset2) / (this.offset * 2)));
      let endTime = Math.trunc(Math.abs((offset - offset2) / (this.offset2 * 2)) + startTime);
      if (prevWord) {
        if (startTime < prevWord.endTime) {
          prevWord.endTime = startTime;
          if (prevWord.startTime > prevWord.endTime) {
            prevWord.startTime = prevWord.endTime;
          }
          prevWord.newTimeStr = `<${prevWord.startTime},${Math.trunc(prevWord.endTime - prevWord.startTime)}>`;
        }
      }
      return {
        startTime,
        endTime,
        timeStr: `<${startTime},${Math.trunc(endTime - startTime)}>`
      };
    },
    parseLine(line) {
      if (line.length < 6) return;
      let result = this.rxps.wordLine.exec(line);
      if (result) {
        const time = result[1];
        let words = result[2];
        if (words == null) {
          words = "";
        }
        const wordTimes = words.match(this.rxps.wordTimeAll);
        if (!wordTimes) return;
        let preTimeInfo;
        for (const timeStr of wordTimes) {
          const result2 = this.rxps.wordTime.exec(timeStr);
          const wordInfo = this.getWordInfo(result2[1], result2[2], preTimeInfo);
          words = words.replace(timeStr, wordInfo.timeStr);
          if (preTimeInfo?.newTimeStr) words = words.replace(preTimeInfo.timeStr, preTimeInfo.newTimeStr);
          preTimeInfo = wordInfo;
        }
        this.lines.push(time + words);
        return;
      }
      result = this.rxps.tagLine.exec(line);
      if (!result) return;
      if (result[1] == "kuwo") {
        let content = result[2];
        if (content != null && content.includes("][")) {
          content = content.substring(0, content.indexOf("]["));
        }
        const valueOf = parseInt(content, 8);
        this.offset = Math.trunc(valueOf / 10);
        this.offset2 = Math.trunc(valueOf % 10);
        if (this.offset == 0 || Number.isNaN(this.offset) || this.offset2 == 0 || Number.isNaN(this.offset2)) {
          this.isOK = false;
        }
      } else {
        this.tags.push(line);
      }
    },
    parse(lrc) {
      const lines = lrc.split(/\r\n|\r|\n/);
      const tools = Object.create(this);
      tools.isOK = true;
      tools.offset = 1;
      tools.offset2 = 1;
      tools.lines = [];
      tools.tags = [];
      for (const line of lines) {
        if (!tools.isOK) throw new Error("failed");
        tools.parseLine(line);
      }
      if (!tools.lines.length) return "";
      let lrcs = tools.lines.join("\n");
      if (tools.tags.length) lrcs = `${tools.tags.join("\n")}
${lrcs}`;
      return lrcs;
    }
  };
  var wbdCrypto = {
    aesMode: "aes-128-ecb",
    // aesKey: Buffer.from([112, 87, 39, 61, 199, 250, 41, 191, 57, 68, 45, 114, 221, 94, 140, 228], 'binary'),
    aesKey: "cFcnPcf6Kb85RC1y3V6M5A==",
    aesIv: "",
    appId: "y67sprxhhpws",
    decodeData(base64Result) {
      const data = decodeURIComponent(base64Result);
      return JSON.parse(aesDecryptSync(data, this.aesKey, this.aesIv, AES_MODE.ECB_128_NoPadding));
    },
    createSign(data, time) {
      const str = `${this.appId}${data}${time}`;
      return toMD5(str).toUpperCase();
    },
    buildParam(jsonData) {
      const data = Buffer.from(JSON.stringify(jsonData)).toString("base64");
      const time = Date.now();
      const encodeData = aesEncryptSync(data, this.aesKey, this.aesIv, AES_MODE.ECB_128_NoPadding);
      const sign = this.createSign(encodeData, time);
      return `data=${encodeURIComponent(encodeData)}&time=${time}&appId=${this.appId}&sign=${sign}`;
    }
  };

  // src/musicSdk/kw/musicSearch.js
  var musicSearch_default = {
    regExps: {
      mInfo: /level:(\w+),bitrate:(\d+),format:(\w+),size:([\w.]+)/
    },
    limit: 30,
    total: 0,
    page: 0,
    allPage: 1,
    // cancelFn: null,
    musicSearch(str, page, limit) {
      const musicSearchRequestObj = httpFetch(`http://search.kuwo.cn/r.s?client=kt&all=${encodeURIComponent(str)}&pn=${page - 1}&rn=${limit}&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1`);
      return musicSearchRequestObj;
    },
    // getImg(songId) {
    // },
    // getLrc(songId) {
    // },
    handleResult(rawData) {
      const result = [];
      if (!rawData) return result;
      for (let i = 0; i < rawData.length; i++) {
        const info = rawData[i];
        let songId = info.MUSICRID.replace("MUSIC_", "");
        if (!info.N_MINFO) {
          console.log("N_MINFO is undefined");
          return null;
        }
        const types = [];
        const _types = {};
        let infoArr = info.N_MINFO.split(";");
        for (let info2 of infoArr) {
          info2 = info2.match(this.regExps.mInfo);
          if (info2) {
            switch (info2[2]) {
              case "4000":
                types.push({ type: "flac24bit", size: info2[4] });
                _types.flac24bit = {
                  size: info2[4].toLocaleUpperCase()
                };
                break;
              case "2000":
                types.push({ type: "flac", size: info2[4] });
                _types.flac = {
                  size: info2[4].toLocaleUpperCase()
                };
                break;
              case "320":
                types.push({ type: "320k", size: info2[4] });
                _types["320k"] = {
                  size: info2[4].toLocaleUpperCase()
                };
                break;
              case "128":
                types.push({ type: "128k", size: info2[4] });
                _types["128k"] = {
                  size: info2[4].toLocaleUpperCase()
                };
                break;
            }
          }
        }
        types.reverse();
        let interval = parseInt(info.DURATION);
        result.push({
          name: decodeName(info.SONGNAME),
          singer: formatSinger(decodeName(info.ARTIST)),
          source: "kw",
          // img = (info.album.name === '' || info.album.name === '空')
          //   ? `http://player.kuwo.cn/webmusic/sj/dtflagdate?flag=6&rid=MUSIC_160911.jpg`
          //   : `https://y.gtimg.cn/music/photo_new/T002R500x500M000${info.album.mid}.jpg`
          songmid: songId,
          albumId: decodeName(info.ALBUMID || ""),
          interval: Number.isNaN(interval) ? 0 : formatPlayTime(interval),
          albumName: info.ALBUM ? decodeName(info.ALBUM) : "",
          lrc: null,
          img: null,
          otherSource: null,
          types,
          _types,
          typeUrl: {}
        });
      }
      return result;
    },
    search(str, page = 1, limit, retryNum = 0) {
      if (retryNum > 2) return Promise.reject(new Error("try max num"));
      if (limit == null) limit = this.limit;
      return this.musicSearch(str, page, limit).then(({ body: result }) => {
        if (!result || result.TOTAL !== "0" && result.SHOW === "0") return this.search(str, page, limit, ++retryNum);
        let list = this.handleResult(result.abslist);
        if (list == null) return this.search(str, page, limit, ++retryNum);
        this.total = parseInt(result.TOTAL);
        this.page = page;
        this.allPage = Math.ceil(this.total / limit);
        return Promise.resolve({
          list,
          allPage: this.allPage,
          total: this.total,
          limit,
          source: "kw"
        });
      });
    }
  };

  // src/musicSdk/kw/album.js
  var album_default = {
    limit_list: 36,
    limit_song: 1e3,
    filterListDetail(rawList, albumName, albumId) {
      return rawList.map((item, inedx) => {
        let formats = item.formats.split("|");
        let types = [];
        let _types = {};
        if (formats.includes("MP3128")) {
          types.push({ type: "128k", size: null });
          _types["128k"] = {
            size: null
          };
        }
        if (formats.includes("MP3H")) {
          types.push({ type: "320k", size: null });
          _types["320k"] = {
            size: null
          };
        }
        if (formats.includes("ALFLAC")) {
          types.push({ type: "flac", size: null });
          _types.flac = {
            size: null
          };
        }
        if (formats.includes("HIRFLAC")) {
          types.push({ type: "flac24bit", size: null });
          _types.flac24bit = {
            size: null
          };
        }
        return {
          singer: formatSinger(decodeName(item.artist)),
          name: decodeName(item.name),
          albumName,
          albumId,
          songmid: item.id,
          source: "kw",
          interval: null,
          img: item.pic,
          lrc: null,
          otherSource: null,
          types,
          _types,
          typeUrl: {}
        };
      });
    },
    /**
     * 格式化播放数量
     * @param {*} num
     */
    formatPlayCount(num) {
      if (num > 1e8) return parseInt(num / 1e7) / 10 + "亿";
      if (num > 1e4) return parseInt(num / 1e3) / 10 + "万";
      return num;
    },
    getAlbumListDetail(id, page, retryNum = 0) {
      if (retryNum > 2) return Promise.reject(new Error("try max num"));
      const requestObj_listDetail = httpFetch(`http://search.kuwo.cn/r.s?pn=${page - 1}&rn=${this.limit_song}&stype=albuminfo&albumid=${id}&show_copyright_off=0&encoding=utf&vipver=MUSIC_9.1.0`);
      return requestObj_listDetail.then(({ statusCode, body }) => {
        if (statusCode !== 200) return this.getAlbumListDetail(id, page, ++retryNum);
        body = objStr2JSON(body);
        if (!body.musiclist) return this.getAlbumListDetail(id, page, ++retryNum);
        body.name = decodeName(body.name);
        return {
          list: this.filterListDetail(body.musiclist, body.name, body.albumid),
          page,
          limit: this.limit_song,
          total: parseInt(body.songnum),
          source: "kw",
          info: {
            name: body.name,
            img: body.img || body.hts_img,
            desc: decodeName(body.info),
            author: decodeName(body.artist)
            // play_count: this.formatPlayCount(body.playnum),
          }
        };
      });
    }
    // getAlbumListDetail(id, page, retryNum = 0) {
    //   if (retryNum > 2) return Promise.reject(new Error('try max num'))
    //   return tokenRequest(`http://www.kuwo.cn/api/www/album/albumInfo?albumId=${id}&pn=${page}&rn=${this.limit_song}&httpsStatus=1`).then((resp) => {
    //     return resp.then(({ statusCode, body }) => {
    //       console.log(body)
    //       return Promise.reject(new Error('failed'))
    //       // if (statusCode !== 200) return this.getAlbumListDetail(id, page, ++retryNum)
    //       // const data = body.data
    //       // console.log(data)
    //       // if (!data.musicList) return this.getAlbumListDetail(id, page, ++retryNum)
    //       // return {
    //       //   list: this.filterListDetail(data.musiclist),
    //       //   page,
    //       //   limit: this.limit_song,
    //       //   total: data.total,
    //       //   source: 'kw',
    //       //   info: {
    //       //     name: data.album,
    //       //     img: data.pic,
    //       //     desc: data.albuminfo,
    //       //     author: data.artist,
    //       //     play_count: this.formatPlayCount(data.playCnt),
    //       //   },
    //       // }
    //     })
    //   })
    // },
  };

  // src/musicSdk/kw/songList.js
  var songList_default = {
    limit_list: 36,
    limit_song: 1e3,
    successCode: 200,
    sortList: [
      {
        name: "最新",
        tid: "new",
        id: "new"
      },
      {
        name: "最热",
        tid: "hot",
        id: "hot"
      }
    ],
    regExps: {
      mInfo: /level:(\w+),bitrate:(\d+),format:(\w+),size:([\w.]+)/,
      // http://www.kuwo.cn/playlist_detail/2886046289
      // https://m.kuwo.cn/h5app/playlist/2736267853?t=qqfriend
      listDetailLink: /^.+\/playlist(?:_detail)?\/(\d+)(?:\?.*|&.*$|#.*$|$)/
    },
    tagsUrl: "http://wapi.kuwo.cn/api/pc/classify/playlist/getTagList?cmd=rcm_keyword_playlist&user=0&prod=kwplayer_pc_9.0.5.0&vipver=9.0.5.0&source=kwplayer_pc_9.0.5.0&loginUid=0&loginSid=0&appUid=76039576",
    hotTagUrl: "http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmTagList?loginUid=0&loginSid=0&appUid=76039576",
    getListUrl({ sortId, id, type, page }) {
      if (!id) return `http://wapi.kuwo.cn/api/pc/classify/playlist/getRcmPlayList?loginUid=0&loginSid=0&appUid=76039576&&pn=${page}&rn=${this.limit_list}&order=${sortId}`;
      switch (type) {
        case "10000":
          return `http://wapi.kuwo.cn/api/pc/classify/playlist/getTagPlayList?loginUid=0&loginSid=0&appUid=76039576&pn=${page}&id=${id}&rn=${this.limit_list}`;
        case "43":
          return `http://mobileinterfaces.kuwo.cn/er.s?type=get_pc_qz_data&f=web&id=${id}&prod=pc`;
      }
    },
    getListDetailUrl(id, page) {
      return `http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=${id}&pn=${page - 1}&rn=${this.limit_song}&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1`;
    },
    // http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=2849349915&pn=0&rn=100&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1
    // 获取标签
    getTag(tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const request = httpFetch(this.tagsUrl);
      return request.then(({ body }) => {
        if (body.code !== this.successCode) return this.getTag(++tryNum);
        return this.filterTagInfo(body.data);
      });
    },
    // 获取标签
    getHotTag(tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const request = httpFetch(this.hotTagUrl);
      return request.then(({ body }) => {
        if (body.code !== this.successCode) return this.getHotTag(++tryNum);
        return this.filterInfoHotTag(body.data[0].data);
      });
    },
    filterInfoHotTag(rawList) {
      return rawList.map((item) => ({
        id: `${item.id}-${item.digest}`,
        name: item.name,
        source: "kw"
      }));
    },
    filterTagInfo(rawList) {
      return rawList.map((type) => ({
        name: type.name,
        list: type.data.map((item) => ({
          parent_id: type.id,
          parent_name: type.name,
          id: `${item.id}-${item.digest}`,
          name: item.name,
          source: "kw"
        }))
      }));
    },
    // 获取列表数据
    getList(sortId, tagId, page, tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      let id;
      let type;
      if (tagId) {
        let arr = tagId.split("-");
        id = arr[0];
        type = arr[1];
      } else {
        id = null;
      }
      const request = httpFetch(this.getListUrl({ sortId, id, type, page }));
      return request.then(({ body }) => {
        if (!id || type == "10000") {
          if (body.code !== this.successCode) return this.getList(sortId, tagId, page, ++tryNum);
          return {
            list: this.filterList(body.data.data),
            total: body.data.total,
            page: body.data.pn,
            limit: body.data.rn,
            source: "kw"
          };
        } else if (!body.length) {
          return this.getList(sortId, tagId, page, ++tryNum);
        }
        return {
          list: this.filterList2(body),
          total: 1e3,
          page,
          limit: 1e3,
          source: "kw"
        };
      });
    },
    /**
     * 格式化播放数量
     * @param {*} num
     */
    formatPlayCount(num) {
      if (num > 1e8) return parseInt(num / 1e7) / 10 + "亿";
      if (num > 1e4) return parseInt(num / 1e3) / 10 + "万";
      return num;
    },
    filterList(rawData) {
      return rawData.map((item) => ({
        play_count: this.formatPlayCount(item.listencnt),
        play_num: parseInt(item.listencnt) || 0,
        fav_num: parseInt(item.favorcnt) || 0,
        id: `digest-${item.digest}__${item.id}`,
        author: item.uname,
        name: item.name,
        // time: item.publish_time,
        total: item.total,
        img: item.img,
        grade: item.favorcnt / 10,
        desc: item.desc,
        source: "kw"
      }));
    },
    filterList2(rawData) {
      const list = [];
      const allowedTypes = ["songlist", "list", "album"];
      rawData.forEach((item) => {
        item.list.forEach((item2) => {
          if (!allowedTypes.includes(item2.type)) return;
          list.push({
            play_count: item2.play_count && this.formatPlayCount(item2.listencnt),
            play_num: parseInt(item2.listencnt) || 0,
            fav_num: parseInt(item2.favorcnt) || 0,
            id: `digest-${item2.digest}__${item2.id}`,
            author: item2.uname,
            name: item2.name,
            total: item2.total,
            // time: item.publish_time,
            img: item2.img,
            grade: item2.favorcnt && item2.favorcnt / 10,
            desc: item2.desc,
            source: "kw"
          });
        });
      });
      return list;
    },
    getListDetailDigest8(id, page, tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const requestObj = httpFetch(this.getListDetailUrl(id, page));
      return requestObj.then(({ body }) => {
        if (body.result !== "ok") return this.getListDetail(id, page, ++tryNum);
        return {
          list: this.filterListDetail(body.musiclist),
          page,
          limit: body.rn,
          total: body.total,
          source: "kw",
          info: {
            name: body.title,
            img: body.pic,
            desc: body.info,
            author: body.uname,
            play_count: this.formatPlayCount(body.playnum)
          }
        };
      });
    },
    getListDetailDigest5Info(id, tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const requestObj = httpFetch(`http://qukudata.kuwo.cn/q.k?op=query&cont=ninfo&node=${id}&pn=0&rn=1&fmt=json&src=mbox&level=2`);
      return requestObj.then(({ statusCode, body }) => {
        if (statusCode != 200 || !body.child) return this.getListDetail(id, ++tryNum);
        return body.child.length ? body.child[0].sourceid : null;
      });
    },
    getListDetailDigest5Music(id, page, tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const requestObj = httpFetch(`http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=${id}&pn=${page - 1}}&rn=${this.limit_song}&encode=utf-8&keyset=pl2012&identity=kuwo&pcmp4=1`);
      return requestObj.then(({ body }) => {
        if (body.result !== "ok") return this.getListDetail(id, page, ++tryNum);
        return {
          list: this.filterListDetail(body.musiclist),
          page,
          limit: body.rn,
          total: body.total,
          source: "kw",
          info: {
            name: body.title,
            img: body.pic,
            desc: body.info,
            author: body.uname,
            play_count: this.formatPlayCount(body.playnum)
          }
        };
      });
    },
    async getListDetailDigest5(id, page, retryNum) {
      const detailId = await this.getListDetailDigest5Info(id, retryNum);
      return this.getListDetailDigest5Music(detailId, page, retryNum);
    },
    filterBDListDetail(rawList) {
      return rawList.map((item) => {
        let types = [];
        let _types = {};
        for (let info of item.audios) {
          info.size = info.size?.toLocaleUpperCase();
          switch (info.bitrate) {
            case "4000":
              types.push({ type: "flac24bit", size: info.size });
              _types.flac24bit = {
                size: info.size
              };
              break;
            case "2000":
              types.push({ type: "flac", size: info.size });
              _types.flac = {
                size: info.size
              };
              break;
            case "320":
              types.push({ type: "320k", size: info.size });
              _types["320k"] = {
                size: info.size
              };
              break;
            case "128":
              types.push({ type: "128k", size: info.size });
              _types["128k"] = {
                size: info.size
              };
              break;
          }
        }
        types.reverse();
        return {
          singer: item.artists.map((s) => s.name).join("、"),
          name: item.name,
          albumName: item.album,
          albumId: item.albumId,
          songmid: item.id,
          source: "kw",
          interval: formatPlayTime(item.duration),
          img: item.albumPic,
          releaseDate: item.releaseDate,
          lrc: null,
          otherSource: null,
          types,
          _types,
          typeUrl: {}
        };
      });
    },
    getReqId() {
      function t() {
        return (65536 * (1 + Math.random()) | 0).toString(16).substring(1);
      }
      return t() + t() + t() + t() + t() + t() + t() + t();
    },
    async getListDetailMusicListByBDListInfo(id, source) {
      const { body: infoData } = await httpFetch(`https://bd-api.kuwo.cn/api/service/playlist/info/${id}?reqId=${this.getReqId()}&source=${source}`, {
        headers: {
          "User-Agent": "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36",
          plat: "h5"
        }
      }).catch(() => ({ code: 0 }));
      if (infoData.code != 200) return null;
      return {
        name: infoData.data.name,
        img: infoData.data.pic,
        desc: infoData.data.description,
        author: infoData.data.creatorName,
        play_count: infoData.data.playNum
      };
    },
    async getListDetailMusicListByBDUserPub(id) {
      const { body: infoData } = await httpFetch(`https://bd-api.kuwo.cn/api/ucenter/users/pub/${id}?reqId=${this.getReqId()}`, {
        headers: {
          "User-Agent": "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36",
          plat: "h5"
        }
      }).catch(() => ({ code: 0 }));
      if (infoData.code != 200) return null;
      return {
        name: infoData.data.userInfo.nickname + "喜欢的音乐",
        img: infoData.data.userInfo.headImg,
        desc: "",
        author: infoData.data.userInfo.nickname,
        play_count: ""
      };
    },
    async getListDetailMusicListByBDList(id, source, page, tryNum = 0) {
      const { body: listData } = await httpFetch(`https://bd-api.kuwo.cn/api/service/playlist/${id}/musicList?reqId=${this.getReqId()}&source=${source}&pn=${page}&rn=${this.limit_song}`, {
        headers: {
          "User-Agent": "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36",
          plat: "h5"
        }
      }).catch(() => {
        if (tryNum > 2) return Promise.reject(new Error("try max num"));
        return this.getListDetailMusicListByBDList(id, source, page, ++tryNum);
      });
      if (listData.code !== 200) return Promise.reject(new Error("failed"));
      return {
        list: this.filterBDListDetail(listData.data.list),
        page,
        limit: listData.data.pageSize,
        total: listData.data.total,
        source: "kw"
      };
    },
    async getListDetailMusicListByBD(id, page) {
      const uid = /uid=(\d+)/.exec(id)?.[1];
      const listId = /playlistId=(\d+)/.exec(id)?.[1];
      const source = /source=(\d+)/.exec(id)?.[1];
      if (!listId) return Promise.reject(new Error("failed"));
      const task = [this.getListDetailMusicListByBDList(listId, source, page)];
      switch (source) {
        case "4":
          task.push(this.getListDetailMusicListByBDListInfo(listId, source));
          break;
        case "5":
          task.push(this.getListDetailMusicListByBDUserPub(uid ?? listId));
          break;
      }
      const [listData, info] = await Promise.all(task);
      listData.info = info ?? {
        name: "",
        img: "",
        desc: "",
        author: "",
        play_count: ""
      };
      return listData;
    },
    // 获取歌曲列表内的音乐
    getListDetail(id, page, retryNum = 0) {
      if (/\/bodian\//.test(id)) return this.getListDetailMusicListByBD(id, page);
      if (/[?&:/]/.test(id)) id = id.replace(this.regExps.listDetailLink, "$1");
      else if (/^digest-/.test(id)) {
        let [digest, _id] = id.split("__");
        digest = digest.replace("digest-", "");
        id = _id;
        switch (digest) {
          case "8":
            break;
          case "13":
            return album_default.getAlbumListDetail(id, page, retryNum);
          case "5":
          default:
            return this.getListDetailDigest5(id, page, retryNum);
        }
      }
      return this.getListDetailDigest8(id, page, retryNum);
    },
    filterListDetail(rawData) {
      return rawData.map((item) => {
        let infoArr = item.N_MINFO.split(";");
        let types = [];
        let _types = {};
        for (let info of infoArr) {
          info = info.match(this.regExps.mInfo);
          if (info) {
            switch (info[2]) {
              case "4000":
                types.push({ type: "flac24bit", size: info[4] });
                _types.flac24bit = {
                  size: info[4].toLocaleUpperCase()
                };
                break;
              case "2000":
                types.push({ type: "flac", size: info[4] });
                _types.flac = {
                  size: info[4].toLocaleUpperCase()
                };
                break;
              case "320":
                types.push({ type: "320k", size: info[4] });
                _types["320k"] = {
                  size: info[4].toLocaleUpperCase()
                };
                break;
              case "128":
                types.push({ type: "128k", size: info[4] });
                _types["128k"] = {
                  size: info[4].toLocaleUpperCase()
                };
                break;
            }
          }
        }
        types.reverse();
        return {
          singer: formatSinger(decodeName(item.artist)),
          name: decodeName(item.name),
          albumName: decodeName(item.album),
          albumId: item.albumid,
          songmid: item.id,
          source: "kw",
          interval: formatPlayTime(parseInt(item.duration)),
          img: null,
          lrc: null,
          otherSource: null,
          types,
          _types,
          typeUrl: {}
        };
      });
    },
    getTags() {
      return Promise.all([this.getTag(), this.getHotTag()]).then(([tags, hotTag]) => ({ tags, hotTag, source: "kw" }));
    },
    getDetailPageUrl(id) {
      if (/[?&:/]/.test(id)) id = id.replace(this.regExps.listDetailLink, "$1");
      else if (/^digest-/.test(id)) {
        let result = id.split("__");
        id = result[1];
      }
      return `http://www.kuwo.cn/playlist_detail/${id}`;
    },
    search(text, page, limit = 20) {
      return httpFetch(`http://search.kuwo.cn/r.s?all=${encodeURIComponent(text)}&pn=${page - 1}&rn=${limit}&rformat=json&encoding=utf8&ver=mbox&vipver=MUSIC_8.7.7.0_BCS37&plat=pc&devid=28156413&ft=playlist&pay=0&needliveshow=0`).then(({ body }) => {
        body = objStr2JSON(body);
        return {
          list: body.abslist.map((item) => {
            return {
              play_count: this.formatPlayCount(item.playcnt),
              id: item.playlistid,
              author: decodeName(item.nickname),
              name: decodeName(item.name),
              total: item.songnum,
              // time: item.publish_time,
              img: item.pic,
              desc: decodeName(item.intro),
              source: "kw"
            };
          }),
          limit,
          total: parseInt(body.TOTAL),
          source: "kw"
        };
      });
    }
  };

  // src/musicSdk/kw/leaderboard.js
  var boardList = [{ id: "kw__93", name: "飙升榜", bangid: "93" }, { id: "kw__17", name: "新歌榜", bangid: "17" }, { id: "kw__16", name: "热歌榜", bangid: "16" }, { id: "kw__158", name: "抖音热歌榜", bangid: "158" }, { id: "kw__292", name: "铃声榜", bangid: "292" }, { id: "kw__284", name: "热评榜", bangid: "284" }, { id: "kw__290", name: "ACG新歌榜", bangid: "290" }, { id: "kw__286", name: "台湾KKBOX榜", bangid: "286" }, { id: "kw__279", name: "冬日暖心榜", bangid: "279" }, { id: "kw__281", name: "巴士随身听榜", bangid: "281" }, { id: "kw__255", name: "KTV点唱榜", bangid: "255" }, { id: "kw__280", name: "家务进行曲榜", bangid: "280" }, { id: "kw__282", name: "熬夜修仙榜", bangid: "282" }, { id: "kw__283", name: "枕边轻音乐榜", bangid: "283" }, { id: "kw__278", name: "古风音乐榜", bangid: "278" }, { id: "kw__264", name: "Vlog音乐榜", bangid: "264" }, { id: "kw__242", name: "电音榜", bangid: "242" }, { id: "kw__187", name: "流行趋势榜", bangid: "187" }, { id: "kw__204", name: "现场音乐榜", bangid: "204" }, { id: "kw__186", name: "ACG神曲榜", bangid: "186" }, { id: "kw__185", name: "最强翻唱榜", bangid: "185" }, { id: "kw__26", name: "经典怀旧榜", bangid: "26" }, { id: "kw__104", name: "华语榜", bangid: "104" }, { id: "kw__182", name: "粤语榜", bangid: "182" }, { id: "kw__22", name: "欧美榜", bangid: "22" }, { id: "kw__184", name: "韩语榜", bangid: "184" }, { id: "kw__183", name: "日语榜", bangid: "183" }, { id: "kw__145", name: "会员畅听榜", bangid: "145" }, { id: "kw__153", name: "网红新歌榜", bangid: "153" }, { id: "kw__64", name: "影视金曲榜", bangid: "64" }, { id: "kw__176", name: "DJ嗨歌榜", bangid: "176" }, { id: "kw__106", name: "真声音", bangid: "106" }, { id: "kw__12", name: "Billboard榜", bangid: "12" }, { id: "kw__49", name: "iTunes音乐榜", bangid: "49" }, { id: "kw__180", name: "beatport电音榜", bangid: "180" }, { id: "kw__13", name: "英国UK榜", bangid: "13" }, { id: "kw__164", name: "百大DJ榜", bangid: "164" }, { id: "kw__246", name: "YouTube音乐排行榜", bangid: "246" }, { id: "kw__265", name: "韩国Genie榜", bangid: "265" }, { id: "kw__14", name: "韩国M-net榜", bangid: "14" }, { id: "kw__8", name: "香港电台榜", bangid: "8" }, { id: "kw__15", name: "日本公信榜", bangid: "15" }, { id: "kw__151", name: "腾讯音乐人原创榜", bangid: "151" }];
  var sortQualityArray = (array) => {
    const qualityMap = {
      flac24bit: 4,
      flac: 3,
      "320k": 2,
      "128k": 1
    };
    const rawQualityArray = [];
    const newQualityArray = [];
    array.forEach((item, index) => {
      const type = qualityMap[item.type];
      if (!type) return;
      rawQualityArray.push({ type, index });
    });
    rawQualityArray.sort((a, b) => a.type - b.type);
    rawQualityArray.forEach((item) => {
      newQualityArray.push(array[item.index]);
    });
    return newQualityArray;
  };
  var leaderboard_default = {
    list: [
      {
        id: "kwbiaosb",
        name: "飙升榜",
        bangid: 93
      },
      {
        id: "kwregb",
        name: "热歌榜",
        bangid: 16
      },
      {
        id: "kwhuiyb",
        name: "会员榜",
        bangid: 145
      },
      {
        id: "kwdouyb",
        name: "抖音榜",
        bangid: 158
      },
      {
        id: "kwqsb",
        name: "趋势榜",
        bangid: 187
      },
      {
        id: "kwhuaijb",
        name: "怀旧榜",
        bangid: 26
      },
      {
        id: "kwhuayb",
        name: "华语榜",
        bangid: 104
      },
      {
        id: "kwyueyb",
        name: "粤语榜",
        bangid: 182
      },
      {
        id: "kwoumb",
        name: "欧美榜",
        bangid: 22
      },
      {
        id: "kwhanyb",
        name: "韩语榜",
        bangid: 184
      },
      {
        id: "kwriyb",
        name: "日语榜",
        bangid: 183
      }
    ],
    // getUrl: (p, l, id) => `http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json&pn=${p - 1}&rn=${l}&type=bang&data=content&id=${id}&show_copyright_off=0&pcmp4=1&isbang=1`,
    regExps: {
      mInfo: /level:(\w+),bitrate:(\d+),format:(\w+),size:([\w.]+)/
    },
    limit: 100,
    getBoardsData() {
      const request = httpFetch("http://qukudata.kuwo.cn/q.k?op=query&cont=tree&node=2&pn=0&rn=1000&fmt=json&level=2");
      return request;
    },
    getData(url) {
      const requestDataObj = httpFetch(url);
      return requestDataObj;
    },
    filterData(rawList) {
      return rawList.map((item) => {
        let types = [];
        const _types = {};
        const qualitys = /* @__PURE__ */ new Set();
        item.n_minfo.split(";").forEach((i) => {
          const info = i.match(this.regExps.mInfo);
          if (!info) return;
          const quality = info[2];
          const size = info[4].toLocaleUpperCase();
          if (qualitys.has(quality)) return;
          qualitys.add(quality);
          switch (quality) {
            case "4000":
              types.push({ type: "flac24bit", size });
              _types.flac24bit = { size };
              break;
            case "2000":
              types.push({ type: "flac", size });
              _types.flac = { size };
              break;
            case "320":
              types.push({ type: "320k", size });
              _types["320k"] = { size };
              break;
            case "128":
              types.push({ type: "128k", size });
              _types["128k"] = { size };
              break;
          }
        });
        types = sortQualityArray(types);
        return {
          singer: formatSinger(decodeName(item.artist)),
          name: decodeName(item.name),
          albumName: decodeName(item.album),
          albumId: item.albumId,
          songmid: item.id,
          source: "kw",
          interval: formatPlayTime(parseInt(item.duration)),
          img: item.pic,
          lrc: null,
          otherSource: null,
          types,
          _types,
          typeUrl: {}
        };
      });
    },
    filterBoardsData(rawList) {
      let list = [];
      for (const board of rawList) {
        if (board.source != "1") continue;
        list.push({
          id: "kw__" + board.sourceid,
          name: board.name,
          bangid: String(board.sourceid)
        });
      }
      return list;
    },
    async getBoards(retryNum = 0) {
      try {
        const { statusCode, body } = await httpFetch("https://wapi.kuwo.cn/api/pc/bang/list?httpsStatus=1", {
          headers: { Referer: "https://www.kuwo.cn/", Accept: "application/json" }
        });
        if (statusCode === 200 && body && Array.isArray(body.child)) {
          const list = [];
          const walk = (nodes) => {
            for (const node of nodes || []) {
              if (Array.isArray(node.child) && node.child.length) {
                walk(node.child);
                continue;
              }
              const id = String(node.sourceid ?? "");
              const name = node.name || node.disname || "";
              if (!id || !name) continue;
              list.push({ id: "kw__" + id, name, bangid: id, img: node.pic || null });
            }
          };
          walk(body.child);
          if (list.length) {
            this.list = list;
            return { list, source: "kw" };
          }
        }
      } catch (error) {
      }
      this.list = boardList;
      return {
        list: boardList,
        source: "kw"
      };
    },
    getList(id, page, limit, retryNum = 0) {
      if (++retryNum > 3) return Promise.reject(new Error("try max num"));
      const pageSize = limit || this.limit;
      const requestBody = { uid: "", devId: "", sFrom: "kuwo_sdk", user_type: "AP", carSource: "kwplayercar_ar_6.0.1.0_apk_keluze.apk", id, pn: page - 1, rn: pageSize };
      const requestUrl = `https://wbd.kuwo.cn/api/bd/bang/bang_info?${wbdCrypto.buildParam(requestBody)}`;
      const request = httpFetch(requestUrl, { cache: "default" });
      return request.then(({ statusCode, body }) => {
        const rawData = wbdCrypto.decodeData(body);
        const data = rawData.data;
        if (statusCode !== 200 || rawData.code != 200 || !data.musiclist) return this.getList(id, page, pageSize, retryNum);
        const total = parseInt(data.total);
        const list = this.filterData(data.musiclist);
        return {
          total,
          list: limit ? list.slice(0, pageSize) : list,
          limit: pageSize,
          page,
          source: "kw"
        };
      });
    }
    // getDetailPageUrl(id) {
    //   return `http://www.kuwo.cn/rankList/${id}`
    // },
  };

  // src/musicSdk/kw/hotSearch.js
  var hotSearch_default = {
    async getList(retryNum = 0) {
      if (retryNum > 2) return Promise.reject(new Error("try max num"));
      const _requestObj = httpFetch("http://hotword.kuwo.cn/hotword.s?prod=kwplayer_ar_9.3.0.1&corp=kuwo&newver=2&vipver=9.3.0.1&source=kwplayer_ar_9.3.0.1_40.apk&p2p=1&notrace=0&uid=0&plat=kwplayer_ar&rformat=json&encoding=utf8&tabid=1", {
        headers: {
          "User-Agent": "Dalvik/2.1.0 (Linux; U; Android 9;)"
        }
      });
      const { body, statusCode } = await _requestObj;
      if (statusCode != 200 || body.status !== "ok") throw new Error("获取热搜词失败");
      return { source: "kw", list: this.filterList(body.tagvalue) };
    },
    filterList(rawList) {
      return rawList.map((item) => item.key);
    }
  };

  // src/musicSdk/kw/tipSearch.js
  var tipSearch_default = {
    regExps: {
      relWord: /RELWORD=(.+)/
    },
    async tipSearchBySong(str) {
      const request = httpFetch(`https://tips.kuwo.cn/t.s?corp=kuwo&newver=3&p2p=1&notrace=0&c=mbox&w=${encodeURIComponent(str)}&encoding=utf8&rformat=json`, {
        Referer: "http://www.kuwo.cn/"
      });
      return request.then(({ body, statusCode }) => {
        if (statusCode != 200 || !body.WORDITEMS) return Promise.reject(new Error("请求失败"));
        return body.WORDITEMS;
      });
    },
    handleResult(rawData) {
      return rawData.map((item) => item.RELWORD);
    },
    async search(str) {
      return this.tipSearchBySong(str).then((result) => this.handleResult(result));
    }
  };

  // src/musicSdk/kw/lyric.js
  var timeExp = /^\[([\d:.]*)\]{1}/g;
  var existTimeExp = /\[\d{1,2}:.*\d{1,4}]/;
  var lyricxTag = /^<-?\d+,-?\d+>/;
  var lyric_default = {
    /* sortLrcArr(arr) {
        const lrcSet = new Set()
        let lrc = []
        let lrcT = []
        let markIndex = []
        for (const item of arr) {
          if (lrcSet.has(item.time)) {
            if (lrc.length < 2) continue
            const index = lrc.findIndex(l => l.time == item.time)
            markIndex.push(index)
            if (index == lrc.length - 1) {
              lrcT.push({ ...lrc[index], time: item.time })
              lrc.push(item)
            } else {
              lrcT.push({ ...lrc[index], time: lrc[index + 1].time })
              if (item.text) {
                //   const lastIndex = lrc.length - 1
                //   markIndex.push(lastIndex)
                //   lrcT.push({ ...lrc[lastIndex], time: lrc[lastIndex - 1].time })
                lrc.push(item)
              }
            }
          } else {
            lrc.push(item)
            lrcSet.add(item.time)
          }
        }
    
        // console.log(markIndex)
        markIndex = Array.from(new Set(markIndex))
        for (let index = markIndex.length - 1; index >= 0; index--) {
          lrc.splice(markIndex[index], 1)
        }
    
        // if (lrcT.length) {
        //   if (lrc.length * 0.4 < lrcT.length) { // 翻译数量需大于歌词数量的0.4倍，否则认为没有翻译
        //     const tItem = lrc.pop()
        //     tItem.time = lrc[lrc.length - 1].time
        //     lrcT.push(tItem)
        //   } else {
        //     lrc = arr
        //     lrcT = []
        //   }
        // }
    
        console.log(lrc, lrcT)
    
        return {
          lrc,
          lrcT,
        }
      }, */
    sortLrcArr(arr) {
      const lrcSet = /* @__PURE__ */ new Set();
      let lrc = [];
      let lrcT = [];
      let isLyricx = false;
      for (const item of arr) {
        if (lrcSet.has(item.time)) {
          if (lrc.length < 2) continue;
          const tItem = lrc.pop();
          tItem.time = lrc[lrc.length - 1].time;
          lrcT.push(tItem);
          lrc.push(item);
        } else {
          lrc.push(item);
          lrcSet.add(item.time);
        }
        if (!isLyricx && lyricxTag.test(item.text)) isLyricx = true;
      }
      if (!isLyricx && lrcT.length > lrc.length * 0.3 && lrc.length - lrcT.length > 6) {
        throw new Error("failed");
      }
      return {
        lrc,
        lrcT
      };
    },
    transformLrc(tags, lrclist) {
      return `${tags.join("\n")}
${lrclist ? lrclist.map((l) => `[${l.time}]${l.text}
`).join("") : "暂无歌词"}`;
    },
    parseLrc(lrc) {
      const lines = lrc.split(/\r\n|\r|\n/);
      let tags = [];
      let lrcArr = [];
      for (let i = 0; i < lines.length; i++) {
        const line = lines[i].trim();
        let result = timeExp.exec(line);
        if (result) {
          const text = line.replace(timeExp, "").trim();
          let time = result[1];
          if (/\.\d\d$/.test(time)) time += "0";
          lrcArr.push({
            time,
            text
          });
        } else if (lrcTools.rxps.tagLine.test(line)) {
          tags.push(line);
        }
      }
      const lrcInfo = this.sortLrcArr(lrcArr);
      return {
        lyric: decodeName(this.transformLrc(tags, lrcInfo.lrc)),
        tlyric: lrcInfo.lrcT.length ? decodeName(this.transformLrc(tags, lrcInfo.lrcT)) : ""
      };
    },
    // getLyric2(musicInfo, isGetLyricx = true) {
    //   let requestObj = httpFetch(`http://newlyric.kuwo.cn/newlyric.lrc?${buildParams(musicInfo.songmid, isGetLyricx)}`)
    //   requestObj = requestObj.then(({ statusCode, body, raw }) => {
    //     if (statusCode != 200) return Promise.reject(new Error(JSON.stringify(body)))
    //     return decodeLyric({ lrcBase64: raw.toString('base64'), isGetLyricx }).then(base64Data => {
    //       let lrcInfo
    //       console.log(Buffer.from(base64Data, 'base64').toString())
    //       try {
    //         lrcInfo = this.parseLrc(Buffer.from(base64Data, 'base64').toString())
    //       } catch {
    //         return Promise.reject(new Error('Get lyric failed'))
    //       }
    //       if (lrcInfo.tlyric) lrcInfo.tlyric = lrcInfo.tlyric.replace(lrcTools.rxps.wordTimeAll, '')
    //       lrcInfo.lxlyric = lrcTools.parse(lrcInfo.lyric)
    //       // console.log(lrcInfo.lyric)
    //       // console.log(lrcInfo.tlyric)
    //       // console.log(lrcInfo.lxlyric)
    //       // console.log(JSON.stringify(lrcInfo))
    //     })
    //   })
    //   return requestObj
    // },
    getLyric(musicInfo, isGetLyricx = true) {
      let requestObj = httpFetch(`http://mlyric.kuwo.cn/mobi.s?f=web&type=lyric&lrcx=${isGetLyricx ? 1 : 0}&rid=${musicInfo.songmid}&encode=utf8`, {
        cache: false,
        binary: true
      });
      requestObj = requestObj.then(async ({ statusCode, body }) => {
        if (statusCode != 200) return Promise.reject(new Error(JSON.stringify(body)));
        const lrcText = await decodeLyric_default({ lrcBuffer: body, isGetLyricx });
        let lrcInfo;
        try {
          lrcInfo = this.parseLrc(lrcText);
        } catch (err2) {
          return Promise.reject(new Error("Get lyric failed"));
        }
        if (lrcInfo.tlyric) lrcInfo.tlyric = lrcInfo.tlyric.replace(lrcTools.rxps.wordTimeAll, "");
        try {
          lrcInfo.lxlyric = lrcTools.parse(lrcInfo.lyric);
        } catch {
          lrcInfo.lxlyric = "";
        }
        lrcInfo.lyric = lrcInfo.lyric.replace(lrcTools.rxps.wordTimeAll, "");
        if (!existTimeExp.test(lrcInfo.lyric)) return Promise.reject(new Error("Get lyric failed"));
        return lrcInfo;
      });
      return requestObj;
    }
  };

  // src/musicSdk/kw/pic.js
  var pic_default = {
    getPic({ songmid }) {
      let requestObj = httpFetch(`http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500&rid=${songmid}`);
      requestObj = requestObj.then(({ body }) => /^http/.test(body) ? body : null);
      return requestObj;
    }
  };

  // src/musicSdk/kg/musicSearch.js
  var musicSearch_default2 = {
    limit: 30,
    total: 0,
    page: 0,
    allPage: 1,
    // 通道 A：App 移动 CDN（无需签名，返回体积小、首包快）
    appSearch(str, page, limit) {
      const requestObj = httpFetch(`http://mobilecdn.kugou.com/api/v3/search/song?keyword=${encodeURIComponent(str)}&page=${page}&pagesize=${limit}&showtype=1&format=json`, {
        headers: {
          "User-Agent": "Android9-AndroidPhone-13194-130-0-searchrecommendprotocol-wifi",
          "kg-rc": 1
        }
      });
      return requestObj.then(({ body, statusCode }) => {
        if (statusCode !== 200 || !body?.data?.info?.length) throw new Error("app search empty");
        const list = body.data.info.map((item) => this.appFilterData(item));
        if (!list.length) throw new Error("app search empty");
        return { list, total: body.data.total ?? list.length };
      });
    },
    appFilterData(rawData) {
      const types = [];
      const _types = {};
      const push = (type, size, hash) => {
        if (!size || size === 0 || !hash) return;
        const formatted = sizeFormate(size);
        types.push({ type, size: formatted, hash });
        _types[type] = { size: formatted, hash };
      };
      push("128k", rawData.filesize, rawData.hash);
      push("320k", rawData["320filesize"], rawData["320hash"]);
      push("flac", rawData.sqfilesize, rawData.sqhash);
      return {
        singer: decodeName(rawData.singername),
        name: decodeName(rawData.songname),
        albumName: decodeName(rawData.album_name),
        albumId: rawData.album_id,
        songmid: String(rawData.audio_id ?? ""),
        source: "kg",
        interval: formatPlayTime(rawData.duration),
        _interval: rawData.duration,
        img: null,
        lrc: null,
        otherSource: null,
        hash: rawData.hash,
        types,
        _types,
        typeUrl: {}
      };
    },
    // 通道 B：网页端搜索（字段更全，作为兜底）
    musicSearch(str, page, limit) {
      const searchRequest = httpFetch(`https://songsearch.kugou.com/song_search_v2?keyword=${encodeURIComponent(str)}&page=${page}&pagesize=${limit}&userid=0&clientver=&platform=WebFilter&filter=2&iscorrection=1&privilege_filter=0&area_code=1`);
      return searchRequest.then(({ body }) => body);
    },
    filterData(rawData) {
      const types = [];
      const _types = {};
      if (rawData.FileSize !== 0) {
        let size = sizeFormate(rawData.FileSize);
        types.push({ type: "128k", size, hash: rawData.FileHash });
        _types["128k"] = {
          size,
          hash: rawData.FileHash
        };
      }
      if (rawData.HQFileSize !== 0) {
        let size = sizeFormate(rawData.HQFileSize);
        types.push({ type: "320k", size, hash: rawData.HQFileHash });
        _types["320k"] = {
          size,
          hash: rawData.HQFileHash
        };
      }
      if (rawData.SQFileSize !== 0) {
        let size = sizeFormate(rawData.SQFileSize);
        types.push({ type: "flac", size, hash: rawData.SQFileHash });
        _types.flac = {
          size,
          hash: rawData.SQFileHash
        };
      }
      if (rawData.ResFileSize !== 0) {
        let size = sizeFormate(rawData.ResFileSize);
        types.push({ type: "flac24bit", size, hash: rawData.ResFileHash });
        _types.flac24bit = {
          size,
          hash: rawData.ResFileHash
        };
      }
      return {
        singer: decodeName(formatSingerName(rawData.Singers, "name")),
        name: decodeName(`${rawData.OriSongName}${rawData.Suffix ? ` ${rawData.Suffix}` : ""}`),
        albumName: decodeName(rawData.AlbumName),
        albumId: rawData.AlbumID,
        songmid: rawData.Audioid,
        source: "kg",
        interval: formatPlayTime(rawData.Duration),
        _interval: rawData.Duration,
        img: null,
        lrc: null,
        otherSource: null,
        hash: rawData.FileHash,
        types,
        _types,
        typeUrl: {}
      };
    },
    handleResult(rawData) {
      let ids = /* @__PURE__ */ new Set();
      const list = [];
      rawData.forEach((item) => {
        const key = item.Audioid + item.FileHash;
        if (ids.has(key)) return;
        ids.add(key);
        list.push(this.filterData(item));
        for (const childItem of item.Grp) {
          const key2 = item.Audioid + item.FileHash;
          if (ids.has(key2)) continue;
          ids.add(key2);
          list.push(this.filterData(childItem));
        }
      });
      return list;
    },
    webSearch(str, page, limit) {
      return this.musicSearch(str, page, limit).then((result) => {
        if (!result || result.error_code !== 0) throw new Error("web search failed");
        let list = this.handleResult(result.data.lists);
        if (list == null || !list.length) throw new Error("web search empty");
        return { list, total: result.data.total ?? list.length };
      });
    },
    search(str, page = 1, limit) {
      if (limit == null) limit = this.limit;
      return requestByDataChannel(
        () => this.appSearch(str, page, limit),
        () => this.webSearch(str, page, limit)
      ).then((result) => {
        this.total = result.total;
        this.page = page;
        this.allPage = Math.ceil(this.total / limit) || 1;
        return {
          list: result.list,
          allPage: this.allPage,
          limit,
          total: this.total,
          source: "kg"
        };
      });
    }
  };

  // src/musicSdk/kg/songList.js
  var import_infSign_min = __toESM(require_infSign_min(), 1);

  // node_modules/pako/dist/pako.esm.mjs
  var Z_FIXED$1 = 4;
  var Z_BINARY = 0;
  var Z_TEXT = 1;
  var Z_UNKNOWN$1 = 2;
  function zero$1(buf) {
    let len = buf.length;
    while (--len >= 0) {
      buf[len] = 0;
    }
  }
  var STORED_BLOCK = 0;
  var STATIC_TREES = 1;
  var DYN_TREES = 2;
  var MIN_MATCH$1 = 3;
  var MAX_MATCH$1 = 258;
  var LENGTH_CODES$1 = 29;
  var LITERALS$1 = 256;
  var L_CODES$1 = LITERALS$1 + 1 + LENGTH_CODES$1;
  var D_CODES$1 = 30;
  var BL_CODES$1 = 19;
  var HEAP_SIZE$1 = 2 * L_CODES$1 + 1;
  var MAX_BITS$1 = 15;
  var Buf_size = 16;
  var MAX_BL_BITS = 7;
  var END_BLOCK = 256;
  var REP_3_6 = 16;
  var REPZ_3_10 = 17;
  var REPZ_11_138 = 18;
  var extra_lbits = (
    /* extra bits for each length code */
    new Uint8Array([0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0])
  );
  var extra_dbits = (
    /* extra bits for each distance code */
    new Uint8Array([0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13])
  );
  var extra_blbits = (
    /* extra bits for each bit length code */
    new Uint8Array([0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 3, 7])
  );
  var bl_order = new Uint8Array([16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15]);
  var DIST_CODE_LEN = 512;
  var static_ltree = new Array((L_CODES$1 + 2) * 2);
  zero$1(static_ltree);
  var static_dtree = new Array(D_CODES$1 * 2);
  zero$1(static_dtree);
  var _dist_code = new Array(DIST_CODE_LEN);
  zero$1(_dist_code);
  var _length_code = new Array(MAX_MATCH$1 - MIN_MATCH$1 + 1);
  zero$1(_length_code);
  var base_length = new Array(LENGTH_CODES$1);
  zero$1(base_length);
  var base_dist = new Array(D_CODES$1);
  zero$1(base_dist);
  function StaticTreeDesc(static_tree, extra_bits, extra_base, elems, max_length) {
    this.static_tree = static_tree;
    this.extra_bits = extra_bits;
    this.extra_base = extra_base;
    this.elems = elems;
    this.max_length = max_length;
    this.has_stree = static_tree && static_tree.length;
  }
  var static_l_desc;
  var static_d_desc;
  var static_bl_desc;
  function TreeDesc(dyn_tree, stat_desc) {
    this.dyn_tree = dyn_tree;
    this.max_code = 0;
    this.stat_desc = stat_desc;
  }
  var d_code = (dist) => {
    return dist < 256 ? _dist_code[dist] : _dist_code[256 + (dist >>> 7)];
  };
  var put_short = (s, w) => {
    s.pending_buf[s.pending++] = w & 255;
    s.pending_buf[s.pending++] = w >>> 8 & 255;
  };
  var send_bits = (s, value, length) => {
    if (s.bi_valid > Buf_size - length) {
      s.bi_buf |= value << s.bi_valid & 65535;
      put_short(s, s.bi_buf);
      s.bi_buf = value >> Buf_size - s.bi_valid;
      s.bi_valid += length - Buf_size;
    } else {
      s.bi_buf |= value << s.bi_valid & 65535;
      s.bi_valid += length;
    }
  };
  var send_code = (s, c, tree) => {
    send_bits(
      s,
      tree[c * 2],
      tree[c * 2 + 1]
      /*.Len*/
    );
  };
  var bi_reverse = (code, len) => {
    let res = 0;
    do {
      res |= code & 1;
      code >>>= 1;
      res <<= 1;
    } while (--len > 0);
    return res >>> 1;
  };
  var bi_flush = (s) => {
    if (s.bi_valid === 16) {
      put_short(s, s.bi_buf);
      s.bi_buf = 0;
      s.bi_valid = 0;
    } else if (s.bi_valid >= 8) {
      s.pending_buf[s.pending++] = s.bi_buf & 255;
      s.bi_buf >>= 8;
      s.bi_valid -= 8;
    }
  };
  var gen_bitlen = (s, desc) => {
    const tree = desc.dyn_tree;
    const max_code = desc.max_code;
    const stree = desc.stat_desc.static_tree;
    const has_stree = desc.stat_desc.has_stree;
    const extra = desc.stat_desc.extra_bits;
    const base = desc.stat_desc.extra_base;
    const max_length = desc.stat_desc.max_length;
    let h;
    let n, m;
    let bits;
    let xbits;
    let f;
    let overflow = 0;
    for (bits = 0; bits <= MAX_BITS$1; bits++) {
      s.bl_count[bits] = 0;
    }
    tree[s.heap[s.heap_max] * 2 + 1] = 0;
    for (h = s.heap_max + 1; h < HEAP_SIZE$1; h++) {
      n = s.heap[h];
      bits = tree[tree[n * 2 + 1] * 2 + 1] + 1;
      if (bits > max_length) {
        bits = max_length;
        overflow++;
      }
      tree[n * 2 + 1] = bits;
      if (n > max_code) {
        continue;
      }
      s.bl_count[bits]++;
      xbits = 0;
      if (n >= base) {
        xbits = extra[n - base];
      }
      f = tree[n * 2];
      s.opt_len += f * (bits + xbits);
      if (has_stree) {
        s.static_len += f * (stree[n * 2 + 1] + xbits);
      }
    }
    if (overflow === 0) {
      return;
    }
    do {
      bits = max_length - 1;
      while (s.bl_count[bits] === 0) {
        bits--;
      }
      s.bl_count[bits]--;
      s.bl_count[bits + 1] += 2;
      s.bl_count[max_length]--;
      overflow -= 2;
    } while (overflow > 0);
    for (bits = max_length; bits !== 0; bits--) {
      n = s.bl_count[bits];
      while (n !== 0) {
        m = s.heap[--h];
        if (m > max_code) {
          continue;
        }
        if (tree[m * 2 + 1] !== bits) {
          s.opt_len += (bits - tree[m * 2 + 1]) * tree[m * 2];
          tree[m * 2 + 1] = bits;
        }
        n--;
      }
    }
  };
  var gen_codes = (tree, max_code, bl_count) => {
    const next_code = new Array(MAX_BITS$1 + 1);
    let code = 0;
    let bits;
    let n;
    for (bits = 1; bits <= MAX_BITS$1; bits++) {
      code = code + bl_count[bits - 1] << 1;
      next_code[bits] = code;
    }
    for (n = 0; n <= max_code; n++) {
      let len = tree[n * 2 + 1];
      if (len === 0) {
        continue;
      }
      tree[n * 2] = bi_reverse(next_code[len]++, len);
    }
  };
  var tr_static_init = () => {
    let n;
    let bits;
    let length;
    let code;
    let dist;
    const bl_count = new Array(MAX_BITS$1 + 1);
    length = 0;
    for (code = 0; code < LENGTH_CODES$1 - 1; code++) {
      base_length[code] = length;
      for (n = 0; n < 1 << extra_lbits[code]; n++) {
        _length_code[length++] = code;
      }
    }
    _length_code[length - 1] = code;
    dist = 0;
    for (code = 0; code < 16; code++) {
      base_dist[code] = dist;
      for (n = 0; n < 1 << extra_dbits[code]; n++) {
        _dist_code[dist++] = code;
      }
    }
    dist >>= 7;
    for (; code < D_CODES$1; code++) {
      base_dist[code] = dist << 7;
      for (n = 0; n < 1 << extra_dbits[code] - 7; n++) {
        _dist_code[256 + dist++] = code;
      }
    }
    for (bits = 0; bits <= MAX_BITS$1; bits++) {
      bl_count[bits] = 0;
    }
    n = 0;
    while (n <= 143) {
      static_ltree[n * 2 + 1] = 8;
      n++;
      bl_count[8]++;
    }
    while (n <= 255) {
      static_ltree[n * 2 + 1] = 9;
      n++;
      bl_count[9]++;
    }
    while (n <= 279) {
      static_ltree[n * 2 + 1] = 7;
      n++;
      bl_count[7]++;
    }
    while (n <= 287) {
      static_ltree[n * 2 + 1] = 8;
      n++;
      bl_count[8]++;
    }
    gen_codes(static_ltree, L_CODES$1 + 1, bl_count);
    for (n = 0; n < D_CODES$1; n++) {
      static_dtree[n * 2 + 1] = 5;
      static_dtree[n * 2] = bi_reverse(n, 5);
    }
    static_l_desc = new StaticTreeDesc(static_ltree, extra_lbits, LITERALS$1 + 1, L_CODES$1, MAX_BITS$1);
    static_d_desc = new StaticTreeDesc(static_dtree, extra_dbits, 0, D_CODES$1, MAX_BITS$1);
    static_bl_desc = new StaticTreeDesc(new Array(0), extra_blbits, 0, BL_CODES$1, MAX_BL_BITS);
  };
  var init_block = (s) => {
    let n;
    for (n = 0; n < L_CODES$1; n++) {
      s.dyn_ltree[n * 2] = 0;
    }
    for (n = 0; n < D_CODES$1; n++) {
      s.dyn_dtree[n * 2] = 0;
    }
    for (n = 0; n < BL_CODES$1; n++) {
      s.bl_tree[n * 2] = 0;
    }
    s.dyn_ltree[END_BLOCK * 2] = 1;
    s.opt_len = s.static_len = 0;
    s.sym_next = s.matches = 0;
  };
  var bi_windup = (s) => {
    if (s.bi_valid > 8) {
      put_short(s, s.bi_buf);
    } else if (s.bi_valid > 0) {
      s.pending_buf[s.pending++] = s.bi_buf;
    }
    s.bi_buf = 0;
    s.bi_valid = 0;
  };
  var smaller = (tree, n, m, depth) => {
    const _n2 = n * 2;
    const _m2 = m * 2;
    return tree[_n2] < tree[_m2] || tree[_n2] === tree[_m2] && depth[n] <= depth[m];
  };
  var pqdownheap = (s, tree, k) => {
    const v = s.heap[k];
    let j = k << 1;
    while (j <= s.heap_len) {
      if (j < s.heap_len && smaller(tree, s.heap[j + 1], s.heap[j], s.depth)) {
        j++;
      }
      if (smaller(tree, v, s.heap[j], s.depth)) {
        break;
      }
      s.heap[k] = s.heap[j];
      k = j;
      j <<= 1;
    }
    s.heap[k] = v;
  };
  var compress_block = (s, ltree, dtree) => {
    let dist;
    let lc;
    let sx = 0;
    let code;
    let extra;
    if (s.sym_next !== 0) {
      do {
        dist = s.pending_buf[s.sym_buf + sx++] & 255;
        dist += (s.pending_buf[s.sym_buf + sx++] & 255) << 8;
        lc = s.pending_buf[s.sym_buf + sx++];
        if (dist === 0) {
          send_code(s, lc, ltree);
        } else {
          code = _length_code[lc];
          send_code(s, code + LITERALS$1 + 1, ltree);
          extra = extra_lbits[code];
          if (extra !== 0) {
            lc -= base_length[code];
            send_bits(s, lc, extra);
          }
          dist--;
          code = d_code(dist);
          send_code(s, code, dtree);
          extra = extra_dbits[code];
          if (extra !== 0) {
            dist -= base_dist[code];
            send_bits(s, dist, extra);
          }
        }
      } while (sx < s.sym_next);
    }
    send_code(s, END_BLOCK, ltree);
  };
  var build_tree = (s, desc) => {
    const tree = desc.dyn_tree;
    const stree = desc.stat_desc.static_tree;
    const has_stree = desc.stat_desc.has_stree;
    const elems = desc.stat_desc.elems;
    let n, m;
    let max_code = -1;
    let node;
    s.heap_len = 0;
    s.heap_max = HEAP_SIZE$1;
    for (n = 0; n < elems; n++) {
      if (tree[n * 2] !== 0) {
        s.heap[++s.heap_len] = max_code = n;
        s.depth[n] = 0;
      } else {
        tree[n * 2 + 1] = 0;
      }
    }
    while (s.heap_len < 2) {
      node = s.heap[++s.heap_len] = max_code < 2 ? ++max_code : 0;
      tree[node * 2] = 1;
      s.depth[node] = 0;
      s.opt_len--;
      if (has_stree) {
        s.static_len -= stree[node * 2 + 1];
      }
    }
    desc.max_code = max_code;
    for (n = s.heap_len >> 1; n >= 1; n--) {
      pqdownheap(s, tree, n);
    }
    node = elems;
    do {
      n = s.heap[
        1
        /*SMALLEST*/
      ];
      s.heap[
        1
        /*SMALLEST*/
      ] = s.heap[s.heap_len--];
      pqdownheap(
        s,
        tree,
        1
        /*SMALLEST*/
      );
      m = s.heap[
        1
        /*SMALLEST*/
      ];
      s.heap[--s.heap_max] = n;
      s.heap[--s.heap_max] = m;
      tree[node * 2] = tree[n * 2] + tree[m * 2];
      s.depth[node] = (s.depth[n] >= s.depth[m] ? s.depth[n] : s.depth[m]) + 1;
      tree[n * 2 + 1] = tree[m * 2 + 1] = node;
      s.heap[
        1
        /*SMALLEST*/
      ] = node++;
      pqdownheap(
        s,
        tree,
        1
        /*SMALLEST*/
      );
    } while (s.heap_len >= 2);
    s.heap[--s.heap_max] = s.heap[
      1
      /*SMALLEST*/
    ];
    gen_bitlen(s, desc);
    gen_codes(tree, max_code, s.bl_count);
  };
  var scan_tree = (s, tree, max_code) => {
    let n;
    let prevlen = -1;
    let curlen;
    let nextlen = tree[0 * 2 + 1];
    let count = 0;
    let max_count = 7;
    let min_count = 4;
    if (nextlen === 0) {
      max_count = 138;
      min_count = 3;
    }
    tree[(max_code + 1) * 2 + 1] = 65535;
    for (n = 0; n <= max_code; n++) {
      curlen = nextlen;
      nextlen = tree[(n + 1) * 2 + 1];
      if (++count < max_count && curlen === nextlen) {
        continue;
      } else if (count < min_count) {
        s.bl_tree[curlen * 2] += count;
      } else if (curlen !== 0) {
        if (curlen !== prevlen) {
          s.bl_tree[curlen * 2]++;
        }
        s.bl_tree[REP_3_6 * 2]++;
      } else if (count <= 10) {
        s.bl_tree[REPZ_3_10 * 2]++;
      } else {
        s.bl_tree[REPZ_11_138 * 2]++;
      }
      count = 0;
      prevlen = curlen;
      if (nextlen === 0) {
        max_count = 138;
        min_count = 3;
      } else if (curlen === nextlen) {
        max_count = 6;
        min_count = 3;
      } else {
        max_count = 7;
        min_count = 4;
      }
    }
  };
  var send_tree = (s, tree, max_code) => {
    let n;
    let prevlen = -1;
    let curlen;
    let nextlen = tree[0 * 2 + 1];
    let count = 0;
    let max_count = 7;
    let min_count = 4;
    if (nextlen === 0) {
      max_count = 138;
      min_count = 3;
    }
    for (n = 0; n <= max_code; n++) {
      curlen = nextlen;
      nextlen = tree[(n + 1) * 2 + 1];
      if (++count < max_count && curlen === nextlen) {
        continue;
      } else if (count < min_count) {
        do {
          send_code(s, curlen, s.bl_tree);
        } while (--count !== 0);
      } else if (curlen !== 0) {
        if (curlen !== prevlen) {
          send_code(s, curlen, s.bl_tree);
          count--;
        }
        send_code(s, REP_3_6, s.bl_tree);
        send_bits(s, count - 3, 2);
      } else if (count <= 10) {
        send_code(s, REPZ_3_10, s.bl_tree);
        send_bits(s, count - 3, 3);
      } else {
        send_code(s, REPZ_11_138, s.bl_tree);
        send_bits(s, count - 11, 7);
      }
      count = 0;
      prevlen = curlen;
      if (nextlen === 0) {
        max_count = 138;
        min_count = 3;
      } else if (curlen === nextlen) {
        max_count = 6;
        min_count = 3;
      } else {
        max_count = 7;
        min_count = 4;
      }
    }
  };
  var build_bl_tree = (s) => {
    let max_blindex;
    scan_tree(s, s.dyn_ltree, s.l_desc.max_code);
    scan_tree(s, s.dyn_dtree, s.d_desc.max_code);
    build_tree(s, s.bl_desc);
    for (max_blindex = BL_CODES$1 - 1; max_blindex >= 3; max_blindex--) {
      if (s.bl_tree[bl_order[max_blindex] * 2 + 1] !== 0) {
        break;
      }
    }
    s.opt_len += 3 * (max_blindex + 1) + 5 + 5 + 4;
    return max_blindex;
  };
  var send_all_trees = (s, lcodes, dcodes, blcodes) => {
    let rank2;
    send_bits(s, lcodes - 257, 5);
    send_bits(s, dcodes - 1, 5);
    send_bits(s, blcodes - 4, 4);
    for (rank2 = 0; rank2 < blcodes; rank2++) {
      send_bits(s, s.bl_tree[bl_order[rank2] * 2 + 1], 3);
    }
    send_tree(s, s.dyn_ltree, lcodes - 1);
    send_tree(s, s.dyn_dtree, dcodes - 1);
  };
  var detect_data_type = (s) => {
    let block_mask = 4093624447;
    let n;
    for (n = 0; n <= 31; n++, block_mask >>>= 1) {
      if (block_mask & 1 && s.dyn_ltree[n * 2] !== 0) {
        return Z_BINARY;
      }
    }
    if (s.dyn_ltree[9 * 2] !== 0 || s.dyn_ltree[10 * 2] !== 0 || s.dyn_ltree[13 * 2] !== 0) {
      return Z_TEXT;
    }
    for (n = 32; n < LITERALS$1; n++) {
      if (s.dyn_ltree[n * 2] !== 0) {
        return Z_TEXT;
      }
    }
    return Z_BINARY;
  };
  var static_init_done = false;
  var _tr_init$1 = (s) => {
    if (!static_init_done) {
      tr_static_init();
      static_init_done = true;
    }
    s.l_desc = new TreeDesc(s.dyn_ltree, static_l_desc);
    s.d_desc = new TreeDesc(s.dyn_dtree, static_d_desc);
    s.bl_desc = new TreeDesc(s.bl_tree, static_bl_desc);
    s.bi_buf = 0;
    s.bi_valid = 0;
    init_block(s);
  };
  var _tr_stored_block$1 = (s, buf, stored_len, last) => {
    send_bits(s, (STORED_BLOCK << 1) + (last ? 1 : 0), 3);
    bi_windup(s);
    put_short(s, stored_len);
    put_short(s, ~stored_len);
    if (stored_len) {
      s.pending_buf.set(s.window.subarray(buf, buf + stored_len), s.pending);
    }
    s.pending += stored_len;
  };
  var _tr_align$1 = (s) => {
    send_bits(s, STATIC_TREES << 1, 3);
    send_code(s, END_BLOCK, static_ltree);
    bi_flush(s);
  };
  var _tr_flush_block$1 = (s, buf, stored_len, last) => {
    let opt_lenb, static_lenb;
    let max_blindex = 0;
    if (s.level > 0) {
      if (s.strm.data_type === Z_UNKNOWN$1) {
        s.strm.data_type = detect_data_type(s);
      }
      build_tree(s, s.l_desc);
      build_tree(s, s.d_desc);
      max_blindex = build_bl_tree(s);
      opt_lenb = s.opt_len + 3 + 7 >>> 3;
      static_lenb = s.static_len + 3 + 7 >>> 3;
      if (static_lenb <= opt_lenb) {
        opt_lenb = static_lenb;
      }
    } else {
      opt_lenb = static_lenb = stored_len + 5;
    }
    if (stored_len + 4 <= opt_lenb && buf !== -1) {
      _tr_stored_block$1(s, buf, stored_len, last);
    } else if (s.strategy === Z_FIXED$1 || static_lenb === opt_lenb) {
      send_bits(s, (STATIC_TREES << 1) + (last ? 1 : 0), 3);
      compress_block(s, static_ltree, static_dtree);
    } else {
      send_bits(s, (DYN_TREES << 1) + (last ? 1 : 0), 3);
      send_all_trees(s, s.l_desc.max_code + 1, s.d_desc.max_code + 1, max_blindex + 1);
      compress_block(s, s.dyn_ltree, s.dyn_dtree);
    }
    init_block(s);
    if (last) {
      bi_windup(s);
    }
  };
  var _tr_tally$1 = (s, dist, lc) => {
    s.pending_buf[s.sym_buf + s.sym_next++] = dist;
    s.pending_buf[s.sym_buf + s.sym_next++] = dist >> 8;
    s.pending_buf[s.sym_buf + s.sym_next++] = lc;
    if (dist === 0) {
      s.dyn_ltree[lc * 2]++;
    } else {
      s.matches++;
      dist--;
      s.dyn_ltree[(_length_code[lc] + LITERALS$1 + 1) * 2]++;
      s.dyn_dtree[d_code(dist) * 2]++;
    }
    return s.sym_next === s.sym_end;
  };
  var _tr_init_1 = _tr_init$1;
  var _tr_stored_block_1 = _tr_stored_block$1;
  var _tr_flush_block_1 = _tr_flush_block$1;
  var _tr_tally_1 = _tr_tally$1;
  var _tr_align_1 = _tr_align$1;
  var trees = {
    _tr_init: _tr_init_1,
    _tr_stored_block: _tr_stored_block_1,
    _tr_flush_block: _tr_flush_block_1,
    _tr_tally: _tr_tally_1,
    _tr_align: _tr_align_1
  };
  var adler32 = (adler, buf, len, pos) => {
    let s1 = adler & 65535 | 0, s2 = adler >>> 16 & 65535 | 0, n = 0;
    while (len !== 0) {
      n = len > 2e3 ? 2e3 : len;
      len -= n;
      do {
        s1 = s1 + buf[pos++] | 0;
        s2 = s2 + s1 | 0;
      } while (--n);
      s1 %= 65521;
      s2 %= 65521;
    }
    return s1 | s2 << 16 | 0;
  };
  var adler32_1 = adler32;
  var makeTable = () => {
    let c, table = [];
    for (var n = 0; n < 256; n++) {
      c = n;
      for (var k = 0; k < 8; k++) {
        c = c & 1 ? 3988292384 ^ c >>> 1 : c >>> 1;
      }
      table[n] = c;
    }
    return table;
  };
  var crcTable = new Uint32Array(makeTable());
  var crc32 = (crc, buf, len, pos) => {
    const t = crcTable;
    const end = pos + len;
    crc ^= -1;
    for (let i = pos; i < end; i++) {
      crc = crc >>> 8 ^ t[(crc ^ buf[i]) & 255];
    }
    return crc ^ -1;
  };
  var crc32_1 = crc32;
  var messages = {
    2: "need dictionary",
    /* Z_NEED_DICT       2  */
    1: "stream end",
    /* Z_STREAM_END      1  */
    0: "",
    /* Z_OK              0  */
    "-1": "file error",
    /* Z_ERRNO         (-1) */
    "-2": "stream error",
    /* Z_STREAM_ERROR  (-2) */
    "-3": "data error",
    /* Z_DATA_ERROR    (-3) */
    "-4": "insufficient memory",
    /* Z_MEM_ERROR     (-4) */
    "-5": "buffer error",
    /* Z_BUF_ERROR     (-5) */
    "-6": "incompatible version"
    /* Z_VERSION_ERROR (-6) */
  };
  var constants$2 = {
    /* Allowed flush values; see deflate() and inflate() below for details */
    Z_NO_FLUSH: 0,
    Z_PARTIAL_FLUSH: 1,
    Z_SYNC_FLUSH: 2,
    Z_FULL_FLUSH: 3,
    Z_FINISH: 4,
    Z_BLOCK: 5,
    Z_TREES: 6,
    /* Return codes for the compression/decompression functions. Negative values
    * are errors, positive values are used for special but normal events.
    */
    Z_OK: 0,
    Z_STREAM_END: 1,
    Z_NEED_DICT: 2,
    Z_ERRNO: -1,
    Z_STREAM_ERROR: -2,
    Z_DATA_ERROR: -3,
    Z_MEM_ERROR: -4,
    Z_BUF_ERROR: -5,
    //Z_VERSION_ERROR: -6,
    /* compression levels */
    Z_NO_COMPRESSION: 0,
    Z_BEST_SPEED: 1,
    Z_BEST_COMPRESSION: 9,
    Z_DEFAULT_COMPRESSION: -1,
    Z_FILTERED: 1,
    Z_HUFFMAN_ONLY: 2,
    Z_RLE: 3,
    Z_FIXED: 4,
    Z_DEFAULT_STRATEGY: 0,
    /* Possible values of the data_type field (though see inflate()) */
    Z_BINARY: 0,
    Z_TEXT: 1,
    //Z_ASCII:                1, // = Z_TEXT (deprecated)
    Z_UNKNOWN: 2,
    /* The deflate compression method */
    Z_DEFLATED: 8
    //Z_NULL:                 null // Use -1 or null inline, depending on var type
  };
  var { _tr_init, _tr_stored_block, _tr_flush_block, _tr_tally, _tr_align } = trees;
  var {
    Z_NO_FLUSH: Z_NO_FLUSH$2,
    Z_PARTIAL_FLUSH,
    Z_FULL_FLUSH: Z_FULL_FLUSH$1,
    Z_FINISH: Z_FINISH$3,
    Z_BLOCK: Z_BLOCK$1,
    Z_OK: Z_OK$3,
    Z_STREAM_END: Z_STREAM_END$3,
    Z_STREAM_ERROR: Z_STREAM_ERROR$2,
    Z_DATA_ERROR: Z_DATA_ERROR$2,
    Z_BUF_ERROR: Z_BUF_ERROR$2,
    Z_DEFAULT_COMPRESSION: Z_DEFAULT_COMPRESSION$1,
    Z_FILTERED,
    Z_HUFFMAN_ONLY,
    Z_RLE,
    Z_FIXED,
    Z_DEFAULT_STRATEGY: Z_DEFAULT_STRATEGY$1,
    Z_UNKNOWN,
    Z_DEFLATED: Z_DEFLATED$2
  } = constants$2;
  var MAX_MEM_LEVEL = 9;
  var MAX_WBITS$1 = 15;
  var DEF_MEM_LEVEL = 8;
  var LENGTH_CODES = 29;
  var LITERALS = 256;
  var L_CODES = LITERALS + 1 + LENGTH_CODES;
  var D_CODES = 30;
  var BL_CODES = 19;
  var HEAP_SIZE = 2 * L_CODES + 1;
  var MAX_BITS = 15;
  var MIN_MATCH = 3;
  var MAX_MATCH = 258;
  var MIN_LOOKAHEAD = MAX_MATCH + MIN_MATCH + 1;
  var PRESET_DICT = 32;
  var INIT_STATE = 42;
  var GZIP_STATE = 57;
  var EXTRA_STATE = 69;
  var NAME_STATE = 73;
  var COMMENT_STATE = 91;
  var HCRC_STATE = 103;
  var BUSY_STATE = 113;
  var FINISH_STATE = 666;
  var BS_NEED_MORE = 1;
  var BS_BLOCK_DONE = 2;
  var BS_FINISH_STARTED = 3;
  var BS_FINISH_DONE = 4;
  var OS_CODE = 3;
  var err = (strm, errorCode) => {
    strm.msg = messages[errorCode];
    return errorCode;
  };
  var rank = (f) => {
    return f * 2 - (f > 4 ? 9 : 0);
  };
  var zero = (buf) => {
    let len = buf.length;
    while (--len >= 0) {
      buf[len] = 0;
    }
  };
  var slide_hash = (s) => {
    let n, m;
    let p;
    let wsize = s.w_size;
    n = s.hash_size;
    p = n;
    do {
      m = s.head[--p];
      s.head[p] = m >= wsize ? m - wsize : 0;
    } while (--n);
    n = wsize;
    p = n;
    do {
      m = s.prev[--p];
      s.prev[p] = m >= wsize ? m - wsize : 0;
    } while (--n);
  };
  var HASH = (s, prev, data) => (prev << s.hash_shift ^ data) & s.hash_mask;
  var INSERT_STRING = (s, str) => {
    let h;
    if (s.legacy_hash) {
      h = s.ins_h = HASH(s, s.ins_h, s.window[str + MIN_MATCH - 1]);
    } else {
      const w = s.window;
      const value = w[str] | w[str + 1] << 8 | w[str + 2] << 16 | w[str + 3] << 24;
      h = s.ins_h = Math.imul(value, 66521) + 66521 >>> 16 & s.hash_mask;
    }
    const hash_head = s.prev[str & s.w_mask] = s.head[h];
    s.head[h] = str;
    return hash_head;
  };
  var flush_pending = (strm) => {
    const s = strm.state;
    let len = s.pending;
    if (len > strm.avail_out) {
      len = strm.avail_out;
    }
    if (len === 0) {
      return;
    }
    strm.output.set(s.pending_buf.subarray(s.pending_out, s.pending_out + len), strm.next_out);
    strm.next_out += len;
    s.pending_out += len;
    strm.total_out += len;
    strm.avail_out -= len;
    s.pending -= len;
    if (s.pending === 0) {
      s.pending_out = 0;
    }
  };
  var flush_block_only = (s, last) => {
    _tr_flush_block(s, s.block_start >= 0 ? s.block_start : -1, s.strstart - s.block_start, last);
    s.block_start = s.strstart;
    flush_pending(s.strm);
  };
  var put_byte = (s, b) => {
    s.pending_buf[s.pending++] = b;
  };
  var putShortMSB = (s, b) => {
    s.pending_buf[s.pending++] = b >>> 8 & 255;
    s.pending_buf[s.pending++] = b & 255;
  };
  var read_buf = (strm, buf, start, size) => {
    let len = strm.avail_in;
    if (len > size) {
      len = size;
    }
    if (len === 0) {
      return 0;
    }
    strm.avail_in -= len;
    buf.set(strm.input.subarray(strm.next_in, strm.next_in + len), start);
    if (strm.state.wrap === 1) {
      strm.adler = adler32_1(strm.adler, buf, len, start);
    } else if (strm.state.wrap === 2) {
      strm.adler = crc32_1(strm.adler, buf, len, start);
    }
    strm.next_in += len;
    strm.total_in += len;
    return len;
  };
  var longest_match = (s, cur_match) => {
    let chain_length = s.max_chain_length;
    let scan = s.strstart;
    let match;
    let len;
    let best_len = s.prev_length;
    let nice_match = s.nice_match;
    const limit = s.strstart > s.w_size - MIN_LOOKAHEAD ? s.strstart - (s.w_size - MIN_LOOKAHEAD) : 0;
    const _win = s.window;
    const wmask = s.w_mask;
    const prev = s.prev;
    const strend = s.strstart + MAX_MATCH;
    let scan_end1 = _win[scan + best_len - 1];
    let scan_end = _win[scan + best_len];
    if (s.prev_length >= s.good_match) {
      chain_length >>= 2;
    }
    if (nice_match > s.lookahead) {
      nice_match = s.lookahead;
    }
    do {
      match = cur_match;
      if (_win[match + best_len] !== scan_end || _win[match + best_len - 1] !== scan_end1 || _win[match] !== _win[scan] || _win[++match] !== _win[scan + 1]) {
        continue;
      }
      scan += 2;
      match++;
      do {
      } while (_win[++scan] === _win[++match] && _win[++scan] === _win[++match] && _win[++scan] === _win[++match] && _win[++scan] === _win[++match] && _win[++scan] === _win[++match] && _win[++scan] === _win[++match] && _win[++scan] === _win[++match] && _win[++scan] === _win[++match] && scan < strend);
      len = MAX_MATCH - (strend - scan);
      scan = strend - MAX_MATCH;
      if (len > best_len) {
        s.match_start = cur_match;
        best_len = len;
        if (len >= nice_match) {
          break;
        }
        scan_end1 = _win[scan + best_len - 1];
        scan_end = _win[scan + best_len];
      }
    } while ((cur_match = prev[cur_match & wmask]) > limit && --chain_length !== 0);
    if (best_len <= s.lookahead) {
      return best_len;
    }
    return s.lookahead;
  };
  var fill_window = (s) => {
    const _w_size = s.w_size;
    let n, more, str;
    do {
      more = s.window_size - s.lookahead - s.strstart;
      if (s.strstart >= _w_size + (_w_size - MIN_LOOKAHEAD)) {
        s.window.set(s.window.subarray(_w_size, _w_size + _w_size - more), 0);
        s.match_start -= _w_size;
        s.strstart -= _w_size;
        s.block_start -= _w_size;
        if (s.insert > s.strstart) {
          s.insert = s.strstart;
        }
        slide_hash(s);
        more += _w_size;
      }
      if (s.strm.avail_in === 0) {
        break;
      }
      n = read_buf(s.strm, s.window, s.strstart + s.lookahead, more);
      s.lookahead += n;
      if (!s.legacy_hash) {
        if (s.lookahead + s.insert > MIN_MATCH) {
          str = s.strstart - s.insert;
          while (s.insert) {
            INSERT_STRING(s, str);
            str++;
            s.insert--;
            if (s.lookahead + s.insert <= MIN_MATCH) {
              break;
            }
          }
        }
      } else if (s.lookahead + s.insert >= MIN_MATCH) {
        str = s.strstart - s.insert;
        s.ins_h = s.window[str];
        s.ins_h = HASH(s, s.ins_h, s.window[str + 1]);
        while (s.insert) {
          INSERT_STRING(s, str);
          str++;
          s.insert--;
          if (s.lookahead + s.insert < MIN_MATCH) {
            break;
          }
        }
      }
    } while (s.lookahead < MIN_LOOKAHEAD && s.strm.avail_in !== 0);
  };
  var deflate_stored = (s, flush) => {
    let min_block = s.pending_buf_size - 5 > s.w_size ? s.w_size : s.pending_buf_size - 5;
    let len, left, have, last = 0;
    let used = s.strm.avail_in;
    do {
      len = 65535;
      have = s.bi_valid + 42 >> 3;
      if (s.strm.avail_out < have) {
        break;
      }
      have = s.strm.avail_out - have;
      left = s.strstart - s.block_start;
      if (len > left + s.strm.avail_in) {
        len = left + s.strm.avail_in;
      }
      if (len > have) {
        len = have;
      }
      if (len < min_block && (len === 0 && flush !== Z_FINISH$3 || flush === Z_NO_FLUSH$2 || len !== left + s.strm.avail_in)) {
        break;
      }
      last = flush === Z_FINISH$3 && len === left + s.strm.avail_in ? 1 : 0;
      _tr_stored_block(s, 0, 0, last);
      s.pending_buf[s.pending - 4] = len;
      s.pending_buf[s.pending - 3] = len >> 8;
      s.pending_buf[s.pending - 2] = ~len;
      s.pending_buf[s.pending - 1] = ~len >> 8;
      flush_pending(s.strm);
      if (left) {
        if (left > len) {
          left = len;
        }
        s.strm.output.set(s.window.subarray(s.block_start, s.block_start + left), s.strm.next_out);
        s.strm.next_out += left;
        s.strm.avail_out -= left;
        s.strm.total_out += left;
        s.block_start += left;
        len -= left;
      }
      if (len) {
        read_buf(s.strm, s.strm.output, s.strm.next_out, len);
        s.strm.next_out += len;
        s.strm.avail_out -= len;
        s.strm.total_out += len;
      }
    } while (last === 0);
    used -= s.strm.avail_in;
    if (used) {
      if (used >= s.w_size) {
        s.matches = 2;
        s.window.set(s.strm.input.subarray(s.strm.next_in - s.w_size, s.strm.next_in), 0);
        s.strstart = s.w_size;
        s.insert = s.strstart;
      } else {
        if (s.window_size - s.strstart <= used) {
          s.strstart -= s.w_size;
          s.window.set(s.window.subarray(s.w_size, s.w_size + s.strstart), 0);
          if (s.matches < 2) {
            s.matches++;
          }
          if (s.insert > s.strstart) {
            s.insert = s.strstart;
          }
        }
        s.window.set(s.strm.input.subarray(s.strm.next_in - used, s.strm.next_in), s.strstart);
        s.strstart += used;
        s.insert += used > s.w_size - s.insert ? s.w_size - s.insert : used;
      }
      s.block_start = s.strstart;
    }
    if (s.high_water < s.strstart) {
      s.high_water = s.strstart;
    }
    if (last) {
      return BS_FINISH_DONE;
    }
    if (flush !== Z_NO_FLUSH$2 && flush !== Z_FINISH$3 && s.strm.avail_in === 0 && s.strstart === s.block_start) {
      return BS_BLOCK_DONE;
    }
    have = s.window_size - s.strstart;
    if (s.strm.avail_in > have && s.block_start >= s.w_size) {
      s.block_start -= s.w_size;
      s.strstart -= s.w_size;
      s.window.set(s.window.subarray(s.w_size, s.w_size + s.strstart), 0);
      if (s.matches < 2) {
        s.matches++;
      }
      have += s.w_size;
      if (s.insert > s.strstart) {
        s.insert = s.strstart;
      }
    }
    if (have > s.strm.avail_in) {
      have = s.strm.avail_in;
    }
    if (have) {
      read_buf(s.strm, s.window, s.strstart, have);
      s.strstart += have;
      s.insert += have > s.w_size - s.insert ? s.w_size - s.insert : have;
    }
    if (s.high_water < s.strstart) {
      s.high_water = s.strstart;
    }
    have = s.bi_valid + 42 >> 3;
    have = s.pending_buf_size - have > 65535 ? 65535 : s.pending_buf_size - have;
    min_block = have > s.w_size ? s.w_size : have;
    left = s.strstart - s.block_start;
    if (left >= min_block || (left || flush === Z_FINISH$3) && flush !== Z_NO_FLUSH$2 && s.strm.avail_in === 0 && left <= have) {
      len = left > have ? have : left;
      last = flush === Z_FINISH$3 && s.strm.avail_in === 0 && len === left ? 1 : 0;
      _tr_stored_block(s, s.block_start, len, last);
      s.block_start += len;
      flush_pending(s.strm);
    }
    return last ? BS_FINISH_STARTED : BS_NEED_MORE;
  };
  var deflate_fast = (s, flush) => {
    let hash_head;
    let bflush;
    for (; ; ) {
      if (s.lookahead < MIN_LOOKAHEAD) {
        fill_window(s);
        if (s.lookahead < MIN_LOOKAHEAD && flush === Z_NO_FLUSH$2) {
          return BS_NEED_MORE;
        }
        if (s.lookahead === 0) {
          break;
        }
      }
      hash_head = 0;
      if (s.lookahead >= MIN_MATCH) {
        hash_head = INSERT_STRING(s, s.strstart);
      }
      if (hash_head !== 0 && s.strstart - hash_head <= s.w_size - MIN_LOOKAHEAD) {
        s.match_length = longest_match(s, hash_head);
      }
      if (s.match_length >= MIN_MATCH) {
        bflush = _tr_tally(s, s.strstart - s.match_start, s.match_length - MIN_MATCH);
        s.lookahead -= s.match_length;
        if (s.match_length <= s.max_lazy_match && s.lookahead >= MIN_MATCH) {
          s.match_length--;
          do {
            s.strstart++;
            hash_head = INSERT_STRING(s, s.strstart);
          } while (--s.match_length !== 0);
          s.strstart++;
        } else {
          s.strstart += s.match_length;
          s.match_length = 0;
          if (s.legacy_hash) {
            s.ins_h = s.window[s.strstart];
            s.ins_h = HASH(s, s.ins_h, s.window[s.strstart + 1]);
          }
        }
      } else {
        bflush = _tr_tally(s, 0, s.window[s.strstart]);
        s.lookahead--;
        s.strstart++;
      }
      if (bflush) {
        flush_block_only(s, false);
        if (s.strm.avail_out === 0) {
          return BS_NEED_MORE;
        }
      }
    }
    s.insert = s.strstart < MIN_MATCH - 1 ? s.strstart : MIN_MATCH - 1;
    if (flush === Z_FINISH$3) {
      flush_block_only(s, true);
      if (s.strm.avail_out === 0) {
        return BS_FINISH_STARTED;
      }
      return BS_FINISH_DONE;
    }
    if (s.sym_next) {
      flush_block_only(s, false);
      if (s.strm.avail_out === 0) {
        return BS_NEED_MORE;
      }
    }
    return BS_BLOCK_DONE;
  };
  var deflate_slow = (s, flush) => {
    let hash_head;
    let bflush;
    let max_insert;
    for (; ; ) {
      if (s.lookahead < MIN_LOOKAHEAD) {
        fill_window(s);
        if (s.lookahead < MIN_LOOKAHEAD && flush === Z_NO_FLUSH$2) {
          return BS_NEED_MORE;
        }
        if (s.lookahead === 0) {
          break;
        }
      }
      hash_head = 0;
      if (s.lookahead >= MIN_MATCH) {
        hash_head = INSERT_STRING(s, s.strstart);
      }
      s.prev_length = s.match_length;
      s.prev_match = s.match_start;
      s.match_length = MIN_MATCH - 1;
      if (hash_head !== 0 && s.prev_length < s.max_lazy_match && s.strstart - hash_head <= s.w_size - MIN_LOOKAHEAD) {
        s.match_length = longest_match(s, hash_head);
        if (s.match_length <= 5 && (s.strategy === Z_FILTERED || s.match_length === MIN_MATCH && s.strstart - s.match_start > 4096)) {
          s.match_length = MIN_MATCH - 1;
        }
      }
      if (s.prev_length >= MIN_MATCH && s.match_length <= s.prev_length) {
        max_insert = s.strstart + s.lookahead - MIN_MATCH;
        bflush = _tr_tally(s, s.strstart - 1 - s.prev_match, s.prev_length - MIN_MATCH);
        s.lookahead -= s.prev_length - 1;
        s.prev_length -= 2;
        do {
          if (++s.strstart <= max_insert) {
            hash_head = INSERT_STRING(s, s.strstart);
          }
        } while (--s.prev_length !== 0);
        s.match_available = 0;
        s.match_length = MIN_MATCH - 1;
        s.strstart++;
        if (bflush) {
          flush_block_only(s, false);
          if (s.strm.avail_out === 0) {
            return BS_NEED_MORE;
          }
        }
      } else if (s.match_available) {
        bflush = _tr_tally(s, 0, s.window[s.strstart - 1]);
        if (bflush) {
          flush_block_only(s, false);
        }
        s.strstart++;
        s.lookahead--;
        if (s.strm.avail_out === 0) {
          return BS_NEED_MORE;
        }
      } else {
        s.match_available = 1;
        s.strstart++;
        s.lookahead--;
      }
    }
    if (s.match_available) {
      bflush = _tr_tally(s, 0, s.window[s.strstart - 1]);
      s.match_available = 0;
    }
    s.insert = s.strstart < MIN_MATCH - 1 ? s.strstart : MIN_MATCH - 1;
    if (flush === Z_FINISH$3) {
      flush_block_only(s, true);
      if (s.strm.avail_out === 0) {
        return BS_FINISH_STARTED;
      }
      return BS_FINISH_DONE;
    }
    if (s.sym_next) {
      flush_block_only(s, false);
      if (s.strm.avail_out === 0) {
        return BS_NEED_MORE;
      }
    }
    return BS_BLOCK_DONE;
  };
  var deflate_rle = (s, flush) => {
    let bflush;
    let prev;
    let scan, strend;
    const _win = s.window;
    for (; ; ) {
      if (s.lookahead <= MAX_MATCH) {
        fill_window(s);
        if (s.lookahead <= MAX_MATCH && flush === Z_NO_FLUSH$2) {
          return BS_NEED_MORE;
        }
        if (s.lookahead === 0) {
          break;
        }
      }
      s.match_length = 0;
      if (s.lookahead >= MIN_MATCH && s.strstart > 0) {
        scan = s.strstart - 1;
        prev = _win[scan];
        if (prev === _win[++scan] && prev === _win[++scan] && prev === _win[++scan]) {
          strend = s.strstart + MAX_MATCH;
          do {
          } while (prev === _win[++scan] && prev === _win[++scan] && prev === _win[++scan] && prev === _win[++scan] && prev === _win[++scan] && prev === _win[++scan] && prev === _win[++scan] && prev === _win[++scan] && scan < strend);
          s.match_length = MAX_MATCH - (strend - scan);
          if (s.match_length > s.lookahead) {
            s.match_length = s.lookahead;
          }
        }
      }
      if (s.match_length >= MIN_MATCH) {
        bflush = _tr_tally(s, 1, s.match_length - MIN_MATCH);
        s.lookahead -= s.match_length;
        s.strstart += s.match_length;
        s.match_length = 0;
      } else {
        bflush = _tr_tally(s, 0, s.window[s.strstart]);
        s.lookahead--;
        s.strstart++;
      }
      if (bflush) {
        flush_block_only(s, false);
        if (s.strm.avail_out === 0) {
          return BS_NEED_MORE;
        }
      }
    }
    s.insert = 0;
    if (flush === Z_FINISH$3) {
      flush_block_only(s, true);
      if (s.strm.avail_out === 0) {
        return BS_FINISH_STARTED;
      }
      return BS_FINISH_DONE;
    }
    if (s.sym_next) {
      flush_block_only(s, false);
      if (s.strm.avail_out === 0) {
        return BS_NEED_MORE;
      }
    }
    return BS_BLOCK_DONE;
  };
  var deflate_huff = (s, flush) => {
    let bflush;
    for (; ; ) {
      if (s.lookahead === 0) {
        fill_window(s);
        if (s.lookahead === 0) {
          if (flush === Z_NO_FLUSH$2) {
            return BS_NEED_MORE;
          }
          break;
        }
      }
      s.match_length = 0;
      bflush = _tr_tally(s, 0, s.window[s.strstart]);
      s.lookahead--;
      s.strstart++;
      if (bflush) {
        flush_block_only(s, false);
        if (s.strm.avail_out === 0) {
          return BS_NEED_MORE;
        }
      }
    }
    s.insert = 0;
    if (flush === Z_FINISH$3) {
      flush_block_only(s, true);
      if (s.strm.avail_out === 0) {
        return BS_FINISH_STARTED;
      }
      return BS_FINISH_DONE;
    }
    if (s.sym_next) {
      flush_block_only(s, false);
      if (s.strm.avail_out === 0) {
        return BS_NEED_MORE;
      }
    }
    return BS_BLOCK_DONE;
  };
  function Config(good_length, max_lazy, nice_length, max_chain, func) {
    this.good_length = good_length;
    this.max_lazy = max_lazy;
    this.nice_length = nice_length;
    this.max_chain = max_chain;
    this.func = func;
  }
  var configuration_table = [
    /*      good lazy nice chain */
    new Config(0, 0, 0, 0, deflate_stored),
    /* 0 store only */
    new Config(4, 4, 8, 4, deflate_fast),
    /* 1 max speed, no lazy matches */
    new Config(4, 5, 16, 8, deflate_fast),
    /* 2 */
    new Config(4, 6, 32, 32, deflate_fast),
    /* 3 */
    new Config(4, 4, 16, 16, deflate_slow),
    /* 4 lazy matches */
    new Config(8, 16, 32, 32, deflate_slow),
    /* 5 */
    new Config(8, 16, 128, 128, deflate_slow),
    /* 6 */
    new Config(8, 32, 128, 256, deflate_slow),
    /* 7 */
    new Config(32, 128, 258, 1024, deflate_slow),
    /* 8 */
    new Config(32, 258, 258, 4096, deflate_slow)
    /* 9 max compression */
  ];
  var lm_init = (s) => {
    s.window_size = 2 * s.w_size;
    zero(s.head);
    s.max_lazy_match = configuration_table[s.level].max_lazy;
    s.good_match = configuration_table[s.level].good_length;
    s.nice_match = configuration_table[s.level].nice_length;
    s.max_chain_length = configuration_table[s.level].max_chain;
    s.strstart = 0;
    s.block_start = 0;
    s.lookahead = 0;
    s.insert = 0;
    s.match_length = s.prev_length = MIN_MATCH - 1;
    s.match_available = 0;
    s.ins_h = 0;
  };
  function DeflateState() {
    this.strm = null;
    this.status = 0;
    this.pending_buf = null;
    this.pending_buf_size = 0;
    this.pending_out = 0;
    this.pending = 0;
    this.wrap = 0;
    this.gzhead = null;
    this.gzindex = 0;
    this.method = Z_DEFLATED$2;
    this.last_flush = -1;
    this.w_size = 0;
    this.w_bits = 0;
    this.w_mask = 0;
    this.window = null;
    this.window_size = 0;
    this.prev = null;
    this.head = null;
    this.ins_h = 0;
    this.legacy_hash = 0;
    this.hash_size = 0;
    this.hash_bits = 0;
    this.hash_mask = 0;
    this.hash_shift = 0;
    this.block_start = 0;
    this.match_length = 0;
    this.prev_match = 0;
    this.match_available = 0;
    this.strstart = 0;
    this.match_start = 0;
    this.lookahead = 0;
    this.prev_length = 0;
    this.max_chain_length = 0;
    this.max_lazy_match = 0;
    this.level = 0;
    this.strategy = 0;
    this.good_match = 0;
    this.nice_match = 0;
    this.dyn_ltree = new Uint16Array(HEAP_SIZE * 2);
    this.dyn_dtree = new Uint16Array((2 * D_CODES + 1) * 2);
    this.bl_tree = new Uint16Array((2 * BL_CODES + 1) * 2);
    zero(this.dyn_ltree);
    zero(this.dyn_dtree);
    zero(this.bl_tree);
    this.l_desc = null;
    this.d_desc = null;
    this.bl_desc = null;
    this.bl_count = new Uint16Array(MAX_BITS + 1);
    this.heap = new Uint16Array(2 * L_CODES + 1);
    zero(this.heap);
    this.heap_len = 0;
    this.heap_max = 0;
    this.depth = new Uint16Array(2 * L_CODES + 1);
    zero(this.depth);
    this.sym_buf = 0;
    this.lit_bufsize = 0;
    this.sym_next = 0;
    this.sym_end = 0;
    this.opt_len = 0;
    this.static_len = 0;
    this.matches = 0;
    this.insert = 0;
    this.bi_buf = 0;
    this.bi_valid = 0;
  }
  var deflateStateCheck = (strm) => {
    if (!strm) {
      return 1;
    }
    const s = strm.state;
    if (!s || s.strm !== strm || s.status !== INIT_STATE && //#ifdef GZIP
    s.status !== GZIP_STATE && //#endif
    s.status !== EXTRA_STATE && s.status !== NAME_STATE && s.status !== COMMENT_STATE && s.status !== HCRC_STATE && s.status !== BUSY_STATE && s.status !== FINISH_STATE) {
      return 1;
    }
    return 0;
  };
  var deflateResetKeep = (strm) => {
    if (deflateStateCheck(strm)) {
      return err(strm, Z_STREAM_ERROR$2);
    }
    strm.total_in = strm.total_out = 0;
    strm.data_type = Z_UNKNOWN;
    const s = strm.state;
    s.pending = 0;
    s.pending_out = 0;
    if (s.wrap < 0) {
      s.wrap = -s.wrap;
    }
    s.status = //#ifdef GZIP
    s.wrap === 2 ? GZIP_STATE : (
      //#endif
      s.wrap ? INIT_STATE : BUSY_STATE
    );
    strm.adler = s.wrap === 2 ? 0 : 1;
    s.last_flush = -2;
    _tr_init(s);
    return Z_OK$3;
  };
  var deflateReset = (strm) => {
    const ret = deflateResetKeep(strm);
    if (ret === Z_OK$3) {
      lm_init(strm.state);
    }
    return ret;
  };
  var deflateSetHeader = (strm, head) => {
    if (deflateStateCheck(strm) || strm.state.wrap !== 2) {
      return Z_STREAM_ERROR$2;
    }
    strm.state.gzhead = head;
    return Z_OK$3;
  };
  var deflateInit2 = (strm, level, method, windowBits, memLevel, strategy, legacyHash) => {
    if (!strm) {
      return Z_STREAM_ERROR$2;
    }
    let wrap = 1;
    if (level === Z_DEFAULT_COMPRESSION$1) {
      level = 6;
    }
    if (windowBits < 0) {
      wrap = 0;
      windowBits = -windowBits;
    } else if (windowBits > 15) {
      wrap = 2;
      windowBits -= 16;
    }
    if (memLevel < 1 || memLevel > MAX_MEM_LEVEL || method !== Z_DEFLATED$2 || windowBits < 8 || windowBits > 15 || level < 0 || level > 9 || strategy < 0 || strategy > Z_FIXED || windowBits === 8 && wrap !== 1) {
      return err(strm, Z_STREAM_ERROR$2);
    }
    if (windowBits === 8) {
      windowBits = 9;
    }
    const s = new DeflateState();
    strm.state = s;
    s.strm = strm;
    s.status = INIT_STATE;
    s.wrap = wrap;
    s.gzhead = null;
    s.w_bits = windowBits;
    s.w_size = 1 << s.w_bits;
    s.w_mask = s.w_size - 1;
    s.legacy_hash = legacyHash ? 1 : 0;
    s.hash_bits = memLevel + 7;
    if (!s.legacy_hash && s.hash_bits < 15) {
      s.hash_bits = 15;
    }
    s.hash_size = 1 << s.hash_bits;
    s.hash_mask = s.hash_size - 1;
    s.hash_shift = ~~((s.hash_bits + MIN_MATCH - 1) / MIN_MATCH);
    s.window = new Uint8Array(s.w_size * 2);
    s.head = new Uint16Array(s.hash_size);
    s.prev = new Uint16Array(s.w_size);
    s.lit_bufsize = 1 << memLevel + 6;
    s.pending_buf_size = s.lit_bufsize * 4;
    s.pending_buf = new Uint8Array(s.pending_buf_size);
    s.sym_buf = s.lit_bufsize;
    s.sym_end = (s.lit_bufsize - 1) * 3;
    s.level = level;
    s.strategy = strategy;
    s.method = method;
    return deflateReset(strm);
  };
  var deflateInit = (strm, level) => {
    return deflateInit2(strm, level, Z_DEFLATED$2, MAX_WBITS$1, DEF_MEM_LEVEL, Z_DEFAULT_STRATEGY$1);
  };
  var deflate$2 = (strm, flush) => {
    if (deflateStateCheck(strm) || flush > Z_BLOCK$1 || flush < 0) {
      return strm ? err(strm, Z_STREAM_ERROR$2) : Z_STREAM_ERROR$2;
    }
    const s = strm.state;
    if (!strm.output || strm.avail_in !== 0 && !strm.input || s.status === FINISH_STATE && flush !== Z_FINISH$3) {
      return err(strm, strm.avail_out === 0 ? Z_BUF_ERROR$2 : Z_STREAM_ERROR$2);
    }
    const old_flush = s.last_flush;
    s.last_flush = flush;
    if (s.pending !== 0) {
      flush_pending(strm);
      if (strm.avail_out === 0) {
        s.last_flush = -1;
        return Z_OK$3;
      }
    } else if (strm.avail_in === 0 && rank(flush) <= rank(old_flush) && flush !== Z_FINISH$3) {
      return err(strm, Z_BUF_ERROR$2);
    }
    if (s.status === FINISH_STATE && strm.avail_in !== 0) {
      return err(strm, Z_BUF_ERROR$2);
    }
    if (s.status === INIT_STATE && s.wrap === 0) {
      s.status = BUSY_STATE;
    }
    if (s.status === INIT_STATE) {
      let header = Z_DEFLATED$2 + (s.w_bits - 8 << 4) << 8;
      let level_flags = -1;
      if (s.strategy >= Z_HUFFMAN_ONLY || s.level < 2) {
        level_flags = 0;
      } else if (s.level < 6) {
        level_flags = 1;
      } else if (s.level === 6) {
        level_flags = 2;
      } else {
        level_flags = 3;
      }
      header |= level_flags << 6;
      if (s.strstart !== 0) {
        header |= PRESET_DICT;
      }
      header += 31 - header % 31;
      putShortMSB(s, header);
      if (s.strstart !== 0) {
        putShortMSB(s, strm.adler >>> 16);
        putShortMSB(s, strm.adler & 65535);
      }
      strm.adler = 1;
      s.status = BUSY_STATE;
      flush_pending(strm);
      if (s.pending !== 0) {
        s.last_flush = -1;
        return Z_OK$3;
      }
    }
    if (s.status === GZIP_STATE) {
      strm.adler = 0;
      put_byte(s, 31);
      put_byte(s, 139);
      put_byte(s, 8);
      if (!s.gzhead) {
        put_byte(s, 0);
        put_byte(s, 0);
        put_byte(s, 0);
        put_byte(s, 0);
        put_byte(s, 0);
        put_byte(s, s.level === 9 ? 2 : s.strategy >= Z_HUFFMAN_ONLY || s.level < 2 ? 4 : 0);
        put_byte(s, OS_CODE);
        s.status = BUSY_STATE;
        flush_pending(strm);
        if (s.pending !== 0) {
          s.last_flush = -1;
          return Z_OK$3;
        }
      } else {
        put_byte(
          s,
          (s.gzhead.text ? 1 : 0) + (s.gzhead.hcrc ? 2 : 0) + (!s.gzhead.extra ? 0 : 4) + (!s.gzhead.name ? 0 : 8) + (!s.gzhead.comment ? 0 : 16)
        );
        put_byte(s, s.gzhead.time & 255);
        put_byte(s, s.gzhead.time >> 8 & 255);
        put_byte(s, s.gzhead.time >> 16 & 255);
        put_byte(s, s.gzhead.time >> 24 & 255);
        put_byte(s, s.level === 9 ? 2 : s.strategy >= Z_HUFFMAN_ONLY || s.level < 2 ? 4 : 0);
        put_byte(s, s.gzhead.os & 255);
        if (s.gzhead.extra && s.gzhead.extra.length) {
          put_byte(s, s.gzhead.extra.length & 255);
          put_byte(s, s.gzhead.extra.length >> 8 & 255);
        }
        if (s.gzhead.hcrc) {
          strm.adler = crc32_1(strm.adler, s.pending_buf, s.pending, 0);
        }
        s.gzindex = 0;
        s.status = EXTRA_STATE;
      }
    }
    if (s.status === EXTRA_STATE) {
      if (s.gzhead.extra) {
        let beg = s.pending;
        let left = (s.gzhead.extra.length & 65535) - s.gzindex;
        while (s.pending + left > s.pending_buf_size) {
          let copy = s.pending_buf_size - s.pending;
          s.pending_buf.set(s.gzhead.extra.subarray(s.gzindex, s.gzindex + copy), s.pending);
          s.pending = s.pending_buf_size;
          if (s.gzhead.hcrc && s.pending > beg) {
            strm.adler = crc32_1(strm.adler, s.pending_buf, s.pending - beg, beg);
          }
          s.gzindex += copy;
          flush_pending(strm);
          if (s.pending !== 0) {
            s.last_flush = -1;
            return Z_OK$3;
          }
          beg = 0;
          left -= copy;
        }
        let gzhead_extra = new Uint8Array(s.gzhead.extra);
        s.pending_buf.set(gzhead_extra.subarray(s.gzindex, s.gzindex + left), s.pending);
        s.pending += left;
        if (s.gzhead.hcrc && s.pending > beg) {
          strm.adler = crc32_1(strm.adler, s.pending_buf, s.pending - beg, beg);
        }
        s.gzindex = 0;
      }
      s.status = NAME_STATE;
    }
    if (s.status === NAME_STATE) {
      if (s.gzhead.name) {
        let beg = s.pending;
        let val;
        do {
          if (s.pending === s.pending_buf_size) {
            if (s.gzhead.hcrc && s.pending > beg) {
              strm.adler = crc32_1(strm.adler, s.pending_buf, s.pending - beg, beg);
            }
            flush_pending(strm);
            if (s.pending !== 0) {
              s.last_flush = -1;
              return Z_OK$3;
            }
            beg = 0;
          }
          if (s.gzindex < s.gzhead.name.length) {
            val = s.gzhead.name.charCodeAt(s.gzindex++) & 255;
          } else {
            val = 0;
          }
          put_byte(s, val);
        } while (val !== 0);
        if (s.gzhead.hcrc && s.pending > beg) {
          strm.adler = crc32_1(strm.adler, s.pending_buf, s.pending - beg, beg);
        }
        s.gzindex = 0;
      }
      s.status = COMMENT_STATE;
    }
    if (s.status === COMMENT_STATE) {
      if (s.gzhead.comment) {
        let beg = s.pending;
        let val;
        do {
          if (s.pending === s.pending_buf_size) {
            if (s.gzhead.hcrc && s.pending > beg) {
              strm.adler = crc32_1(strm.adler, s.pending_buf, s.pending - beg, beg);
            }
            flush_pending(strm);
            if (s.pending !== 0) {
              s.last_flush = -1;
              return Z_OK$3;
            }
            beg = 0;
          }
          if (s.gzindex < s.gzhead.comment.length) {
            val = s.gzhead.comment.charCodeAt(s.gzindex++) & 255;
          } else {
            val = 0;
          }
          put_byte(s, val);
        } while (val !== 0);
        if (s.gzhead.hcrc && s.pending > beg) {
          strm.adler = crc32_1(strm.adler, s.pending_buf, s.pending - beg, beg);
        }
      }
      s.status = HCRC_STATE;
    }
    if (s.status === HCRC_STATE) {
      if (s.gzhead.hcrc) {
        if (s.pending + 2 > s.pending_buf_size) {
          flush_pending(strm);
          if (s.pending !== 0) {
            s.last_flush = -1;
            return Z_OK$3;
          }
        }
        put_byte(s, strm.adler & 255);
        put_byte(s, strm.adler >> 8 & 255);
        strm.adler = 0;
      }
      s.status = BUSY_STATE;
      flush_pending(strm);
      if (s.pending !== 0) {
        s.last_flush = -1;
        return Z_OK$3;
      }
    }
    if (strm.avail_in !== 0 || s.lookahead !== 0 || flush !== Z_NO_FLUSH$2 && s.status !== FINISH_STATE) {
      let bstate = s.level === 0 ? deflate_stored(s, flush) : s.strategy === Z_HUFFMAN_ONLY ? deflate_huff(s, flush) : s.strategy === Z_RLE ? deflate_rle(s, flush) : configuration_table[s.level].func(s, flush);
      if (bstate === BS_FINISH_STARTED || bstate === BS_FINISH_DONE) {
        s.status = FINISH_STATE;
      }
      if (bstate === BS_NEED_MORE || bstate === BS_FINISH_STARTED) {
        if (strm.avail_out === 0) {
          s.last_flush = -1;
        }
        return Z_OK$3;
      }
      if (bstate === BS_BLOCK_DONE) {
        if (flush === Z_PARTIAL_FLUSH) {
          _tr_align(s);
        } else if (flush !== Z_BLOCK$1) {
          _tr_stored_block(s, 0, 0, false);
          if (flush === Z_FULL_FLUSH$1) {
            zero(s.head);
            if (s.lookahead === 0) {
              s.strstart = 0;
              s.block_start = 0;
              s.insert = 0;
            }
          }
        }
        flush_pending(strm);
        if (strm.avail_out === 0) {
          s.last_flush = -1;
          return Z_OK$3;
        }
      }
    }
    if (flush !== Z_FINISH$3) {
      return Z_OK$3;
    }
    if (s.wrap <= 0) {
      return Z_STREAM_END$3;
    }
    if (s.wrap === 2) {
      put_byte(s, strm.adler & 255);
      put_byte(s, strm.adler >> 8 & 255);
      put_byte(s, strm.adler >> 16 & 255);
      put_byte(s, strm.adler >> 24 & 255);
      put_byte(s, strm.total_in & 255);
      put_byte(s, strm.total_in >> 8 & 255);
      put_byte(s, strm.total_in >> 16 & 255);
      put_byte(s, strm.total_in >> 24 & 255);
    } else {
      putShortMSB(s, strm.adler >>> 16);
      putShortMSB(s, strm.adler & 65535);
    }
    flush_pending(strm);
    if (s.wrap > 0) {
      s.wrap = -s.wrap;
    }
    return s.pending !== 0 ? Z_OK$3 : Z_STREAM_END$3;
  };
  var deflateEnd = (strm) => {
    if (deflateStateCheck(strm)) {
      return Z_STREAM_ERROR$2;
    }
    const status = strm.state.status;
    strm.state = null;
    return status === BUSY_STATE ? err(strm, Z_DATA_ERROR$2) : Z_OK$3;
  };
  var deflateSetDictionary = (strm, dictionary) => {
    let dictLength = dictionary.length;
    if (deflateStateCheck(strm)) {
      return Z_STREAM_ERROR$2;
    }
    const s = strm.state;
    const wrap = s.wrap;
    if (wrap === 2 || wrap === 1 && s.status !== INIT_STATE || s.lookahead) {
      return Z_STREAM_ERROR$2;
    }
    if (wrap === 1) {
      strm.adler = adler32_1(strm.adler, dictionary, dictLength, 0);
    }
    s.wrap = 0;
    if (dictLength >= s.w_size) {
      if (wrap === 0) {
        zero(s.head);
        s.strstart = 0;
        s.block_start = 0;
        s.insert = 0;
      }
      let tmpDict = new Uint8Array(s.w_size);
      tmpDict.set(dictionary.subarray(dictLength - s.w_size, dictLength), 0);
      dictionary = tmpDict;
      dictLength = s.w_size;
    }
    const avail = strm.avail_in;
    const next = strm.next_in;
    const input = strm.input;
    strm.avail_in = dictLength;
    strm.next_in = 0;
    strm.input = dictionary;
    fill_window(s);
    while (s.lookahead >= MIN_MATCH) {
      let str = s.strstart;
      let n = s.lookahead - (MIN_MATCH - 1);
      do {
        INSERT_STRING(s, str);
        str++;
      } while (--n);
      s.strstart = str;
      s.lookahead = MIN_MATCH - 1;
      fill_window(s);
    }
    s.strstart += s.lookahead;
    s.block_start = s.strstart;
    s.insert = s.lookahead;
    s.lookahead = 0;
    s.match_length = s.prev_length = MIN_MATCH - 1;
    s.match_available = 0;
    strm.next_in = next;
    strm.input = input;
    strm.avail_in = avail;
    s.wrap = wrap;
    return Z_OK$3;
  };
  var deflateInit_1 = deflateInit;
  var deflateInit2_1 = deflateInit2;
  var deflateReset_1 = deflateReset;
  var deflateResetKeep_1 = deflateResetKeep;
  var deflateSetHeader_1 = deflateSetHeader;
  var deflate_2$1 = deflate$2;
  var deflateEnd_1 = deflateEnd;
  var deflateSetDictionary_1 = deflateSetDictionary;
  var deflateInfo = "pako deflate (from Nodeca project)";
  var deflate_1$2 = {
    deflateInit: deflateInit_1,
    deflateInit2: deflateInit2_1,
    deflateReset: deflateReset_1,
    deflateResetKeep: deflateResetKeep_1,
    deflateSetHeader: deflateSetHeader_1,
    deflate: deflate_2$1,
    deflateEnd: deflateEnd_1,
    deflateSetDictionary: deflateSetDictionary_1,
    deflateInfo
  };
  var _has = (obj, key) => {
    return Object.prototype.hasOwnProperty.call(obj, key);
  };
  var assign = function(obj) {
    const sources = Array.prototype.slice.call(arguments, 1);
    while (sources.length) {
      const source = sources.shift();
      if (!source) {
        continue;
      }
      if (typeof source !== "object") {
        throw new TypeError(source + "must be non-object");
      }
      for (const p in source) {
        if (_has(source, p)) {
          obj[p] = source[p];
        }
      }
    }
    return obj;
  };
  var flattenChunks = (chunks) => {
    let len = 0;
    for (let i = 0, l = chunks.length; i < l; i++) {
      len += chunks[i].length;
    }
    const result = new Uint8Array(len);
    for (let i = 0, pos = 0, l = chunks.length; i < l; i++) {
      let chunk = chunks[i];
      result.set(chunk, pos);
      pos += chunk.length;
    }
    return result;
  };
  var common = {
    assign,
    flattenChunks
  };
  var STR_APPLY_UIA_OK = true;
  try {
    String.fromCharCode.apply(null, new Uint8Array(1));
  } catch (__) {
    STR_APPLY_UIA_OK = false;
  }
  var _utf8len = new Uint8Array(256);
  for (let q = 0; q < 256; q++) {
    _utf8len[q] = q >= 252 ? 6 : q >= 248 ? 5 : q >= 240 ? 4 : q >= 224 ? 3 : q >= 192 ? 2 : 1;
  }
  _utf8len[254] = _utf8len[255] = 1;
  var string2buf = (str) => {
    if (typeof TextEncoder === "function" && TextEncoder.prototype.encode) {
      return new TextEncoder().encode(str);
    }
    let buf, c, c2, m_pos, i, str_len = str.length, buf_len = 0;
    for (m_pos = 0; m_pos < str_len; m_pos++) {
      c = str.charCodeAt(m_pos);
      if ((c & 64512) === 55296 && m_pos + 1 < str_len) {
        c2 = str.charCodeAt(m_pos + 1);
        if ((c2 & 64512) === 56320) {
          c = 65536 + (c - 55296 << 10) + (c2 - 56320);
          m_pos++;
        }
      }
      buf_len += c < 128 ? 1 : c < 2048 ? 2 : c < 65536 ? 3 : 4;
    }
    buf = new Uint8Array(buf_len);
    for (i = 0, m_pos = 0; i < buf_len; m_pos++) {
      c = str.charCodeAt(m_pos);
      if ((c & 64512) === 55296 && m_pos + 1 < str_len) {
        c2 = str.charCodeAt(m_pos + 1);
        if ((c2 & 64512) === 56320) {
          c = 65536 + (c - 55296 << 10) + (c2 - 56320);
          m_pos++;
        }
      }
      if (c < 128) {
        buf[i++] = c;
      } else if (c < 2048) {
        buf[i++] = 192 | c >>> 6;
        buf[i++] = 128 | c & 63;
      } else if (c < 65536) {
        buf[i++] = 224 | c >>> 12;
        buf[i++] = 128 | c >>> 6 & 63;
        buf[i++] = 128 | c & 63;
      } else {
        buf[i++] = 240 | c >>> 18;
        buf[i++] = 128 | c >>> 12 & 63;
        buf[i++] = 128 | c >>> 6 & 63;
        buf[i++] = 128 | c & 63;
      }
    }
    return buf;
  };
  var buf2binstring = (buf, len) => {
    if (len < 65534) {
      if (buf.subarray && STR_APPLY_UIA_OK) {
        return String.fromCharCode.apply(null, buf.length === len ? buf : buf.subarray(0, len));
      }
    }
    let result = "";
    for (let i = 0; i < len; i++) {
      result += String.fromCharCode(buf[i]);
    }
    return result;
  };
  var buf2string = (buf, max) => {
    const len = max || buf.length;
    if (typeof TextDecoder === "function" && TextDecoder.prototype.decode) {
      return new TextDecoder().decode(buf.subarray(0, max));
    }
    let i, out;
    const utf16buf = new Array(len * 2);
    for (out = 0, i = 0; i < len; ) {
      let c = buf[i++];
      if (c < 128) {
        utf16buf[out++] = c;
        continue;
      }
      let c_len = _utf8len[c];
      if (c_len > 4) {
        utf16buf[out++] = 65533;
        i += c_len - 1;
        continue;
      }
      c &= c_len === 2 ? 31 : c_len === 3 ? 15 : 7;
      while (c_len > 1 && i < len) {
        c = c << 6 | buf[i++] & 63;
        c_len--;
      }
      if (c_len > 1) {
        utf16buf[out++] = 65533;
        continue;
      }
      if (c < 65536) {
        utf16buf[out++] = c;
      } else {
        c -= 65536;
        utf16buf[out++] = 55296 | c >> 10 & 1023;
        utf16buf[out++] = 56320 | c & 1023;
      }
    }
    return buf2binstring(utf16buf, out);
  };
  var utf8border = (buf, max) => {
    max = max || buf.length;
    if (max > buf.length) {
      max = buf.length;
    }
    let pos = max - 1;
    while (pos >= 0 && (buf[pos] & 192) === 128) {
      pos--;
    }
    if (pos < 0) {
      return max;
    }
    if (pos === 0) {
      return max;
    }
    return pos + _utf8len[buf[pos]] > max ? pos : max;
  };
  var strings = {
    string2buf,
    buf2string,
    utf8border
  };
  function ZStream() {
    this.input = null;
    this.next_in = 0;
    this.avail_in = 0;
    this.total_in = 0;
    this.output = null;
    this.next_out = 0;
    this.avail_out = 0;
    this.total_out = 0;
    this.msg = "";
    this.state = null;
    this.data_type = 2;
    this.adler = 0;
  }
  var zstream = ZStream;
  var toString$1 = Object.prototype.toString;
  var {
    Z_NO_FLUSH: Z_NO_FLUSH$1,
    Z_SYNC_FLUSH,
    Z_FULL_FLUSH,
    Z_FINISH: Z_FINISH$2,
    Z_OK: Z_OK$2,
    Z_STREAM_END: Z_STREAM_END$2,
    Z_DEFAULT_COMPRESSION,
    Z_DEFAULT_STRATEGY,
    Z_DEFLATED: Z_DEFLATED$1
  } = constants$2;
  var defaultOptions$1 = {
    level: Z_DEFAULT_COMPRESSION,
    method: Z_DEFLATED$1,
    chunkSize: 16384,
    windowBits: 15,
    memLevel: 8,
    strategy: Z_DEFAULT_STRATEGY,
    legacyHash: true
  };
  function Deflate$1(options) {
    this.options = common.assign({}, defaultOptions$1, options || {});
    let opt = this.options;
    if (opt.raw && opt.windowBits > 0) {
      opt.windowBits = -opt.windowBits;
    } else if (opt.gzip && opt.windowBits > 0 && opt.windowBits < 16) {
      opt.windowBits += 16;
    }
    this.err = 0;
    this.msg = "";
    this.ended = false;
    this.chunks = [];
    this.strm = new zstream();
    this.strm.avail_out = 0;
    let status = deflate_1$2.deflateInit2(
      this.strm,
      opt.level,
      opt.method,
      opt.windowBits,
      opt.memLevel,
      opt.strategy,
      opt.legacyHash
    );
    if (status !== Z_OK$2) {
      throw new Error(messages[status]);
    }
    if (opt.header) {
      deflate_1$2.deflateSetHeader(this.strm, opt.header);
    }
    if (opt.dictionary) {
      let dict;
      if (typeof opt.dictionary === "string") {
        dict = strings.string2buf(opt.dictionary);
      } else if (toString$1.call(opt.dictionary) === "[object ArrayBuffer]") {
        dict = new Uint8Array(opt.dictionary);
      } else {
        dict = opt.dictionary;
      }
      status = deflate_1$2.deflateSetDictionary(this.strm, dict);
      if (status !== Z_OK$2) {
        throw new Error(messages[status]);
      }
      this._dict_set = true;
    }
  }
  Deflate$1.prototype.push = function(data, flush_mode) {
    const strm = this.strm;
    const chunkSize = this.options.chunkSize;
    let status, _flush_mode;
    if (this.ended) {
      return false;
    }
    if (flush_mode === ~~flush_mode) _flush_mode = flush_mode;
    else _flush_mode = flush_mode === true ? Z_FINISH$2 : Z_NO_FLUSH$1;
    if (typeof data === "string") {
      strm.input = strings.string2buf(data);
    } else if (toString$1.call(data) === "[object ArrayBuffer]") {
      strm.input = new Uint8Array(data);
    } else {
      strm.input = data;
    }
    strm.next_in = 0;
    strm.avail_in = strm.input.length;
    for (; ; ) {
      if (strm.avail_out === 0) {
        strm.output = new Uint8Array(chunkSize);
        strm.next_out = 0;
        strm.avail_out = chunkSize;
      }
      if ((_flush_mode === Z_SYNC_FLUSH || _flush_mode === Z_FULL_FLUSH) && strm.avail_out <= 6) {
        this.onData(strm.output.subarray(0, strm.next_out));
        strm.avail_out = 0;
        continue;
      }
      status = deflate_1$2.deflate(strm, _flush_mode);
      if (status === Z_STREAM_END$2) {
        if (strm.next_out > 0) {
          this.onData(strm.output.subarray(0, strm.next_out));
        }
        status = deflate_1$2.deflateEnd(this.strm);
        this.onEnd(status);
        this.ended = true;
        return status === Z_OK$2;
      }
      if (strm.avail_out === 0) {
        this.onData(strm.output);
        continue;
      }
      if (_flush_mode > 0 && strm.next_out > 0) {
        this.onData(strm.output.subarray(0, strm.next_out));
        strm.avail_out = 0;
        continue;
      }
      if (strm.avail_in === 0) break;
    }
    return true;
  };
  Deflate$1.prototype.onData = function(chunk) {
    this.chunks.push(chunk);
  };
  Deflate$1.prototype.onEnd = function(status) {
    if (status === Z_OK$2) {
      this.result = common.flattenChunks(this.chunks);
    }
    this.chunks = [];
    this.err = status;
    this.msg = this.strm.msg;
  };
  function deflate$1(input, options) {
    const deflator = new Deflate$1(options);
    deflator.push(input, true);
    if (deflator.err) {
      throw deflator.msg || messages[deflator.err];
    }
    return deflator.result;
  }
  function deflateRaw$1(input, options) {
    options = options || {};
    options.raw = true;
    return deflate$1(input, options);
  }
  function gzip$1(input, options) {
    options = options || {};
    options.gzip = true;
    return deflate$1(input, options);
  }
  var Deflate_1$1 = Deflate$1;
  var deflate_2 = deflate$1;
  var deflateRaw_1$1 = deflateRaw$1;
  var gzip_1$1 = gzip$1;
  var constants$1 = constants$2;
  var deflate_1$1 = {
    Deflate: Deflate_1$1,
    deflate: deflate_2,
    deflateRaw: deflateRaw_1$1,
    gzip: gzip_1$1,
    constants: constants$1
  };
  var BAD$1 = 16209;
  var TYPE$1 = 16191;
  var inffast = function inflate_fast(strm, start) {
    let _in;
    let last;
    let _out;
    let beg;
    let end;
    let dmax;
    let wsize;
    let whave;
    let wnext;
    let s_window;
    let hold;
    let bits;
    let lcode;
    let dcode;
    let lmask;
    let dmask;
    let here;
    let op;
    let len;
    let dist;
    let from;
    let from_source;
    let input, output;
    const state = strm.state;
    _in = strm.next_in;
    input = strm.input;
    last = _in + (strm.avail_in - 5);
    _out = strm.next_out;
    output = strm.output;
    beg = _out - (start - strm.avail_out);
    end = _out + (strm.avail_out - 257);
    dmax = state.dmax;
    wsize = state.wsize;
    whave = state.whave;
    wnext = state.wnext;
    s_window = state.window;
    hold = state.hold;
    bits = state.bits;
    lcode = state.lencode;
    dcode = state.distcode;
    lmask = (1 << state.lenbits) - 1;
    dmask = (1 << state.distbits) - 1;
    top:
      do {
        if (bits < 15) {
          hold += input[_in++] << bits;
          bits += 8;
          hold += input[_in++] << bits;
          bits += 8;
        }
        here = lcode[hold & lmask];
        dolen:
          for (; ; ) {
            op = here >>> 24;
            hold >>>= op;
            bits -= op;
            op = here >>> 16 & 255;
            if (op === 0) {
              output[_out++] = here & 65535;
            } else if (op & 16) {
              len = here & 65535;
              op &= 15;
              if (op) {
                if (bits < op) {
                  hold += input[_in++] << bits;
                  bits += 8;
                }
                len += hold & (1 << op) - 1;
                hold >>>= op;
                bits -= op;
              }
              if (bits < 15) {
                hold += input[_in++] << bits;
                bits += 8;
                hold += input[_in++] << bits;
                bits += 8;
              }
              here = dcode[hold & dmask];
              dodist:
                for (; ; ) {
                  op = here >>> 24;
                  hold >>>= op;
                  bits -= op;
                  op = here >>> 16 & 255;
                  if (op & 16) {
                    dist = here & 65535;
                    op &= 15;
                    if (bits < op) {
                      hold += input[_in++] << bits;
                      bits += 8;
                      if (bits < op) {
                        hold += input[_in++] << bits;
                        bits += 8;
                      }
                    }
                    dist += hold & (1 << op) - 1;
                    if (dist > dmax) {
                      strm.msg = "invalid distance too far back";
                      state.mode = BAD$1;
                      break top;
                    }
                    hold >>>= op;
                    bits -= op;
                    op = _out - beg;
                    if (dist > op) {
                      op = dist - op;
                      if (op > whave) {
                        if (state.sane) {
                          strm.msg = "invalid distance too far back";
                          state.mode = BAD$1;
                          break top;
                        }
                      }
                      from = 0;
                      from_source = s_window;
                      if (wnext === 0) {
                        from += wsize - op;
                        if (op < len) {
                          len -= op;
                          do {
                            output[_out++] = s_window[from++];
                          } while (--op);
                          from = _out - dist;
                          from_source = output;
                        }
                      } else if (wnext < op) {
                        from += wsize + wnext - op;
                        op -= wnext;
                        if (op < len) {
                          len -= op;
                          do {
                            output[_out++] = s_window[from++];
                          } while (--op);
                          from = 0;
                          if (wnext < len) {
                            op = wnext;
                            len -= op;
                            do {
                              output[_out++] = s_window[from++];
                            } while (--op);
                            from = _out - dist;
                            from_source = output;
                          }
                        }
                      } else {
                        from += wnext - op;
                        if (op < len) {
                          len -= op;
                          do {
                            output[_out++] = s_window[from++];
                          } while (--op);
                          from = _out - dist;
                          from_source = output;
                        }
                      }
                      while (len > 2) {
                        output[_out++] = from_source[from++];
                        output[_out++] = from_source[from++];
                        output[_out++] = from_source[from++];
                        len -= 3;
                      }
                      if (len) {
                        output[_out++] = from_source[from++];
                        if (len > 1) {
                          output[_out++] = from_source[from++];
                        }
                      }
                    } else {
                      from = _out - dist;
                      do {
                        output[_out++] = output[from++];
                        output[_out++] = output[from++];
                        output[_out++] = output[from++];
                        len -= 3;
                      } while (len > 2);
                      if (len) {
                        output[_out++] = output[from++];
                        if (len > 1) {
                          output[_out++] = output[from++];
                        }
                      }
                    }
                  } else if ((op & 64) === 0) {
                    here = dcode[(here & 65535) + (hold & (1 << op) - 1)];
                    continue dodist;
                  } else {
                    strm.msg = "invalid distance code";
                    state.mode = BAD$1;
                    break top;
                  }
                  break;
                }
            } else if ((op & 64) === 0) {
              here = lcode[(here & 65535) + (hold & (1 << op) - 1)];
              continue dolen;
            } else if (op & 32) {
              state.mode = TYPE$1;
              break top;
            } else {
              strm.msg = "invalid literal/length code";
              state.mode = BAD$1;
              break top;
            }
            break;
          }
      } while (_in < last && _out < end);
    len = bits >> 3;
    _in -= len;
    bits -= len << 3;
    hold &= (1 << bits) - 1;
    strm.next_in = _in;
    strm.next_out = _out;
    strm.avail_in = _in < last ? 5 + (last - _in) : 5 - (_in - last);
    strm.avail_out = _out < end ? 257 + (end - _out) : 257 - (_out - end);
    state.hold = hold;
    state.bits = bits;
    return;
  };
  var MAXBITS = 15;
  var ENOUGH_LENS$1 = 852;
  var ENOUGH_DISTS$1 = 592;
  var CODES$1 = 0;
  var LENS$1 = 1;
  var DISTS$1 = 2;
  var lbase = new Uint16Array([
    /* Length codes 257..285 base */
    3,
    4,
    5,
    6,
    7,
    8,
    9,
    10,
    11,
    13,
    15,
    17,
    19,
    23,
    27,
    31,
    35,
    43,
    51,
    59,
    67,
    83,
    99,
    115,
    131,
    163,
    195,
    227,
    258,
    0,
    0
  ]);
  var lext = new Uint8Array([
    /* Length codes 257..285 extra */
    16,
    16,
    16,
    16,
    16,
    16,
    16,
    16,
    17,
    17,
    17,
    17,
    18,
    18,
    18,
    18,
    19,
    19,
    19,
    19,
    20,
    20,
    20,
    20,
    21,
    21,
    21,
    21,
    16,
    199,
    75
  ]);
  var dbase = new Uint16Array([
    /* Distance codes 0..29 base */
    1,
    2,
    3,
    4,
    5,
    7,
    9,
    13,
    17,
    25,
    33,
    49,
    65,
    97,
    129,
    193,
    257,
    385,
    513,
    769,
    1025,
    1537,
    2049,
    3073,
    4097,
    6145,
    8193,
    12289,
    16385,
    24577,
    0,
    0
  ]);
  var dext = new Uint8Array([
    /* Distance codes 0..29 extra */
    16,
    16,
    16,
    16,
    17,
    17,
    18,
    18,
    19,
    19,
    20,
    20,
    21,
    21,
    22,
    22,
    23,
    23,
    24,
    24,
    25,
    25,
    26,
    26,
    27,
    27,
    28,
    28,
    29,
    29,
    64,
    64
  ]);
  var inflate_table = (type, lens, lens_index, codes, table, table_index, work, opts) => {
    const bits = opts.bits;
    let len = 0;
    let sym = 0;
    let min = 0, max = 0;
    let root = 0;
    let curr = 0;
    let drop = 0;
    let left = 0;
    let used = 0;
    let huff = 0;
    let incr;
    let fill;
    let low;
    let mask;
    let next;
    let base = null;
    let match;
    const count = new Uint16Array(MAXBITS + 1);
    const offs = new Uint16Array(MAXBITS + 1);
    let extra = null;
    let here_bits, here_op, here_val;
    for (len = 0; len <= MAXBITS; len++) {
      count[len] = 0;
    }
    for (sym = 0; sym < codes; sym++) {
      count[lens[lens_index + sym]]++;
    }
    root = bits;
    for (max = MAXBITS; max >= 1; max--) {
      if (count[max] !== 0) {
        break;
      }
    }
    if (root > max) {
      root = max;
    }
    if (max === 0) {
      table[table_index++] = 1 << 24 | 64 << 16 | 0;
      table[table_index++] = 1 << 24 | 64 << 16 | 0;
      opts.bits = 1;
      return 0;
    }
    for (min = 1; min < max; min++) {
      if (count[min] !== 0) {
        break;
      }
    }
    if (root < min) {
      root = min;
    }
    left = 1;
    for (len = 1; len <= MAXBITS; len++) {
      left <<= 1;
      left -= count[len];
      if (left < 0) {
        return -1;
      }
    }
    if (left > 0 && (type === CODES$1 || max !== 1)) {
      return -1;
    }
    offs[1] = 0;
    for (len = 1; len < MAXBITS; len++) {
      offs[len + 1] = offs[len] + count[len];
    }
    for (sym = 0; sym < codes; sym++) {
      if (lens[lens_index + sym] !== 0) {
        work[offs[lens[lens_index + sym]]++] = sym;
      }
    }
    if (type === CODES$1) {
      base = extra = work;
      match = 20;
    } else if (type === LENS$1) {
      base = lbase;
      extra = lext;
      match = 257;
    } else {
      base = dbase;
      extra = dext;
      match = 0;
    }
    huff = 0;
    sym = 0;
    len = min;
    next = table_index;
    curr = root;
    drop = 0;
    low = -1;
    used = 1 << root;
    mask = used - 1;
    if (type === LENS$1 && used > ENOUGH_LENS$1 || type === DISTS$1 && used > ENOUGH_DISTS$1) {
      return 1;
    }
    for (; ; ) {
      here_bits = len - drop;
      if (work[sym] + 1 < match) {
        here_op = 0;
        here_val = work[sym];
      } else if (work[sym] >= match) {
        here_op = extra[work[sym] - match];
        here_val = base[work[sym] - match];
      } else {
        here_op = 32 + 64;
        here_val = 0;
      }
      incr = 1 << len - drop;
      fill = 1 << curr;
      min = fill;
      do {
        fill -= incr;
        table[next + (huff >> drop) + fill] = here_bits << 24 | here_op << 16 | here_val | 0;
      } while (fill !== 0);
      incr = 1 << len - 1;
      while (huff & incr) {
        incr >>= 1;
      }
      if (incr !== 0) {
        huff &= incr - 1;
        huff += incr;
      } else {
        huff = 0;
      }
      sym++;
      if (--count[len] === 0) {
        if (len === max) {
          break;
        }
        len = lens[lens_index + work[sym]];
      }
      if (len > root && (huff & mask) !== low) {
        if (drop === 0) {
          drop = root;
        }
        next += min;
        curr = len - drop;
        left = 1 << curr;
        while (curr + drop < max) {
          left -= count[curr + drop];
          if (left <= 0) {
            break;
          }
          curr++;
          left <<= 1;
        }
        used += 1 << curr;
        if (type === LENS$1 && used > ENOUGH_LENS$1 || type === DISTS$1 && used > ENOUGH_DISTS$1) {
          return 1;
        }
        low = huff & mask;
        table[low] = root << 24 | curr << 16 | next - table_index | 0;
      }
    }
    if (huff !== 0) {
      table[next + huff] = len - drop << 24 | 64 << 16 | 0;
    }
    opts.bits = root;
    return 0;
  };
  var inftrees = inflate_table;
  var CODES = 0;
  var LENS = 1;
  var DISTS = 2;
  var {
    Z_FINISH: Z_FINISH$1,
    Z_BLOCK,
    Z_TREES,
    Z_OK: Z_OK$1,
    Z_STREAM_END: Z_STREAM_END$1,
    Z_NEED_DICT: Z_NEED_DICT$1,
    Z_STREAM_ERROR: Z_STREAM_ERROR$1,
    Z_DATA_ERROR: Z_DATA_ERROR$1,
    Z_MEM_ERROR: Z_MEM_ERROR$1,
    Z_BUF_ERROR: Z_BUF_ERROR$1,
    Z_DEFLATED
  } = constants$2;
  var HEAD = 16180;
  var FLAGS = 16181;
  var TIME = 16182;
  var OS = 16183;
  var EXLEN = 16184;
  var EXTRA = 16185;
  var NAME = 16186;
  var COMMENT = 16187;
  var HCRC = 16188;
  var DICTID = 16189;
  var DICT = 16190;
  var TYPE = 16191;
  var TYPEDO = 16192;
  var STORED = 16193;
  var COPY_ = 16194;
  var COPY = 16195;
  var TABLE = 16196;
  var LENLENS = 16197;
  var CODELENS = 16198;
  var LEN_ = 16199;
  var LEN = 16200;
  var LENEXT = 16201;
  var DIST = 16202;
  var DISTEXT = 16203;
  var MATCH = 16204;
  var LIT = 16205;
  var CHECK = 16206;
  var LENGTH = 16207;
  var DONE = 16208;
  var BAD = 16209;
  var MEM = 16210;
  var SYNC = 16211;
  var ENOUGH_LENS = 852;
  var ENOUGH_DISTS = 592;
  var MAX_WBITS = 15;
  var DEF_WBITS = MAX_WBITS;
  var zswap32 = (q) => {
    return (q >>> 24 & 255) + (q >>> 8 & 65280) + ((q & 65280) << 8) + ((q & 255) << 24);
  };
  function InflateState() {
    this.strm = null;
    this.mode = 0;
    this.last = false;
    this.wrap = 0;
    this.havedict = false;
    this.flags = 0;
    this.dmax = 0;
    this.check = 0;
    this.total = 0;
    this.head = null;
    this.wbits = 0;
    this.wsize = 0;
    this.whave = 0;
    this.wnext = 0;
    this.window = null;
    this.hold = 0;
    this.bits = 0;
    this.length = 0;
    this.offset = 0;
    this.extra = 0;
    this.lencode = null;
    this.distcode = null;
    this.lenbits = 0;
    this.distbits = 0;
    this.ncode = 0;
    this.nlen = 0;
    this.ndist = 0;
    this.have = 0;
    this.next = null;
    this.lens = new Uint16Array(320);
    this.work = new Uint16Array(288);
    this.lendyn = null;
    this.distdyn = null;
    this.sane = 0;
    this.back = 0;
    this.was = 0;
  }
  var inflateStateCheck = (strm) => {
    if (!strm) {
      return 1;
    }
    const state = strm.state;
    if (!state || state.strm !== strm || state.mode < HEAD || state.mode > SYNC) {
      return 1;
    }
    return 0;
  };
  var inflateResetKeep = (strm) => {
    if (inflateStateCheck(strm)) {
      return Z_STREAM_ERROR$1;
    }
    const state = strm.state;
    strm.total_in = strm.total_out = state.total = 0;
    strm.msg = "";
    if (state.wrap) {
      strm.adler = state.wrap & 1;
    }
    state.mode = HEAD;
    state.last = 0;
    state.havedict = 0;
    state.flags = -1;
    state.dmax = 32768;
    state.head = null;
    state.hold = 0;
    state.bits = 0;
    state.lencode = state.lendyn = new Int32Array(ENOUGH_LENS);
    state.distcode = state.distdyn = new Int32Array(ENOUGH_DISTS);
    state.sane = 1;
    state.back = -1;
    return Z_OK$1;
  };
  var inflateReset = (strm) => {
    if (inflateStateCheck(strm)) {
      return Z_STREAM_ERROR$1;
    }
    const state = strm.state;
    state.wsize = 0;
    state.whave = 0;
    state.wnext = 0;
    return inflateResetKeep(strm);
  };
  var inflateReset2 = (strm, windowBits) => {
    let wrap;
    if (inflateStateCheck(strm)) {
      return Z_STREAM_ERROR$1;
    }
    const state = strm.state;
    if (windowBits < 0) {
      wrap = 0;
      windowBits = -windowBits;
    } else {
      wrap = (windowBits >> 4) + 5;
      if (windowBits < 48) {
        windowBits &= 15;
      }
    }
    if (windowBits && (windowBits < 8 || windowBits > 15)) {
      return Z_STREAM_ERROR$1;
    }
    if (state.window !== null && state.wbits !== windowBits) {
      state.window = null;
    }
    state.wrap = wrap;
    state.wbits = windowBits;
    return inflateReset(strm);
  };
  var inflateInit2 = (strm, windowBits) => {
    if (!strm) {
      return Z_STREAM_ERROR$1;
    }
    const state = new InflateState();
    strm.state = state;
    state.strm = strm;
    state.window = null;
    state.mode = HEAD;
    const ret = inflateReset2(strm, windowBits);
    if (ret !== Z_OK$1) {
      strm.state = null;
    }
    return ret;
  };
  var inflateInit = (strm) => {
    return inflateInit2(strm, DEF_WBITS);
  };
  var virgin = true;
  var lenfix;
  var distfix;
  var fixedtables = (state) => {
    if (virgin) {
      lenfix = new Int32Array(512);
      distfix = new Int32Array(32);
      let sym = 0;
      while (sym < 144) {
        state.lens[sym++] = 8;
      }
      while (sym < 256) {
        state.lens[sym++] = 9;
      }
      while (sym < 280) {
        state.lens[sym++] = 7;
      }
      while (sym < 288) {
        state.lens[sym++] = 8;
      }
      inftrees(LENS, state.lens, 0, 288, lenfix, 0, state.work, { bits: 9 });
      sym = 0;
      while (sym < 32) {
        state.lens[sym++] = 5;
      }
      inftrees(DISTS, state.lens, 0, 32, distfix, 0, state.work, { bits: 5 });
      virgin = false;
    }
    state.lencode = lenfix;
    state.lenbits = 9;
    state.distcode = distfix;
    state.distbits = 5;
  };
  var updatewindow = (strm, src, end, copy) => {
    let dist;
    const state = strm.state;
    if (state.window === null) {
      state.window = new Uint8Array(1 << state.wbits);
    }
    if (state.wsize === 0) {
      state.wsize = 1 << state.wbits;
      state.wnext = 0;
      state.whave = 0;
    }
    if (copy >= state.wsize) {
      state.window.set(src.subarray(end - state.wsize, end), 0);
      state.wnext = 0;
      state.whave = state.wsize;
    } else {
      dist = state.wsize - state.wnext;
      if (dist > copy) {
        dist = copy;
      }
      state.window.set(src.subarray(end - copy, end - copy + dist), state.wnext);
      copy -= dist;
      if (copy) {
        state.window.set(src.subarray(end - copy, end), 0);
        state.wnext = copy;
        state.whave = state.wsize;
      } else {
        state.wnext += dist;
        if (state.wnext === state.wsize) {
          state.wnext = 0;
        }
        if (state.whave < state.wsize) {
          state.whave += dist;
        }
      }
    }
    return 0;
  };
  var inflate$2 = (strm, flush) => {
    let state;
    let input, output;
    let next;
    let put;
    let have, left;
    let hold;
    let bits;
    let _in, _out;
    let copy;
    let from;
    let from_source;
    let here = 0;
    let here_bits, here_op, here_val;
    let last_bits, last_op, last_val;
    let len;
    let ret;
    const hbuf = new Uint8Array(4);
    let opts;
    let n;
    const order = (
      /* permutation of code lengths */
      new Uint8Array([16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15])
    );
    if (inflateStateCheck(strm) || !strm.output || !strm.input && strm.avail_in !== 0) {
      return Z_STREAM_ERROR$1;
    }
    state = strm.state;
    if (state.mode === TYPE) {
      state.mode = TYPEDO;
    }
    put = strm.next_out;
    output = strm.output;
    left = strm.avail_out;
    next = strm.next_in;
    input = strm.input;
    have = strm.avail_in;
    hold = state.hold;
    bits = state.bits;
    _in = have;
    _out = left;
    ret = Z_OK$1;
    inf_leave:
      for (; ; ) {
        switch (state.mode) {
          case HEAD:
            if (state.wrap === 0) {
              state.mode = TYPEDO;
              break;
            }
            while (bits < 16) {
              if (have === 0) {
                break inf_leave;
              }
              have--;
              hold += input[next++] << bits;
              bits += 8;
            }
            if (state.wrap & 2 && hold === 35615) {
              if (state.wbits === 0) {
                state.wbits = 15;
              }
              state.check = 0;
              hbuf[0] = hold & 255;
              hbuf[1] = hold >>> 8 & 255;
              state.check = crc32_1(state.check, hbuf, 2, 0);
              hold = 0;
              bits = 0;
              state.mode = FLAGS;
              break;
            }
            if (state.head) {
              state.head.done = false;
            }
            if (!(state.wrap & 1) || /* check if zlib header allowed */
            (((hold & 255) << 8) + (hold >> 8)) % 31) {
              strm.msg = "incorrect header check";
              state.mode = BAD;
              break;
            }
            if ((hold & 15) !== Z_DEFLATED) {
              strm.msg = "unknown compression method";
              state.mode = BAD;
              break;
            }
            hold >>>= 4;
            bits -= 4;
            len = (hold & 15) + 8;
            if (state.wbits === 0) {
              state.wbits = len;
            }
            if (len > 15 || len > state.wbits) {
              strm.msg = "invalid window size";
              state.mode = BAD;
              break;
            }
            state.dmax = 1 << state.wbits;
            state.flags = 0;
            strm.adler = state.check = 1;
            state.mode = hold & 512 ? DICTID : TYPE;
            hold = 0;
            bits = 0;
            break;
          case FLAGS:
            while (bits < 16) {
              if (have === 0) {
                break inf_leave;
              }
              have--;
              hold += input[next++] << bits;
              bits += 8;
            }
            state.flags = hold;
            if ((state.flags & 255) !== Z_DEFLATED) {
              strm.msg = "unknown compression method";
              state.mode = BAD;
              break;
            }
            if (state.flags & 57344) {
              strm.msg = "unknown header flags set";
              state.mode = BAD;
              break;
            }
            if (state.head) {
              state.head.text = hold >> 8 & 1;
            }
            if (state.flags & 512 && state.wrap & 4) {
              hbuf[0] = hold & 255;
              hbuf[1] = hold >>> 8 & 255;
              state.check = crc32_1(state.check, hbuf, 2, 0);
            }
            hold = 0;
            bits = 0;
            state.mode = TIME;
          /* falls through */
          case TIME:
            while (bits < 32) {
              if (have === 0) {
                break inf_leave;
              }
              have--;
              hold += input[next++] << bits;
              bits += 8;
            }
            if (state.head) {
              state.head.time = hold;
            }
            if (state.flags & 512 && state.wrap & 4) {
              hbuf[0] = hold & 255;
              hbuf[1] = hold >>> 8 & 255;
              hbuf[2] = hold >>> 16 & 255;
              hbuf[3] = hold >>> 24 & 255;
              state.check = crc32_1(state.check, hbuf, 4, 0);
            }
            hold = 0;
            bits = 0;
            state.mode = OS;
          /* falls through */
          case OS:
            while (bits < 16) {
              if (have === 0) {
                break inf_leave;
              }
              have--;
              hold += input[next++] << bits;
              bits += 8;
            }
            if (state.head) {
              state.head.xflags = hold & 255;
              state.head.os = hold >> 8;
            }
            if (state.flags & 512 && state.wrap & 4) {
              hbuf[0] = hold & 255;
              hbuf[1] = hold >>> 8 & 255;
              state.check = crc32_1(state.check, hbuf, 2, 0);
            }
            hold = 0;
            bits = 0;
            state.mode = EXLEN;
          /* falls through */
          case EXLEN:
            if (state.flags & 1024) {
              while (bits < 16) {
                if (have === 0) {
                  break inf_leave;
                }
                have--;
                hold += input[next++] << bits;
                bits += 8;
              }
              state.length = hold;
              if (state.head) {
                state.head.extra_len = hold;
              }
              if (state.flags & 512 && state.wrap & 4) {
                hbuf[0] = hold & 255;
                hbuf[1] = hold >>> 8 & 255;
                state.check = crc32_1(state.check, hbuf, 2, 0);
              }
              hold = 0;
              bits = 0;
            } else if (state.head) {
              state.head.extra = null;
            }
            state.mode = EXTRA;
          /* falls through */
          case EXTRA:
            if (state.flags & 1024) {
              copy = state.length;
              if (copy > have) {
                copy = have;
              }
              if (copy) {
                if (state.head) {
                  len = state.head.extra_len - state.length;
                  if (!state.head.extra) {
                    state.head.extra = new Uint8Array(state.head.extra_len);
                  }
                  state.head.extra.set(
                    input.subarray(
                      next,
                      // extra field is limited to 65536 bytes
                      // - no need for additional size check
                      next + copy
                    ),
                    /*len + copy > state.head.extra_max - len ? state.head.extra_max : copy,*/
                    len
                  );
                }
                if (state.flags & 512 && state.wrap & 4) {
                  state.check = crc32_1(state.check, input, copy, next);
                }
                have -= copy;
                next += copy;
                state.length -= copy;
              }
              if (state.length) {
                break inf_leave;
              }
            }
            state.length = 0;
            state.mode = NAME;
          /* falls through */
          case NAME:
            if (state.flags & 2048) {
              if (have === 0) {
                break inf_leave;
              }
              copy = 0;
              do {
                len = input[next + copy++];
                if (state.head && len && state.length < 65536) {
                  state.head.name += String.fromCharCode(len);
                }
              } while (len && copy < have);
              if (state.flags & 512 && state.wrap & 4) {
                state.check = crc32_1(state.check, input, copy, next);
              }
              have -= copy;
              next += copy;
              if (len) {
                break inf_leave;
              }
            } else if (state.head) {
              state.head.name = null;
            }
            state.length = 0;
            state.mode = COMMENT;
          /* falls through */
          case COMMENT:
            if (state.flags & 4096) {
              if (have === 0) {
                break inf_leave;
              }
              copy = 0;
              do {
                len = input[next + copy++];
                if (state.head && len && state.length < 65536) {
                  state.head.comment += String.fromCharCode(len);
                }
              } while (len && copy < have);
              if (state.flags & 512 && state.wrap & 4) {
                state.check = crc32_1(state.check, input, copy, next);
              }
              have -= copy;
              next += copy;
              if (len) {
                break inf_leave;
              }
            } else if (state.head) {
              state.head.comment = null;
            }
            state.mode = HCRC;
          /* falls through */
          case HCRC:
            if (state.flags & 512) {
              while (bits < 16) {
                if (have === 0) {
                  break inf_leave;
                }
                have--;
                hold += input[next++] << bits;
                bits += 8;
              }
              if (state.wrap & 4 && hold !== (state.check & 65535)) {
                strm.msg = "header crc mismatch";
                state.mode = BAD;
                break;
              }
              hold = 0;
              bits = 0;
            }
            if (state.head) {
              state.head.hcrc = state.flags >> 9 & 1;
              state.head.done = true;
            }
            strm.adler = state.check = 0;
            state.mode = TYPE;
            break;
          case DICTID:
            while (bits < 32) {
              if (have === 0) {
                break inf_leave;
              }
              have--;
              hold += input[next++] << bits;
              bits += 8;
            }
            strm.adler = state.check = zswap32(hold);
            hold = 0;
            bits = 0;
            state.mode = DICT;
          /* falls through */
          case DICT:
            if (state.havedict === 0) {
              strm.next_out = put;
              strm.avail_out = left;
              strm.next_in = next;
              strm.avail_in = have;
              state.hold = hold;
              state.bits = bits;
              return Z_NEED_DICT$1;
            }
            strm.adler = state.check = 1;
            state.mode = TYPE;
          /* falls through */
          case TYPE:
            if (flush === Z_BLOCK || flush === Z_TREES) {
              break inf_leave;
            }
          /* falls through */
          case TYPEDO:
            if (state.last) {
              hold >>>= bits & 7;
              bits -= bits & 7;
              state.mode = CHECK;
              break;
            }
            while (bits < 3) {
              if (have === 0) {
                break inf_leave;
              }
              have--;
              hold += input[next++] << bits;
              bits += 8;
            }
            state.last = hold & 1;
            hold >>>= 1;
            bits -= 1;
            switch (hold & 3) {
              case 0:
                state.mode = STORED;
                break;
              case 1:
                fixedtables(state);
                state.mode = LEN_;
                if (flush === Z_TREES) {
                  hold >>>= 2;
                  bits -= 2;
                  break inf_leave;
                }
                break;
              case 2:
                state.mode = TABLE;
                break;
              case 3:
                strm.msg = "invalid block type";
                state.mode = BAD;
            }
            hold >>>= 2;
            bits -= 2;
            break;
          case STORED:
            hold >>>= bits & 7;
            bits -= bits & 7;
            while (bits < 32) {
              if (have === 0) {
                break inf_leave;
              }
              have--;
              hold += input[next++] << bits;
              bits += 8;
            }
            if ((hold & 65535) !== (hold >>> 16 ^ 65535)) {
              strm.msg = "invalid stored block lengths";
              state.mode = BAD;
              break;
            }
            state.length = hold & 65535;
            hold = 0;
            bits = 0;
            state.mode = COPY_;
            if (flush === Z_TREES) {
              break inf_leave;
            }
          /* falls through */
          case COPY_:
            state.mode = COPY;
          /* falls through */
          case COPY:
            copy = state.length;
            if (copy) {
              if (copy > have) {
                copy = have;
              }
              if (copy > left) {
                copy = left;
              }
              if (copy === 0) {
                break inf_leave;
              }
              output.set(input.subarray(next, next + copy), put);
              have -= copy;
              next += copy;
              left -= copy;
              put += copy;
              state.length -= copy;
              break;
            }
            state.mode = TYPE;
            break;
          case TABLE:
            while (bits < 14) {
              if (have === 0) {
                break inf_leave;
              }
              have--;
              hold += input[next++] << bits;
              bits += 8;
            }
            state.nlen = (hold & 31) + 257;
            hold >>>= 5;
            bits -= 5;
            state.ndist = (hold & 31) + 1;
            hold >>>= 5;
            bits -= 5;
            state.ncode = (hold & 15) + 4;
            hold >>>= 4;
            bits -= 4;
            if (state.nlen > 286 || state.ndist > 30) {
              strm.msg = "too many length or distance symbols";
              state.mode = BAD;
              break;
            }
            state.have = 0;
            state.mode = LENLENS;
          /* falls through */
          case LENLENS:
            while (state.have < state.ncode) {
              while (bits < 3) {
                if (have === 0) {
                  break inf_leave;
                }
                have--;
                hold += input[next++] << bits;
                bits += 8;
              }
              state.lens[order[state.have++]] = hold & 7;
              hold >>>= 3;
              bits -= 3;
            }
            while (state.have < 19) {
              state.lens[order[state.have++]] = 0;
            }
            state.lencode = state.lendyn;
            state.lenbits = 7;
            opts = { bits: state.lenbits };
            ret = inftrees(CODES, state.lens, 0, 19, state.lencode, 0, state.work, opts);
            state.lenbits = opts.bits;
            if (ret) {
              strm.msg = "invalid code lengths set";
              state.mode = BAD;
              break;
            }
            state.have = 0;
            state.mode = CODELENS;
          /* falls through */
          case CODELENS:
            while (state.have < state.nlen + state.ndist) {
              for (; ; ) {
                here = state.lencode[hold & (1 << state.lenbits) - 1];
                here_bits = here >>> 24;
                here_op = here >>> 16 & 255;
                here_val = here & 65535;
                if (here_bits <= bits) {
                  break;
                }
                if (have === 0) {
                  break inf_leave;
                }
                have--;
                hold += input[next++] << bits;
                bits += 8;
              }
              if (here_val < 16) {
                hold >>>= here_bits;
                bits -= here_bits;
                state.lens[state.have++] = here_val;
              } else {
                if (here_val === 16) {
                  n = here_bits + 2;
                  while (bits < n) {
                    if (have === 0) {
                      break inf_leave;
                    }
                    have--;
                    hold += input[next++] << bits;
                    bits += 8;
                  }
                  hold >>>= here_bits;
                  bits -= here_bits;
                  if (state.have === 0) {
                    strm.msg = "invalid bit length repeat";
                    state.mode = BAD;
                    break;
                  }
                  len = state.lens[state.have - 1];
                  copy = 3 + (hold & 3);
                  hold >>>= 2;
                  bits -= 2;
                } else if (here_val === 17) {
                  n = here_bits + 3;
                  while (bits < n) {
                    if (have === 0) {
                      break inf_leave;
                    }
                    have--;
                    hold += input[next++] << bits;
                    bits += 8;
                  }
                  hold >>>= here_bits;
                  bits -= here_bits;
                  len = 0;
                  copy = 3 + (hold & 7);
                  hold >>>= 3;
                  bits -= 3;
                } else {
                  n = here_bits + 7;
                  while (bits < n) {
                    if (have === 0) {
                      break inf_leave;
                    }
                    have--;
                    hold += input[next++] << bits;
                    bits += 8;
                  }
                  hold >>>= here_bits;
                  bits -= here_bits;
                  len = 0;
                  copy = 11 + (hold & 127);
                  hold >>>= 7;
                  bits -= 7;
                }
                if (state.have + copy > state.nlen + state.ndist) {
                  strm.msg = "invalid bit length repeat";
                  state.mode = BAD;
                  break;
                }
                while (copy--) {
                  state.lens[state.have++] = len;
                }
              }
            }
            if (state.mode === BAD) {
              break;
            }
            if (state.lens[256] === 0) {
              strm.msg = "invalid code -- missing end-of-block";
              state.mode = BAD;
              break;
            }
            state.lenbits = 9;
            opts = { bits: state.lenbits };
            ret = inftrees(LENS, state.lens, 0, state.nlen, state.lencode, 0, state.work, opts);
            state.lenbits = opts.bits;
            if (ret) {
              strm.msg = "invalid literal/lengths set";
              state.mode = BAD;
              break;
            }
            state.distbits = 6;
            state.distcode = state.distdyn;
            opts = { bits: state.distbits };
            ret = inftrees(DISTS, state.lens, state.nlen, state.ndist, state.distcode, 0, state.work, opts);
            state.distbits = opts.bits;
            if (ret) {
              strm.msg = "invalid distances set";
              state.mode = BAD;
              break;
            }
            state.mode = LEN_;
            if (flush === Z_TREES) {
              break inf_leave;
            }
          /* falls through */
          case LEN_:
            state.mode = LEN;
          /* falls through */
          case LEN:
            if (have >= 6 && left >= 258) {
              strm.next_out = put;
              strm.avail_out = left;
              strm.next_in = next;
              strm.avail_in = have;
              state.hold = hold;
              state.bits = bits;
              inffast(strm, _out);
              put = strm.next_out;
              output = strm.output;
              left = strm.avail_out;
              next = strm.next_in;
              input = strm.input;
              have = strm.avail_in;
              hold = state.hold;
              bits = state.bits;
              if (state.mode === TYPE) {
                state.back = -1;
              }
              break;
            }
            state.back = 0;
            for (; ; ) {
              here = state.lencode[hold & (1 << state.lenbits) - 1];
              here_bits = here >>> 24;
              here_op = here >>> 16 & 255;
              here_val = here & 65535;
              if (here_bits <= bits) {
                break;
              }
              if (have === 0) {
                break inf_leave;
              }
              have--;
              hold += input[next++] << bits;
              bits += 8;
            }
            if (here_op && (here_op & 240) === 0) {
              last_bits = here_bits;
              last_op = here_op;
              last_val = here_val;
              for (; ; ) {
                here = state.lencode[last_val + ((hold & (1 << last_bits + last_op) - 1) >> last_bits)];
                here_bits = here >>> 24;
                here_op = here >>> 16 & 255;
                here_val = here & 65535;
                if (last_bits + here_bits <= bits) {
                  break;
                }
                if (have === 0) {
                  break inf_leave;
                }
                have--;
                hold += input[next++] << bits;
                bits += 8;
              }
              hold >>>= last_bits;
              bits -= last_bits;
              state.back += last_bits;
            }
            hold >>>= here_bits;
            bits -= here_bits;
            state.back += here_bits;
            state.length = here_val;
            if (here_op === 0) {
              state.mode = LIT;
              break;
            }
            if (here_op & 32) {
              state.back = -1;
              state.mode = TYPE;
              break;
            }
            if (here_op & 64) {
              strm.msg = "invalid literal/length code";
              state.mode = BAD;
              break;
            }
            state.extra = here_op & 15;
            state.mode = LENEXT;
          /* falls through */
          case LENEXT:
            if (state.extra) {
              n = state.extra;
              while (bits < n) {
                if (have === 0) {
                  break inf_leave;
                }
                have--;
                hold += input[next++] << bits;
                bits += 8;
              }
              state.length += hold & (1 << state.extra) - 1;
              hold >>>= state.extra;
              bits -= state.extra;
              state.back += state.extra;
            }
            state.was = state.length;
            state.mode = DIST;
          /* falls through */
          case DIST:
            for (; ; ) {
              here = state.distcode[hold & (1 << state.distbits) - 1];
              here_bits = here >>> 24;
              here_op = here >>> 16 & 255;
              here_val = here & 65535;
              if (here_bits <= bits) {
                break;
              }
              if (have === 0) {
                break inf_leave;
              }
              have--;
              hold += input[next++] << bits;
              bits += 8;
            }
            if ((here_op & 240) === 0) {
              last_bits = here_bits;
              last_op = here_op;
              last_val = here_val;
              for (; ; ) {
                here = state.distcode[last_val + ((hold & (1 << last_bits + last_op) - 1) >> last_bits)];
                here_bits = here >>> 24;
                here_op = here >>> 16 & 255;
                here_val = here & 65535;
                if (last_bits + here_bits <= bits) {
                  break;
                }
                if (have === 0) {
                  break inf_leave;
                }
                have--;
                hold += input[next++] << bits;
                bits += 8;
              }
              hold >>>= last_bits;
              bits -= last_bits;
              state.back += last_bits;
            }
            hold >>>= here_bits;
            bits -= here_bits;
            state.back += here_bits;
            if (here_op & 64) {
              strm.msg = "invalid distance code";
              state.mode = BAD;
              break;
            }
            state.offset = here_val;
            state.extra = here_op & 15;
            state.mode = DISTEXT;
          /* falls through */
          case DISTEXT:
            if (state.extra) {
              n = state.extra;
              while (bits < n) {
                if (have === 0) {
                  break inf_leave;
                }
                have--;
                hold += input[next++] << bits;
                bits += 8;
              }
              state.offset += hold & (1 << state.extra) - 1;
              hold >>>= state.extra;
              bits -= state.extra;
              state.back += state.extra;
            }
            if (state.offset > state.dmax) {
              strm.msg = "invalid distance too far back";
              state.mode = BAD;
              break;
            }
            state.mode = MATCH;
          /* falls through */
          case MATCH:
            if (left === 0) {
              break inf_leave;
            }
            copy = _out - left;
            if (state.offset > copy) {
              copy = state.offset - copy;
              if (copy > state.whave) {
                if (state.sane) {
                  strm.msg = "invalid distance too far back";
                  state.mode = BAD;
                  break;
                }
              }
              if (copy > state.wnext) {
                copy -= state.wnext;
                from = state.wsize - copy;
              } else {
                from = state.wnext - copy;
              }
              if (copy > state.length) {
                copy = state.length;
              }
              from_source = state.window;
            } else {
              from_source = output;
              from = put - state.offset;
              copy = state.length;
            }
            if (copy > left) {
              copy = left;
            }
            left -= copy;
            state.length -= copy;
            do {
              output[put++] = from_source[from++];
            } while (--copy);
            if (state.length === 0) {
              state.mode = LEN;
            }
            break;
          case LIT:
            if (left === 0) {
              break inf_leave;
            }
            output[put++] = state.length;
            left--;
            state.mode = LEN;
            break;
          case CHECK:
            if (state.wrap) {
              while (bits < 32) {
                if (have === 0) {
                  break inf_leave;
                }
                have--;
                hold |= input[next++] << bits;
                bits += 8;
              }
              _out -= left;
              strm.total_out += _out;
              state.total += _out;
              if (state.wrap & 4 && _out) {
                strm.adler = state.check = /*UPDATE_CHECK(state.check, put - _out, _out);*/
                state.flags ? crc32_1(state.check, output, _out, put - _out) : adler32_1(state.check, output, _out, put - _out);
              }
              _out = left;
              if (state.wrap & 4 && (state.flags ? hold : zswap32(hold)) !== state.check) {
                strm.msg = "incorrect data check";
                state.mode = BAD;
                break;
              }
              hold = 0;
              bits = 0;
            }
            state.mode = LENGTH;
          /* falls through */
          case LENGTH:
            if (state.wrap && state.flags) {
              while (bits < 32) {
                if (have === 0) {
                  break inf_leave;
                }
                have--;
                hold += input[next++] << bits;
                bits += 8;
              }
              if (state.wrap & 4 && hold !== (state.total & 4294967295)) {
                strm.msg = "incorrect length check";
                state.mode = BAD;
                break;
              }
              hold = 0;
              bits = 0;
            }
            state.mode = DONE;
          /* falls through */
          case DONE:
            ret = Z_STREAM_END$1;
            break inf_leave;
          case BAD:
            ret = Z_DATA_ERROR$1;
            break inf_leave;
          case MEM:
            return Z_MEM_ERROR$1;
          case SYNC:
          /* falls through */
          default:
            return Z_STREAM_ERROR$1;
        }
      }
    strm.next_out = put;
    strm.avail_out = left;
    strm.next_in = next;
    strm.avail_in = have;
    state.hold = hold;
    state.bits = bits;
    if (state.wsize || _out !== strm.avail_out && state.mode < BAD && (state.mode < CHECK || flush !== Z_FINISH$1)) {
      if (updatewindow(strm, strm.output, strm.next_out, _out - strm.avail_out)) ;
    }
    _in -= strm.avail_in;
    _out -= strm.avail_out;
    strm.total_in += _in;
    strm.total_out += _out;
    state.total += _out;
    if (state.wrap & 4 && _out) {
      strm.adler = state.check = /*UPDATE_CHECK(state.check, strm.next_out - _out, _out);*/
      state.flags ? crc32_1(state.check, output, _out, strm.next_out - _out) : adler32_1(state.check, output, _out, strm.next_out - _out);
    }
    strm.data_type = state.bits + (state.last ? 64 : 0) + (state.mode === TYPE ? 128 : 0) + (state.mode === LEN_ || state.mode === COPY_ ? 256 : 0);
    if ((_in === 0 && _out === 0 || flush === Z_FINISH$1) && ret === Z_OK$1) {
      ret = Z_BUF_ERROR$1;
    }
    return ret;
  };
  var inflateEnd = (strm) => {
    if (inflateStateCheck(strm)) {
      return Z_STREAM_ERROR$1;
    }
    let state = strm.state;
    if (state.window) {
      state.window = null;
    }
    strm.state = null;
    return Z_OK$1;
  };
  var inflateGetHeader = (strm, head) => {
    if (inflateStateCheck(strm)) {
      return Z_STREAM_ERROR$1;
    }
    const state = strm.state;
    if ((state.wrap & 2) === 0) {
      return Z_STREAM_ERROR$1;
    }
    state.head = head;
    head.done = false;
    return Z_OK$1;
  };
  var inflateSetDictionary = (strm, dictionary) => {
    const dictLength = dictionary.length;
    let state;
    let dictid;
    let ret;
    if (inflateStateCheck(strm)) {
      return Z_STREAM_ERROR$1;
    }
    state = strm.state;
    if (state.wrap !== 0 && state.mode !== DICT) {
      return Z_STREAM_ERROR$1;
    }
    if (state.mode === DICT) {
      dictid = 1;
      dictid = adler32_1(dictid, dictionary, dictLength, 0);
      if (dictid !== state.check) {
        return Z_DATA_ERROR$1;
      }
    }
    ret = updatewindow(strm, dictionary, dictLength, dictLength);
    if (ret) {
      state.mode = MEM;
      return Z_MEM_ERROR$1;
    }
    state.havedict = 1;
    return Z_OK$1;
  };
  var inflateReset_1 = inflateReset;
  var inflateReset2_1 = inflateReset2;
  var inflateResetKeep_1 = inflateResetKeep;
  var inflateInit_1 = inflateInit;
  var inflateInit2_1 = inflateInit2;
  var inflate_2$1 = inflate$2;
  var inflateEnd_1 = inflateEnd;
  var inflateGetHeader_1 = inflateGetHeader;
  var inflateSetDictionary_1 = inflateSetDictionary;
  var inflateInfo = "pako inflate (from Nodeca project)";
  var inflate_1$2 = {
    inflateReset: inflateReset_1,
    inflateReset2: inflateReset2_1,
    inflateResetKeep: inflateResetKeep_1,
    inflateInit: inflateInit_1,
    inflateInit2: inflateInit2_1,
    inflate: inflate_2$1,
    inflateEnd: inflateEnd_1,
    inflateGetHeader: inflateGetHeader_1,
    inflateSetDictionary: inflateSetDictionary_1,
    inflateInfo
  };
  function GZheader() {
    this.text = 0;
    this.time = 0;
    this.xflags = 0;
    this.os = 0;
    this.extra = null;
    this.extra_len = 0;
    this.name = "";
    this.comment = "";
    this.hcrc = 0;
    this.done = false;
  }
  var gzheader = GZheader;
  var toString = Object.prototype.toString;
  var {
    Z_NO_FLUSH,
    Z_FINISH,
    Z_OK,
    Z_STREAM_END,
    Z_NEED_DICT,
    Z_STREAM_ERROR,
    Z_DATA_ERROR,
    Z_MEM_ERROR,
    Z_BUF_ERROR
  } = constants$2;
  var defaultOptions = {
    chunkSize: 1024 * 64,
    windowBits: 15,
    to: ""
  };
  function Inflate$1(options) {
    this.options = common.assign({}, defaultOptions, options || {});
    const opt = this.options;
    if (opt.raw && opt.windowBits >= 0 && opt.windowBits < 16) {
      opt.windowBits = -opt.windowBits;
      if (opt.windowBits === 0) {
        opt.windowBits = -15;
      }
    }
    if (opt.windowBits >= 0 && opt.windowBits < 16 && !(options && options.windowBits)) {
      opt.windowBits += 32;
    }
    if (opt.windowBits > 15 && opt.windowBits < 48) {
      if ((opt.windowBits & 15) === 0) {
        opt.windowBits |= 15;
      }
    }
    this.err = 0;
    this.msg = "";
    this.ended = false;
    this.chunks = [];
    this.strm = new zstream();
    this.strm.avail_out = 0;
    let status = inflate_1$2.inflateInit2(
      this.strm,
      opt.windowBits
    );
    if (status !== Z_OK) {
      throw new Error(messages[status]);
    }
    this.header = new gzheader();
    inflate_1$2.inflateGetHeader(this.strm, this.header);
    if (opt.dictionary) {
      if (typeof opt.dictionary === "string") {
        opt.dictionary = strings.string2buf(opt.dictionary);
      } else if (toString.call(opt.dictionary) === "[object ArrayBuffer]") {
        opt.dictionary = new Uint8Array(opt.dictionary);
      }
      if (opt.raw) {
        status = inflate_1$2.inflateSetDictionary(this.strm, opt.dictionary);
        if (status !== Z_OK) {
          throw new Error(messages[status]);
        }
      }
    }
  }
  Inflate$1.prototype.push = function(data, flush_mode) {
    const strm = this.strm;
    const chunkSize = this.options.chunkSize;
    const dictionary = this.options.dictionary;
    let status, _flush_mode, last_avail_out;
    if (this.ended) return false;
    if (flush_mode === ~~flush_mode) _flush_mode = flush_mode;
    else _flush_mode = flush_mode === true ? Z_FINISH : Z_NO_FLUSH;
    if (toString.call(data) === "[object ArrayBuffer]") {
      strm.input = new Uint8Array(data);
    } else {
      strm.input = data;
    }
    strm.next_in = 0;
    strm.avail_in = strm.input.length;
    for (; ; ) {
      if (strm.avail_out === 0) {
        strm.output = new Uint8Array(chunkSize);
        strm.next_out = 0;
        strm.avail_out = chunkSize;
      }
      status = inflate_1$2.inflate(strm, _flush_mode);
      if (status === Z_NEED_DICT && dictionary) {
        status = inflate_1$2.inflateSetDictionary(strm, dictionary);
        if (status === Z_OK) {
          status = inflate_1$2.inflate(strm, _flush_mode);
        } else if (status === Z_DATA_ERROR) {
          status = Z_NEED_DICT;
        }
      }
      while (strm.avail_in > 0 && status === Z_STREAM_END && strm.state.wrap & 2 && strm.state.flags !== 0 && strm.input[strm.next_in] !== 0) {
        inflate_1$2.inflateReset(strm);
        status = inflate_1$2.inflate(strm, _flush_mode);
      }
      switch (status) {
        case Z_STREAM_ERROR:
        case Z_DATA_ERROR:
        case Z_NEED_DICT:
        case Z_MEM_ERROR:
          this.onEnd(status);
          this.ended = true;
          return false;
      }
      last_avail_out = strm.avail_out;
      if (strm.next_out) {
        if (strm.avail_out === 0 || status === Z_STREAM_END || _flush_mode > 0) {
          if (this.options.to === "string") {
            let next_out_utf8 = strings.utf8border(strm.output, strm.next_out);
            let tail = strm.next_out - next_out_utf8;
            let utf8str = strings.buf2string(strm.output, next_out_utf8);
            strm.next_out = tail;
            strm.avail_out = chunkSize - tail;
            if (tail) strm.output.set(strm.output.subarray(next_out_utf8, next_out_utf8 + tail), 0);
            this.onData(utf8str);
          } else {
            this.onData(strm.output.length === strm.next_out ? strm.output : strm.output.subarray(0, strm.next_out));
            strm.avail_out = 0;
            strm.next_out = 0;
          }
        }
      }
      if ((status === Z_OK || status === Z_BUF_ERROR) && last_avail_out === 0) continue;
      if (status === Z_STREAM_END) {
        status = inflate_1$2.inflateEnd(this.strm);
        this.onEnd(status);
        this.ended = true;
        return true;
      }
      if (strm.avail_in === 0) {
        if (_flush_mode === Z_FINISH) {
          status = inflate_1$2.inflateEnd(this.strm);
          this.onEnd(status === Z_OK ? Z_BUF_ERROR : status);
          this.ended = true;
          return false;
        }
        break;
      }
    }
    return true;
  };
  Inflate$1.prototype.onData = function(chunk) {
    this.chunks.push(chunk);
  };
  Inflate$1.prototype.onEnd = function(status) {
    if (status === Z_OK) {
      if (this.options.to === "string") {
        this.result = this.chunks.join("");
      } else {
        this.result = common.flattenChunks(this.chunks);
      }
    }
    this.chunks = [];
    this.err = status;
    this.msg = this.strm.msg;
  };
  function inflate$1(input, options) {
    const inflator = new Inflate$1(options);
    inflator.push(input, true);
    if (inflator.err) throw inflator.msg || messages[inflator.err];
    return inflator.result;
  }
  function inflateRaw$1(input, options) {
    options = options || {};
    options.raw = true;
    return inflate$1(input, options);
  }
  var Inflate_1$1 = Inflate$1;
  var inflate_2 = inflate$1;
  var inflateRaw_1$1 = inflateRaw$1;
  var ungzip$1 = inflate$1;
  var constants = constants$2;
  var inflate_1$1 = {
    Inflate: Inflate_1$1,
    inflate: inflate_2,
    inflateRaw: inflateRaw_1$1,
    ungzip: ungzip$1,
    constants
  };
  var { Deflate, deflate, deflateRaw, gzip } = deflate_1$1;
  var { Inflate, inflate: inflate2, inflateRaw, ungzip } = inflate_1$1;
  var Deflate_1 = Deflate;
  var deflate_1 = deflate;
  var deflateRaw_1 = deflateRaw;
  var gzip_1 = gzip;
  var Inflate_1 = Inflate;
  var inflate_1 = inflate2;
  var inflateRaw_1 = inflateRaw;
  var ungzip_1 = ungzip;
  var constants_1 = constants$2;
  var pako = {
    Deflate: Deflate_1,
    deflate: deflate_1,
    deflateRaw: deflateRaw_1,
    gzip: gzip_1,
    Inflate: Inflate_1,
    inflate: inflate_1,
    inflateRaw: inflateRaw_1,
    ungzip: ungzip_1,
    constants: constants_1
  };

  // src/musicSdk/kg/util.js
  var import_buffer6 = __toESM(require_buffer(), 1);
  var enc_key = import_buffer6.Buffer.from([64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105], "binary");
  var decodeLyric2 = (str) => new Promise((resolve, reject) => {
    if (!str.length) return;
    const buf_str = import_buffer6.Buffer.from(str, "base64").slice(4);
    for (let i = 0, len = buf_str.length; i < len; i++) {
      buf_str[i] = buf_str[i] ^ enc_key[i % 16];
    }
    const result = pako.inflate(buf_str, { to: "string" });
    resolve(result);
  });
  var signatureParams = (params, platform = "android", body = "") => {
    let keyparam = "OIlwieks28dk2k092lksi2UIkp";
    if (platform === "web") keyparam = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";
    let param_list = params.split("&");
    param_list.sort();
    let sign_params = `${keyparam}${param_list.join("")}${body}${keyparam}`;
    return toMD5(sign_params);
  };
  var createHttpFetch = async (url, options, retryNum = 0) => {
    if (retryNum > 2) throw new Error("try max num");
    let result;
    options.cache = "default";
    try {
      result = await httpFetch(url, options);
    } catch (err2) {
      console.log(err2);
      return createHttpFetch(url, options, ++retryNum);
    }
    if (result.statusCode !== 200 || (result.body.error_code ?? result.body.errcode ?? result.body.err_code) != 0) return createHttpFetch(url, options, ++retryNum);
    if (result.body.data) return result.body.data;
    if (Array.isArray(result.body.info)) return result.body;
    return result.body.info;
  };

  // src/musicSdk/kg/songList.js
  var handleSignature = (id, page, limit) => new Promise((resolve, reject) => {
    (0, import_infSign_min.default)({ appid: 1058, type: 0, module: "playlist", page, pagesize: limit, specialid: id }, null, {
      useH5: true,
      isCDN: true,
      callback(i) {
        resolve(i.signature);
      }
    });
  });
  var songList_default2 = {
    listDetailLimit: 1e4,
    currentTagInfo: {
      id: void 0,
      info: void 0
    },
    sortList: [
      {
        name: "推荐",
        tid: "recommend",
        id: "5"
      },
      {
        name: "最热",
        tid: "hot",
        id: "6"
      },
      {
        name: "最新",
        tid: "new",
        id: "7"
      },
      {
        name: "热藏",
        tid: "hot_collect",
        id: "3"
      },
      {
        name: "飙升",
        tid: "rise",
        id: "8"
      }
    ],
    cache: /* @__PURE__ */ new Map(),
    regExps: {
      // https://www.kugou.com/yy/special/single/1067062.html
      listDetailLink: /^.+\/(\d+)\.html(?:\?.*|&.*$|#.*$|$)/
    },
    filterSpecialDetail(rawList) {
      const ids = /* @__PURE__ */ new Set();
      const qualityNames = { 2: "128k", 4: "320k", 5: "flac", 6: "flac24bit" };
      return rawList.flatMap((item) => {
        if (!item) return [];
        const songmid = String(item.audio_id ?? "");
        const uniqueId = songmid || item.hash;
        if (!uniqueId || ids.has(uniqueId)) return [];
        ids.add(uniqueId);
        const types = [];
        const _types = {};
        const qualities = Array.isArray(item.relate_goods) && item.relate_goods.length ? item.relate_goods : [{ level: 2, bitrate: 128, hash: item.hash, size: item.size }];
        qualities.forEach((quality) => {
          const type = qualityNames[quality.level] || (quality.bitrate === 128 ? "128k" : null);
          if (!type || !quality.hash || _types[type]) return;
          const size = sizeFormate(Number(quality.size) || 0);
          types.push({ type, size, hash: quality.hash });
          _types[type] = { size, hash: quality.hash };
        });
        const fullName = String(item.name || item.remark || "");
        const separator = fullName.indexOf(" - ");
        const fallbackSinger = separator > 0 ? fullName.slice(0, separator) : "";
        const name = separator > 0 ? fullName.slice(separator + 3) : fullName;
        const cover = item.cover || item.trans_param?.union_cover;
        return [{
          singer: decodeName(item.singerinfo?.map((singer) => singer.name).filter(Boolean).join("、") || fallbackSinger),
          name: decodeName(name),
          albumName: decodeName(String(item.albuminfo?.name || "")),
          albumId: item.albuminfo?.id ?? item.album_id ?? null,
          songmid,
          source: "kg",
          interval: formatPlayTime((Number(item.timelen) || 0) / 1e3),
          img: cover ? cover.replace("{size}", "500").replace(/^http:/, "https:") : null,
          lrc: null,
          hash: item.hash,
          otherSource: null,
          types,
          _types,
          typeUrl: {}
        }];
      });
    },
    async getListDetailBySpecialId(id, page, retryNum = 0) {
      const limit = 30;
      const params = [
        `specialid=${id}`,
        "need_sort=1",
        "module=CloudMusic",
        "clientver=11239",
        `pagesize=${limit}`,
        `specalidpgc=${id}`,
        "userid=0",
        `page=${page}`,
        "type=0",
        "area_code=1",
        "appid=1005"
      ].join("&");
      try {
        const { body, statusCode } = await httpFetch(
          `https://gatewayretry.kugou.com/v2/get_other_list_file?${params}&signature=${signatureParams(params)}`,
          {
            timeout: 1e4,
            headers: {
              "User-Agent": "Android9-AndroidPhone-11239-18-0-playlist-wifi",
              "x-router": "pubsongscdn.kugou.com"
            }
          }
        );
        if (statusCode !== 200 || body?.status !== 1 || body?.error_code !== 0 || !Array.isArray(body.data?.info)) {
          throw new Error("invalid Kugou playlist response");
        }
        const list = this.filterSpecialDetail(body.data.info);
        return {
          list,
          page: Number(body.data.page) || page,
          limit: Number(body.data.pagesize) || limit,
          total: Number(body.data.count) || list.length,
          source: "kg"
        };
      } catch (error) {
        if (retryNum < 1) return this.getListDetailBySpecialId(id, page, retryNum + 1);
        throw new Error("酷狗歌单暂时无法加载，请稍后重试");
      }
    },
    getInfoUrl(tagId) {
      return tagId ? `http://www2.kugou.kugou.com/yueku/v9/special/getSpecial?is_smarty=1&cdn=cdn&t=5&c=${tagId}` : "http://www2.kugou.kugou.com/yueku/v9/special/getSpecial?is_smarty=1&";
    },
    getSongListUrl(sortId, tagId, page) {
      if (tagId == null) tagId = "";
      return `http://www2.kugou.kugou.com/yueku/v9/special/getSpecial?is_ajax=1&cdn=cdn&t=${sortId}&c=${tagId}&p=${page}`;
    },
    filterInfoHotTag(rawData) {
      const result = [];
      if (rawData.status !== 1) return result;
      for (const key of Object.keys(rawData.data)) {
        let tag = rawData.data[key];
        result.push({
          id: tag.special_id,
          name: tag.special_name,
          source: "kg"
        });
      }
      return result;
    },
    filterTagInfo(rawData) {
      const result = [];
      for (const name of Object.keys(rawData)) {
        result.push({
          name,
          list: rawData[name].data.map((tag) => ({
            parent_id: tag.parent_id,
            parent_name: tag.pname,
            id: tag.id,
            name: tag.name,
            source: "kg"
          }))
        });
      }
      return result;
    },
    getSongList(sortId, tagId, page, tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const request = httpFetch(
        this.getSongListUrl(sortId, tagId, page)
      );
      return request.then(({ body }) => {
        if (!body || body.status !== 1) return this.getSongList(sortId, tagId, page, ++tryNum);
        return this.filterList(body.special_db);
      });
    },
    getSongListRecommend(tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const requestRecommend = httpFetch(
        "http://everydayrec.service.kugou.com/guess_special_recommend",
        {
          method: "post",
          headers: {
            "User-Agent": "KuGou2012-8275-web_browser_event_handler"
          },
          body: {
            appid: 1001,
            clienttime: 1566798337219,
            clientver: 8275,
            key: "f1f93580115bb106680d2375f8032d96",
            mid: "21511157a05844bd085308bc76ef3343",
            platform: "pc",
            userid: "262643156",
            return_min: 6,
            return_max: 15
          }
        }
      );
      return requestRecommend.then(({ body }) => {
        if (body.status !== 1) return this.getSongListRecommend(++tryNum);
        return this.filterList(body.data.special_list);
      });
    },
    filterList(rawData) {
      return rawData.map((item) => ({
        play_count: item.total_play_count || formatPlayCount(item.play_count),
        id: "id_" + item.specialid,
        author: item.nickname,
        name: item.specialname,
        time: dateFormat(item.publish_time || item.publishtime, "Y-M-D"),
        img: item.img || item.imgurl,
        total: item.songcount,
        grade: item.grade,
        desc: item.intro,
        source: "kg"
      }));
    },
    async createHttp(url, options, retryNum = 0) {
      if (retryNum > 2) throw new Error("try max num");
      let result;
      options.cache = "default";
      try {
        result = await httpFetch(url, options);
      } catch (err2) {
        console.log(err2);
        return this.createHttp(url, options, ++retryNum);
      }
      if (result.statusCode !== 200 || (result.body.error_code !== void 0 ? result.body.error_code : result.body.errcode !== void 0 ? result.body.errcode : result.body.err_code) !== 0) return this.createHttp(url, options, ++retryNum);
      if (result.body.data) return result.body.data;
      if (Array.isArray(result.body.info)) return result.body;
      return result.body.info;
    },
    createTask(hashs) {
      let data = {
        area_code: "1",
        show_privilege: 1,
        show_album_info: "1",
        is_publish: "",
        appid: 1005,
        clientver: 11451,
        mid: "1",
        dfid: "-",
        clienttime: Date.now(),
        key: "OIlwieks28dk2k092lksi2UIkp",
        fields: "album_info,author_name,audio_info,ori_audio_name,base,songname"
      };
      let list = hashs;
      let tasks = [];
      while (list.length) {
        tasks.push(Object.assign({ data: list.slice(0, 100) }, data));
        if (list.length < 100) break;
        list = list.slice(100);
      }
      let url = "http://gateway.kugou.com/v2/album_audio/audio";
      return tasks.map((task) => this.createHttp(url, {
        method: "POST",
        body: task,
        headers: {
          "KG-THash": "13a3164",
          "KG-RC": "1",
          "KG-Fake": "0",
          "KG-RF": "00869891",
          "User-Agent": "Android712-AndroidPhone-11451-376-0-FeeCacheUpdate-wifi",
          "x-router": "kmr.service.kugou.com"
        }
      }).then((data2) => data2.map((s) => s[0])));
    },
    async getMusicInfos(list) {
      return this.filterData2(
        await Promise.all(
          this.createTask(
            this.deDuplication(list).map((item) => ({ hash: item.hash }))
          )
        ).then(([...datas]) => datas.flat())
      );
    },
    async getUserListDetailByCode(id) {
      const songInfo = await this.createHttp("http://t.kugou.com/command/", {
        method: "POST",
        headers: {
          "KG-RC": 1,
          "KG-THash": "network_super_call.cpp:3676261689:379",
          "User-Agent": ""
        },
        body: { appid: 1001, clientver: 9020, mid: "21511157a05844bd085308bc76ef3343", clienttime: 640612895, key: "36164c4015e704673c588ee202b9ecb8", data: id }
      });
      let songList;
      let info = songInfo.info;
      switch (info.type) {
        case 2:
          if (!info.global_collection_id) return this.getListDetailBySpecialId(info.id);
          break;
        default:
          break;
      }
      if (info.global_collection_id) return this.getUserListDetail2(info.global_collection_id);
      if (info.userid != null) {
        songList = await this.createHttp("http://www2.kugou.kugou.com/apps/kucodeAndShare/app/", {
          method: "POST",
          headers: {
            "KG-RC": 1,
            "KG-THash": "network_super_call.cpp:3676261689:379",
            "User-Agent": ""
          },
          body: { appid: 1001, clientver: 9020, mid: "21511157a05844bd085308bc76ef3343", clienttime: 640612895, key: "36164c4015e704673c588ee202b9ecb8", data: { id: info.id, type: 3, userid: info.userid, collect_type: 0, page: 1, pagesize: info.count } }
        });
      }
      let list = await this.getMusicInfos(songList || songInfo.list);
      return {
        list,
        page: 1,
        limit: info.count,
        total: list.length,
        source: "kg",
        info: {
          name: info.name,
          img: info.img_size && info.img_size.replace("{size}", 240) || info.img,
          // desc: body.result.info.list_desc,
          author: info.username
          // play_count: formatPlayCount(info.count),
        }
      };
    },
    async getUserListDetail3(chain, page) {
      const songInfo = await this.createHttp(`http://m.kugou.com/schain/transfer?pagesize=${this.listDetailLimit}&chain=${chain}&su=1&page=${page}&n=0.7928855356604456`, {
        headers: {
          "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 9_1 like Mac OS X) AppleWebKit/601.1.46 (KHTML, like Gecko) Version/9.0 Mobile/13B143 Safari/601.1"
        }
      });
      if (!songInfo.list) {
        if (songInfo.global_collection_id) return this.getUserListDetail2(songInfo.global_collection_id);
        else return this.getUserListDetail4(songInfo, chain, page).catch(() => this.getUserListDetail5(chain));
      }
      let list = await this.getMusicInfos(songInfo.list);
      return {
        list,
        page: 1,
        limit: this.listDetailLimit,
        total: list.length,
        source: "kg",
        info: {
          name: songInfo.info.name,
          img: songInfo.info.img,
          // desc: body.result.info.list_desc,
          author: songInfo.info.username
          // play_count: formatPlayCount(info.count),
        }
      };
    },
    deDuplication(datas) {
      let ids = /* @__PURE__ */ new Set();
      return datas.filter(({ hash }) => {
        if (ids.has(hash)) return false;
        ids.add(hash);
        return true;
      });
    },
    async decodeGcid(gcid) {
      const params = "dfid=-&appid=1005&mid=0&clientver=20109&clienttime=640612895&uuid=-";
      const body = {
        ret_info: 1,
        data: [
          {
            id: gcid,
            id_type: 2
          }
        ]
      };
      const result = await this.createHttp(`https://t.kugou.com/v1/songlist/batch_decode?${params}&signature=${signatureParams(params, "android", JSON.stringify(body))}`, {
        method: "POST",
        headers: {
          "User-Agent": "Mozilla/5.0 (Linux; Android 10; HUAWEI HMA-AL00) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.106 Mobile Safari/537.36",
          Referer: "https://m.kugou.com/"
        },
        body
      });
      return result.list[0].global_collection_id;
    },
    async getUserListDetailByLink({ info }, link) {
      let listInfo = info["0"];
      let total = listInfo.count;
      let tasks = [];
      let page = 0;
      while (total) {
        const limit = total > 90 ? 90 : total;
        total -= limit;
        page += 1;
        tasks.push(this.createHttp(link.replace(/pagesize=\d+/, "pagesize=" + limit).replace(/page=\d+/, "page=" + page), {
          headers: {
            "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 9_1 like Mac OS X) AppleWebKit/601.1.46 (KHTML, like Gecko) Version/9.0 Mobile/13B143 Safari/601.1",
            Referer: link
          }
        }).then((data) => data.list.info));
      }
      let result = await Promise.all(tasks).then(([...datas]) => datas.flat());
      result = await this.getMusicInfos(result);
      return {
        list: result,
        page,
        limit: this.listDetailLimit,
        total: result.length,
        source: "kg",
        info: {
          name: listInfo.name,
          img: listInfo.pic && listInfo.pic.replace("{size}", 240),
          // desc: body.result.info.list_desc,
          author: listInfo.list_create_username
          // play_count: formatPlayCount(listInfo.count),
        }
      };
    },
    createGetListDetail2Task(id, total) {
      let tasks = [];
      let page = 0;
      while (total) {
        const limit = total > 300 ? 300 : total;
        total -= limit;
        page += 1;
        const params = "appid=1058&global_specialid=" + id + "&specialid=0&plat=0&version=8000&page=" + page + "&pagesize=" + limit + "&srcappid=2919&clientver=20000&clienttime=1586163263991&mid=1586163263991&uuid=1586163263991&dfid=-";
        tasks.push(this.createHttp(`https://mobiles.kugou.com/api/v5/special/song_v2?${params}&signature=${signatureParams(params, "web")}`, {
          headers: {
            mid: "1586163263991",
            Referer: "https://m3ws.kugou.com/share/index.php",
            "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 11_0 like Mac OS X) AppleWebKit/604.1.38 (KHTML, like Gecko) Version/11.0 Mobile/15A372 Safari/604.1",
            dfid: "-",
            clienttime: "1586163263991"
          }
        }).then((data) => data.info));
      }
      return Promise.all(tasks).then(([...datas]) => datas.flat());
    },
    async getUserListDetail2(global_collection_id) {
      let id = global_collection_id;
      if (id.length > 1e3) throw new Error("get list error");
      const params = "appid=1058&specialid=0&global_specialid=" + id + "&format=jsonp&srcappid=2919&clientver=20000&clienttime=1586163242519&mid=1586163242519&uuid=1586163242519&dfid=-";
      let info = await this.createHttp(`https://mobiles.kugou.com/api/v5/special/info_v2?${params}&signature=${signatureParams(params, "web")}`, {
        headers: {
          mid: "1586163242519",
          Referer: "https://m3ws.kugou.com/share/index.php",
          "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 11_0 like Mac OS X) AppleWebKit/604.1.38 (KHTML, like Gecko) Version/11.0 Mobile/15A372 Safari/604.1",
          dfid: "-",
          clienttime: "1586163242519"
        }
      });
      const songInfo = await this.createGetListDetail2Task(id, info.songcount);
      let list = await this.getMusicInfos(songInfo);
      return {
        list,
        page: 1,
        limit: this.listDetailLimit,
        total: list.length,
        source: "kg",
        info: {
          name: info.specialname,
          img: info.imgurl && info.imgurl.replace("{size}", 240),
          desc: info.intro,
          author: info.nickname,
          play_count: formatPlayCount(info.playcount)
        }
      };
    },
    async getListInfoByChain(chain) {
      if (this.cache.has(chain)) return this.cache.get(chain);
      const { body } = await httpFetch(`https://m.kugou.com/share/?chain=${chain}&id=${chain}`, {
        headers: {
          "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1"
        }
      });
      let result = body.match(/var\sphpParam\s=\s({.+?});/);
      if (result) result = JSON.parse(result[1]);
      this.cache.set(chain, result);
      return result;
    },
    async getUserListDetailByPcChain(chain) {
      let key = `${chain}_pc_list`;
      if (this.cache.has(key)) return this.cache.get(key);
      const { body } = await httpFetch(`http://www.kugou.com/share/${chain}.html`, {
        headers: {
          "User-Agent": "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36"
        }
      });
      let result = body.match(/var\sdataFromSmarty\s=\s(\[.+?\])/);
      if (result) result = JSON.parse(result[1]);
      this.cache.set(chain, result);
      result = await this.getMusicInfos(result);
      return result;
    },
    async getUserListDetail4(songInfo, chain, page) {
      const limit = 100;
      const [listInfo, list] = await Promise.all([
        this.getListInfoByChain(chain),
        this.getUserListDetailById(songInfo.id, page, limit)
      ]);
      return {
        list: list || [],
        page,
        limit,
        total: list.length ?? 0,
        source: "kg",
        info: {
          name: listInfo.specialname,
          img: listInfo.imgurl && listInfo.imgurl.replace("{size}", 240),
          // desc: body.result.info.list_desc,
          author: listInfo.nickname
          // play_count: formatPlayCount(info.count),
        }
      };
    },
    async getUserListDetail5(chain) {
      const [listInfo, list] = await Promise.all([
        this.getListInfoByChain(chain),
        this.getUserListDetailByPcChain(chain)
      ]);
      return {
        list: list || [],
        page: 1,
        limit: this.listDetailLimit,
        total: list.length ?? 0,
        source: "kg",
        info: {
          name: listInfo.specialname,
          img: listInfo.imgurl && listInfo.imgurl.replace("{size}", 240),
          // desc: body.result.info.list_desc,
          author: listInfo.nickname
          // play_count: formatPlayCount(info.count),
        }
      };
    },
    async getUserListDetailById(id, page, limit) {
      const signature = await handleSignature(id, page, limit);
      let info = await this.createHttp(`https://pubsongscdn.kugou.com/v2/get_other_list_file?srcappid=2919&clientver=20000&appid=1058&type=0&module=playlist&page=${page}&pagesize=${limit}&specialid=${id}&signature=${signature}`, {
        headers: {
          Referer: "https://m3ws.kugou.com/share/index.php",
          "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 11_0 like Mac OS X) AppleWebKit/604.1.38 (KHTML, like Gecko) Version/11.0 Mobile/15A372 Safari/604.1",
          dfid: "-"
        }
      });
      let result = await this.getMusicInfos(info.info);
      return result;
    },
    async getUserListDetail(link, page, retryNum = 0) {
      if (retryNum > 3) return Promise.reject(new Error("link try max num"));
      if (link.includes("#")) link = link.replace(/#.*$/, "");
      if (link.includes("global_collection_id")) return this.getUserListDetail2(link.replace(/^.*?global_collection_id=(\w+)(?:&.*$|#.*$|$)/, "$1"));
      if (link.includes("gcid_")) {
        let gcid = link.match(/gcid_\w+/)?.[0];
        if (gcid) {
          const global_collection_id = await this.decodeGcid(gcid);
          if (global_collection_id) return this.getUserListDetail2(global_collection_id);
        }
      }
      if (link.includes("chain=")) return this.getUserListDetail3(link.replace(/^.*?chain=(\w+)(?:&.*$|#.*$|$)/, "$1"), page);
      if (link.includes(".html")) {
        if (link.includes("zlist.html")) {
          link = link.replace(/^(.*)zlist\.html/, "https://m3ws.kugou.com/zlist/list");
          if (link.includes("pagesize")) {
            link = link.replace("pagesize=30", "pagesize=" + this.listDetailLimit).replace("page=1", "page=" + page);
          } else {
            link += `&pagesize=${this.listDetailLimit}&page=${page}`;
          }
        } else if (!link.includes("song.html")) return this.getUserListDetail3(link.replace(/.+\/(\w+).html(?:\?.*|&.*$|#.*$|$)/, "$1"), page);
      }
      const requestObj_listDetailLink = httpFetch(link, {
        headers: {
          "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 9_1 like Mac OS X) AppleWebKit/601.1.46 (KHTML, like Gecko) Version/9.0 Mobile/13B143 Safari/601.1",
          Referer: link
        }
      });
      const { url: location, statusCode, body } = await requestObj_listDetailLink;
      if (statusCode > 400) return this.getUserListDetail(link, page, ++retryNum);
      if (location.split("?")[0] != link.split("?")[0]) {
        if (location.includes("global_collection_id")) return this.getUserListDetail2(location.replace(/^.*?global_collection_id=(\w+)(?:&.*$|#.*$|$)/, "$1"));
        if (location.includes("gcid_")) {
          let gcid = link.match(/gcid_\w+/)?.[0];
          if (gcid) {
            const global_collection_id = await this.decodeGcid(gcid);
            if (global_collection_id) return this.getUserListDetail2(global_collection_id);
          }
        }
        if (location.includes("chain=")) return this.getUserListDetail3(location.replace(/^.*?chain=(\w+)(?:&.*$|#.*$|$)/, "$1"), page);
        if (location.includes(".html")) {
          if (location.includes("zlist.html")) {
            let link2 = location.replace(/^(.*)zlist\.html/, "https://m3ws.kugou.com/zlist/list");
            if (link2.includes("pagesize")) {
              link2 = link2.replace("pagesize=30", "pagesize=" + this.listDetailLimit).replace("page=1", "page=" + page);
            } else {
              link2 += `&pagesize=${this.listDetailLimit}&page=${page}`;
            }
            return this.getUserListDetail(link2, page, ++retryNum);
          } else return this.getUserListDetail3(location.replace(/.+\/(\w+).html(?:\?.*|&.*$|#.*$|$)/, "$1"), page);
        }
      }
      if (typeof body == "string") return this.getUserListDetail2(body.replace(/^[\s\S]+?"global_collection_id":"(\w+)"[\s\S]+?$/, "$1"));
      if (body.errcode !== 0) return this.getUserListDetail(link, page, ++retryNum);
      return this.getUserListDetailByLink(body, link);
    },
    async getListDetail(id, page) {
      id = id.toString();
      if (id.includes("special/single/")) {
        id = id.replace(this.regExps.listDetailLink, "$1");
      } else if (/https?:/.test(id)) {
        return this.getUserListDetail(id.replace(/^.*?http/, "http"), page);
      } else if (/^\d+$/.test(id)) {
        return this.getUserListDetailByCode(id);
      } else if (id.startsWith("id_")) {
        id = id.replace("id_", "");
      }
      return this.getListDetailBySpecialId(id, page);
    },
    filterData(rawList) {
      return rawList.map((item) => {
        const types = [];
        const _types = {};
        if (item.filesize !== 0) {
          let size = sizeFormate(item.filesize);
          types.push({ type: "128k", size, hash: item.hash });
          _types["128k"] = {
            size,
            hash: item.hash
          };
        }
        if (item.filesize_320 !== 0) {
          let size = sizeFormate(item.filesize_320);
          types.push({ type: "320k", size, hash: item.hash_320 });
          _types["320k"] = {
            size,
            hash: item.hash_320
          };
        }
        if (item.filesize_ape !== 0) {
          let size = sizeFormate(item.filesize_ape);
          types.push({ type: "ape", size, hash: item.hash_ape });
          _types.ape = {
            size,
            hash: item.hash_ape
          };
        }
        if (item.filesize_flac !== 0) {
          let size = sizeFormate(item.filesize_flac);
          types.push({ type: "flac", size, hash: item.hash_flac });
          _types.flac = {
            size,
            hash: item.hash_flac
          };
        }
        return {
          singer: decodeName(item.singername),
          name: decodeName(item.songname),
          albumName: decodeName(item.album_name),
          albumId: item.album_id,
          songmid: item.audio_id,
          source: "kg",
          interval: formatPlayTime(item.duration / 1e3),
          img: null,
          lrc: null,
          hash: item.hash,
          types,
          _types,
          typeUrl: {}
        };
      });
    },
    // getSinger(singers) {
    //   let arr = []
    //   singers?.forEach(singer => {
    //     arr.push(singer.name)
    //   })
    //   return arr.join('、')
    // },
    // v9 API
    // filterDatav9(rawList) {
    //   console.log(rawList)
    //   return rawList.map(item => {
    //     const types = []
    //     const _types = {}
    //     item.relate_goods.forEach(qualityObj => {
    //       if (qualityObj.level === 2) {
    //         let size = sizeFormate(qualityObj.size)
    //         types.push({ type: '128k', size, hash: qualityObj.hash })
    //         _types['128k'] = {
    //           size,
    //           hash: qualityObj.hash,
    //         }
    //       } else if (qualityObj.level === 4) {
    //         let size = sizeFormate(qualityObj.size)
    //         types.push({ type: '320k', size, hash: qualityObj.hash })
    //         _types['320k'] = {
    //           size,
    //           hash: qualityObj.hash,
    //         }
    //       } else if (qualityObj.level === 5) {
    //         let size = sizeFormate(qualityObj.size)
    //         types.push({ type: 'flac', size, hash: qualityObj.hash })
    //         _types.flac = {
    //           size,
    //           hash: qualityObj.hash,
    //         }
    //       } else if (qualityObj.level === 6) {
    //         let size = sizeFormate(qualityObj.size)
    //         types.push({ type: 'flac24bit', size, hash: qualityObj.hash })
    //         _types.flac24bit = {
    //           size,
    //           hash: qualityObj.hash,
    //         }
    //       }
    //     })
    //     const nameInfo = item.name.split(' - ')
    //     return {
    //       singer: this.getSinger(item.singerinfo),
    //       name: decodeName((nameInfo[1] ?? nameInfo[0]).trim()),
    //       albumName: decodeName(item.albuminfo.name),
    //       albumId: item.albuminfo.id,
    //       songmid: item.audio_id,
    //       source: 'kg',
    //       interval: formatPlayTime(item.timelen / 1000),
    //       img: null,
    //       lrc: null,
    //       hash: item.hash,
    //       types,
    //       _types,
    //       typeUrl: {},
    //     }
    //   })
    // },
    // hash list filter
    filterData2(rawList) {
      let ids = /* @__PURE__ */ new Set();
      let list = [];
      rawList.forEach((item) => {
        if (!item) return;
        if (ids.has(item.audio_info.audio_id)) return;
        ids.add(item.audio_info.audio_id);
        const types = [];
        const _types = {};
        if (item.audio_info.filesize !== "0") {
          let size = sizeFormate(parseInt(item.audio_info.filesize));
          types.push({ type: "128k", size, hash: item.audio_info.hash });
          _types["128k"] = {
            size,
            hash: item.audio_info.hash
          };
        }
        if (item.audio_info.filesize_320 !== "0") {
          let size = sizeFormate(parseInt(item.audio_info.filesize_320));
          types.push({ type: "320k", size, hash: item.audio_info.hash_320 });
          _types["320k"] = {
            size,
            hash: item.audio_info.hash_320
          };
        }
        if (item.audio_info.filesize_flac !== "0") {
          let size = sizeFormate(parseInt(item.audio_info.filesize_flac));
          types.push({ type: "flac", size, hash: item.audio_info.hash_flac });
          _types.flac = {
            size,
            hash: item.audio_info.hash_flac
          };
        }
        if (item.audio_info.filesize_high !== "0") {
          let size = sizeFormate(parseInt(item.audio_info.filesize_high));
          types.push({ type: "flac24bit", size, hash: item.audio_info.hash_high });
          _types.flac24bit = {
            size,
            hash: item.audio_info.hash_high
          };
        }
        list.push({
          singer: decodeName(item.author_name),
          name: decodeName(item.songname),
          albumName: decodeName(item.album_info.album_name),
          albumId: item.album_info.album_id,
          songmid: item.audio_info.audio_id,
          source: "kg",
          interval: formatPlayTime(parseInt(item.audio_info.timelength) / 1e3),
          img: null,
          lrc: null,
          hash: item.audio_info.hash,
          otherSource: null,
          types,
          _types,
          typeUrl: {}
        });
      });
      return list;
    },
    // 获取列表信息
    getListInfo(tagId, tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const requestInfo = httpFetch(this.getInfoUrl(tagId));
      return requestInfo.then(({ body }) => {
        if (body.status !== 1) return this.getListInfo(tagId, ++tryNum);
        return {
          limit: body.data.params.pagesize,
          page: body.data.params.p,
          total: body.data.params.total,
          source: "kg"
        };
      });
    },
    // 获取列表数据
    getList(sortId, tagId, page) {
      let tasks = [this.getSongList(sortId, tagId, page)];
      tasks.push(
        this.currentTagInfo.id === tagId ? Promise.resolve(this.currentTagInfo.info) : this.getListInfo(tagId).then((info) => {
          this.currentTagInfo.id = tagId;
          this.currentTagInfo.info = Object.assign({}, info);
          return info;
        })
      );
      if (!tagId && page === 1 && sortId === this.sortList[0].id) tasks.push(this.getSongListRecommend());
      return Promise.all(tasks).then(([list, info, recommendList]) => {
        if (recommendList) list.unshift(...recommendList);
        return {
          list,
          ...info
        };
      });
    },
    // 获取标签
    getTags(tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const request = httpFetch(this.getInfoUrl());
      return request.then(({ body }) => {
        if (body.status !== 1) return this.getTags(++tryNum);
        return {
          hotTag: this.filterInfoHotTag(body.data.hotTag),
          tags: this.filterTagInfo(body.data.tagids),
          source: "kg"
        };
      });
    },
    getDetailPageUrl(id) {
      if (typeof id == "string") {
        if (/^https?:\/\//.test(id)) return id;
        id = id.replace("id_", "");
      }
      return `https://www.kugou.com/yy/special/single/${id}.html`;
    },
    search(text, page, limit = 20) {
      return httpFetch(`http://msearchretry.kugou.com/api/v3/search/special?keyword=${encodeURIComponent(text)}&page=${page}&pagesize=${limit}&showtype=10&filter=0&version=7910&sver=2`).then(({ body }) => {
        if (body.errcode != 0) throw new Error("filed");
        return {
          list: body.data.info.map((item) => {
            return {
              play_count: formatPlayCount(item.playcount),
              id: "id_" + item.specialid,
              author: item.nickname,
              name: item.specialname,
              time: dateFormat(item.publishtime, "Y-M-D"),
              img: item.imgurl,
              grade: item.grade,
              desc: item.intro,
              total: item.songcount,
              source: "kg"
            };
          }),
          limit,
          total: body.data.total,
          source: "kg"
        };
      });
    }
  };

  // src/musicSdk/kg/leaderboard.js
  var boardList2 = [{ id: "kg__8888", name: "TOP500", bangid: "8888" }, { id: "kg__6666", name: "飙升榜", bangid: "6666" }, { id: "kg__59703", name: "蜂鸟流行音乐榜", bangid: "59703" }, { id: "kg__52144", name: "抖音热歌榜", bangid: "52144" }, { id: "kg__52767", name: "快手热歌榜", bangid: "52767" }, { id: "kg__24971", name: "DJ热歌榜", bangid: "24971" }, { id: "kg__23784", name: "网络红歌榜", bangid: "23784" }, { id: "kg__44412", name: "说唱先锋榜", bangid: "44412" }, { id: "kg__31308", name: "内地榜", bangid: "31308" }, { id: "kg__33160", name: "电音榜", bangid: "33160" }, { id: "kg__31313", name: "香港地区榜", bangid: "31313" }, { id: "kg__51341", name: "民谣榜", bangid: "51341" }, { id: "kg__54848", name: "台湾地区榜", bangid: "54848" }, { id: "kg__31310", name: "欧美榜", bangid: "31310" }, { id: "kg__33162", name: "ACG新歌榜", bangid: "33162" }, { id: "kg__31311", name: "韩国榜", bangid: "31311" }, { id: "kg__31312", name: "日本榜", bangid: "31312" }, { id: "kg__49225", name: "80后热歌榜", bangid: "49225" }, { id: "kg__49223", name: "90后热歌榜", bangid: "49223" }, { id: "kg__49224", name: "00后热歌榜", bangid: "49224" }, { id: "kg__33165", name: "粤语金曲榜", bangid: "33165" }, { id: "kg__33166", name: "欧美金曲榜", bangid: "33166" }, { id: "kg__33163", name: "影视金曲榜", bangid: "33163" }, { id: "kg__51340", name: "伤感榜", bangid: "51340" }, { id: "kg__35811", name: "会员专享榜", bangid: "35811" }, { id: "kg__37361", name: "雷达榜", bangid: "37361" }, { id: "kg__21101", name: "分享榜", bangid: "21101" }, { id: "kg__46910", name: "综艺新歌榜", bangid: "46910" }, { id: "kg__30972", name: "酷狗音乐人原创榜", bangid: "30972" }, { id: "kg__60170", name: "闽南语榜", bangid: "60170" }, { id: "kg__65234", name: "儿歌榜", bangid: "65234" }, { id: "kg__4681", name: "美国BillBoard榜", bangid: "4681" }, { id: "kg__25028", name: "Beatport电子舞曲榜", bangid: "25028" }, { id: "kg__4680", name: "英国单曲榜", bangid: "4680" }, { id: "kg__38623", name: "韩国Melon音乐榜", bangid: "38623" }, { id: "kg__42807", name: "joox本地热歌榜", bangid: "42807" }, { id: "kg__36107", name: "小语种热歌榜", bangid: "36107" }, { id: "kg__4673", name: "日本公信榜", bangid: "4673" }, { id: "kg__46868", name: "日本SPACE SHOWER榜", bangid: "46868" }, { id: "kg__42808", name: "KKBOX风云榜", bangid: "42808" }, { id: "kg__60171", name: "越南语榜", bangid: "60171" }, { id: "kg__60172", name: "泰语榜", bangid: "60172" }, { id: "kg__59895", name: "R&B榜", bangid: "59895" }, { id: "kg__59896", name: "摇滚榜", bangid: "59896" }, { id: "kg__59897", name: "爵士榜", bangid: "59897" }, { id: "kg__59898", name: "乡村音乐榜", bangid: "59898" }, { id: "kg__59900", name: "纯音乐榜", bangid: "59900" }, { id: "kg__59899", name: "古典榜", bangid: "59899" }, { id: "kg__22603", name: "5sing音乐榜", bangid: "22603" }, { id: "kg__21335", name: "繁星音乐榜", bangid: "21335" }, { id: "kg__33161", name: "古风新歌榜", bangid: "33161" }];
  var leaderboard_default2 = {
    listDetailLimit: 100,
    list: [
      {
        id: "kgtop500",
        name: "TOP500",
        bangid: "8888"
      },
      {
        id: "kgwlhgb",
        name: "网络榜",
        bangid: "23784"
      },
      {
        id: "kgbsb",
        name: "飙升榜",
        bangid: "6666"
      },
      {
        id: "kgfxb",
        name: "分享榜",
        bangid: "21101"
      },
      {
        id: "kgcyyb",
        name: "纯音乐榜",
        bangid: "33164"
      },
      {
        id: "kggfjqb",
        name: "古风榜",
        bangid: "33161"
      },
      {
        id: "kgyyjqb",
        name: "粤语榜",
        bangid: "33165"
      },
      {
        id: "kgomjqb",
        name: "欧美榜",
        bangid: "33166"
      },
      {
        id: "kgdyrgb",
        name: "电音榜",
        bangid: "33160"
      },
      {
        id: "kgjdrgb",
        name: "DJ热歌榜",
        bangid: "24971"
      },
      {
        id: "kghyxgb",
        name: "华语新歌榜",
        bangid: "31308"
      }
    ],
    getUrl(p, id, limit) {
      return `http://mobilecdnbj.kugou.com/api/v3/rank/song?version=9108&ranktype=1&plat=0&pagesize=${limit}&area_code=1&page=${p}&rankid=${id}&with_res_tag=0&show_portrait_mv=1`;
    },
    regExps: {
      total: /total: '(\d+)',/,
      page: /page: '(\d+)',/,
      limit: /pagesize: '(\d+)',/,
      listData: /global\.features = (\[.+\]);/
    },
    getBoardsData() {
      const request = httpFetch("http://mobilecdnbj.kugou.com/api/v5/rank/list?version=9108&plat=0&showtype=2&parentid=0&apiver=6&area_code=1&withsong=1");
      return request;
    },
    getData(url) {
      const requestDataObj = httpFetch(url);
      return requestDataObj;
    },
    getSinger(singers) {
      let arr = [];
      singers.forEach((singer) => {
        arr.push(singer.author_name);
      });
      return arr.join("、");
    },
    filterData(rawList) {
      return rawList.map((item) => {
        const types = [];
        const _types = {};
        if (item.filesize !== 0) {
          let size = sizeFormate(item.filesize);
          types.push({ type: "128k", size, hash: item.hash });
          _types["128k"] = {
            size,
            hash: item.hash
          };
        }
        if (item["320filesize"] !== 0) {
          let size = sizeFormate(item["320filesize"]);
          types.push({ type: "320k", size, hash: item["320hash"] });
          _types["320k"] = {
            size,
            hash: item["320hash"]
          };
        }
        if (item.sqfilesize !== 0) {
          let size = sizeFormate(item.sqfilesize);
          types.push({ type: "flac", size, hash: item.sqhash });
          _types.flac = {
            size,
            hash: item.sqhash
          };
        }
        if (item.filesize_high !== 0) {
          let size = sizeFormate(item.filesize_high);
          types.push({ type: "flac24bit", size, hash: item.hash_high });
          _types.flac24bit = {
            size,
            hash: item.hash_high
          };
        }
        return {
          singer: formatSingerName(item.authors, "author_name"),
          name: decodeName(item.songname),
          albumName: decodeName(item.remark),
          albumId: item.album_id,
          songmid: item.audio_id,
          source: "kg",
          interval: formatPlayTime(item.duration),
          img: null,
          lrc: null,
          hash: item.hash,
          otherSource: null,
          types,
          _types,
          typeUrl: {}
        };
      });
    },
    filterBoardsData(rawList) {
      let list = [];
      for (const board of rawList) {
        if (board.isvol != 1) continue;
        list.push({
          id: "kg__" + board.rankid,
          name: board.rankname,
          bangid: String(board.rankid)
        });
      }
      return list;
    },
    async getBoards(retryNum = 0) {
      try {
        const { body } = await this.getBoardsData();
        if (body && body.errcode === 0 && Array.isArray(body.data?.info)) {
          const list = [];
          for (const board of body.data.info) {
            if (board.isvol != 1 || !board.rankid) continue;
            const name = board.rankname || "";
            if (!name) continue;
            const img = String(board.imgurl || board.album_img_9 || board.banner_9 || "").replace("{size}", "240");
            list.push({ id: "kg__" + board.rankid, name, bangid: String(board.rankid), img: img || null });
          }
          if (list.length) {
            this.list = list;
            return { list, source: "kg" };
          }
        }
      } catch (error) {
      }
      this.list = boardList2;
      return {
        list: boardList2,
        source: "kg"
      };
    },
    async getList(bangid, page, limit, retryNum = 0) {
      if (++retryNum > 3) throw new Error("try max num");
      const pageSize = limit || this.listDetailLimit;
      const { body } = await this.getData(this.getUrl(page, bangid, pageSize));
      if (body.errcode != 0) return this.getList(bangid, page, pageSize, retryNum);
      let total = body.data.total;
      let listData = this.filterData(body.data.info);
      return {
        total,
        list: limit ? listData.slice(0, pageSize) : listData,
        limit: pageSize,
        page,
        source: "kg"
      };
    },
    getDetailPageUrl(id) {
      if (typeof id == "string") id = id.replace("kg__", "");
      return `https://www.kugou.com/yy/rank/home/1-${id}.html`;
    }
  };

  // src/musicSdk/kg/hotSearch.js
  var hotSearch_default2 = {
    getList() {
      return requestByDataChannel(
        () => this.appHot(),
        () => this.gatewayHot()
      ).then((list) => ({ source: "kg", list }));
    },
    // App 通道：移动 CDN 热搜（免签名，首包小）
    appHot() {
      const requestObj = httpFetch("http://mobilecdn.kugou.com/api/v3/search/hot?plat=0&page=1&pagesize=20&format=json", {
        cache: "default",
        headers: { "User-Agent": "Android9-AndroidPhone-13194-130-0-searchrecommendprotocol-wifi", "kg-rc": 1 }
      });
      return requestObj.then(({ body, statusCode }) => {
        const list = body?.data?.info?.map((item) => decodeName(item.keyword)).filter(Boolean) ?? [];
        if (statusCode !== 200 || !list.length) throw new Error("app hot empty");
        return list;
      });
    },
    // 网关通道：热搜 tab（字段分组，作为兜底）
    gatewayHot() {
      const _requestObj = httpFetch("http://gateway.kugou.com/api/v3/search/hot_tab?signature=ee44edb9d7155821412d220bcaf509dd&appid=1005&clientver=10026&plat=0", {
        method: "get",
        cache: null,
        headers: {
          dfid: "1ssiv93oVqMp27cirf2CvoF1",
          mid: "156798703528610303473757548878786007104",
          clienttime: 1584257267,
          "x-router": "msearch.kugou.com",
          "user-agent": "Android9-AndroidPhone-10020-130-0-searchrecommendprotocol-wifi",
          "kg-rc": 1
        }
      });
      return _requestObj.then(({ body, statusCode }) => {
        if (statusCode != 200 || body.errcode !== 0) throw new Error("获取热搜词失败");
        const list = this.filterList(body.data.list);
        if (!list.length) throw new Error("gateway hot empty");
        return list;
      });
    },
    filterList(rawList) {
      const list = [];
      rawList.forEach((item) => {
        item.keywords.map((k) => list.push(decodeName(k.keyword)));
      });
      return list;
    }
  };

  // src/musicSdk/kg/tipSearch.js
  var tipSearch_default2 = {
    tipSearchBySong(str) {
      const request = createHttpFetch(`https://searchtip.kugou.com/getSearchTip?MusicTipCount=10&keyword=${encodeURIComponent(str)}`, {
        headers: {
          referer: "https://www.kugou.com/"
        }
      });
      return request.then((body) => {
        return body[0].RecordDatas;
      });
    },
    handleResult(rawData) {
      return rawData.map((info) => info.HintInfo);
    },
    async search(str) {
      return this.tipSearchBySong(str).then((result) => this.handleResult(result));
    }
  };

  // src/musicSdk/kg/lyric.js
  var headExp = /^.*\[id:\$\w+\]\n/;
  var parseLyric = (str) => {
    str = str.replace(/\r/g, "");
    if (headExp.test(str)) str = str.replace(headExp, "");
    let trans = str.match(/\[language:([\w=\\/+]+)\]/);
    let lyric;
    let rlyric;
    let tlyric;
    if (trans) {
      str = str.replace(/\[language:[\w=\\/+]+\]\n/, "");
      let json = JSON.parse(Buffer.from(trans[1], "base64").toString());
      for (const item of json.content) {
        switch (item.type) {
          case 0:
            rlyric = item.lyricContent;
            break;
          case 1:
            tlyric = item.lyricContent;
            break;
        }
      }
    }
    let i = 0;
    let lxlyric = str.replace(/\[((\d+),\d+)\].*/g, (str2) => {
      let result = str2.match(/\[((\d+),\d+)\].*/);
      let time = parseInt(result[2]);
      let ms = (time % 1e3).toString().padStart(3, "0");
      time /= 1e3;
      let m = parseInt(time / 60).toString().padStart(2, "0");
      time %= 60;
      let s = parseInt(time).toString().padStart(2, "0");
      time = `${m}:${s}.${ms}`;
      if (rlyric) rlyric[i] = `[${time}]${rlyric[i]?.join("") ?? ""}`;
      if (tlyric) tlyric[i] = `[${time}]${tlyric[i]?.join("") ?? ""}`;
      i++;
      return str2.replace(result[1], time);
    });
    rlyric = rlyric ? rlyric.join("\n") : "";
    tlyric = tlyric ? tlyric.join("\n") : "";
    lxlyric = lxlyric.replace(/<(\d+,\d+),\d+>/g, "<$1>");
    lxlyric = decodeName(lxlyric);
    lyric = lxlyric.replace(/<\d+,\d+>/g, "");
    rlyric = decodeName(rlyric);
    tlyric = decodeName(tlyric);
    return {
      lyric,
      tlyric,
      rlyric,
      lxlyric
    };
  };
  var lyric_default2 = {
    getIntv(interval) {
      if (!interval) return 0;
      let intvArr = interval.split(":");
      let intv = 0;
      let unit = 1;
      while (intvArr.length) {
        intv += intvArr.pop() * unit;
        unit *= 60;
      }
      return parseInt(intv);
    },
    // getLyric(songInfo, tryNum = 0) {
    //   let requestObj = httpFetch(`http://m.kugou.com/app/i/krc.php?cmd=100&keyword=${encodeURIComponent(songInfo.name)}&hash=${songInfo.hash}&timelength=${songInfo._interval || this.getIntv(songInfo.interval)}&d=0.38664927426725626`, {
    //     headers: {
    //       'KG-RC': 1,
    //       'KG-THash': 'expand_search_manager.cpp:852736169:451',
    //       'User-Agent': 'KuGou2012-9020-ExpandSearchManager',
    //     },
    //   })
    //   requestObj = requestObj.then(({ body, statusCode }) => {
    //     if (statusCode !== 200) {
    //       if (tryNum > 5) return Promise.reject(new Error('歌词获取失败'))
    //       let tryRequestObj = this.getLyric(songInfo, ++tryNum)
    //       return tryRequestObj
    //     }
    //     return {
    //       lyric: body,
    //       tlyric: '',
    //     }
    //   })
    //   return requestObj
    // },
    searchLyric(name, hash, time, tryNum = 0) {
      let requestObj = httpFetch(`http://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=${encodeURIComponent(name)}&hash=${hash}&timelength=${time}&lrctxt=1`, {
        headers: {
          "KG-RC": 1,
          "KG-THash": "expand_search_manager.cpp:852736169:451",
          "User-Agent": "KuGou2012-9020-ExpandSearchManager"
        }
      });
      requestObj = requestObj.then(({ body, statusCode }) => {
        if (statusCode !== 200) {
          if (tryNum > 5) return Promise.reject(new Error("歌词获取失败"));
          let tryRequestObj = this.searchLyric(name, hash, time, ++tryNum);
          return tryRequestObj;
        }
        if (body.candidates.length) {
          let info = body.candidates[0];
          return { id: info.id, accessKey: info.accesskey, fmt: info.krctype == 1 && info.contenttype != 1 ? "krc" : "lrc" };
        }
        return null;
      });
      return requestObj;
    },
    getLyricDownload(id, accessKey, fmt, tryNum = 0) {
      let requestObj = httpFetch(`http://lyrics.kugou.com/download?ver=1&client=pc&id=${id}&accesskey=${accessKey}&fmt=${fmt}&charset=utf8`, {
        headers: {
          "KG-RC": 1,
          "KG-THash": "expand_search_manager.cpp:852736169:451",
          "User-Agent": "KuGou2012-9020-ExpandSearchManager"
        }
      });
      requestObj = requestObj.then(({ body, statusCode }) => {
        if (statusCode !== 200) {
          if (tryNum > 5) return Promise.reject(new Error("歌词获取失败"));
          let tryRequestObj = this.getLyricDownload(id, accessKey, fmt, ++tryNum);
          return tryRequestObj;
        }
        switch (body.fmt) {
          case "krc":
            return decodeLyric2(body.content).then((result) => parseLyric(result));
          case "lrc":
            return {
              lyric: Buffer.from(body.content, "base64").toString("utf-8"),
              tlyric: "",
              rlyric: "",
              lxlyric: ""
            };
          default:
            return Promise.reject(new Error(`未知歌词格式: ${body.fmt}`));
        }
      });
      return requestObj;
    },
    getLyric(songInfo, tryNum = 0) {
      let requestObj = this.searchLyric(songInfo.name, songInfo.hash, songInfo._interval || this.getIntv(songInfo.interval));
      requestObj = requestObj.then((result) => {
        if (!result) return Promise.reject(new Error("Get lyric failed"));
        let requestObj2 = this.getLyricDownload(result.id, result.accessKey, result.fmt);
        return requestObj2;
      });
      return requestObj;
    }
  };

  // src/musicSdk/kg/pic.js
  var pic_default2 = {
    getPic(songInfo) {
      const requestObj = httpFetch(
        "http://media.store.kugou.com/v1/get_res_privilege",
        {
          method: "POST",
          headers: {
            "KG-RC": 1,
            "KG-THash": "expand_search_manager.cpp:852736169:451",
            "User-Agent": "KuGou2012-9020-ExpandSearchManager"
          },
          body: {
            appid: 1001,
            area_code: "1",
            behavior: "play",
            clientver: "9020",
            need_hash_offset: 1,
            relate: 1,
            resource: [
              {
                album_audio_id: songInfo.songmid.length == 32 ? songInfo.audioId.split("_")[0] : songInfo.songmid,
                album_id: songInfo.albumId,
                hash: songInfo.hash,
                id: 0,
                name: `${songInfo.singer} - ${songInfo.name}.mp3`,
                type: "audio"
              }
            ],
            token: "",
            userid: 2626431536,
            vip: 1
          }
        }
      );
      return requestObj.then(({ body }) => {
        if (body.error_code !== 0) return Promise.reject(new Error("图片获取失败"));
        let info = body.data[0].info;
        const img = info.imgsize ? info.image.replace("{size}", info.imgsize[0]) : info.image;
        if (!img) return Promise.reject(new Error("Pic get failed"));
        return img;
      });
    }
  };

  // src/musicSdk/tx/utils/crypto.js
  var PART_1_INDEXES = [23, 14, 6, 36, 16, 40, 7, 19];
  var PART_2_INDEXES = [16, 1, 32, 12, 19, 27, 8, 5];
  var SCRAMBLE_VALUES = [89, 39, 179, 150, 218, 82, 58, 252, 177, 52, 186, 123, 120, 64, 242, 133, 143, 161, 121, 179];
  function pickHashByIdx(hash, indexes) {
    return indexes.map((idx) => hash[idx]).join("");
  }
  function base64Encode(data) {
    return Buffer.from(data).toString("base64").replace(/[\\/+=]/g, "");
  }
  async function zzcSign(text) {
    const hash = await hashSHA1(text);
    const part1 = pickHashByIdx(hash, PART_1_INDEXES);
    const part2 = pickHashByIdx(hash, PART_2_INDEXES);
    const part3 = SCRAMBLE_VALUES.map((value, i) => value ^ parseInt(hash.slice(i * 2, i * 2 + 2), 16));
    const b64Part = base64Encode(part3).replace(/[\\/+=]/g, "");
    return `zzc${part1}${b64Part}${part2}`.toLowerCase();
  }

  // src/musicSdk/tx/utils/index.js
  var signRequest = async (data) => {
    const sign = await zzcSign(JSON.stringify(data));
    return httpFetch(`https://u.y.qq.com/cgi-bin/musics.fcg?sign=${sign}`, {
      method: "post",
      headers: {
        "User-Agent": "QQMusic 14090508(android 12)"
      },
      body: data
    });
  };

  // src/musicSdk/tx/musicSearch.js
  var musicSearch_default3 = {
    limit: 50,
    total: 0,
    page: 0,
    allPage: 1,
    successCode: 0,
    musicSearch(str, page, limit, retryNum = 0) {
      if (retryNum > 5) return Promise.reject(new Error("搜索失败"));
      const searchRequest = signRequest({
        comm: {
          _channelid: "0",
          _os_version: "6.2.9200-2",
          ct: "19",
          cv: "2151",
          guid: "1F70E520B2EAA7D25E11760783C53CA9",
          patch: "118",
          psrf_access_token_expiresAt: 0,
          psrf_qqaccess_token: "",
          psrf_qqopenid: "",
          psrf_qqunionid: "",
          tmeAppID: "qqmusic",
          tmeLoginType: 0,
          uin: "0",
          wid: "7223299733393904640"
        },
        "music.search.SearchCgiService": {
          module: "music.search.SearchCgiService",
          method: "DoSearchForQQMusicDesktop",
          param: {
            grp: 1,
            num_per_page: limit,
            page_num: page,
            query: str,
            remoteplace: "txt.newclient.top",
            search_type: 0,
            searchid: this.getSearchId()
          }
        }
      });
      return searchRequest.then(({ body }) => {
        const req = body?.["music.search.SearchCgiService"] ?? body?.req;
        if (!req || body.code != this.successCode || req.code != this.successCode) {
          return this.musicSearch(str, page, limit, ++retryNum);
        }
        return req.data;
      });
    },
    /**
     * PC 客户端版 searchid：32 位大写十六进制 GUID + 5 位补零随机数 = 37 字符。
     * 对应 QQ 音乐 PC 端 searchid 形状（服务端只需要唯一的会话 ID，形状一致即可）。
     */
    getSearchId() {
      let guid = "";
      for (let i = 0; i < 32; i++) guid += Math.floor(Math.random() * 16).toString(16);
      return guid.toUpperCase() + String(Math.floor(Math.random() * 1e5)).padStart(5, "0");
    },
    handleResult(rawList) {
      if (!rawList || !Array.isArray(rawList)) return [];
      const list = [];
      rawList.forEach((item) => {
        if (!item.file?.media_mid) return;
        let types = [];
        let _types = {};
        const file = item.file;
        if (file.size_128mp3 != 0) {
          let size = sizeFormate(file.size_128mp3);
          types.push({ type: "128k", size });
          _types["128k"] = {
            size
          };
        }
        if (file.size_320mp3 !== 0) {
          let size = sizeFormate(file.size_320mp3);
          types.push({ type: "320k", size });
          _types["320k"] = {
            size
          };
        }
        if (file.size_flac !== 0) {
          let size = sizeFormate(file.size_flac);
          types.push({ type: "flac", size });
          _types.flac = {
            size
          };
        }
        if (file.size_hires !== 0) {
          let size = sizeFormate(file.size_hires);
          types.push({ type: "flac24bit", size });
          _types.flac24bit = {
            size
          };
        }
        let albumId = "";
        let albumName = "";
        if (item.album) {
          albumName = item.album.name;
          albumId = item.album.mid;
        }
        list.push({
          singer: formatSingerName(item.singer, "name"),
          // name: item.name + (item.title_extra ?? ''),
          name: item.title,
          albumName,
          albumId,
          source: "tx",
          interval: item.interval ? formatPlayTime(item.interval) : null,
          songId: item.id,
          albumMid: item.album?.mid ?? "",
          strMediaMid: item.file.media_mid,
          songmid: item.mid,
          img: albumId === "" || albumId === "空" ? item.singer?.length ? `https://y.gtimg.cn/music/photo_new/T001R500x500M000${item.singer[0].mid}.jpg` : "" : `https://y.gtimg.cn/music/photo_new/T002R500x500M000${albumId}.jpg`,
          types,
          _types,
          typeUrl: {}
        });
      });
      return list;
    },
    search(str, page = 1, limit) {
      if (limit == null) limit = this.limit;
      return this.musicSearch(str, page, limit).then(({ body, meta }) => {
        let list = this.handleResult(body.song.list);
        this.total = meta.sum;
        this.page = page;
        this.allPage = Math.ceil(this.total / limit);
        return Promise.resolve({
          list,
          allPage: this.allPage,
          limit,
          total: this.total,
          source: "tx"
        });
      });
    }
  };

  // src/musicSdk/tx/songList.js
  var songList_default3 = {
    limit_list: 36,
    limit_song: 1e5,
    successCode: 0,
    sortList: [
      {
        name: "最热",
        tid: "hot",
        id: 5
      },
      {
        name: "最新",
        tid: "new",
        id: 2
      }
    ],
    regExps: {
      hotTagHtml: /class="c_bg_link js_tag_item" data-id="\w+">.+?<\/a>/g,
      hotTag: /data-id="(\w+)">(.+?)<\/a>/,
      // https://y.qq.com/n/yqq/playlist/7217720898.html
      // https://i.y.qq.com/n2/m/share/details/taoge.html?platform=11&appshare=android_qq&appversion=9050006&id=7217720898&ADTAG=qfshare
      listDetailLink: /\/playlist\/(\d+)/,
      listDetailLink2: /id=(\d+)/
    },
    tagsUrl: "https://u.y.qq.com/cgi-bin/musicu.fcg?loginUin=0&hostUin=0&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=wk_v15.json&needNewCode=0&data=%7B%22tags%22%3A%7B%22method%22%3A%22get_all_categories%22%2C%22param%22%3A%7B%22qq%22%3A%22%22%7D%2C%22module%22%3A%22playlist.PlaylistAllCategoriesServer%22%7D%7D",
    hotTagUrl: "https://c.y.qq.com/node/pc/wk_v15/category_playlist.html",
    getListUrl(sortId, id, page) {
      if (id) {
        id = parseInt(id);
        return `https://u.y.qq.com/cgi-bin/musicu.fcg?loginUin=0&hostUin=0&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=wk_v15.json&needNewCode=0&data=${encodeURIComponent(JSON.stringify({
          comm: { cv: 1602, ct: 20 },
          playlist: {
            method: "get_category_content",
            param: {
              titleid: id,
              caller: "0",
              category_id: id,
              size: this.limit_list,
              page: page - 1,
              use_page: 1
            },
            module: "playlist.PlayListCategoryServer"
          }
        }))}`;
      }
      return `https://u.y.qq.com/cgi-bin/musicu.fcg?loginUin=0&hostUin=0&format=json&inCharset=utf-8&outCharset=utf-8&notice=0&platform=wk_v15.json&needNewCode=0&data=${encodeURIComponent(JSON.stringify({
        comm: { cv: 1602, ct: 20 },
        playlist: {
          method: "get_playlist_by_tag",
          param: { id: 1e7, sin: this.limit_list * (page - 1), size: this.limit_list, order: sortId, cur_page: page },
          module: "playlist.PlayListPlazaServer"
        }
      }))}`;
    },
    getListDetailUrl(id) {
      return `https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&json=1&utf8=1&onlysong=0&new_format=1&disstid=${id}&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0`;
    },
    // http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=2849349915&pn=0&rn=100&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.0.5.0_W1&newver=1
    // 获取标签
    getTag(tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const request = httpFetch(this.tagsUrl);
      return request.then(({ body }) => {
        if (body.code !== this.successCode) return this.getTag(++tryNum);
        return this.filterTagInfo(body.tags.data.v_group);
      });
    },
    // 获取标签
    getHotTag(tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const request = httpFetch(this.hotTagUrl);
      return request.then(({ statusCode, body }) => {
        if (statusCode !== 200) return this.getHotTag(++tryNum);
        return this.filterInfoHotTag(body);
      });
    },
    filterInfoHotTag(html) {
      let hotTag = html.match(this.regExps.hotTagHtml);
      const hotTags = [];
      if (!hotTag) return hotTags;
      hotTag.forEach((tagHtml) => {
        let result = tagHtml.match(this.regExps.hotTag);
        if (!result) return;
        hotTags.push({
          id: parseInt(result[1]),
          name: result[2],
          source: "tx"
        });
      });
      return hotTags;
    },
    filterTagInfo(rawList) {
      return rawList.map((type) => ({
        name: type.group_name,
        list: type.v_item.map((item) => ({
          parent_id: type.group_id,
          parent_name: type.group_name,
          id: item.id,
          name: item.name,
          source: "tx"
        }))
      }));
    },
    // 获取列表数据
    getList(sortId, tagId, page, tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const request = httpFetch(
        this.getListUrl(sortId, tagId, page)
      );
      return request.then(({ body }) => {
        if (body.code !== this.successCode) return this.getList(sortId, tagId, page, ++tryNum);
        return tagId ? this.filterList2(body.playlist.data, page) : this.filterList(body.playlist.data, page);
      });
    },
    filterList(data, page) {
      return {
        list: data.v_playlist.map((item) => ({
          play_count: formatPlayCount(item.access_num),
          id: String(item.tid),
          author: item.creator_info.nick,
          name: item.title,
          time: item.modify_time ? dateFormat(item.modify_time * 1e3, "Y-M-D") : "",
          img: item.cover_url_medium,
          // grade: item.favorcnt / 10,
          total: item.song_ids?.length,
          desc: decodeName(item.desc).replace(/<br>/g, "\n"),
          source: "tx"
        })),
        total: data.total,
        page,
        limit: this.limit_list,
        source: "tx"
      };
    },
    filterList2({ content }, page) {
      return {
        list: content.v_item.map(({ basic }) => ({
          play_count: formatPlayCount(basic.play_cnt),
          id: String(basic.tid),
          author: basic.creator.nick,
          name: basic.title,
          // time: basic.publish_time,
          img: basic.cover.medium_url || basic.cover.default_url,
          // grade: basic.favorcnt / 10,
          desc: decodeName(basic.desc).replace(/<br>/g, "\n"),
          source: "tx"
        })),
        total: content.total_cnt,
        page,
        limit: this.limit_list,
        source: "tx"
      };
    },
    async handleParseId(link, retryNum = 0) {
      if (retryNum > 2) return Promise.reject(new Error("link try max num"));
      const requestObj_listDetailLink = httpFetch(link);
      const { url, statusCode } = await requestObj_listDetailLink;
      if (statusCode > 400) return this.handleParseId(link, ++retryNum);
      return url;
    },
    async getListId(id) {
      if (/[?&:/]/.test(id)) {
        if (!this.regExps.listDetailLink.test(id)) {
          id = await this.handleParseId(id);
        }
        let result = this.regExps.listDetailLink.exec(id);
        if (!result) {
          result = this.regExps.listDetailLink2.exec(id);
          if (!result) throw new Error("failed");
        }
        id = result[1];
      }
      return id;
    },
    // 获取歌曲列表内的音乐
    async getListDetail2(id, tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const requestObj_listDetail = httpFetch("https://u.y.qq.com/cgi-bin/musicu.fcg", {
        method: "post",
        headers: {
          Origin: "https://y.qq.com",
          Referer: `https://y.qq.com/n/yqq/playsquare/${id}.html`
        },
        body: {
          comm: {
            cv: 4747474,
            ct: 24,
            format: "json",
            inCharset: "utf-8",
            outCharset: "utf-8",
            platform: "yqq.json",
            needNewCode: 1,
            uin: 0
          },
          req_1: {
            module: "music.srfDissInfo.aiDissInfo",
            method: "uniform_get_Dissinfo",
            param: {
              disstid: parseInt(id),
              userinfo: 1,
              tag: 1,
              orderlist: 1,
              song_begin: 0,
              song_num: this.limit_song,
              onlysonglist: 0,
              enc_host_uin: ""
            }
          }
        }
      });
      const { body } = await requestObj_listDetail;
      if (body.code !== this.successCode) return this.getListDetail2(id, ++tryNum);
      if (body.req_1.code !== this.successCode) throw new Error("failed");
      const result = body.req_1.data;
      const dirinfo = result.dirinfo;
      return {
        list: this.filterListDetail(result.songlist),
        page: 1,
        limit: this.limit_song,
        total: result.total_song_num,
        source: "tx",
        info: {
          name: dirinfo.title,
          img: dirinfo.picurl,
          desc: decodeName(dirinfo.desc ?? "").replace(/<br>/g, "\n"),
          author: dirinfo.host_nick,
          play_count: formatPlayCount(dirinfo.listennum)
        }
      };
    },
    // 获取歌曲列表内的音乐
    async getListDetail(id, tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      id = await this.getListId(id);
      const requestObj_listDetail = httpFetch(this.getListDetailUrl(id), {
        headers: {
          Origin: "https://y.qq.com",
          Referer: `https://y.qq.com/n/yqq/playsquare/${id}.html`
        }
      });
      const { body } = await requestObj_listDetail;
      if (body.code !== this.successCode) return this.getListDetail(id, ++tryNum);
      if (body.subcode !== this.successCode || !body.cdlist) return this.getListDetail2(id);
      const cdlist = body.cdlist[0];
      return {
        list: this.filterListDetail(cdlist.songlist),
        page: 1,
        limit: cdlist.songlist.length + 1,
        total: cdlist.songlist.length,
        source: "tx",
        info: {
          name: cdlist.dissname,
          img: cdlist.logo,
          desc: decodeName(cdlist.desc).replace(/<br>/g, "\n"),
          author: cdlist.nickname,
          play_count: formatPlayCount(cdlist.visitnum)
        }
      };
    },
    filterListDetail(rawList) {
      return rawList.map((item) => {
        let types = [];
        let _types = {};
        if (item.file.size_128mp3 !== 0) {
          let size = sizeFormate(item.file.size_128mp3);
          types.push({ type: "128k", size });
          _types["128k"] = {
            size
          };
        }
        if (item.file.size_320mp3 !== 0) {
          let size = sizeFormate(item.file.size_320mp3);
          types.push({ type: "320k", size });
          _types["320k"] = {
            size
          };
        }
        if (item.file.size_flac !== 0) {
          let size = sizeFormate(item.file.size_flac);
          types.push({ type: "flac", size });
          _types.flac = {
            size
          };
        }
        if (item.file.size_hires !== 0) {
          let size = sizeFormate(item.file.size_hires);
          types.push({ type: "flac24bit", size });
          _types.flac24bit = {
            size
          };
        }
        return {
          singer: formatSingerName(item.singer, "name"),
          name: item.title,
          albumName: item.album.name,
          albumId: item.album.mid,
          source: "tx",
          interval: formatPlayTime(item.interval),
          songId: item.id,
          albumMid: item.album.mid,
          strMediaMid: item.file.media_mid,
          songmid: item.mid,
          img: item.album.name === "" || item.album.name === "空" ? item.singer?.length ? `https://y.gtimg.cn/music/photo_new/T001R500x500M000${item.singer[0].mid}.jpg` : "" : `https://y.gtimg.cn/music/photo_new/T002R500x500M000${item.album.mid}.jpg`,
          lrc: null,
          otherSource: null,
          types,
          _types,
          typeUrl: {}
        };
      });
    },
    getTags() {
      return Promise.all([this.getTag(), this.getHotTag()]).then(([tags, hotTag]) => ({ tags, hotTag, source: "tx" }));
    },
    async getDetailPageUrl(id) {
      id = await this.getListId(id);
      return `https://y.qq.com/n/ryqq/playlist/${id}`;
    },
    search(text, page, limit = 20, retryNum = 0) {
      if (retryNum > 5) throw new Error("max retry");
      return httpFetch(`http://c.y.qq.com/soso/fcgi-bin/client_music_search_songlist?page_no=${page - 1}&num_per_page=${limit}&format=json&query=${encodeURIComponent(text)}&remoteplace=txt.yqq.playlist&inCharset=utf8&outCharset=utf-8`, {
        headers: {
          "User-Agent": "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)",
          Referer: "http://y.qq.com/portal/search.html"
        }
      }).then(({ body }) => {
        if (body.code != 0) return this.search(text, page, limit, ++retryNum);
        return {
          list: body.data.list.map((item) => {
            return {
              play_count: formatPlayCount(item.listennum),
              id: String(item.dissid),
              author: decodeName(item.creator.name),
              name: decodeName(item.dissname),
              time: dateFormat(item.createtime, "Y-M-D"),
              img: item.imgurl,
              // grade: item.favorcnt / 10,
              total: item.song_count,
              desc: decodeName(decodeName(item.introduction)).replace(/<br>/g, "\n"),
              source: "tx"
            };
          }),
          limit,
          total: body.data.sum,
          source: "tx"
        };
      });
    }
  };

  // src/musicSdk/tx/leaderboard.js
  var boardList3 = [{ id: "tx__4", name: "流行指数榜", bangid: "4" }, { id: "tx__26", name: "热歌榜", bangid: "26" }, { id: "tx__27", name: "新歌榜", bangid: "27" }, { id: "tx__62", name: "飙升榜", bangid: "62" }, { id: "tx__58", name: "说唱榜", bangid: "58" }, { id: "tx__57", name: "喜力电音榜", bangid: "57" }, { id: "tx__28", name: "网络歌曲榜", bangid: "28" }, { id: "tx__5", name: "内地榜", bangid: "5" }, { id: "tx__3", name: "欧美榜", bangid: "3" }, { id: "tx__59", name: "香港地区榜", bangid: "59" }, { id: "tx__16", name: "韩国榜", bangid: "16" }, { id: "tx__60", name: "抖快榜", bangid: "60" }, { id: "tx__29", name: "影视金曲榜", bangid: "29" }, { id: "tx__17", name: "日本榜", bangid: "17" }, { id: "tx__52", name: "腾讯音乐人原创榜", bangid: "52" }, { id: "tx__36", name: "K歌金曲榜", bangid: "36" }, { id: "tx__61", name: "台湾地区榜", bangid: "61" }, { id: "tx__63", name: "DJ舞曲榜", bangid: "63" }, { id: "tx__64", name: "综艺新歌榜", bangid: "64" }, { id: "tx__65", name: "国风热歌榜", bangid: "65" }, { id: "tx__67", name: "听歌识曲榜", bangid: "67" }, { id: "tx__72", name: "动漫音乐榜", bangid: "72" }, { id: "tx__73", name: "游戏音乐榜", bangid: "73" }, { id: "tx__75", name: "有声榜", bangid: "75" }, { id: "tx__131", name: "校园音乐人排行榜", bangid: "131" }];
  var leaderboard_default3 = {
    limit: 300,
    list: [
      {
        id: "txlxzsb",
        name: "流行榜",
        bangid: 4
      },
      {
        id: "txrgb",
        name: "热歌榜",
        bangid: 26
      },
      {
        id: "txwlhgb",
        name: "网络榜",
        bangid: 28
      },
      {
        id: "txdyb",
        name: "抖音榜",
        bangid: 60
      },
      {
        id: "txndb",
        name: "内地榜",
        bangid: 5
      },
      {
        id: "txxgb",
        name: "香港榜",
        bangid: 59
      },
      {
        id: "txtwb",
        name: "台湾榜",
        bangid: 61
      },
      {
        id: "txoumb",
        name: "欧美榜",
        bangid: 3
      },
      {
        id: "txhgb",
        name: "韩国榜",
        bangid: 16
      },
      {
        id: "txrbb",
        name: "日本榜",
        bangid: 17
      },
      {
        id: "txtybb",
        name: "YouTube榜",
        bangid: 128
      }
    ],
    listDetailRequest(id, period, limit) {
      return httpFetch("https://u.y.qq.com/cgi-bin/musicu.fcg", {
        method: "post",
        headers: {
          "User-Agent": "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)"
        },
        body: {
          toplist: {
            module: "musicToplist.ToplistInfoServer",
            method: "GetDetail",
            param: {
              topid: id,
              num: limit,
              period
            }
          },
          comm: {
            uin: 0,
            format: "json",
            ct: 20,
            cv: 1859
          }
        }
      });
    },
    regExps: {
      periodList: /<i class="play_cover__btn c_tx_link js_icon_play" data-listkey=".+?" data-listname=".+?" data-tid=".+?" data-date=".+?" .+?<\/i>/g,
      period: /data-listname="(.+?)" data-tid=".*?\/(.+?)" data-date="(.+?)" .+?<\/i>/
    },
    periods: {},
    periodUrl: "https://c.y.qq.com/node/pc/wk_v15/top.html",
    getBoardsData() {
      const request = httpFetch("https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg?g_tk=1928093487&inCharset=utf-8&outCharset=utf-8&notice=0&format=json&uin=0&needNewCode=1&platform=h5");
      return request;
    },
    getData(url) {
      const requestDataObj = httpFetch(url);
      return requestDataObj;
    },
    filterData(rawList) {
      return rawList.map((item) => {
        let types = [];
        let _types = {};
        if (item.file.size_128mp3 !== 0) {
          let size = sizeFormate(item.file.size_128mp3);
          types.push({ type: "128k", size });
          _types["128k"] = {
            size
          };
        }
        if (item.file.size_320mp3 !== 0) {
          let size = sizeFormate(item.file.size_320mp3);
          types.push({ type: "320k", size });
          _types["320k"] = {
            size
          };
        }
        if (item.file.size_flac !== 0) {
          let size = sizeFormate(item.file.size_flac);
          types.push({ type: "flac", size });
          _types.flac = {
            size
          };
        }
        if (item.file.size_hires !== 0) {
          let size = sizeFormate(item.file.size_hires);
          types.push({ type: "flac24bit", size });
          _types.flac24bit = {
            size
          };
        }
        return {
          singer: formatSingerName(item.singer, "name"),
          name: item.title,
          albumName: item.album.name,
          albumId: item.album.mid,
          source: "tx",
          interval: formatPlayTime(item.interval),
          songId: item.id,
          albumMid: item.album.mid,
          strMediaMid: item.file.media_mid,
          songmid: item.mid,
          img: item.album.name === "" || item.album.name === "空" ? item.singer?.length ? `https://y.gtimg.cn/music/photo_new/T001R500x500M000${item.singer[0].mid}.jpg` : "" : `https://y.gtimg.cn/music/photo_new/T002R500x500M000${item.album.mid}.jpg`,
          lrc: null,
          otherSource: null,
          types,
          _types,
          typeUrl: {}
        };
      });
    },
    getPeriods(bangid) {
      return this.getData(this.periodUrl).then(({ body: html }) => {
        let result = html.match(this.regExps.periodList);
        if (!result) return Promise.reject(new Error("get data failed"));
        result.forEach((item) => {
          let result2 = item.match(this.regExps.period);
          if (!result2) return;
          this.periods[result2[2]] = {
            name: result2[1],
            bangid: result2[2],
            period: result2[3]
          };
        });
        const info = this.periods[bangid];
        return info && info.period;
      });
    },
    filterBoardsData(rawList) {
      let list = [];
      for (const board of rawList) {
        if (board.id == 201) continue;
        if (board.topTitle.startsWith("巅峰榜·")) {
          board.topTitle = board.topTitle.substring(4, board.topTitle.length);
        }
        if (!board.topTitle.endsWith("榜")) board.topTitle += "榜";
        list.push({
          id: "tx__" + board.id,
          name: board.topTitle,
          bangid: String(board.id)
        });
      }
      return list;
    },
    async getBoards(retryNum = 0) {
      try {
        const response = await this.getBoardsData();
        if (response && response.statusCode === 200 && response.body?.code === 0 && Array.isArray(response.body.data?.topList)) {
          const list = [];
          for (const board of response.body.data.topList) {
            if (!board.id) continue;
            const name = board.topTitle || board.listName || "";
            if (!name) continue;
            list.push({ id: "tx__" + board.id, name, bangid: String(board.id), img: board.picUrl || null });
          }
          if (list.length) {
            this.list = list;
            return { list, source: "tx" };
          }
        }
      } catch (error) {
      }
      this.list = boardList3;
      return {
        list: boardList3,
        source: "tx"
      };
    },
    getList(bangid, page, limit, retryNum = 0) {
      if (++retryNum > 3) return Promise.reject(new Error("try max num"));
      const pageSize = limit || this.limit;
      bangid = parseInt(bangid);
      let info = this.periods[bangid];
      let p = info ? Promise.resolve(info.period) : this.getPeriods(bangid);
      return p.then((period) => {
        return this.listDetailRequest(bangid, period, pageSize).then((resp) => {
          if (resp.body.code !== 0) return this.getList(bangid, page, pageSize, retryNum);
          const list = this.filterData(resp.body.toplist.data.songInfoList);
          return {
            total: resp.body.toplist.data.songInfoList.length,
            list: limit ? list.slice(0, pageSize) : list,
            limit: pageSize,
            page: 1,
            source: "tx"
          };
        });
      });
    },
    getDetailPageUrl(id) {
      if (typeof id == "string") id = id.replace("tx__", "");
      return `https://y.qq.com/n/ryqq/toplist/${id}`;
    }
  };

  // src/musicSdk/tx/hotSearch.js
  var hotSearch_default3 = {
    async getList(retryNum = 0) {
      if (retryNum > 2) return Promise.reject(new Error("try max num"));
      const _requestObj = httpFetch("https://u.y.qq.com/cgi-bin/musicu.fcg", {
        method: "post",
        body: {
          comm: {
            ct: "19",
            cv: "1803",
            guid: "0",
            patch: "118",
            psrf_access_token_expiresAt: 0,
            psrf_qqaccess_token: "",
            psrf_qqopenid: "",
            psrf_qqunionid: "",
            tmeAppID: "qqmusic",
            tmeLoginType: 0,
            uin: "0",
            wid: "0"
          },
          hotkey: {
            method: "GetHotkeyForQQMusicPC",
            module: "tencent_musicsoso_hotkey.HotkeyService",
            param: {
              search_id: "",
              uin: 0
            }
          }
        },
        headers: {
          Referer: "https://y.qq.com/portal/player.html"
        }
      });
      const { body, statusCode } = await _requestObj;
      if (statusCode != 200 || body.code !== 0) throw new Error("获取热搜词失败");
      return { source: "tx", list: this.filterList(body.hotkey.data.vec_hotkey) };
    },
    filterList(rawList) {
      return rawList.map((item) => item.query);
    }
  };

  // src/musicSdk/tx/tipSearch.js
  var tipSearch_default3 = {
    // regExps: {
    //   relWord: /RELWORD=(.+)/,
    // },
    tipSearch(str) {
      const request = httpFetch(`https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg?is_xml=0&format=json&key=${encodeURIComponent(str)}&loginUin=0&hostUin=0&format=json&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq&needNewCode=0`, {
        headers: {
          Referer: "https://y.qq.com/portal/player.html"
        }
      });
      return request.then(({ statusCode, body }) => {
        if (statusCode != 200 || body.code != 0) return Promise.reject(new Error("请求失败"));
        return body.data;
      });
    },
    handleResult(rawData) {
      return rawData.map((info) => `${info.name} - ${info.singer}`);
    },
    async search(str) {
      return this.tipSearch(str).then((result) => this.handleResult(result.song.itemlist));
    }
  };

  // src/musicSdk/tx/musicInfo.js
  var getSinger = (singers) => {
    let arr = [];
    singers.forEach((singer) => {
      arr.push(singer.name);
    });
    return arr.join("、");
  };
  var musicInfo_default = (songmid) => {
    const requestObj = httpFetch("https://u.y.qq.com/cgi-bin/musicu.fcg", {
      method: "post",
      headers: {
        "User-Agent": "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)"
      },
      body: {
        comm: {
          ct: "19",
          cv: "1859",
          uin: "0"
        },
        req: {
          module: "music.pf_song_detail_svr",
          method: "get_song_detail_yqq",
          param: {
            song_type: 0,
            song_mid: songmid
          }
        }
      }
    });
    return requestObj.then(({ body }) => {
      if (body.code != 0 || body.req.code != 0) return Promise.reject(new Error("获取歌曲信息失败"));
      const item = body.req.data.track_info;
      if (!item.file?.media_mid) return null;
      let types = [];
      let _types = {};
      const file = item.file;
      if (file.size_128mp3 != 0) {
        let size = sizeFormate(file.size_128mp3);
        types.push({ type: "128k", size });
        _types["128k"] = {
          size
        };
      }
      if (file.size_320mp3 !== 0) {
        let size = sizeFormate(file.size_320mp3);
        types.push({ type: "320k", size });
        _types["320k"] = {
          size
        };
      }
      if (file.size_flac !== 0) {
        let size = sizeFormate(file.size_flac);
        types.push({ type: "flac", size });
        _types.flac = {
          size
        };
      }
      if (file.size_hires !== 0) {
        let size = sizeFormate(file.size_hires);
        types.push({ type: "flac24bit", size });
        _types.flac24bit = {
          size
        };
      }
      let albumId = "";
      let albumName = "";
      if (item.album) {
        albumName = item.album.name;
        albumId = item.album.mid;
      }
      return {
        singer: getSinger(item.singer),
        name: item.title,
        albumName,
        albumId,
        source: "tx",
        interval: formatPlayTime(item.interval),
        songId: item.id,
        albumMid: item.album?.mid ?? "",
        strMediaMid: item.file.media_mid,
        songmid: item.mid,
        img: albumId === "" || albumId === "空" ? item.singer?.length ? `https://y.gtimg.cn/music/photo_new/T001R500x500M000${item.singer[0].mid}.jpg` : "" : `https://y.gtimg.cn/music/photo_new/T002R500x500M000${albumId}.jpg`,
        types,
        _types,
        typeUrl: {}
      };
    });
  };

  // src/musicSdk/tx/qrcDecode.js
  var DES_ENCRYPT = 1;
  var DES_DECRYPT = 0;
  var sbox = [
    [
      14,
      4,
      13,
      1,
      2,
      15,
      11,
      8,
      3,
      10,
      6,
      12,
      5,
      9,
      0,
      7,
      0,
      15,
      7,
      4,
      14,
      2,
      13,
      1,
      10,
      6,
      12,
      11,
      9,
      5,
      3,
      8,
      4,
      1,
      14,
      8,
      13,
      6,
      2,
      11,
      15,
      12,
      9,
      7,
      3,
      10,
      5,
      0,
      15,
      12,
      8,
      2,
      4,
      9,
      1,
      7,
      5,
      11,
      3,
      14,
      10,
      0,
      6,
      13
    ],
    [
      15,
      1,
      8,
      14,
      6,
      11,
      3,
      4,
      9,
      7,
      2,
      13,
      12,
      0,
      5,
      10,
      3,
      13,
      4,
      7,
      15,
      2,
      8,
      15,
      12,
      0,
      1,
      10,
      6,
      9,
      11,
      5,
      0,
      14,
      7,
      11,
      10,
      4,
      13,
      1,
      5,
      8,
      12,
      6,
      9,
      3,
      2,
      15,
      13,
      8,
      10,
      1,
      3,
      15,
      4,
      2,
      11,
      6,
      7,
      12,
      0,
      5,
      14,
      9
    ],
    [
      10,
      0,
      9,
      14,
      6,
      3,
      15,
      5,
      1,
      13,
      12,
      7,
      11,
      4,
      2,
      8,
      13,
      7,
      0,
      9,
      3,
      4,
      6,
      10,
      2,
      8,
      5,
      14,
      12,
      11,
      15,
      1,
      13,
      6,
      4,
      9,
      8,
      15,
      3,
      0,
      11,
      1,
      2,
      12,
      5,
      10,
      14,
      7,
      1,
      10,
      13,
      0,
      6,
      9,
      8,
      7,
      4,
      15,
      14,
      3,
      11,
      5,
      2,
      12
    ],
    [
      7,
      13,
      14,
      3,
      0,
      6,
      9,
      10,
      1,
      2,
      8,
      5,
      11,
      12,
      4,
      15,
      13,
      8,
      11,
      5,
      6,
      15,
      0,
      3,
      4,
      7,
      2,
      12,
      1,
      10,
      14,
      9,
      10,
      6,
      9,
      0,
      12,
      11,
      7,
      13,
      15,
      1,
      3,
      14,
      5,
      2,
      8,
      4,
      3,
      15,
      0,
      6,
      10,
      10,
      13,
      8,
      9,
      4,
      5,
      11,
      12,
      7,
      2,
      14
    ],
    [
      2,
      12,
      4,
      1,
      7,
      10,
      11,
      6,
      8,
      5,
      3,
      15,
      13,
      0,
      14,
      9,
      14,
      11,
      2,
      12,
      4,
      7,
      13,
      1,
      5,
      0,
      15,
      10,
      3,
      9,
      8,
      6,
      4,
      2,
      1,
      11,
      10,
      13,
      7,
      8,
      15,
      9,
      12,
      5,
      6,
      3,
      0,
      14,
      11,
      8,
      12,
      7,
      1,
      14,
      2,
      13,
      6,
      15,
      0,
      9,
      10,
      4,
      5,
      3
    ],
    [
      12,
      1,
      10,
      15,
      9,
      2,
      6,
      8,
      0,
      13,
      3,
      4,
      14,
      7,
      5,
      11,
      10,
      15,
      4,
      2,
      7,
      12,
      9,
      5,
      6,
      1,
      13,
      14,
      0,
      11,
      3,
      8,
      9,
      14,
      15,
      5,
      2,
      8,
      12,
      3,
      7,
      0,
      4,
      10,
      1,
      13,
      11,
      6,
      4,
      3,
      2,
      12,
      9,
      5,
      15,
      10,
      11,
      14,
      1,
      7,
      6,
      0,
      8,
      13
    ],
    [
      4,
      11,
      2,
      14,
      15,
      0,
      8,
      13,
      3,
      12,
      9,
      7,
      5,
      10,
      6,
      1,
      13,
      0,
      11,
      7,
      4,
      9,
      1,
      10,
      14,
      3,
      5,
      12,
      2,
      15,
      8,
      6,
      1,
      4,
      11,
      13,
      12,
      3,
      7,
      14,
      10,
      15,
      6,
      8,
      0,
      5,
      9,
      2,
      6,
      11,
      13,
      8,
      1,
      4,
      10,
      7,
      9,
      5,
      0,
      15,
      14,
      2,
      3,
      12
    ],
    [
      13,
      2,
      8,
      4,
      6,
      15,
      11,
      1,
      10,
      9,
      3,
      14,
      5,
      0,
      12,
      7,
      1,
      15,
      13,
      8,
      10,
      3,
      7,
      4,
      12,
      5,
      6,
      11,
      0,
      14,
      9,
      2,
      7,
      11,
      4,
      1,
      9,
      12,
      14,
      2,
      0,
      6,
      10,
      13,
      15,
      3,
      5,
      8,
      2,
      1,
      14,
      7,
      4,
      10,
      8,
      13,
      15,
      12,
      9,
      0,
      3,
      5,
      6,
      11
    ]
  ];
  var key_rnd_shift = [1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1];
  var key_perm_c = [
    56,
    48,
    40,
    32,
    24,
    16,
    8,
    0,
    57,
    49,
    41,
    33,
    25,
    17,
    9,
    1,
    58,
    50,
    42,
    34,
    26,
    18,
    10,
    2,
    59,
    51,
    43,
    35
  ];
  var key_perm_d = [
    62,
    54,
    46,
    38,
    30,
    22,
    14,
    6,
    61,
    53,
    45,
    37,
    29,
    21,
    13,
    5,
    60,
    52,
    44,
    36,
    28,
    20,
    12,
    4,
    27,
    19,
    11,
    3
  ];
  var key_compression = [
    13,
    16,
    10,
    23,
    0,
    4,
    2,
    27,
    14,
    5,
    20,
    9,
    22,
    18,
    11,
    3,
    25,
    7,
    15,
    6,
    26,
    19,
    12,
    1,
    40,
    51,
    30,
    36,
    46,
    54,
    29,
    39,
    50,
    44,
    32,
    47,
    43,
    48,
    38,
    55,
    33,
    52,
    45,
    41,
    49,
    35,
    28,
    31
  ];
  var bitnum = (a, b, c) => (a[(b / 32 | 0) * 4 + 3 - (b % 32 / 8 | 0)] >>> 7 - b % 8 & 1) << c >>> 0;
  var bitnum_intr = (a, b, c) => (a >>> 31 - b & 1) << c >>> 0;
  var bitnum_intl = (a, b, c) => (a << b >>> 0 & 2147483648) >>> 0 >>> c >>> 0;
  var sbox_bit = (a) => a & 32 | (a & 31) >>> 1 | (a & 1) << 4;
  var initial_permutation = (input) => {
    const s0 = (bitnum(input, 57, 31) | bitnum(input, 49, 30) | bitnum(input, 41, 29) | bitnum(input, 33, 28) | bitnum(input, 25, 27) | bitnum(input, 17, 26) | bitnum(input, 9, 25) | bitnum(input, 1, 24) | bitnum(input, 59, 23) | bitnum(input, 51, 22) | bitnum(input, 43, 21) | bitnum(input, 35, 20) | bitnum(input, 27, 19) | bitnum(input, 19, 18) | bitnum(input, 11, 17) | bitnum(input, 3, 16) | bitnum(input, 61, 15) | bitnum(input, 53, 14) | bitnum(input, 45, 13) | bitnum(input, 37, 12) | bitnum(input, 29, 11) | bitnum(input, 21, 10) | bitnum(input, 13, 9) | bitnum(input, 5, 8) | bitnum(input, 63, 7) | bitnum(input, 55, 6) | bitnum(input, 47, 5) | bitnum(input, 39, 4) | bitnum(input, 31, 3) | bitnum(input, 23, 2) | bitnum(input, 15, 1) | bitnum(input, 7, 0)) >>> 0;
    const s1 = (bitnum(input, 56, 31) | bitnum(input, 48, 30) | bitnum(input, 40, 29) | bitnum(input, 32, 28) | bitnum(input, 24, 27) | bitnum(input, 16, 26) | bitnum(input, 8, 25) | bitnum(input, 0, 24) | bitnum(input, 58, 23) | bitnum(input, 50, 22) | bitnum(input, 42, 21) | bitnum(input, 34, 20) | bitnum(input, 26, 19) | bitnum(input, 18, 18) | bitnum(input, 10, 17) | bitnum(input, 2, 16) | bitnum(input, 60, 15) | bitnum(input, 52, 14) | bitnum(input, 44, 13) | bitnum(input, 36, 12) | bitnum(input, 28, 11) | bitnum(input, 20, 10) | bitnum(input, 12, 9) | bitnum(input, 4, 8) | bitnum(input, 62, 7) | bitnum(input, 54, 6) | bitnum(input, 46, 5) | bitnum(input, 38, 4) | bitnum(input, 30, 3) | bitnum(input, 22, 2) | bitnum(input, 14, 1) | bitnum(input, 6, 0)) >>> 0;
    return [s0, s1];
  };
  var inverse_permutation = (s0, s1, out) => {
    out[3] = (bitnum_intr(s1, 7, 7) | bitnum_intr(s0, 7, 6) | bitnum_intr(s1, 15, 5) | bitnum_intr(s0, 15, 4) | bitnum_intr(s1, 23, 3) | bitnum_intr(s0, 23, 2) | bitnum_intr(s1, 31, 1) | bitnum_intr(s0, 31, 0)) & 255;
    out[2] = (bitnum_intr(s1, 6, 7) | bitnum_intr(s0, 6, 6) | bitnum_intr(s1, 14, 5) | bitnum_intr(s0, 14, 4) | bitnum_intr(s1, 22, 3) | bitnum_intr(s0, 22, 2) | bitnum_intr(s1, 30, 1) | bitnum_intr(s0, 30, 0)) & 255;
    out[1] = (bitnum_intr(s1, 5, 7) | bitnum_intr(s0, 5, 6) | bitnum_intr(s1, 13, 5) | bitnum_intr(s0, 13, 4) | bitnum_intr(s1, 21, 3) | bitnum_intr(s0, 21, 2) | bitnum_intr(s1, 29, 1) | bitnum_intr(s0, 29, 0)) & 255;
    out[0] = (bitnum_intr(s1, 4, 7) | bitnum_intr(s0, 4, 6) | bitnum_intr(s1, 12, 5) | bitnum_intr(s0, 12, 4) | bitnum_intr(s1, 20, 3) | bitnum_intr(s0, 20, 2) | bitnum_intr(s1, 28, 1) | bitnum_intr(s0, 28, 0)) & 255;
    out[7] = (bitnum_intr(s1, 3, 7) | bitnum_intr(s0, 3, 6) | bitnum_intr(s1, 11, 5) | bitnum_intr(s0, 11, 4) | bitnum_intr(s1, 19, 3) | bitnum_intr(s0, 19, 2) | bitnum_intr(s1, 27, 1) | bitnum_intr(s0, 27, 0)) & 255;
    out[6] = (bitnum_intr(s1, 2, 7) | bitnum_intr(s0, 2, 6) | bitnum_intr(s1, 10, 5) | bitnum_intr(s0, 10, 4) | bitnum_intr(s1, 18, 3) | bitnum_intr(s0, 18, 2) | bitnum_intr(s1, 26, 1) | bitnum_intr(s0, 26, 0)) & 255;
    out[5] = (bitnum_intr(s1, 1, 7) | bitnum_intr(s0, 1, 6) | bitnum_intr(s1, 9, 5) | bitnum_intr(s0, 9, 4) | bitnum_intr(s1, 17, 3) | bitnum_intr(s0, 17, 2) | bitnum_intr(s1, 25, 1) | bitnum_intr(s0, 25, 0)) & 255;
    out[4] = (bitnum_intr(s1, 0, 7) | bitnum_intr(s0, 0, 6) | bitnum_intr(s1, 8, 5) | bitnum_intr(s0, 8, 4) | bitnum_intr(s1, 16, 3) | bitnum_intr(s0, 16, 2) | bitnum_intr(s1, 24, 1) | bitnum_intr(s0, 24, 0)) & 255;
  };
  var des_f = (state, key) => {
    const t1 = (bitnum_intl(state, 31, 0) | (state & 4026531840) >>> 0 >>> 1 | bitnum_intl(state, 4, 5) | bitnum_intl(state, 3, 6) | (state & 251658240) >>> 0 >>> 3 | bitnum_intl(state, 8, 11) | bitnum_intl(state, 7, 12) | (state & 15728640) >>> 0 >>> 5 | bitnum_intl(state, 12, 17) | bitnum_intl(state, 11, 18) | (state & 983040) >>> 0 >>> 7 | bitnum_intl(state, 16, 23)) >>> 0;
    const t2 = (bitnum_intl(state, 15, 0) | (state & 61440) << 15 >>> 0 | bitnum_intl(state, 20, 5) | bitnum_intl(state, 19, 6) | (state & 3840) << 13 >>> 0 | bitnum_intl(state, 24, 11) | bitnum_intl(state, 23, 12) | (state & 240) << 11 >>> 0 | bitnum_intl(state, 28, 17) | bitnum_intl(state, 27, 18) | (state & 15) << 9 >>> 0 | bitnum_intl(state, 0, 23)) >>> 0;
    const lrgstate = [
      t1 >>> 24 & 255,
      t1 >>> 16 & 255,
      t1 >>> 8 & 255,
      t2 >>> 24 & 255,
      t2 >>> 16 & 255,
      t2 >>> 8 & 255
    ];
    for (let i = 0; i < 6; i++) lrgstate[i] ^= key[i];
    let s = (sbox[0][sbox_bit(lrgstate[0] >>> 2)] << 28 | sbox[1][sbox_bit((lrgstate[0] & 3) << 4 | lrgstate[1] >>> 4)] << 24 | sbox[2][sbox_bit((lrgstate[1] & 15) << 2 | lrgstate[2] >>> 6)] << 20 | sbox[3][sbox_bit(lrgstate[2] & 63)] << 16 | sbox[4][sbox_bit(lrgstate[3] >>> 2)] << 12 | sbox[5][sbox_bit((lrgstate[3] & 3) << 4 | lrgstate[4] >>> 4)] << 8 | sbox[6][sbox_bit((lrgstate[4] & 15) << 2 | lrgstate[5] >>> 6)] << 4 | sbox[7][sbox_bit(lrgstate[5] & 63)]) >>> 0;
    return (bitnum_intl(s, 15, 0) | bitnum_intl(s, 6, 1) | bitnum_intl(s, 19, 2) | bitnum_intl(s, 20, 3) | bitnum_intl(s, 28, 4) | bitnum_intl(s, 11, 5) | bitnum_intl(s, 27, 6) | bitnum_intl(s, 16, 7) | bitnum_intl(s, 0, 8) | bitnum_intl(s, 14, 9) | bitnum_intl(s, 22, 10) | bitnum_intl(s, 25, 11) | bitnum_intl(s, 4, 12) | bitnum_intl(s, 17, 13) | bitnum_intl(s, 30, 14) | bitnum_intl(s, 9, 15) | bitnum_intl(s, 1, 16) | bitnum_intl(s, 7, 17) | bitnum_intl(s, 23, 18) | bitnum_intl(s, 13, 19) | bitnum_intl(s, 31, 20) | bitnum_intl(s, 26, 21) | bitnum_intl(s, 2, 22) | bitnum_intl(s, 8, 23) | bitnum_intl(s, 18, 24) | bitnum_intl(s, 12, 25) | bitnum_intl(s, 29, 26) | bitnum_intl(s, 5, 27) | bitnum_intl(s, 21, 28) | bitnum_intl(s, 10, 29) | bitnum_intl(s, 3, 30) | bitnum_intl(s, 24, 31)) >>> 0;
  };
  var des_crypt = (input, schedule, output) => {
    let [s0, s1] = initial_permutation(input);
    for (let i = 0; i < 15; i++) {
      const prev = s1;
      s1 = (des_f(s1, schedule[i]) ^ s0) >>> 0;
      s0 = prev;
    }
    s0 = (des_f(s1, schedule[15]) ^ s0) >>> 0;
    inverse_permutation(s0, s1, output);
  };
  var key_schedule = (key, mode) => {
    const schedule = Array.from({ length: 16 }, () => new Uint8Array(6));
    let c = 0;
    let d = 0;
    for (let i = 0; i < 28; i++) {
      c = (c | bitnum(key, key_perm_c[i], 31 - i)) >>> 0;
      d = (d | bitnum(key, key_perm_d[i], 31 - i)) >>> 0;
    }
    for (let i = 0; i < 16; i++) {
      c = ((c << key_rnd_shift[i] >>> 0 | c >>> 28 - key_rnd_shift[i]) & 4294967280) >>> 0;
      d = ((d << key_rnd_shift[i] >>> 0 | d >>> 28 - key_rnd_shift[i]) & 4294967280) >>> 0;
      const togen = mode === DES_DECRYPT ? 15 - i : i;
      for (let j = 0; j < 24; j++) {
        schedule[togen][j / 8 | 0] |= bitnum_intr(c, key_compression[j], 7 - j % 8);
      }
      for (let j = 24; j < 48; j++) {
        schedule[togen][j / 8 | 0] |= bitnum_intr(d, key_compression[j] - 27, 7 - j % 8);
      }
    }
    return schedule;
  };
  var tripledes_key_setup = (key, mode) => {
    if (mode === DES_ENCRYPT) {
      return [
        key_schedule(key.subarray(0, 8), DES_ENCRYPT),
        key_schedule(key.subarray(8, 16), DES_DECRYPT),
        key_schedule(key.subarray(16, 24), DES_ENCRYPT)
      ];
    }
    return [
      key_schedule(key.subarray(16, 24), DES_DECRYPT),
      key_schedule(key.subarray(8, 16), DES_ENCRYPT),
      key_schedule(key.subarray(0, 8), DES_DECRYPT)
    ];
  };
  var tripledes_crypt = (input, schedule, output) => {
    const buf = new Uint8Array(8);
    des_crypt(input, schedule[0], buf);
    des_crypt(buf, schedule[1], output);
    des_crypt(output, schedule[2], buf);
    output.set(buf);
  };
  var QRC_KEY = Buffer.from([
    33,
    64,
    35,
    41,
    40,
    42,
    36,
    37,
    49,
    50,
    51,
    90,
    88,
    67,
    33,
    64,
    33,
    64,
    35,
    41,
    40,
    78,
    72,
    76
  ]);
  var handleInflate2 = (data) => new Promise((resolve, reject) => {
    resolve(inflate_1(data, { finishFlush: constants_1.Z_SYNC_FLUSH }));
  });
  var decodeQrc = async (hexData) => {
    if (!hexData || hexData.length % 2 !== 0) return "";
    const encrypted = Buffer.from(hexData, "hex");
    if (encrypted.length === 0) return "";
    const schedule = tripledes_key_setup(QRC_KEY, DES_DECRYPT);
    const block = new Uint8Array(8);
    for (let i = 0; i + 8 <= encrypted.length; i += 8) {
      tripledes_crypt(encrypted.subarray(i, i + 8), schedule, block);
      encrypted.set(block, i);
    }
    try {
      const result = await handleInflate2(encrypted);
      return Buffer.from(result).toString("utf-8");
    } catch {
      return "";
    }
  };

  // src/musicSdk/tx/lyric.js
  var songIdMap = /* @__PURE__ */ new Map();
  var promises = /* @__PURE__ */ new Map();
  var decodeLyric3 = async (lrc, tlrc, rlrc) => ({
    lyric: await decodeQrc(lrc),
    tlyric: await decodeQrc(tlrc),
    rlyric: await decodeQrc(rlrc)
  });
  var parseTools = {
    rxps: {
      info: /^{"/,
      lineTime: /^\[(\d+),\d+\]/,
      lineTime2: /^\[([\d:.]+)\]/,
      wordTime: /\(\d+,\d+\)/,
      wordTimeAll: /(\(\d+,\d+\))/g,
      timeLabelFixRxp: /(?:\.0+|0+)$/
    },
    msFormat(timeMs) {
      if (Number.isNaN(timeMs)) return "";
      let ms = timeMs % 1e3;
      timeMs /= 1e3;
      let m = parseInt(timeMs / 60).toString().padStart(2, "0");
      timeMs %= 60;
      let s = parseInt(timeMs).toString().padStart(2, "0");
      return `[${m}:${s}.${String(ms).padStart(3, "0")}]`;
    },
    parseLyric(lrc) {
      lrc = lrc.trim();
      lrc = lrc.replace(/\r/g, "");
      if (!lrc) return { lyric: "", lxlyric: "" };
      const lines = lrc.split("\n");
      const lxlrcLines = [];
      const lrcLines = [];
      for (let line of lines) {
        line = line.trim();
        let result = this.rxps.lineTime.exec(line);
        if (!result) {
          if (line.startsWith("[offset")) {
            lxlrcLines.push(line);
            lrcLines.push(line);
          }
          if (this.rxps.lineTime2.test(line)) {
            lrcLines.push(line);
          }
          continue;
        }
        const startMsTime = parseInt(result[1]);
        const startTimeStr = this.msFormat(startMsTime);
        if (!startTimeStr) continue;
        let words = line.replace(this.rxps.lineTime, "");
        lrcLines.push(`${startTimeStr}${words.replace(this.rxps.wordTimeAll, "")}`);
        let times = words.match(this.rxps.wordTimeAll);
        if (!times) continue;
        times = times.map((time) => {
          const result2 = /\((\d+),(\d+)\)/.exec(time);
          return `<${Math.max(parseInt(result2[1]) - startMsTime, 0)},${result2[2]}>`;
        });
        const wordArr = words.split(this.rxps.wordTime);
        const newWords = times.map((time, index) => `${time}${wordArr[index]}`).join("");
        lxlrcLines.push(`${startTimeStr}${newWords}`);
      }
      return {
        lyric: lrcLines.join("\n"),
        lxlyric: lxlrcLines.join("\n")
      };
    },
    parseRlyric(lrc) {
      lrc = lrc.trim();
      lrc = lrc.replace(/\r/g, "");
      if (!lrc) return { lyric: "", lxlyric: "" };
      const lines = lrc.split("\n");
      const lrcLines = [];
      for (let line of lines) {
        line = line.trim();
        let result = this.rxps.lineTime.exec(line);
        if (!result) continue;
        const startMsTime = parseInt(result[1]);
        const startTimeStr = this.msFormat(startMsTime);
        if (!startTimeStr) continue;
        let words = line.replace(this.rxps.lineTime, "");
        lrcLines.push(`${startTimeStr}${words.replace(this.rxps.wordTimeAll, "")}`);
      }
      return lrcLines.join("\n");
    },
    removeTag(str) {
      return str.replace(/^[\S\s]*?LyricContent="/, "").replace(/"\/>[\S\s]*?$/, "");
    },
    getIntv(interval) {
      if (!interval) return 0;
      if (!interval.includes(".")) interval += ".0";
      let arr = interval.split(/:|\./);
      while (arr.length < 3) arr.unshift("0");
      const [m, s, ms] = arr;
      return parseInt(m) * 36e5 + parseInt(s) * 1e3 + parseInt(ms);
    },
    fixRlrcTimeTag(rlrc, lrc) {
      const rlrcLines = rlrc.split("\n");
      let lrcLines = lrc.split("\n");
      let newLrc = [];
      rlrcLines.forEach((line) => {
        const result = this.rxps.lineTime2.exec(line);
        if (!result) return;
        const words = line.replace(this.rxps.lineTime2, "");
        if (!words.trim()) return;
        const t1 = this.getIntv(result[1]);
        while (lrcLines.length) {
          const lrcLine = lrcLines.shift();
          const lrcLineResult = this.rxps.lineTime2.exec(lrcLine);
          if (!lrcLineResult) continue;
          const t2 = this.getIntv(lrcLineResult[1]);
          if (Math.abs(t1 - t2) < 100) {
            newLrc.push(line.replace(this.rxps.lineTime2, lrcLineResult[0]));
            break;
          }
        }
      });
      return newLrc.join("\n");
    },
    fixTlrcTimeTag(tlrc, lrc) {
      const tlrcLines = tlrc.split("\n");
      let lrcLines = lrc.split("\n");
      let newLrc = [];
      tlrcLines.forEach((line) => {
        const result = this.rxps.lineTime2.exec(line);
        if (!result) return;
        const words = line.replace(this.rxps.lineTime2, "");
        if (!words.trim()) return;
        let time = result[1];
        if (time.includes(".")) {
          time += "".padStart(3 - time.split(".")[1].length, "0");
        }
        const t1 = this.getIntv(time);
        while (lrcLines.length) {
          const lrcLine = lrcLines.shift();
          const lrcLineResult = this.rxps.lineTime2.exec(lrcLine);
          if (!lrcLineResult) continue;
          const t2 = this.getIntv(lrcLineResult[1]);
          if (Math.abs(t1 - t2) < 100) {
            newLrc.push(line.replace(this.rxps.lineTime2, lrcLineResult[0]));
            break;
          }
        }
      });
      return newLrc.join("\n");
    },
    parse(lrc, tlrc, rlrc) {
      const info = {
        lyric: "",
        tlyric: "",
        rlyric: "",
        lxlyric: ""
      };
      if (lrc) {
        let { lyric, lxlyric } = this.parseLyric(this.removeTag(lrc));
        info.lyric = lyric;
        info.lxlyric = lxlyric;
      }
      if (rlrc) info.rlyric = this.fixRlrcTimeTag(this.parseRlyric(this.removeTag(rlrc)), info.lyric);
      if (tlrc) info.tlyric = this.fixTlrcTimeTag(tlrc, info.lyric);
      return info;
    }
  };
  var lyric_default3 = {
    successCode: 0,
    async getSongId({ songId, songmid }) {
      if (songId) return songId;
      if (songIdMap.has(songmid)) return songIdMap.get(songmid);
      if (promises.has(songmid)) return (await promises.get(songmid)).songId;
      const promise = musicInfo_default(songmid);
      promises.set(promise);
      const info = await promise;
      songIdMap.set(songmid, info.songId);
      promises.delete(songmid);
      return info.songId;
    },
    async parseLyric(lrc, tlrc, rlrc) {
      const { lyric, tlyric, rlyric } = await decodeLyric3(lrc, tlrc, rlrc);
      return parseTools.parse(lyric, tlyric, rlyric);
    },
    getLyric(mInfo, retryNum = 0) {
      if (retryNum > 3) return Promise.reject(new Error("Get lyric failed"));
      return this.getSongId(mInfo).then((songId) => httpFetch("https://u.y.qq.com/cgi-bin/musicu.fcg", {
        method: "post",
        headers: {
          referer: "https://y.qq.com",
          "user-agent": "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36"
        },
        body: {
          comm: {
            ct: "19",
            cv: "1859",
            uin: "0"
          },
          req: {
            method: "GetPlayLyricInfo",
            module: "music.musichallSong.PlayLyricInfo",
            param: {
              format: "json",
              crypt: 1,
              ct: 19,
              cv: 1873,
              interval: 0,
              lrc_t: 0,
              qrc: 1,
              qrc_t: 0,
              roma: 1,
              roma_t: 0,
              songID: songId,
              trans: 1,
              trans_t: 0,
              type: -1
            }
          }
        }
      }).then(({ body }) => {
        if (body.code != this.successCode || body.req.code != this.successCode) return this.getLyric(songId, retryNum + 1);
        const data = body.req.data;
        return this.parseLyric(data.lyric, data.trans, data.roma);
      }));
    }
  };

  // compat/quick-base64.js
  var import_buffer7 = __toESM(require_buffer(), 1);
  var btoa = (text) => import_buffer7.Buffer.from(String(text), "utf8").toString("base64");

  // src/musicSdk/wy/utils/crypto.js
  var iv = btoa("0102030405060708");
  var presetKey = btoa("0CoJUm6Qyw8W8jud");
  var linuxapiKey = btoa("rFgB&h#%2?^eDg:Q");
  var publicKey = "-----BEGIN PUBLIC KEY-----\nMIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB\n-----END PUBLIC KEY-----";
  var eapiKey = btoa("e82ckenh8dichen8");
  var aesEncrypt = (b64, mode, key, iv2) => {
    return aesEncryptSync(b64, key, iv2, mode);
  };
  var rsaEncrypt = (buffer, key) => {
    buffer = Buffer.concat([Buffer.alloc(128 - buffer.length), buffer]);
    return Buffer.from(rsaEncryptSync(buffer.toString("base64"), key, RSA_PADDING.NoPadding), "base64");
  };
  var weapi = (object) => {
    const text = JSON.stringify(object);
    let secretKey = String(Math.random()).substring(2, 18);
    while (secretKey.length < 16) secretKey += Math.floor(Math.random() * 10).toString();
    secretKey = secretKey.slice(0, 16);
    return {
      params: aesEncrypt(btoa(aesEncrypt(Buffer.from(text).toString("base64"), AES_MODE.CBC_128_PKCS7Padding, presetKey, iv)), AES_MODE.CBC_128_PKCS7Padding, btoa(secretKey), iv),
      encSecKey: rsaEncrypt(Buffer.from(secretKey).reverse(), publicKey).toString("hex")
    };
  };
  var linuxapi = (object) => {
    const text = JSON.stringify(object);
    return {
      eparams: Buffer.from(aesEncrypt(Buffer.from(text).toString("base64"), AES_MODE.ECB_128_NoPadding, linuxapiKey, ""), "base64").toString("hex").toUpperCase()
    };
  };
  var eapi = (url, object) => {
    const text = typeof object === "object" ? JSON.stringify(object) : object;
    const message = `nobody${url}use${text}md5forencrypt`;
    const digest = toMD5(message);
    const data = `${url}-36cd479b6b5-${text}-36cd479b6b5-${digest}`;
    return {
      params: Buffer.from(aesEncrypt(Buffer.from(data).toString("base64"), AES_MODE.ECB_128_NoPadding, eapiKey, ""), "base64").toString("hex").toUpperCase()
    };
  };

  // src/musicSdk/wy/utils/index.js
  var eapiRequest = (url, data) => {
    return httpFetch("http://interface.music.163.com/eapi/batch", {
      method: "post",
      headers: {
        "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36",
        origin: "https://music.163.com"
        // cookie: 'os=pc; deviceId=A9C064BB4584D038B1565B58CB05F95290998EE8B025AA2D07AE; osver=Microsoft-Windows-10-Home-China-build-19043-64bit; appver=2.5.2.197409; channel=netease; MUSIC_A=37a11f2eb9de9930cad479b2ad495b0e4c982367fb6f909d9a3f18f876c6b49faddb3081250c4980dd7e19d4bd9bf384e004602712cf2b2b8efaafaab164268a00b47359f85f22705cc95cb6180f3aee40f5be1ebf3148d888aa2d90636647d0c3061cd18d77b7a0; __csrf=05b50d54082694f945d7de75c210ef94; mode=Z7M-KP5(7)GZ; NMTID=00OZLp2VVgq9QdwokUgq3XNfOddQyIAAAF_6i8eJg; ntes_kaola_ad=1',
      },
      form: eapi(url, data)
    });
  };

  // src/musicSdk/wy/musicSearch.js
  var musicSearch_default4 = {
    limit: 30,
    total: 0,
    page: 0,
    allPage: 1,
    musicSearch(str, page, limit) {
      const searchRequest = eapiRequest("/api/search/song/list/page", {
        keyword: str,
        needCorrect: "1",
        channel: "typing",
        offset: limit * (page - 1),
        scene: "normal",
        total: page == 1,
        limit
      });
      return searchRequest.then(({ body }) => body);
    },
    getSinger(singers) {
      let arr = [];
      singers.forEach((singer) => {
        arr.push(singer.name);
      });
      return arr.join("、");
    },
    handleResult(rawList) {
      if (!rawList) return [];
      return rawList.map((item) => {
        item = item.baseInfo.simpleSongData;
        const types = [];
        const _types = {};
        let size;
        if (item.privilege.maxBrLevel == "hires") {
          size = item.hr ? sizeFormate(item.hr.size) : null;
          types.push({ type: "flac24bit", size });
          _types.flac24bit = {
            size
          };
        }
        switch (item.privilege.maxbr) {
          case 999e3:
            size = item.sq ? sizeFormate(item.sq.size) : null;
            types.push({ type: "flac", size });
            _types.flac = {
              size
            };
          case 32e4:
            size = item.h ? sizeFormate(item.h.size) : null;
            types.push({ type: "320k", size });
            _types["320k"] = {
              size
            };
          case 192e3:
          case 128e3:
            size = item.l ? sizeFormate(item.l.size) : null;
            types.push({ type: "128k", size });
            _types["128k"] = {
              size
            };
        }
        types.reverse();
        return {
          singer: this.getSinger(item.ar),
          name: item.name,
          albumName: item.al.name,
          albumId: item.al.id,
          source: "wy",
          interval: formatPlayTime(item.dt / 1e3),
          songmid: item.id,
          img: item.al.picUrl,
          lrc: null,
          types,
          _types,
          typeUrl: {}
        };
      });
    },
    search(str, page = 1, limit, retryNum = 0) {
      if (++retryNum > 3) return Promise.reject(new Error("try max num"));
      if (limit == null) limit = this.limit;
      return this.musicSearch(str, page, limit).then((result) => {
        if (!result || result.code !== 200) return this.search(str, page, limit, retryNum);
        let list = this.handleResult(result.data.resources || []);
        if (list == null) return this.search(str, page, limit, retryNum);
        this.total = result.data.totalCount || 0;
        this.page = page;
        this.allPage = Math.ceil(this.total / this.limit);
        return {
          list,
          allPage: this.allPage,
          limit: this.limit,
          total: this.total,
          source: "wy"
        };
      });
    }
  };

  // src/musicSdk/wy/musicDetail.js
  var musicDetail_default = {
    getSinger(singers) {
      let arr = [];
      singers?.forEach((singer) => {
        arr.push(singer.name);
      });
      return arr.join("、");
    },
    filterList({ songs, privileges }) {
      const list = [];
      songs.forEach((item, index) => {
        const types = [];
        const _types = {};
        let size;
        let privilege = privileges[index];
        if (privilege.id !== item.id) privilege = privileges.find((p) => p.id === item.id);
        if (!privilege) return;
        if (privilege.maxBrLevel == "hires") {
          size = item.hr ? sizeFormate(item.hr.size) : null;
          types.push({ type: "flac24bit", size });
          _types.flac24bit = {
            size
          };
        }
        switch (privilege.maxbr) {
          case 999e3:
            size = item.sq ? sizeFormate(item.sq.size) : null;
            types.push({ type: "flac", size });
            _types.flac = {
              size
            };
          case 32e4:
            size = item.h ? sizeFormate(item.h.size) : null;
            types.push({ type: "320k", size });
            _types["320k"] = {
              size
            };
          case 192e3:
          case 128e3:
            size = item.l ? sizeFormate(item.l.size) : null;
            types.push({ type: "128k", size });
            _types["128k"] = {
              size
            };
        }
        types.reverse();
        if (item.pc) {
          list.push({
            singer: item.pc.ar ?? "",
            name: item.pc.sn ?? "",
            albumName: item.pc.alb ?? "",
            albumId: item.al?.id,
            source: "wy",
            interval: formatPlayTime(item.dt / 1e3),
            songmid: item.id,
            img: item.al?.picUrl ?? "",
            lrc: null,
            otherSource: null,
            types,
            _types,
            typeUrl: {}
          });
        } else {
          list.push({
            singer: this.getSinger(item.ar),
            name: item.name ?? "",
            albumName: item.al?.name,
            albumId: item.al?.id,
            source: "wy",
            interval: formatPlayTime(item.dt / 1e3),
            songmid: item.id,
            img: item.al?.picUrl,
            lrc: null,
            otherSource: null,
            types,
            _types,
            typeUrl: {}
          });
        }
      });
      return list;
    },
    async getList(ids = [], retryNum = 0) {
      if (retryNum > 2) return Promise.reject(new Error("try max num"));
      const requestObj = httpFetch("https://music.163.com/weapi/v3/song/detail", {
        method: "post",
        headers: {
          "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36",
          origin: "https://music.163.com"
        },
        form: weapi({
          c: "[" + ids.map((id) => '{"id":' + id + "}").join(",") + "]",
          ids: "[" + ids.join(",") + "]"
        })
      });
      const { body, statusCode } = await requestObj;
      if (statusCode != 200 || body.code !== 200) throw new Error("获取歌曲详情失败");
      return { source: "wy", list: this.filterList(body) };
    }
  };

  // src/musicSdk/wy/songList.js
  var songList_default4 = {
    limit_list: 30,
    limit_song: 1e5,
    successCode: 200,
    cookie: "MUSIC_U=",
    sortList: [
      {
        name: "最热",
        tid: "hot",
        id: "hot"
      }
      // {
      //   name: '最新',
      //   tid: 'new',
      //   id: 'new',
      // },
    ],
    regExps: {
      listDetailLink: /^.+(?:\?|&)id=(\d+)(?:&.*$|#.*$|$)/,
      listDetailLink2: /^.+\/playlist\/(\d+)\/\d+\/.+$/
    },
    async handleParseId(link, retryNum = 0) {
      if (retryNum > 2) throw new Error("link try max num");
      const requestObj_listDetailLink = httpFetch(link);
      const { url, statusCode } = await requestObj_listDetailLink;
      if (statusCode > 400) return this.handleParseId(link, ++retryNum);
      return this.regExps.listDetailLink.test(url) ? url.replace(this.regExps.listDetailLink, "$1") : url.replace(this.regExps.listDetailLink2, "$1");
    },
    async getListId(id) {
      let cookie;
      if (/###/.test(id)) {
        const [url, token] = id.split("###");
        id = url;
        cookie = `MUSIC_U=${token}`;
      }
      if (/[?&:/]/.test(id)) {
        if (this.regExps.listDetailLink.test(id)) {
          id = id.replace(this.regExps.listDetailLink, "$1");
        } else if (this.regExps.listDetailLink2.test(id)) {
          id = id.replace(this.regExps.listDetailLink2, "$1");
        } else {
          id = await this.handleParseId(id);
        }
      }
      return { id, cookie };
    },
    async getListDetail(rawId, page, tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const { id, cookie } = await this.getListId(rawId);
      if (cookie) this.cookie = cookie;
      const requestObj_listDetail = httpFetch("https://music.163.com/api/linux/forward", {
        method: "post",
        headers: {
          "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36",
          Cookie: this.cookie
        },
        credentials: "omit",
        cache: "default",
        form: linuxapi({
          method: "POST",
          url: "https://music.163.com/api/v3/playlist/detail",
          params: {
            id,
            n: this.limit_song,
            s: 8
          }
        })
      });
      const { statusCode, body } = await requestObj_listDetail;
      if (statusCode !== 200 || body.code !== this.successCode) return this.getListDetail(id, page, ++tryNum);
      let limit = 1e3;
      let rangeStart = (page - 1) * limit;
      let list;
      if (body.playlist.trackIds.length == body.privileges.length) {
        list = this.filterListDetail(body);
      } else {
        try {
          list = (await musicDetail_default.getList(body.playlist.trackIds.slice(rangeStart, limit * page).map((trackId) => trackId.id))).list;
        } catch (err2) {
          console.log(err2);
          if (err2.message == "try max num") {
            throw err2;
          } else {
            return this.getListDetail(id, page, ++tryNum);
          }
        }
      }
      return {
        list,
        page,
        limit,
        total: body.playlist.trackIds.length,
        source: "wy",
        info: {
          play_count: formatPlayCount(body.playlist.playCount),
          name: body.playlist.name,
          img: body.playlist.coverImgUrl,
          desc: body.playlist.description,
          author: body.playlist.creator.nickname
        }
      };
    },
    filterListDetail({ playlist: { tracks }, privileges }) {
      const list = [];
      tracks.forEach((item, index) => {
        const types = [];
        const _types = {};
        let size;
        let privilege = privileges[index];
        if (privilege.id !== item.id) privilege = privileges.find((p) => p.id === item.id);
        if (!privilege) return;
        if (privilege.maxBrLevel == "hires") {
          size = item.hr ? sizeFormate(item.hr.size) : null;
          types.push({ type: "flac24bit", size });
          _types.flac24bit = {
            size
          };
        }
        switch (privilege.maxbr) {
          case 999e3:
            size = null;
            types.push({ type: "flac", size });
            _types.flac = {
              size
            };
          case 32e4:
            size = item.h ? sizeFormate(item.h.size) : null;
            types.push({ type: "320k", size });
            _types["320k"] = {
              size
            };
          case 192e3:
          case 128e3:
            size = item.l ? sizeFormate(item.l.size) : null;
            types.push({ type: "128k", size });
            _types["128k"] = {
              size
            };
        }
        types.reverse();
        if (item.pc) {
          list.push({
            singer: item.pc.ar ?? "",
            name: item.pc.sn ?? "",
            albumName: item.pc.alb ?? "",
            albumId: item.al?.id,
            source: "wy",
            interval: formatPlayTime(item.dt / 1e3),
            songmid: item.id,
            img: item.al?.picUrl ?? "",
            lrc: null,
            otherSource: null,
            types,
            _types,
            typeUrl: {}
          });
        } else {
          list.push({
            singer: formatSingerName(item.ar, "name"),
            name: item.name ?? "",
            albumName: item.al?.name,
            albumId: item.al?.id,
            source: "wy",
            interval: formatPlayTime(item.dt / 1e3),
            songmid: item.id,
            img: item.al?.picUrl,
            lrc: null,
            otherSource: null,
            types,
            _types,
            typeUrl: {}
          });
        }
      });
      return list;
    },
    // 获取列表数据
    getList(sortId, tagId, page, tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const request = httpFetch("https://music.163.com/weapi/playlist/list", {
        method: "post",
        form: weapi({
          cat: tagId || "全部",
          // 全部,华语,欧美,日语,韩语,粤语,小语种,流行,摇滚,民谣,电子,舞曲,说唱,轻音乐,爵士,乡村,R&B/Soul,古典,民族,英伦,金属,朋克,蓝调,雷鬼,世界音乐,拉丁,另类/独立,New Age,古风,后摇,Bossa Nova,清晨,夜晚,学习,工作,午休,下午茶,地铁,驾车,运动,旅行,散步,酒吧,怀旧,清新,浪漫,性感,伤感,治愈,放松,孤独,感动,兴奋,快乐,安静,思念,影视原声,ACG,儿童,校园,游戏,70后,80后,90后,网络歌曲,KTV,经典,翻唱,吉他,钢琴,器乐,榜单,00后
          order: sortId,
          // hot,new
          limit: this.limit_list,
          offset: this.limit_list * (page - 1),
          total: true
        })
      });
      return request.then(({ body }) => {
        if (body.code !== this.successCode) return this.getList(sortId, tagId, page, ++tryNum);
        return {
          list: this.filterList(body.playlists),
          total: parseInt(body.total),
          page,
          limit: this.limit_list,
          source: "wy"
        };
      });
    },
    filterList(rawData) {
      return rawData.map((item) => ({
        play_count: formatPlayCount(item.playCount),
        id: String(item.id),
        author: item.creator.nickname,
        name: item.name,
        time: item.createTime ? dateFormat(item.createTime, "Y-M-D") : "",
        img: item.coverImgUrl,
        grade: item.grade,
        total: item.trackCount,
        desc: item.description,
        source: "wy"
      }));
    },
    // 获取标签
    getTag(tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const request = httpFetch("https://music.163.com/weapi/playlist/catalogue", {
        method: "post",
        form: weapi({})
      });
      return request.then(({ body }) => {
        if (body.code !== this.successCode) return this.getTag(++tryNum);
        return this.filterTagInfo(body);
      });
    },
    filterTagInfo({ sub, categories }) {
      const subList = {};
      for (const item of sub) {
        if (!subList[item.category]) subList[item.category] = [];
        subList[item.category].push({
          parent_id: categories[item.category],
          parent_name: categories[item.category],
          id: item.name,
          name: item.name,
          source: "wy"
        });
      }
      const list = [];
      for (const key of Object.keys(categories)) {
        list.push({
          name: categories[key],
          list: subList[key],
          source: "wy"
        });
      }
      return list;
    },
    // 获取热门标签
    getHotTag(tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const request = httpFetch("https://music.163.com/weapi/playlist/hottags", {
        method: "post",
        form: weapi({})
      });
      return request.then(({ body }) => {
        if (body.code !== this.successCode) return this.getTag(++tryNum);
        return this.filterHotTagInfo(body.tags);
      });
    },
    filterHotTagInfo(rawList) {
      return rawList.map((item) => ({
        id: item.playlistTag.name,
        name: item.playlistTag.name,
        source: "wy"
      }));
    },
    getTags() {
      return Promise.all([this.getTag(), this.getHotTag()]).then(([tags, hotTag]) => ({ tags, hotTag, source: "wy" }));
    },
    async getDetailPageUrl(rawId) {
      const { id } = await this.getListId(rawId);
      return `https://music.163.com/#/playlist?id=${id}`;
    },
    search(text, page, limit = 20) {
      return eapiRequest("/api/cloudsearch/pc", {
        s: text,
        type: 1e3,
        // 1: 单曲, 10: 专辑, 100: 歌手, 1000: 歌单, 1002: 用户, 1004: MV, 1006: 歌词, 1009: 电台, 1014: 视频
        limit,
        total: page == 1,
        offset: limit * (page - 1)
      }).then(({ body }) => {
        if (body.code != this.successCode) throw new Error("filed");
        return {
          list: this.filterList(body.result.playlists),
          limit,
          total: body.result.playlistCount,
          source: "wy"
        };
      });
    }
  };

  // src/musicSdk/wy/leaderboard.js
  var topList = [
    { id: "wy__19723756", name: "飙升榜", bangid: "19723756" },
    { id: "wy__3779629", name: "新歌榜", bangid: "3779629" },
    { id: "wy__2884035", name: "原创榜", bangid: "2884035" },
    { id: "wy__3778678", name: "热歌榜", bangid: "3778678" },
    { id: "wy__991319590", name: "说唱榜", bangid: "991319590" },
    { id: "wy__71384707", name: "古典榜", bangid: "71384707" },
    { id: "wy__1978921795", name: "电音榜", bangid: "1978921795" },
    { id: "wy__5453912201", name: "黑胶VIP爱听榜", bangid: "5453912201" },
    { id: "wy__71385702", name: "ACG榜", bangid: "71385702" },
    { id: "wy__745956260", name: "韩语榜", bangid: "745956260" },
    { id: "wy__10520166", name: "国电榜", bangid: "10520166" },
    { id: "wy__180106", name: "UK排行榜周榜", bangid: "180106" },
    { id: "wy__60198", name: "美国Billboard榜", bangid: "60198" },
    { id: "wy__3812895", name: "Beatport全球电子舞曲榜", bangid: "3812895" },
    { id: "wy__21845217", name: "KTV唛榜", bangid: "21845217" },
    { id: "wy__60131", name: "日本Oricon榜", bangid: "60131" },
    { id: "wy__2809513713", name: "欧美热歌榜", bangid: "2809513713" },
    { id: "wy__2809577409", name: "欧美新歌榜", bangid: "2809577409" },
    { id: "wy__27135204", name: "法国 NRJ Vos Hits 周榜", bangid: "27135204" },
    { id: "wy__3001835560", name: "ACG动画榜", bangid: "3001835560" },
    { id: "wy__3001795926", name: "ACG游戏榜", bangid: "3001795926" },
    { id: "wy__3001890046", name: "ACG VOCALOID榜", bangid: "3001890046" },
    { id: "wy__3112516681", name: "中国新乡村音乐排行榜", bangid: "3112516681" },
    { id: "wy__5059644681", name: "日语榜", bangid: "5059644681" },
    { id: "wy__5059633707", name: "摇滚榜", bangid: "5059633707" },
    { id: "wy__5059642708", name: "国风榜", bangid: "5059642708" },
    { id: "wy__5338990334", name: "潜力爆款榜", bangid: "5338990334" },
    { id: "wy__5059661515", name: "民谣榜", bangid: "5059661515" },
    { id: "wy__6688069460", name: "听歌识曲榜", bangid: "6688069460" },
    { id: "wy__6723173524", name: "网络热歌榜", bangid: "6723173524" },
    { id: "wy__6732051320", name: "俄语榜", bangid: "6732051320" },
    { id: "wy__6732014811", name: "越南语榜", bangid: "6732014811" },
    { id: "wy__6886768100", name: "中文DJ榜", bangid: "6886768100" },
    { id: "wy__6939992364", name: "俄罗斯top hit流行音乐榜", bangid: "6939992364" },
    { id: "wy__7095271308", name: "泰语榜", bangid: "7095271308" },
    { id: "wy__7356827205", name: "BEAT排行榜", bangid: "7356827205" },
    { id: "wy__7325478166", name: "编辑推荐榜VOL.44 天才女子摇滚乐队boygenius剖白卑微心迹", bangid: "7325478166" },
    { id: "wy__7603212484", name: "LOOK直播歌曲榜", bangid: "7603212484" },
    { id: "wy__7775163417", name: "赏音榜", bangid: "7775163417" },
    { id: "wy__7785123708", name: "黑胶VIP新歌榜", bangid: "7785123708" },
    { id: "wy__7785066739", name: "黑胶VIP热歌榜", bangid: "7785066739" },
    { id: "wy__7785091694", name: "黑胶VIP爱搜榜", bangid: "7785091694" }
  ];
  var leaderboard_default4 = {
    limit: 1e5,
    list: [
      {
        id: "wybsb",
        name: "飙升榜",
        bangid: "19723756"
      },
      {
        id: "wyrgb",
        name: "热歌榜",
        bangid: "3778678"
      },
      {
        id: "wyxgb",
        name: "新歌榜",
        bangid: "3779629"
      },
      {
        id: "wyycb",
        name: "原创榜",
        bangid: "2884035"
      },
      {
        id: "wygdb",
        name: "古典榜",
        bangid: "71384707"
      },
      {
        id: "wydouyb",
        name: "抖音榜",
        bangid: "2250011882"
      },
      {
        id: "wyhyb",
        name: "韩语榜",
        bangid: "745956260"
      },
      {
        id: "wydianyb",
        name: "电音榜",
        bangid: "1978921795"
      },
      {
        id: "wydjb",
        name: "电竞榜",
        bangid: "2006508653"
      },
      {
        id: "wyktvbb",
        name: "KTV唛榜",
        bangid: "21845217"
      }
    ],
    getUrl(id) {
      return `https://music.163.com/discover/toplist?id=${id}`;
    },
    regExps: {
      list: /<textarea id="song-list-pre-data" style="display:none;">(.+?)<\/textarea>/
    },
    getBoardsData() {
      const request = httpFetch("https://music.163.com/weapi/toplist", {
        method: "post",
        form: weapi({})
      });
      return request;
    },
    getData(id, limit = this.limit) {
      const requestBoardsDetailObj = httpFetch("https://music.163.com/weapi/v3/playlist/detail", {
        method: "post",
        form: weapi({
          id,
          n: limit,
          p: 1
        })
      });
      return requestBoardsDetailObj;
    },
    filterBoardsData(rawList) {
      let list = [];
      for (const board of rawList) {
        list.push({
          id: "wy__" + board.id,
          name: board.name,
          bangid: String(board.id)
        });
      }
      return list;
    },
    async getBoards(retryNum = 0) {
      try {
        const response = await this.getBoardsData();
        if (response && response.statusCode === 200 && response.body?.code === 200 && Array.isArray(response.body.list)) {
          const list = [];
          for (const board of response.body.list) {
            const name = board.name || "";
            if (!board.id || !name) continue;
            list.push({ id: "wy__" + board.id, name, bangid: String(board.id), img: board.coverImgUrl || null });
          }
          if (list.length) {
            this.list = list;
            return { list, source: "wy" };
          }
        }
      } catch (error) {
      }
      this.list = topList;
      return {
        list: topList,
        source: "wy"
      };
    },
    async getList(bangid, page, limit, retryNum = 0) {
      if (++retryNum > 6) return Promise.reject(new Error("try max num"));
      const pageSize = limit || this.limit;
      let resp;
      try {
        resp = await this.getData(bangid, pageSize);
      } catch (err2) {
        if (err2.message == "try max num") {
          throw err2;
        } else {
          return this.getList(bangid, page, pageSize, retryNum);
        }
      }
      if (resp.statusCode !== 200 || resp.body.code !== 200) return this.getList(bangid, page, pageSize, retryNum);
      let musicDetail;
      try {
        musicDetail = await musicDetail_default.getList(resp.body.playlist.trackIds.slice(0, pageSize).map((trackId) => trackId.id));
      } catch (err2) {
        console.log(err2);
        if (err2.message == "try max num") {
          throw err2;
        } else {
          return this.getList(bangid, page, pageSize, retryNum);
        }
      }
      const list = limit ? musicDetail.list.slice(0, pageSize) : musicDetail.list;
      return {
        total: list.length,
        list,
        limit: pageSize,
        page,
        source: "wy"
      };
    },
    getDetailPageUrl(id) {
      if (typeof id == "string") id = id.replace("wy__", "");
      return `https://music.163.com/#/discover/toplist?id=${id}`;
    }
  };

  // src/musicSdk/wy/hotSearch.js
  var hotSearch_default4 = {
    async getList(retryNum = 0) {
      if (retryNum > 2) return Promise.reject(new Error("try max num"));
      const _requestObj = eapiRequest("/api/search/chart/detail", {
        id: "HOT_SEARCH_SONG#@#"
      });
      const { body, statusCode } = await _requestObj;
      if (statusCode != 200 || body.code !== 200) throw new Error("获取热搜词失败");
      return { source: "wy", list: this.filterList(body.data.itemList) };
    },
    filterList(rawList) {
      return rawList.map((item) => item.searchWord);
    }
  };

  // src/musicSdk/wy/tipSearch.js
  var tipSearch_default4 = {
    tipSearchBySong(str) {
      const request = httpFetch("https://music.163.com/weapi/search/suggest/web", {
        method: "POST",
        headers: {
          referer: "https://music.163.com/",
          origin: "https://music.163.com/"
        },
        form: weapi({
          s: str
        })
      });
      return request.then(({ statusCode, body }) => {
        if (statusCode != 200 || body.code != 200) return Promise.reject(new Error("请求失败"));
        return body.result.songs;
      });
    },
    handleResult(rawData) {
      return rawData.map((info) => `${info.name} - ${formatSingerName(info.artists, "name")}`);
    },
    async search(str) {
      return this.tipSearchBySong(str).then((result) => this.handleResult(result));
    }
  };

  // src/musicSdk/wy/lyric.js
  var eapiRequest2 = (url, data) => {
    return httpFetch("https://interface3.music.163.com/eapi/song/lyric/v1", {
      method: "post",
      headers: {
        "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36",
        origin: "https://music.163.com"
        // cookie: 'os=pc; deviceId=A9C064BB4584D038B1565B58CB05F95290998EE8B025AA2D07AE; osver=Microsoft-Windows-10-Home-China-build-19043-64bit; appver=2.5.2.197409; channel=netease; MUSIC_A=37a11f2eb9de9930cad479b2ad495b0e4c982367fb6f909d9a3f18f876c6b49faddb3081250c4980dd7e19d4bd9bf384e004602712cf2b2b8efaafaab164268a00b47359f85f22705cc95cb6180f3aee40f5be1ebf3148d888aa2d90636647d0c3061cd18d77b7a0; __csrf=05b50d54082694f945d7de75c210ef94; mode=Z7M-KP5(7)GZ; NMTID=00OZLp2VVgq9QdwokUgq3XNfOddQyIAAAF_6i8eJg; ntes_kaola_ad=1',
      },
      form: eapi(url, data)
    });
  };
  var parseTools2 = {
    rxps: {
      info: /^{"/,
      lineTime: /^\[(\d+),\d+\]/,
      wordTime: /\(\d+,\d+,\d+\)/,
      wordTimeAll: /(\(\d+,\d+,\d+\))/g,
      timeMs2: /\[\d+:\d+\.\d{2}]/,
      timeMs3: /\[\d+:\d+\.\d{3}]/
    },
    msFormat(timeMs, pad3 = true) {
      if (Number.isNaN(timeMs)) return "";
      let ms = (timeMs % 1e3).toString().padStart(pad3 ? 3 : 2, "0");
      if (!pad3 && ms.length > 2) ms = ms.slice(0, 2);
      timeMs /= 1e3;
      let m = parseInt(timeMs / 60).toString().padStart(2, "0");
      timeMs %= 60;
      let s = parseInt(timeMs).toString().padStart(2, "0");
      return `[${m}:${s}.${ms}]`;
    },
    parseLyric(lines) {
      const lxlrcLines = [];
      const lrcLines = [];
      for (let line of lines) {
        line = line.trim();
        let result = this.rxps.lineTime.exec(line);
        if (!result) {
          if (line.startsWith("[offset")) {
            lxlrcLines.push(line);
            lrcLines.push(line);
          }
          continue;
        }
        const startMsTime = parseInt(result[1]);
        const startTimeStr = this.msFormat(startMsTime);
        if (!startTimeStr) continue;
        let words = line.replace(this.rxps.lineTime, "");
        lrcLines.push(`${startTimeStr}${words.replace(this.rxps.wordTimeAll, "")}`);
        let times = words.match(this.rxps.wordTimeAll);
        if (!times) continue;
        times = times.map((time) => {
          const result2 = /\((\d+),(\d+),\d+\)/.exec(time);
          return `<${Math.trunc(Math.max(parseInt(result2[1]) - startMsTime, 0))},${result2[2]}>`;
        });
        const wordArr = words.split(this.rxps.wordTime);
        wordArr.shift();
        const newWords = times.map((time, index) => `${time}${wordArr[index]}`).join("");
        lxlrcLines.push(`${startTimeStr}${newWords}`);
      }
      return {
        lyric: lrcLines.join("\n"),
        lxlyric: lxlrcLines.join("\n")
      };
    },
    parseHeaderInfo(str) {
      str = str.trim();
      str = str.replace(/\r/g, "");
      if (!str) return null;
      const isPad3 = this.rxps.timeMs3.test(str) || !this.rxps.timeMs2.test(str);
      const lines = str.split("\n");
      return lines.map((line) => {
        if (!this.rxps.info.test(line)) return line;
        try {
          const info = JSON.parse(line);
          const timeTag = this.msFormat(info.t, isPad3);
          return timeTag ? `${timeTag}${info.c.map((t) => t.tx).join("")}` : "";
        } catch {
          return "";
        }
      });
    },
    getIntv(interval) {
      if (!interval) return 0;
      if (!interval.includes(".")) interval += ".0";
      let arr = interval.split(/:|\./);
      while (arr.length < 3) arr.unshift("0");
      const [m, s, ms] = arr;
      return parseInt(m) * 36e5 + parseInt(s) * 1e3 + parseInt(ms);
    },
    fixTimeTag(lrc, targetlrc) {
      let lrcLines = lrc.split("\n");
      const targetlrcLines = targetlrc.split("\n");
      const timeRxp = /^\[([\d:.]+)\]/;
      let temp = [];
      let newLrc = [];
      targetlrcLines.forEach((line) => {
        const result = timeRxp.exec(line);
        if (!result) return;
        const words = line.replace(timeRxp, "");
        if (!words.trim()) return;
        const t1 = this.getIntv(result[1]);
        while (lrcLines.length) {
          const lrcLine = lrcLines.shift();
          const lrcLineResult = timeRxp.exec(lrcLine);
          if (!lrcLineResult) continue;
          const t2 = this.getIntv(lrcLineResult[1]);
          if (Math.abs(t1 - t2) < 100) {
            const lrc2 = line.replace(timeRxp, lrcLineResult[0]).trim();
            if (!lrc2) continue;
            newLrc.push(lrc2);
            break;
          }
          temp.push(lrcLine);
        }
        lrcLines = [...temp, ...lrcLines];
        temp = [];
      });
      return newLrc.join("\n");
    },
    parse(ylrc, ytlrc, yrlrc, lrc, tlrc, rlrc) {
      const info = {
        lyric: "",
        tlyric: "",
        rlyric: "",
        lxlyric: ""
      };
      if (ylrc) {
        let lines = this.parseHeaderInfo(ylrc);
        if (lines) {
          const result = this.parseLyric(lines);
          if (ytlrc) {
            const lines2 = this.parseHeaderInfo(ytlrc);
            if (lines2) {
              info.tlyric = this.fixTimeTag(result.lyric, lines2.join("\n"));
            }
          }
          if (yrlrc) {
            const lines2 = this.parseHeaderInfo(yrlrc);
            if (lines2) {
              info.rlyric = this.fixTimeTag(result.lyric, lines2.join("\n"));
            }
          }
          const timeRxp = /^\[[\d:.]+\]/;
          const headers = lines.filter((l) => timeRxp.test(l)).join("\n");
          info.lyric = `${headers}
${result.lyric}`;
          info.lxlyric = result.lxlyric;
          return info;
        }
      }
      if (lrc) {
        const lines = this.parseHeaderInfo(lrc);
        if (lines) info.lyric = lines.join("\n");
      }
      if (tlrc) {
        const lines = this.parseHeaderInfo(tlrc);
        if (lines) info.tlyric = lines.join("\n");
      }
      if (rlrc) {
        const lines = this.parseHeaderInfo(rlrc);
        if (lines) info.rlyric = lines.join("\n");
      }
      return info;
    }
  };
  var fixTimeLabel = (lrc, tlrc, romalrc) => {
    if (lrc) {
      let newLrc = lrc.replace(/\[(\d{2}:\d{2}):(\d{2})]/g, "[$1.$2]");
      let newTlrc = tlrc?.replace(/\[(\d{2}:\d{2}):(\d{2})]/g, "[$1.$2]") ?? tlrc;
      if (newLrc != lrc || newTlrc != tlrc) {
        lrc = newLrc;
        tlrc = newTlrc;
        if (romalrc) romalrc = romalrc.replace(/\[(\d{2}:\d{2}):(\d{2,3})]/g, "[$1.$2]").replace(/\[(\d{2}:\d{2}\.\d{2})0]/g, "[$1]");
      }
    }
    return { lrc, tlrc, romalrc };
  };
  var lyric_default4 = (songmid) => {
    let requestObj = eapiRequest2("/api/song/lyric/v1", {
      id: songmid,
      cp: false,
      tv: 0,
      lv: 0,
      rv: 0,
      kv: 0,
      yv: 0,
      ytv: 0,
      yrv: 0
    });
    requestObj = requestObj.then(({ body }) => {
      if (body.code !== 200 || !body?.lrc?.lyric) return Promise.reject(new Error("Get lyric failed"));
      const fixTimeLabelLrc = fixTimeLabel(body.lrc.lyric, body.tlyric?.lyric, body.romalrc?.lyric);
      const info = parseTools2.parse(body.yrc?.lyric, body.ytlrc?.lyric, body.yromalrc?.lyric, fixTimeLabelLrc.lrc, fixTimeLabelLrc.tlrc, fixTimeLabelLrc.romalrc);
      if (!info.lyric) return Promise.reject(new Error("Get lyric failed"));
      return info;
    });
    return requestObj;
  };

  // src/musicSdk/wy/musicInfo.js
  var musicInfo_default2 = (songmid) => {
    let requestObj = httpFetch("https://music.163.com/weapi/v3/song/detail", {
      method: "post",
      headers: {
        "User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.90 Safari/537.36",
        Referer: "https://music.163.com/song?id=" + songmid,
        origin: "https://music.163.com"
      },
      form: weapi({
        c: `[{"id":${songmid}}]`,
        ids: `[${songmid}]`
      })
    });
    requestObj = requestObj.then(({ body }) => {
      if (body.code !== 200 || !body.songs.length) return Promise.reject(new Error("获取歌曲信息失败"));
      return body.songs[0];
    });
    return requestObj;
  };

  // src/musicSdk/mg/musicSearch.js
  var createSignature = (time, str) => {
    const deviceId = "963B7AA0D21511ED807EE5846EC87D20";
    const signatureMd5 = "6cdc72a439cef99a3418d2a78aa28c73";
    const sign = toMD5(`${str}${signatureMd5}yyapp2d16148780a1dcc7408e06336b98cfd50${deviceId}${time}`);
    return { sign, deviceId };
  };
  var musicSearch_default5 = {
    limit: 20,
    total: 0,
    page: 0,
    allPage: 1,
    // 旧版API
    // musicSearch(str, page, limit) {
    //   const searchRequest = httpFetch(`http://pd.musicapp.migu.cn/MIGUM2.0/v1.0/content/search_all.do?ua=Android_migu&version=5.0.1&text=${encodeURIComponent(str)}&pageNo=${page}&pageSize=${limit}&searchSwitch=%7B%22song%22%3A1%2C%22album%22%3A0%2C%22singer%22%3A0%2C%22tagSong%22%3A0%2C%22mvSong%22%3A0%2C%22songlist%22%3A0%2C%22bestShow%22%3A1%7D`, {
    // searchRequest = httpFetch(`http://pd.musicapp.migu.cn/MIGUM2.0/v1.0/content/search_all.do?ua=Android_migu&version=5.0.1&text=${encodeURIComponent(str)}&pageNo=${page}&pageSize=${limit}&searchSwitch=%7B%22song%22%3A1%2C%22album%22%3A0%2C%22singer%22%3A0%2C%22tagSong%22%3A0%2C%22mvSong%22%3A0%2C%22songlist%22%3A0%2C%22bestShow%22%3A1%7D`, {
    // searchRequest = httpFetch(`http://jadeite.migu.cn:7090/music_search/v2/search/searchAll?sid=4f87090d01c84984a11976b828e2b02c18946be88a6b4c47bcdc92fbd40762db&isCorrect=1&isCopyright=1&searchSwitch=%7B%22song%22%3A1%2C%22album%22%3A0%2C%22singer%22%3A0%2C%22tagSong%22%3A1%2C%22mvSong%22%3A0%2C%22bestShow%22%3A1%2C%22songlist%22%3A0%2C%22lyricSong%22%3A0%7D&pageSize=${limit}&text=${encodeURIComponent(str)}&pageNo=${page}&sort=0`, {
    // searchRequest = httpFetch(`https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do?isCopyright=1&isCorrect=1&pageNo=${page}&pageSize=${limit}&searchSwitch={%22song%22:1,%22album%22:0,%22singer%22:0,%22tagSong%22:0,%22mvSong%22:0,%22songlist%22:0,%22bestShow%22:0}&sort=0&text=${encodeURIComponent(str)}`)
    //   // searchRequest = httpFetch(`http://jadeite.migu.cn:7090/music_search/v2/search/searchAll?sid=4f87090d01c84984a11976b828e2b02c18946be88a6b4c47bcdc92fbd40762db&isCorrect=1&isCopyright=1&searchSwitch=%7B%22song%22%3A1%2C%22album%22%3A0%2C%22singer%22%3A0%2C%22tagSong%22%3A1%2C%22mvSong%22%3A0%2C%22bestShow%22%3A1%2C%22songlist%22%3A0%2C%22lyricSong%22%3A0%7D&pageSize=${limit}&text=${encodeURIComponent(str)}&pageNo=${page}&sort=0`, {
    //     headers: {
    //       // sign: 'c3b7ae985e2206e97f1b2de8f88691e2',
    //       // timestamp: 1578225871982,
    //       // appId: 'yyapp2',
    //       // mode: 'android',
    //       // ua: 'Android_migu',
    //       // version: '6.9.4',
    //       osVersion: 'android 7.0',
    //       'User-Agent': 'okhttp/3.9.1',
    //     },
    //   })
    //   // searchRequest = httpFetch(`https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/search_all.do?isCopyright=1&isCorrect=1&pageNo=${page}&pageSize=${limit}&searchSwitch={%22song%22:1,%22album%22:0,%22singer%22:0,%22tagSong%22:0,%22mvSong%22:0,%22songlist%22:0,%22bestShow%22:0}&sort=0&text=${encodeURIComponent(str)}`)
    //   return searchRequest.then(({ body }) => body)
    // },
    // handleResult(rawData) {
    //   // console.log(rawData)
    //   let ids = new Set()
    //   const list = []
    //   rawData.forEach(item => {
    //     if (ids.has(item.id)) return
    //     ids.add(item.id)
    //     const types = []
    //     const _types = {}
    //     item.newRateFormats && item.newRateFormats.forEach(type => {
    //       let size
    //       switch (type.formatType) {
    //         case 'PQ':
    //           size = sizeFormate(type.size ?? type.androidSize)
    //           types.push({ type: '128k', size })
    //           _types['128k'] = {
    //             size,
    //           }
    //           break
    //         case 'HQ':
    //           size = sizeFormate(type.size ?? type.androidSize)
    //           types.push({ type: '320k', size })
    //           _types['320k'] = {
    //             size,
    //           }
    //           break
    //         case 'SQ':
    //           size = sizeFormate(type.size ?? type.androidSize)
    //           types.push({ type: 'flac', size })
    //           _types.flac = {
    //             size,
    //           }
    //           break
    //         case 'ZQ':
    //           size = sizeFormate(type.size ?? type.androidSize)
    //           types.push({ type: 'flac24bit', size })
    //           _types.flac24bit = {
    //             size,
    //           }
    //           break
    //       }
    //     })
    //     const albumNInfo = item.albums && item.albums.length
    //       ? {
    //           id: item.albums[0].id,
    //           name: item.albums[0].name,
    //         }
    //       : {}
    //     list.push({
    //       singer: this.getSinger(item.singers),
    //       name: item.name,
    //       albumName: albumNInfo.name,
    //       albumId: albumNInfo.id,
    //       songmid: item.songId,
    //       copyrightId: item.copyrightId,
    //       source: 'mg',
    //       interval: null,
    //       img: item.imgItems && item.imgItems.length ? item.imgItems[0].img : null,
    //       lrc: null,
    //       lrcUrl: item.lyricUrl,
    //       mrcUrl: item.mrcurl,
    //       trcUrl: item.trcUrl,
    //       otherSource: null,
    //       types,
    //       _types,
    //       typeUrl: {},
    //     })
    //   })
    //   return list
    // },
    musicSearch(str, page, limit) {
      const time = Date.now().toString();
      const signData = createSignature(time, str);
      const searchRequest = httpFetch(`https://jadeite.migu.cn/music_search/v3/search/searchAll?isCorrect=0&isCopyright=1&searchSwitch=%7B%22song%22%3A1%2C%22album%22%3A0%2C%22singer%22%3A0%2C%22tagSong%22%3A1%2C%22mvSong%22%3A0%2C%22bestShow%22%3A1%2C%22songlist%22%3A0%2C%22lyricSong%22%3A0%7D&pageSize=${limit}&text=${encodeURIComponent(str)}&pageNo=${page}&sort=0&sid=USS`, {
        headers: {
          uiVersion: "A_music_3.6.1",
          deviceId: signData.deviceId,
          timestamp: time,
          sign: signData.sign,
          channel: "0146921",
          "User-Agent": "Mozilla/5.0 (Linux; U; Android 11.0.0; zh-cn; MI 11 Build/OPR1.170623.032) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30"
        }
      });
      return searchRequest.then(({ body }) => body);
    },
    filterData(rawData) {
      const list = [];
      const ids = /* @__PURE__ */ new Set();
      rawData.forEach((item) => {
        item.forEach((data) => {
          if (!data.songId || !data.copyrightId || ids.has(data.copyrightId)) return;
          ids.add(data.copyrightId);
          const types = [];
          const _types = {};
          data.audioFormats && data.audioFormats.forEach((type) => {
            let size;
            switch (type.formatType) {
              case "PQ":
                size = sizeFormate(type.asize ?? type.isize);
                types.push({ type: "128k", size });
                _types["128k"] = {
                  size
                };
                break;
              case "HQ":
                size = sizeFormate(type.asize ?? type.isize);
                types.push({ type: "320k", size });
                _types["320k"] = {
                  size
                };
                break;
              case "SQ":
                size = sizeFormate(type.asize ?? type.isize);
                types.push({ type: "flac", size });
                _types.flac = {
                  size
                };
                break;
              case "ZQ24":
                size = sizeFormate(type.asize ?? type.isize);
                types.push({ type: "flac24bit", size });
                _types.flac24bit = {
                  size
                };
                break;
            }
          });
          let img = data.img3 || data.img2 || data.img1 || null;
          if (img && !/https?:/.test(data.img3)) img = "http://d.musicapp.migu.cn" + img;
          list.push({
            singer: formatSingerName(data.singerList),
            name: data.name,
            albumName: data.album,
            albumId: data.albumId,
            songmid: data.songId,
            copyrightId: data.copyrightId,
            source: "mg",
            interval: formatPlayTime(data.duration),
            img,
            lrc: null,
            lrcUrl: data.lrcUrl,
            mrcUrl: data.mrcurl,
            trcUrl: data.trcUrl,
            types,
            _types,
            typeUrl: {}
          });
        });
      });
      return list;
    },
    search(str, page = 1, limit, retryNum = 0) {
      if (++retryNum > 3) return Promise.reject(new Error("try max num"));
      if (limit == null) limit = this.limit;
      return this.musicSearch(str, page, limit).then((result) => {
        if (!result || result.code !== "000000") return Promise.reject(new Error(result ? result.info : "搜索失败"));
        const songResultData = result.songResultData || { resultList: [], totalCount: 0 };
        let list = this.filterData(songResultData.resultList);
        if (list == null) return this.search(str, page, limit, retryNum);
        this.total = parseInt(songResultData.totalCount);
        this.page = page;
        this.allPage = Math.ceil(this.total / limit);
        return {
          list,
          allPage: this.allPage,
          limit,
          total: this.total,
          source: "mg"
        };
      });
    }
  };

  // src/musicSdk/mg/utils/index.js
  var createHttpFetch2 = async (url, options, retryNum = 0) => {
    if (retryNum > 2) throw new Error("try max num");
    let result;
    try {
      result = await httpFetch(url, options);
    } catch (err2) {
      console.log(err2);
      return createHttpFetch2(url, options, ++retryNum);
    }
    if (result.statusCode !== 200 || (result.body.code !== void 0 ? result.body.code : result.body.returnCode !== void 0 ? result.body.returnCode : result.body.code) !== "000000") return createHttpFetch2(url, options, ++retryNum);
    if (result.body.data) return result.body.data;
    return result.body;
  };

  // src/musicSdk/mg/musicInfo.js
  var normalizeImageUrl = (image) => {
    const value = String(image ?? "").trim();
    if (!value) return null;
    if (/^https?:\/\//i.test(value)) return value.replace(/^http:/i, "https:");
    if (value.startsWith("//")) return `https:${value}`;
    return `https://d.musicapp.migu.cn/${value.replace(/^\/+/, "")}`;
  };
  var formatAudioSize = (format) => sizeFormate(format.asize ?? format.isize ?? format.size ?? format.androidSize);
  var createGetMusicInfosTask = (ids) => {
    let list = ids;
    let tasks = [];
    while (list.length) {
      tasks.push(list.slice(0, 100));
      if (list.length < 100) break;
      list = list.slice(100);
    }
    let url = "https://c.musicapp.migu.cn/MIGUM2.0/v1.0/content/resourceinfo.do?resourceType=2";
    return Promise.all(tasks.map((task) => createHttpFetch2(url, {
      method: "POST",
      form: {
        resourceId: task.join("|")
      }
    }).then((data) => data.resource)));
  };
  var filterMusicInfoList = (rawList) => {
    let ids = /* @__PURE__ */ new Set();
    const list = [];
    rawList.forEach((item) => {
      if (!item.songId || ids.has(item.songId)) return;
      ids.add(item.songId);
      const types = [];
      const _types = {};
      item.newRateFormats?.forEach((type) => {
        let size;
        switch (type.formatType) {
          case "PQ":
            size = formatAudioSize(type);
            types.push({ type: "128k", size });
            _types["128k"] = {
              size
            };
            break;
          case "HQ":
            size = formatAudioSize(type);
            types.push({ type: "320k", size });
            _types["320k"] = {
              size
            };
            break;
          case "SQ":
            size = formatAudioSize(type);
            types.push({ type: "flac", size });
            _types.flac = {
              size
            };
            break;
          case "ZQ":
          case "ZQ24":
            size = formatAudioSize(type);
            types.push({ type: "flac24bit", size });
            _types.flac24bit = {
              size
            };
            break;
        }
      });
      const intervalMatch = /(\d\d:\d\d)$/.exec(item.length);
      list.push({
        singer: formatSingerName(item.artists, "name"),
        name: item.songName,
        albumName: item.album,
        albumId: item.albumId,
        songmid: item.songId,
        copyrightId: item.copyrightId,
        source: "mg",
        interval: intervalMatch ? intervalMatch[1] : null,
        img: normalizeImageUrl(item.albumImgs?.[0]?.img),
        lrc: null,
        lrcUrl: item.lrcUrl,
        mrcUrl: item.mrcUrl,
        trcUrl: item.trcUrl,
        otherSource: null,
        types,
        _types,
        typeUrl: {}
      });
    });
    return list;
  };
  var filterMusicInfoListV5 = (rawList) => {
    let ids = /* @__PURE__ */ new Set();
    const list = [];
    rawList.forEach((item) => {
      if (!item.songId || ids.has(item.songId)) return;
      ids.add(item.songId);
      const types = [];
      const _types = {};
      item.audioFormats?.forEach((type) => {
        let size;
        switch (type.formatType) {
          case "PQ":
            size = formatAudioSize(type);
            types.push({ type: "128k", size });
            _types["128k"] = {
              size
            };
            break;
          case "HQ":
            size = formatAudioSize(type);
            types.push({ type: "320k", size });
            _types["320k"] = {
              size
            };
            break;
          case "SQ":
            size = formatAudioSize(type);
            types.push({ type: "flac", size });
            _types.flac = {
              size
            };
            break;
          case "ZQ":
          case "ZQ24":
            size = formatAudioSize(type);
            types.push({ type: "flac24bit", size });
            _types.flac24bit = {
              size
            };
            break;
        }
      });
      list.push({
        singer: formatSingerName(item.singerList, "name"),
        name: item.songName,
        albumName: item.album,
        albumId: item.albumId,
        songmid: item.songId,
        copyrightId: item.copyrightId,
        source: "mg",
        interval: formatPlayTime(item.duration),
        img: normalizeImageUrl(item.img3 || item.img2 || item.img1),
        lrc: null,
        lrcUrl: item.lrcUrl,
        mrcUrl: item.mrcUrl,
        trcUrl: item.trcUrl,
        otherSource: null,
        types,
        _types,
        typeUrl: {}
      });
    });
    return list;
  };
  var getMusicInfo = async (copyrightId) => {
    return getMusicInfos([copyrightId]).then((data) => data[0]);
  };
  var getMusicInfos = async (copyrightIds) => {
    const chunks = await createGetMusicInfosTask(copyrightIds);
    return filterMusicInfoList(chunks.flat());
  };

  // src/musicSdk/mg/songList.js
  var songList_default5 = {
    limit_list: 30,
    limit_song: 30,
    successCode: "000000",
    cachedDetailInfo: {},
    cachedUrl: {},
    sortList: [
      {
        name: "推荐",
        id: "15127315",
        tid: "recommend"
        // id: '1',
      }
      // {
      //   name: '最新',
      //   id: '15127272',
      //   tid: 'new',
      //   // id: '2',
      // },
    ],
    regExps: {
      list: /<li><div class="thumb">.+?<\/li>/g,
      listInfo: /.+data-original="(.+?)".*data-id="(\d+)".*<div class="song-list-name"><a\s.*?>(.+?)<\/a>.+<i class="iconfont cf-bofangliang"><\/i>(.+?)<\/div>/,
      // https://music.migu.cn/v3/music/playlist/161044573?page=1
      listDetailLink: /^.+\/playlist\/(\d+)(?:\?.*|&.*$|#.*$|$)/
    },
    tagsUrl: "https://app.c.nf.migu.cn/pc/v1.0/template/musiclistplaza-taglist/release",
    // tagsUrl: 'https://app.c.nf.migu.cn/MIGUM3.0/v1.0/template/musiclistplaza-taglist/release',
    // tagsUrl: 'https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/indexTagPage.do?needAll=0',
    getSongListUrl(sortId, tagId, page) {
      if (!tagId) {
        return `https://app.c.nf.migu.cn/pc/bmw/page-data/playlist-square-recommend/v1.0?templateVersion=2&pageNo=${page}`;
      }
      return `https://app.c.nf.migu.cn/pc/v1.0/template/musiclistplaza-listbytag/release?pageNumber=${page}&templateVersion=2&tagId=${tagId}`;
    },
    getSongListDetailUrl(id, page) {
      return `https://app.c.nf.migu.cn/MIGUM3.0/resource/playlist/song/v2.0?pageNo=${page}&pageSize=${this.limit_song}&playlistId=${id}`;
    },
    defaultHeaders: {
      "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 13_2_3 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/13.0.3 Mobile/15E148 Safari/604.1",
      Referer: "https://m.music.migu.cn/"
      // language: 'Chinese',
      // ua: 'Android_migu',
      // mode: 'android',
      // version: '6.8.5',
    },
    getListDetailList(id, page, tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const requestObj_listDetail = httpFetch(this.getSongListDetailUrl(id, page), { headers: this.defaultHeaders });
      return requestObj_listDetail.then(({ body }) => {
        if (body.code !== this.successCode) return this.getListDetailList(id, page, ++tryNum);
        return {
          list: filterMusicInfoListV5(body.data.songList),
          page,
          limit: this.limit_song,
          total: body.data.totalCount,
          source: "mg"
        };
      });
    },
    getListDetailInfo(id, tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      if (this.cachedDetailInfo[id]) return Promise.resolve(this.cachedDetailInfo[id]);
      const requestObj_listDetailInfo = httpFetch(`https://c.musicapp.migu.cn/MIGUM3.0/resource/playlist/v2.0?playlistId=${id}`, {
        headers: this.defaultHeaders
      });
      return requestObj_listDetailInfo.then(({ body }) => {
        if (body.code !== this.successCode) return this.getListDetail(id, ++tryNum);
        const cachedDetailInfo = this.cachedDetailInfo[id] = {
          name: body.data.title,
          img: body.data.imgItem.img,
          desc: body.data.summary,
          author: body.data.ownerName,
          play_count: formatPlayCount(body.data.opNumItem.playNum)
        };
        return cachedDetailInfo;
      });
    },
    async getDetailUrl(link, page, retryNum = 0) {
      if (retryNum > 3) return Promise.reject(new Error("link try max num"));
      const requestObj_listDetailLink = httpFetch(link, {
        headers: {
          "User-Agent": "Mozilla/5.0 (iPhone; CPU iPhone OS 9_1 like Mac OS X) AppleWebKit/601.1.46 (KHTML, like Gecko) Version/9.0 Mobile/13B143 Safari/601.1",
          Referer: link
        }
      });
      const { url: location, statusCode } = await requestObj_listDetailLink;
      if (statusCode > 400) return this.getDetailUrl(link, page, ++retryNum);
      if (location.split("?")[0] != link.split("?")[0]) {
        this.cachedUrl[link] = location;
        return this.getListDetail(location, page);
      }
      return Promise.reject(new Error("link get failed"));
    },
    getListDetail(id, page, retryNum = 0) {
      if (/\/playlist[/?]/.test(id)) {
        id = /(?:playlistId|id)=(\d+)/.exec(id)?.[1];
        if (!id) throw new Error("list detail id parse failed");
      } else if (this.regExps.listDetailLink.test(id)) {
        id = id.replace(this.regExps.listDetailLink, "$1");
      } else if (/[?&:/]/.test(id)) {
        const url = this.cachedUrl[id];
        return url ? this.getListDetail(url, page) : this.getDetailUrl(id, page);
      }
      return Promise.all([
        this.getListDetailList(id, page, retryNum),
        this.getListDetailInfo(id, retryNum)
      ]).then(([listData, info]) => {
        listData.info = info;
        return listData;
      });
    },
    // 获取列表数据
    getList(sortId, tagId, page, tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const request = httpFetch(this.getSongListUrl(sortId, tagId, page), {
        headers: this.defaultHeaders
        // headers: {
        //   sign: 'c3b7ae985e2206e97f1b2de8f88691e2',
        //   timestamp: 1578225871982,
        //   appId: 'yyapp2',
        //   mode: 'android',
        //   ua: 'Android_migu',
        //   version: '6.9.4',
        //   osVersion: 'android 7.0',
        //   'User-Agent': 'okhttp/3.9.1',
        // },
      });
      return request.then(({ body }) => {
        if (body.code !== "000000") return this.getList(sortId, tagId, page, ++tryNum);
        const list = body.data.contents ? this.filterList2(body.data.contents) : this.filterList(body.data.contentItemList[1].itemList);
        return {
          list,
          total: 99999,
          page,
          limit: this.limit_list,
          source: "mg"
        };
      });
    },
    filterList2(listData, list = [], ids = /* @__PURE__ */ new Set()) {
      for (const item of listData) {
        if (item.contents) this.filterList2(item.contents, list, ids);
        else if (item.resType == "2021" && !ids.has(item.resId)) {
          ids.add(item.resId);
          list.push({
            id: String(item.resId),
            author: "",
            name: item.txt,
            // time: dateFormat(item.createTime, 'Y-M-D'),
            img: item.img,
            // grade: item.grade,
            // total: item.contentCount,
            desc: item.txt2,
            source: "mg"
          });
        }
      }
      return list;
    },
    filterList(rawData) {
      return rawData.map((item) => ({
        play_count: item.barList[0]?.title,
        id: String(item.logEvent.contentId),
        author: "",
        name: item.title,
        // time: dateFormat(item.createTime, 'Y-M-D'),
        img: item.imageUrl,
        // grade: item.grade,
        // total: item.contentCount,
        desc: "",
        source: "mg"
      }));
    },
    // 获取标签
    getTag(tryNum = 0) {
      if (tryNum > 2) return Promise.reject(new Error("try max num"));
      const request = httpFetch(this.tagsUrl, { headers: this.defaultHeaders });
      return request.then(({ body }) => {
        if (body.code !== this.successCode) return this.getTag(++tryNum);
        return this.filterTagInfo(body.data);
      });
    },
    filterTagInfo(rawList) {
      return {
        hotTag: rawList[0].content.map(({ texts: [name, id] }) => ({
          id,
          name,
          source: "mg"
        })),
        tags: rawList.slice(1).map(({ header, content }) => ({
          name: header.title,
          list: content.map(({ texts: [name, id] }) => ({
            // parent_id: objectInfo.columnId,
            // parent_name: objectInfo.columnTitle,
            id,
            name,
            source: "mg"
          }))
        })),
        source: "mg"
      };
    },
    getTags() {
      return this.getTag();
    },
    getDetailPageUrl(id) {
      if (/playlist\/index\.html\?/.test(id)) {
        id = id.replace(/.*(?:\?|&)id=(\d+)(?:&.*|$)/, "$1");
      } else if (this.regExps.listDetailLink.test(id)) {
        id = id.replace(this.regExps.listDetailLink, "$1");
      }
      return `https://music.migu.cn/v3/music/playlist/${id}`;
    },
    filterSongListResult(raw) {
      const list = [];
      raw.forEach((item) => {
        if (!item.id) return;
        const playCount = parseInt(item.playNum);
        list.push({
          play_count: isNaN(playCount) ? 0 : formatPlayCount(playCount),
          id: item.id,
          author: item.userName,
          name: item.name,
          img: item.musicListPicUrl,
          total: item.musicNum,
          source: "mg"
        });
      });
      return list;
    },
    search(text, page, limit = 20) {
      const timeStr = Date.now().toString();
      const signResult = createSignature(timeStr, text);
      return createHttpFetch2(`https://jadeite.migu.cn/music_search/v3/search/searchAll?isCorrect=1&isCopyright=1&searchSwitch=%7B%22song%22%3A0%2C%22album%22%3A0%2C%22singer%22%3A0%2C%22tagSong%22%3A0%2C%22mvSong%22%3A0%2C%22bestShow%22%3A0%2C%22songlist%22%3A1%2C%22lyricSong%22%3A0%7D&pageSize=${limit}&text=${encodeURIComponent(text)}&pageNo=${page}&sort=0&sid=USS`, {
        headers: {
          uiVersion: "A_music_3.6.1",
          deviceId: signResult.deviceId,
          timestamp: timeStr,
          sign: signResult.sign,
          channel: "0146921",
          "User-Agent": "Mozilla/5.0 (Linux; U; Android 11.0.0; zh-cn; MI 11 Build/OPR1.170623.032) AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30"
        }
      }).then((body) => {
        if (!body.songListResultData) throw new Error("get song list faild.");
        const list = this.filterSongListResult(body.songListResultData.result);
        return {
          list,
          limit,
          total: parseInt(body.songListResultData.totalCount),
          source: "mg"
        };
      });
    }
  };

  // src/musicSdk/mg/leaderboard.js
  var boardList4 = [
    {
      id: "mg__27553319",
      name: "新歌榜",
      bangid: "27553319",
      source: "mg"
    },
    {
      id: "mg__27186466",
      name: "热歌榜",
      bangid: "27186466",
      source: "mg"
    },
    {
      id: "mg__27553408",
      name: "原创榜",
      bangid: "27553408",
      source: "mg"
    },
    {
      id: "mg__75959118",
      name: "音乐风向榜",
      bangid: "75959118",
      source: "mg"
    },
    {
      id: "mg__76557036",
      name: "彩铃分贝榜",
      bangid: "76557036",
      source: "mg"
    },
    {
      id: "mg__76557745",
      name: "会员臻爱榜",
      bangid: "76557745",
      source: "mg"
    },
    {
      id: "mg__23189800",
      name: "港台榜",
      bangid: "23189800",
      source: "mg"
    },
    {
      id: "mg__23189399",
      name: "内地榜",
      bangid: "23189399",
      source: "mg"
    },
    {
      id: "mg__19190036",
      name: "欧美榜",
      bangid: "19190036",
      source: "mg"
    },
    {
      id: "mg__83176390",
      name: "国风金曲榜",
      bangid: "83176390",
      source: "mg"
    }
  ];
  var leaderboard_default5 = {
    limit: 200,
    list: [
      {
        id: "mgyyb",
        name: "音乐榜",
        bangid: "27553319"
      },
      {
        id: "mgysb",
        name: "影视榜",
        bangid: "23603721"
      },
      {
        id: "mghybnd",
        name: "华语内地榜",
        bangid: "23603926"
      },
      {
        id: "mghyjqbgt",
        name: "华语港台榜",
        bangid: "23603954"
      },
      {
        id: "mgomb",
        name: "欧美榜",
        bangid: "23603974"
      },
      {
        id: "mgrhb",
        name: "日韩榜",
        bangid: "23603982"
      },
      {
        id: "mgwlb",
        name: "网络榜",
        bangid: "23604058"
      },
      {
        id: "mgclb",
        name: "彩铃榜",
        bangid: "23604023"
      },
      {
        id: "mgktvb",
        name: "KTV榜",
        bangid: "23604040"
      },
      {
        id: "mgrcb",
        name: "原创榜",
        bangid: "23604032"
      }
    ],
    getUrl(id, page, limit) {
      const paging = limit ? `&pageSize=${limit}&pageNo=${Math.max(0, page - 1)}` : "";
      return `https://app.c.nf.migu.cn/MIGUM2.0/v1.0/content/querycontentbyId.do?columnId=${id}&needAll=0${paging}`;
    },
    successCode: "000000",
    getBoardsData() {
      const request = httpFetch("https://app.c.nf.migu.cn/pc/bmw/rank/rank-index/v1.0", {
        // const request = httpFetch('https://app.c.nf.migu.cn/MIGUM3.0/v1.0/template/rank-list/release', {
        // const request = httpFetch('https://app.c.nf.migu.cn/MIGUM2.0/v2.0/content/indexrank.do?templateVersion=8', {
        headers: {
          Referer: "https://app.c.nf.migu.cn/",
          "User-Agent": "Mozilla/5.0 (Linux; Android 5.1.1; Nexus 6 Build/LYZ28E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.115 Mobile Safari/537.36",
          channel: "0146921"
        }
      });
      return request;
    },
    getData(url) {
      const requestObj = httpFetch(url);
      return requestObj;
    },
    // filterBoardsData(listData, list = [], ids = new Set()) {
    //   for (const item of listData) {
    //     if (item.rankId && !ids.has(item.rankId)) {
    //       ids.add(item.rankId)
    //       list.push({
    //         id: 'mg__' + item.rankId,
    //         name: item.rankName,
    //         bangid: String(item.rankId),
    //         source: 'mg',
    //       })
    //     } else if (item.contents) this.filterBoardsData(item.contents, list, ids)
    //   }
    //   return list
    // },
    // filterBoardsData(rawList) {
    //   // console.log(rawList)
    //   let list = []
    //   for (const board of rawList) {
    //     if (board.template != 'group1') continue
    //     for (const item of board.itemList) {
    //       if ((item.template != 'row1' && item.template != 'grid1' && !item.actionUrl) || !item.actionUrl.includes('rank-info')) continue
    //       let data = item.displayLogId.param
    //       list.push({
    //         id: 'mg__' + data.rankId,
    //         name: data.rankName,
    //         bangid: String(data.rankId),
    //       })
    //     }
    //   }
    //   return list
    // },
    async getBoards(retryNum = 0) {
      try {
        const response = await this.getBoardsData();
        if (response && response.statusCode === 200 && response.body?.code === this.successCode) {
          const list = [];
          const ids = /* @__PURE__ */ new Set();
          const walk = (nodes) => {
            for (const item of nodes || []) {
              if (item.rankId && !ids.has(item.rankId)) {
                ids.add(item.rankId);
                if (item.rankName) {
                  list.push({ id: "mg__" + item.rankId, name: item.rankName, bangid: String(item.rankId), img: item.imageUrl || null });
                }
              }
              if (Array.isArray(item.contents) && item.contents.length) walk(item.contents);
            }
          };
          walk(response.body.data?.contents);
          if (list.length) {
            this.list = list;
            return { list, source: "mg" };
          }
        }
      } catch (error) {
      }
      this.list = boardList4;
      return {
        list: boardList4,
        source: "mg"
      };
    },
    getList(bangid, page, limit, retryNum = 0) {
      if (++retryNum > 3) return Promise.reject(new Error("try max num"));
      const pageSize = limit || this.limit;
      return this.getData(this.getUrl(bangid, page, limit)).then(({ statusCode, body }) => {
        if (statusCode !== 200 || body.code !== this.successCode) return this.getList(bangid, page, limit, retryNum);
        const list = filterMusicInfoList(body.columnInfo.contents.map((m) => m.objectInfo));
        const boundedList = limit ? list.slice(0, pageSize) : list;
        return {
          total: boundedList.length,
          list: boundedList,
          limit: pageSize,
          page,
          source: "mg"
        };
      });
    },
    getDetailPageUrl(id) {
      if (typeof id == "string") id = id.replace("mg__", "");
      for (const item of boardList4) {
        if (item.bangid == id) {
          return `https://music.migu.cn/v3/music/top/${item.webId}`;
        }
      }
      return null;
    }
  };

  // src/musicSdk/mg/hotSearch.js
  var hotSearch_default5 = {
    async getList(retryNum = 0) {
      if (retryNum > 2) return Promise.reject(new Error("try max num"));
      const _requestObj = httpFetch("http://jadeite.migu.cn:7090/music_search/v3/search/hotword");
      const { body, statusCode } = await _requestObj;
      if (statusCode != 200 || body.code !== "000000") throw new Error("获取热搜词失败");
      return { source: "mg", list: this.filterList(body.data.hotwords[0].hotwordList) };
    },
    filterList(rawList) {
      return rawList.filter((item) => item.resourceType == "song").map((item) => item.word);
    }
  };

  // src/musicSdk/mg/tipSearch.js
  var tipSearch_default5 = {
    // 咪咕官方 suggest 接口已下线（v3 站点 301），以轻量搜索结果作为联想词兜底。
    async search(str) {
      return musicSearch_default5.search(str, 1, 6).then(({ list }) => (list || []).map((item) => `${item.name} - ${item.singer}`));
    }
  };

  // src/musicSdk/mg/utils/mrc.js
  var DELTA = 2654435769n;
  var MIN_LENGTH = 32;
  var keyArr = [
    27303562373562475n,
    18014862372307051n,
    22799692160172081n,
    34058940340699235n,
    30962724186095721n,
    27303523720101991n,
    27303523720101998n,
    31244139033526382n,
    28992395054481524n
  ];
  var teaDecrypt = (data, key) => {
    const length = data.length;
    const lengthBitint = BigInt(length);
    if (length >= 1) {
      let j2 = data[0];
      let j3 = toLong((6n + 52n / lengthBitint) * DELTA);
      while (true) {
        let j4 = j3;
        if (j4 == 0n) break;
        let j5 = toLong(3n & toLong(j4 >> 2n));
        let j6 = lengthBitint;
        while (true) {
          j6--;
          if (j6 > 0n) {
            let j7 = data[j6 - 1n];
            let i = j6;
            j2 = toLong(data[i] - (toLong(toLong(j2 ^ j4) + toLong(j7 ^ key[toLong(toLong(3n & j6) ^ j5)])) ^ toLong(toLong(toLong(j7 >> 5n) ^ toLong(j2 << 2n)) + toLong(toLong(j2 >> 3n) ^ toLong(j7 << 4n)))));
            data[i] = j2;
          } else break;
        }
        let j8 = data[lengthBitint - 1n];
        j2 = toLong(data[0n] - toLong(toLong(toLong(key[toLong(toLong(j6 & 3n) ^ j5)] ^ j8) + toLong(j2 ^ j4)) ^ toLong(toLong(toLong(j8 >> 5n) ^ toLong(j2 << 2n)) + toLong(toLong(j2 >> 3n) ^ toLong(j8 << 4n)))));
        data[0] = j2;
        j3 = toLong(j4 - DELTA);
      }
    }
    return data;
  };
  var longArrToString = (data) => {
    const arrayList = [];
    for (const j of data) arrayList.push(longToBytes(j).toString("utf16le"));
    return arrayList.join("");
  };
  var longToBytes = (l) => {
    const result = Buffer.alloc(8);
    for (let i = 0; i < 8; i++) {
      result[i] = parseInt(l & 0xFFn);
      l >>= 8n;
    }
    return result;
  };
  var toBigintArray = (data) => {
    const length = Math.floor(data.length / 16);
    const jArr = Array(length);
    for (let i = 0; i < length; i++) {
      jArr[i] = toLong(data.substring(i * 16, i * 16 + 16));
    }
    return jArr;
  };
  var MAX = 9223372036854775807n;
  var MIN = -9223372036854775808n;
  var toLong = (str) => {
    const num = typeof str == "string" ? BigInt("0x" + str) : str;
    if (num > MAX) return toLong(num - (1n << 64n));
    else if (num < MIN) return toLong(num + (1n << 64n));
    return num;
  };
  var decrypt = (data) => {
    return data == null || data.length < MIN_LENGTH ? data : longArrToString(teaDecrypt(toBigintArray(data), keyArr));
  };

  // src/musicSdk/mg/lyric.js
  var mrcTools = {
    rxps: {
      lineTime: /^\s*\[(\d+),\d+\]/,
      wordTime: /\(\d+,\d+\)/,
      wordTimeAll: /(\(\d+,\d+\))/g
    },
    parseLyric(str) {
      str = str.replace(/\r/g, "");
      const lines = str.split("\n");
      const lxlrcLines = [];
      const lrcLines = [];
      for (const line of lines) {
        if (line.length < 6) continue;
        let result = this.rxps.lineTime.exec(line);
        if (!result) continue;
        const startTime = parseInt(result[1]);
        let time = startTime;
        let ms = (time % 1e3).toString().padStart(3, "0");
        time /= 1e3;
        let m = parseInt(time / 60).toString().padStart(2, "0");
        time %= 60;
        let s = parseInt(time).toString().padStart(2, "0");
        time = `${m}:${s}.${ms}`;
        let words = line.replace(this.rxps.lineTime, "");
        lrcLines.push(`[${time}]${words.replace(this.rxps.wordTimeAll, "")}`);
        let times = words.match(this.rxps.wordTimeAll);
        if (!times) continue;
        times = times.map((time2) => {
          const result2 = /\((\d+),(\d+)\)/.exec(time2);
          return `<${Math.trunc(parseInt(result2[1]) - startTime)},${result2[2]}>`;
        });
        const wordArr = words.split(this.rxps.wordTime);
        const newWords = times.map((time2, index) => `${time2}${wordArr[index]}`).join("");
        lxlrcLines.push(`[${time}]${newWords}`);
      }
      return {
        lyric: lrcLines.join("\n"),
        lxlyric: lxlrcLines.join("\n")
      };
    },
    getText(url, tryNum = 0) {
      const requestObj = httpFetch(url, {
        headers: {
          Referer: "https://app.c.nf.migu.cn/",
          "User-Agent": "Mozilla/5.0 (Linux; Android 5.1.1; Nexus 6 Build/LYZ28E) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.115 Mobile Safari/537.36",
          channel: "0146921"
        }
      });
      return requestObj.then(({ statusCode, body }) => {
        if (statusCode == 200) return body;
        if (tryNum > 5 || statusCode == 404) return Promise.reject(new Error("歌词获取失败"));
        return this.getText(url, ++tryNum);
      });
    },
    getMrc(url) {
      return this.getText(url).then((text) => {
        return this.parseLyric(decrypt(text));
      });
    },
    getLrc(url) {
      return this.getText(url).then((text) => ({ lxlyric: "", lyric: text }));
    },
    getTrc(url) {
      if (!url) return Promise.resolve("");
      return this.getText(url);
    },
    async getMusicInfo(songInfo) {
      return songInfo.mrcUrl == null ? getMusicInfo(songInfo.songmid) : songInfo;
    },
    getLyric(songInfo) {
      return this.getMusicInfo(songInfo).then((info) => {
        let p;
        if (info.mrcUrl) p = this.getMrc(info.mrcUrl);
        else if (info.lrcUrl) p = this.getLrc(info.lrcUrl);
        if (p == null) return Promise.reject(new Error("获取歌词失败"));
        return Promise.all([p, this.getTrc(info.trcUrl)]).then(([lrcInfo, tlyric]) => {
          lrcInfo.tlyric = tlyric;
          return lrcInfo;
        });
      });
    }
  };
  var lyric_default5 = {
    getLyric(songInfo) {
      return mrcTools.getLyric(songInfo);
    }
  };

  // src/musicSdk/mg/pic.js
  var pic_default3 = {
    async getPic(songInfo) {
      const info = await getMusicInfo(songInfo.songmid);
      return info.img;
    }
  };

  // entry.js
  var PLATFORMS = {
    kw: { search: musicSearch_default, songList: songList_default, leaderboard: leaderboard_default, hotSearch: hotSearch_default, tipSearch: tipSearch_default, lyric: lyric_default, pic: pic_default },
    kg: { search: musicSearch_default2, songList: songList_default2, leaderboard: leaderboard_default2, hotSearch: hotSearch_default2, tipSearch: tipSearch_default2, lyric: lyric_default2, pic: pic_default2 },
    tx: { search: musicSearch_default3, songList: songList_default3, leaderboard: leaderboard_default3, hotSearch: hotSearch_default3, tipSearch: tipSearch_default3, lyric: lyric_default3 },
    wy: { search: musicSearch_default4, songList: songList_default4, leaderboard: leaderboard_default4, hotSearch: hotSearch_default4, tipSearch: tipSearch_default4, lyric: wyLyricAdapter(lyric_default4), musicInfo: musicInfo_default2 },
    mg: { search: musicSearch_default5, songList: songList_default5, leaderboard: leaderboard_default5, hotSearch: hotSearch_default5, tipSearch: tipSearch_default5, lyric: lyric_default5, pic: pic_default3 }
  };
  function wyLyricAdapter(fn) {
    return { getLyric: (songInfo) => fn(songInfo.songmid ?? songInfo.songId) };
  }
  var QUALITY_ORDER = ["master", "atmos_plus", "atmos", "hires", "flac24bit", "flac", "ape", "wav", "320k", "192k", "128k"];
  var decodeEscapes = (text) => typeof text === "string" ? text.replace(/\\+u([0-9a-fA-F]{4})/g, (_, code) => String.fromCharCode(parseInt(code, 16))) : text;
  var normalizeSongs = (list, source) => (Array.isArray(list) ? list : []).map((raw) => normalizeSong(raw, source));
  var normalizeSong = (raw, source) => {
    const out = {};
    for (const key in raw) {
      const value = raw[key];
      if (typeof value === "function" || value === void 0) continue;
      out[key] = value;
    }
    out.source = out.source || source;
    out.songmid = String(out.songmid ?? "");
    out.name = decodeEscapes(out.name ?? "");
    out.singer = decodeEscapes(out.singer ?? "");
    out.albumName = decodeEscapes(out.albumName ?? "");
    out.interval = out.interval && out.interval !== "--/--" ? out.interval : "00:00";
    if (!Array.isArray(out.types)) out.types = [];
    if (!out._types || typeof out._types !== "object") out._types = {};
    out.types.sort((a, b) => QUALITY_ORDER.indexOf(a.type) - QUALITY_ORDER.indexOf(b.type));
    if (!out.img) out.img = coverFallback(source, out);
    return out;
  };
  var coverFallback = (source, song) => {
    if (source === "tx" && song.albumMid) return `https://y.gtimg.cn/music/photo_new/T002R500x500M000${song.albumMid}.jpg`;
    return null;
  };
  var normalizePlaylists = (list, source) => (Array.isArray(list) ? list : []).map((raw) => {
    const out = {};
    for (const key in raw) {
      const value = raw[key];
      if (typeof value === "function" || value === void 0) continue;
      out[key] = value;
    }
    out.source = out.source || source;
    out.id = String(out.id ?? "");
    out.name = decodeEscapes(out.name ?? "");
    out.img = out.img ?? null;
    out.play_count = out.play_count ?? out.playCount ?? "";
    out.total = out.total ?? 0;
    out.author = decodeEscapes(out.author ?? out.uname ?? "");
    return out;
  });
  var lyricsOf = (raw) => {
    if (!raw || typeof raw !== "object") return { lyric: typeof raw === "string" ? raw : "", tlyric: "", rlyric: "", lxlyric: "" };
    return {
      lyric: raw.lyric ?? raw.lrc ?? "",
      tlyric: raw.tlyric ?? raw.tlrc ?? "",
      rlyric: raw.rlyric ?? "",
      lxlyric: raw.lxlyric ?? "",
      raw: raw.raw ?? null
    };
  };
  async function dispatch(action, source, params = {}) {
    if (params.channel) setDataChannel(params.channel);
    const platform = PLATFORMS[source];
    if (!platform) throw new Error(`不支持的平台: ${source}`);
    switch (action) {
      case "search": {
        const result = await platform.search.search(params.text, params.page || 1, params.limit || 30);
        return {
          kind: "songs",
          source,
          list: normalizeSongs(result.list, source),
          total: result.total ?? 0,
          allPage: result.allPage ?? 1,
          limit: result.limit ?? 30,
          page: params.page || 1
        };
      }
      case "songlistSearch": {
        if (!platform.songList || !platform.songList.search) throw new Error(`${source} 不支持歌单搜索`);
        const result = await platform.songList.search(params.text, params.page || 1, params.limit || 20);
        return {
          kind: "playlists",
          source,
          list: normalizePlaylists(result.list, source),
          total: result.total ?? 0,
          page: params.page || 1
        };
      }
      case "hotSearch": {
        const result = await platform.hotSearch.getList();
        return { kind: "words", source, list: result && result.list || [] };
      }
      case "tipSearch": {
        const result = await platform.tipSearch.search(params.text);
        return { kind: "words", source, list: Array.isArray(result) ? result : result && result.list || [] };
      }
      case "boards": {
        const result = await platform.leaderboard.getBoards();
        return {
          kind: "boards",
          source,
          list: (result && result.list || []).map((item) => ({
            id: String(item.id ?? ""),
            name: item.name ?? "",
            bangid: String(item.bangid ?? item.id ?? ""),
            img: item.img ?? null
          }))
        };
      }
      case "boardSongs": {
        const result = await platform.leaderboard.getList(params.bangId, params.page || 1, params.limit);
        return {
          kind: "songs",
          source,
          list: normalizeSongs(result.list, source),
          total: result.total ?? 0,
          allPage: result.allPage ?? 0,
          limit: result.limit ?? 30,
          page: params.page || 1
        };
      }
      case "playlistTags": {
        const result = await platform.songList.getTags();
        return {
          kind: "tags",
          source,
          hotTag: (result && result.hotTag || []).map((item) => ({ id: String(item.id ?? ""), name: item.name ?? "" })),
          tags: (result && result.tags || []).map((group) => ({
            name: group.name ?? "",
            list: (group.list || []).map((item) => ({ id: String(item.id ?? ""), name: item.name ?? "" }))
          }))
        };
      }
      case "playlists": {
        const result = await platform.songList.getList(params.sortId ?? "hot", params.tagId ?? "", params.page || 1);
        return {
          kind: "playlists",
          source,
          list: normalizePlaylists(result.list, source),
          total: result.total ?? 0,
          page: params.page || 1
        };
      }
      case "playlistSongs": {
        const result = await platform.songList.getListDetail(params.id, params.page || 1);
        return {
          kind: "songs",
          source,
          list: normalizeSongs(result.list, source),
          total: result.total ?? 0,
          allPage: result.allPage ?? 0,
          limit: result.limit ?? 30,
          page: params.page || 1
        };
      }
      case "lyric": {
        const result = await platform.lyric.getLyric(params.song);
        return Object.assign({ kind: "lyric", source }, lyricsOf(result));
      }
      case "pic": {
        if (platform.pic) {
          const url = await platform.pic.getPic(params.song);
          return { kind: "pic", source, url: typeof url === "string" ? url : null };
        }
        if (source === "tx") {
          return { kind: "pic", source, url: coverFallback("tx", params.song) };
        }
        if (source === "wy") {
          const info = await platform.musicInfo(params.song.songmid);
          return { kind: "pic", source, url: info && info.img || null };
        }
        return { kind: "pic", source, url: null };
      }
      default:
        throw new Error(`不支持的动作: ${action}`);
    }
  }
  var pendingResult;
  globalThis.__meloraInvoke = (payloadJson) => {
    pendingResult = void 0;
    let payload;
    try {
      payload = JSON.parse(payloadJson);
    } catch (error) {
      pendingResult = JSON.stringify({ ok: false, error: "调用参数解析失败" });
      return;
    }
    Promise.resolve().then(() => dispatch(payload.action, payload.source, payload.params || {})).then(
      (data) => {
        pendingResult = JSON.stringify({ ok: true, data });
      },
      (error) => {
        pendingResult = JSON.stringify({ ok: false, error: String(globalThis.__meloraDebug ? error && error.stack || error : error && error.message || error) });
      }
    );
  };
  globalThis.__meloraTake = () => {
    const value = pendingResult;
    pendingResult = void 0;
    return value === void 0 ? "" : value;
  };
})();
