// react-native-quick-base64 兼容层。
import { Buffer } from 'buffer'

export const btoa = (text) => Buffer.from(String(text), 'utf8').toString('base64')

export const atob = (text) => Buffer.from(String(text), 'base64').toString('utf8')
