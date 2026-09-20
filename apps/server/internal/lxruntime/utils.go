package lxruntime

import (
	"bytes"
	"compress/zlib"
	"crypto/aes"
	"crypto/cipher"
	"crypto/md5"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/pem"
	"io"
	"math"
	"math/big"
	"reflect"
	"strings"
	"unicode/utf16"

	"github.com/dop251/goja"
)

const maxBufferBytes = 1 << 20

func (w *runtimeWorker) buffer(data []byte) goja.Value {
	if len(data) > maxBufferBytes {
		w.fail(ErrLimit)
	}
	return w.vm.ToValue(w.vm.NewArrayBuffer(data))
}
func (w *runtimeWorker) bytes(value goja.Value, encoding string) []byte {
	if goja.IsUndefined(value) || goja.IsNull(value) {
		w.fail(ErrScript)
	}
	if _, ok := value.(goja.String); ok {
		text := value.String()
		if len(text) > maxBufferBytes*2 {
			w.fail(ErrLimit)
		}
		var data []byte
		var err error
		switch strings.ToLower(encoding) {
		case "", "utf8", "utf-8":
			data = []byte(text)
		case "hex":
			// Node Buffer.from(hex) 只消费首个完整合法前缀；奇数尾半字节/坏字符截断。
			end := 0
			for end < len(text) {
				c := text[end]
				if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F') {
					break
				}
				end++
			}
			data, err = hex.DecodeString(text[:end&^1])
		case "base64", "base64url":
			text = strings.Join(strings.Fields(text), "")
			// Node 的 base64 同样接受 URL-safe 字母表；其余无效字符仍拒绝。
			text = strings.ReplaceAll(strings.ReplaceAll(text, "-", "+"), "_", "/")
			data, err = base64.StdEncoding.DecodeString(text)
			if err != nil {
				data, err = base64.RawStdEncoding.DecodeString(strings.TrimRight(text, "="))
			}
		case "latin1", "binary", "ascii":
			units := utf16.Encode([]rune(text))
			data = make([]byte, len(units))
			for i, u := range units {
				data[i] = byte(u)
			}
		case "utf16le", "ucs2", "ucs-2":
			units := utf16.Encode([]rune(text))
			data = make([]byte, len(units)*2)
			for i, u := range units {
				data[2*i] = byte(u)
				data[2*i+1] = byte(u >> 8)
			}
		default:
			w.fail(w.missing("lx.utils.buffer.encoding"))
		}
		if err != nil {
			w.fail(ErrScript)
		}
		if len(data) > maxBufferBytes {
			w.fail(ErrLimit)
		}
		return data
	}
	object := value.ToObject(w.vm)
	length := object.Get("byteLength")
	if length == nil || goja.IsUndefined(length) {
		length = object.Get("length")
	}
	if length == nil || goja.IsUndefined(length) || length.ToInteger() < 0 || length.ToInteger() > maxBufferBytes {
		w.fail(ErrLimit)
	}
	if array, ok := value.Export().(goja.ArrayBuffer); ok {
		data := array.Bytes()
		if len(data) > maxBufferBytes {
			w.fail(ErrLimit)
		}
		return append([]byte(nil), data...)
	}
	var data []byte
	if w.vm.ExportTo(value, &data) != nil {
		w.fail(ErrScript)
	}
	if len(data) > maxBufferBytes {
		w.fail(ErrLimit)
	}
	return data
}
func (w *runtimeWorker) text(data []byte, encoding string) string {
	switch strings.ToLower(encoding) {
	case "", "utf8", "utf-8":
		return strings.ToValidUTF8(string(data), "�")
	case "hex":
		return hex.EncodeToString(data)
	case "base64":
		return base64.StdEncoding.EncodeToString(data)
	case "base64url":
		return base64.RawURLEncoding.EncodeToString(data)
	case "latin1", "binary", "ascii":
		runes := make([]rune, len(data))
		for i, b := range data {
			if encoding == "ascii" {
				b &= 0x7f
			}
			runes[i] = rune(b)
		}
		return string(runes)
	case "utf16le", "ucs2", "ucs-2":
		units := make([]uint16, len(data)/2)
		for i := range units {
			units[i] = uint16(data[2*i]) | uint16(data[2*i+1])<<8
		}
		return string(utf16.Decode(units))
	default:
		w.fail(w.missing("lx.utils.buffer.encoding"))
		return ""
	}
}
func (w *runtimeWorker) installUtils(native *goja.Object) {
	_ = native.Set("bufferFrom", func(call goja.FunctionCall) goja.Value {
		value := call.Argument(0)
		// 先检查原生导出类型，避免为了探测 ArrayBuffer 遍历任意用户对象。
		if value.ExportType() == reflect.TypeOf(goja.ArrayBuffer{}) {
			data := value.Export().(goja.ArrayBuffer).Bytes()
			if len(data) > maxBufferBytes {
				w.fail(ErrLimit) // 切小片段也不能绕过原有输入字节上限。
			}
			offset := 0.0
			if !goja.IsUndefined(call.Argument(1)) {
				offset = call.Argument(1).ToFloat()
				if math.IsNaN(offset) {
					offset = 0
				}
			}
			if math.IsInf(offset, 0) || math.Trunc(offset) < 0 || offset > float64(len(data)) {
				w.fail(ErrScript)
			}
			start, end := int(math.Trunc(offset)), len(data)
			if !goja.IsUndefined(call.Argument(2)) {
				length := call.Argument(2).ToFloat()
				if math.IsNaN(length) || length <= 0 {
					length = 0
				}
				if length > float64(len(data))-offset {
					w.fail(ErrScript)
				}
				end = start + int(math.Trunc(length))
			}
			return w.buffer(append([]byte(nil), data[start:end]...))
		}
		if object, ok := value.(*goja.Object); ok {
			if kind, ok := object.Get("type").(goja.String); ok && kind.String() == "Buffer" {
				data := object.Get("data")
				array, ok := data.(*goja.Object)
				if !ok || array.ClassName() != "Array" {
					w.fail(ErrScript)
				}
				if array.Get("length").ToInteger() > maxBufferBytes {
					w.fail(ErrLimit)
				}
				value = data
			}
		}
		encoding := "utf8"
		if !goja.IsUndefined(call.Argument(1)) {
			encoding = call.Argument(1).String()
		}
		return w.buffer(w.bytes(value, encoding))
	})
	_ = native.Set("encode", func(call goja.FunctionCall) goja.Value {
		encoding := "utf8"
		if !goja.IsUndefined(call.Argument(1)) {
			encoding = call.Argument(1).String()
		}
		return w.vm.ToValue(w.text(w.bytes(call.Argument(0), "binary"), encoding))
	})
	_ = native.Set("md5", func(call goja.FunctionCall) goja.Value {
		digest := md5.Sum(w.bytes(call.Argument(0), "utf8"))
		return w.vm.ToValue(hex.EncodeToString(digest[:]))
	})
	_ = native.Set("randomBytes", func(call goja.FunctionCall) goja.Value {
		size := call.Argument(0).ToInteger()
		if size < 0 || size > maxBufferBytes {
			w.fail(ErrLimit)
		}
		data := make([]byte, size)
		if _, err := rand.Read(data); err != nil {
			w.fail(ErrScript)
		}
		return w.buffer(data)
	})
	_ = native.Set("aesEncrypt", func(call goja.FunctionCall) goja.Value {
		data := w.bytes(call.Argument(0), "utf8")
		mode := strings.ToLower(call.Argument(1).String())
		key := w.bytes(call.Argument(2), "utf8")
		expected := 0
		algorithm := ""
		switch mode {
		case "aes-128-cbc", "aes-128-ecb", "aes-128-ctr":
			expected = 16
		case "aes-192-cbc", "aes-192-ecb", "aes-192-ctr":
			expected = 24
		case "aes-256-cbc", "aes-256-ecb", "aes-256-ctr":
			expected = 32
		default:
			w.fail(w.missing("lx.utils.crypto.aesEncrypt.mode"))
		}
		algorithm = mode[len(mode)-3:]
		if len(key) != expected {
			w.fail(ErrScript)
		}
		block, err := aes.NewCipher(key)
		if err != nil {
			w.fail(ErrScript)
		}
		var iv []byte
		if !goja.IsUndefined(call.Argument(3)) && !goja.IsNull(call.Argument(3)) {
			iv = w.bytes(call.Argument(3), "utf8")
		}
		if algorithm != "ecb" && len(iv) != aes.BlockSize || algorithm == "ecb" && len(iv) != 0 {
			w.fail(ErrScript)
		}
		if algorithm != "ctr" {
			padding := aes.BlockSize - len(data)%aes.BlockSize
			data = append(data, bytes.Repeat([]byte{byte(padding)}, padding)...)
		}
		encrypted := make([]byte, len(data))
		switch algorithm {
		case "cbc":
			cipher.NewCBCEncrypter(block, iv).CryptBlocks(encrypted, data)
		case "ctr":
			cipher.NewCTR(block, iv).XORKeyStream(encrypted, data)
		case "ecb":
			for offset := 0; offset < len(data); offset += aes.BlockSize {
				block.Encrypt(encrypted[offset:offset+aes.BlockSize], data[offset:offset+aes.BlockSize])
			}
		}
		return w.buffer(encrypted)
	})
	_ = native.Set("rsaEncrypt", func(call goja.FunctionCall) goja.Value {
		data := w.bytes(call.Argument(0), "utf8")
		keyText := call.Argument(1).String()
		if len(data) > 128 || len(keyText) > 8192 {
			w.fail(ErrLimit)
		}
		block, _ := pem.Decode([]byte(keyText))
		if block == nil {
			w.fail(w.missing("lx.utils.crypto.rsaEncrypt.key"))
		}
		var key *rsa.PublicKey
		if parsed, err := x509.ParsePKIXPublicKey(block.Bytes); err == nil {
			key, _ = parsed.(*rsa.PublicKey)
		}
		if key == nil {
			key, _ = x509.ParsePKCS1PublicKey(block.Bytes)
		}
		// 官方 LX 使用固定 128 字节左补零、RSA_NO_PADDING；不冒充其它填充模式。
		if key == nil || key.Size() != 128 || key.E < 3 || key.E > 65537 {
			w.fail(w.missing("lx.utils.crypto.rsaEncrypt.key"))
		}
		value := new(big.Int).SetBytes(data)
		if value.Cmp(key.N) >= 0 {
			w.fail(ErrScript)
		}
		encrypted := new(big.Int).Exp(value, big.NewInt(int64(key.E)), key.N).FillBytes(make([]byte, 128))
		return w.buffer(encrypted)
	})
	_ = native.Set("inflate", func(call goja.FunctionCall) goja.Value {
		reader, err := zlib.NewReader(bytes.NewReader(w.bytes(call.Argument(0), "binary")))
		if err != nil {
			w.fail(ErrScript)
		}
		data, err := io.ReadAll(io.LimitReader(reader, maxBufferBytes+1))
		closeErr := reader.Close()
		if err != nil || closeErr != nil {
			w.fail(ErrScript)
		}
		return w.buffer(data)
	})
	_ = native.Set("deflate", func(call goja.FunctionCall) goja.Value {
		data := w.bytes(call.Argument(0), "binary")
		var output bytes.Buffer
		writer := zlib.NewWriter(&output)
		if _, err := writer.Write(data); err != nil {
			w.fail(ErrScript)
		}
		if writer.Close() != nil {
			w.fail(ErrScript)
		}
		return w.buffer(output.Bytes())
	})
}
