// react-native-quick-md5 兼容层。
import { Buffer } from 'buffer'

const native = globalThis.__lxNative

export const stringMd5 = (text) => native.hash('md5', Buffer.from(String(text), 'utf8').toString('base64'))
