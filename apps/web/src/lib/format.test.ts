import { describe, expect, it } from 'vitest'
import { formatBytes, formatTime, qualityName, formatPlayCount } from './format'
describe('时间与下载大小', () => {
  it.each([
    [0, '00:00'],
    [65, '01:05'],
    [233.8, '03:53'],
    [-5, '00:00'],
    [NaN, '00:00'],
    [Infinity, '00:00'],
  ])('formatTime(%s)', (value, expected) => expect(formatTime(value)).toBe(expected))
  it.each([
    [0, '0 B'],
    [-1, '0 B'],
    [1024, '1.0 KB'],
    [1048576, '1.0 MB'],
    [Infinity, '0 B'],
  ])('formatBytes(%s)', (value, expected) => expect(formatBytes(value)).toBe(expected))
  it('不将 standard 冒充无损音质', () => {
    expect(qualityName('standard')).toBe('自动')
    expect(qualityName('ogg')).toBe('OGG')
  })
})

it('播放量保留真实零值并压缩大数字，不编造未知计数', () => {
  expect(formatPlayCount(0)).toBe('0')
  expect(formatPlayCount(1234)).toBe('1,234')
  expect(formatPlayCount(12500)).toBe('1.3万')
  expect(formatPlayCount(100000000)).toBe('1亿')
  expect(formatPlayCount(NaN)).toBe('')
})
