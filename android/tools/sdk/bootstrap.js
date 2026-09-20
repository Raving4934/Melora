// 全局环境注入：必须在所有 musicSdk 模块求值前执行（写入 globalThis.Buffer 等）。
import { Buffer } from 'buffer'

globalThis.Buffer = globalThis.Buffer || Buffer
globalThis.global = globalThis.global || globalThis
globalThis.process = globalThis.process || { env: {}, versions: { app: '2.12.2' } }
if (typeof globalThis.console === 'undefined') {
  globalThis.console = { log() {}, warn() {}, error() {}, info() {}, debug() {} }
}
