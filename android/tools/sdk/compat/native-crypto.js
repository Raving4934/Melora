// 原生加密兼容层：crypto 导出子集。
import { Buffer } from 'buffer'

const native = globalThis.__lxNative

export const RSA_PADDING = {
  OAEPWithSHA1AndMGF1Padding: 'RSA/ECB/OAEPWithSHA1AndMGF1Padding',
  NoPadding: 'RSA/ECB/NoPadding',
}

export const AES_MODE = {
  CBC_128_PKCS7Padding: 'AES/CBC/PKCS7Padding',
  ECB_128_NoPadding: 'AES',
}

const modeToNative = (mode) => {
  if (mode === AES_MODE.CBC_128_PKCS7Padding) return 'aes-128-cbc'
  // 注意：AES_MODE.ECB_128_NoPadding 实际传入 "AES"，
  // Java Cipher.getInstance("AES") 等价 AES/ECB/PKCS5Padding（与官方 eapi 实现一致）。
  if (mode === AES_MODE.ECB_128_NoPadding || mode === 'AES') return 'aes-128-ecb'
  return String(mode)
}

export const aesEncryptSync = (textBase64, key, iv, mode) => native.aes(
  'encrypt',
  modeToNative(mode),
  key.toString('base64'),
  iv ? iv.toString('base64') : '',
  textBase64,
)

export const aesDecryptSync = (textBase64, key, iv, mode) => Buffer.from(
  native.aes(
    'decrypt',
    modeToNative(mode),
    key.toString('base64'),
    iv ? iv.toString('base64') : '',
    textBase64,
  ),
  'base64',
).toString('utf8')

export const rsaEncryptSync = (textBase64, keyPem, padding) => native.rsaEncrypt(textBase64, keyPem, padding || RSA_PADDING.NoPadding)

export const hashSHA1 = (text) => native.hash('sha1', Buffer.from(String(text), 'utf8').toString('base64'))

export const hashMD5 = (text) => native.hash('md5', Buffer.from(String(text), 'utf8').toString('base64'))
