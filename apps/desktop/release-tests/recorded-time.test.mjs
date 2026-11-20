import {test} from 'node:test'
import assert from 'node:assert/strict'
import {formatRecordedTime} from '../src/release/recordedTime.mjs'

test('recorded UTC instants display the actual local clock and its zone, including a new day',()=>{
  assert.equal(formatRecordedTime('2026-10-30T07:22:22.123Z','Asia/Shanghai'),'2026/10/30 15:22:22（Asia/Shanghai）')
  assert.equal(formatRecordedTime('2026-10-30T20:22:22Z','Asia/Shanghai'),'2026/10/31 04:22:22（Asia/Shanghai）')
  assert.equal(formatRecordedTime('2026-10-30T07:22:22+00:00','America/New_York'),'2026/10/30 03:22:22（America/New_York）')
})
test('timestamps without an offset are labelled instead of inventing a timezone',()=>{
  assert.equal(formatRecordedTime('2026-10-30 07:22:22','Asia/Shanghai'),'2026-10-30 07:22:22（未注明时区）')
  assert.equal(formatRecordedTime(null,'Asia/Shanghai'),'')
  assert.equal(formatRecordedTime('2026-10-30','Asia/Shanghai'),'2026-10-30（未注明时区）')
})
test('PostgreSQL short offsets and microseconds retain the real instant',()=>{
  assert.equal(formatRecordedTime('2026-10-29 20:43:48.978916+08','Asia/Shanghai'),'2026/10/29 20:43:48（Asia/Shanghai）')
  assert.equal(formatRecordedTime('2026-10-29 20:43:48.978916+0530','Asia/Shanghai'),'2026/10/29 23:13:48（Asia/Shanghai）')
  assert.equal(formatRecordedTime('2026-10-29 20:43:48.978916-08','Asia/Shanghai'),'2026/10/30 12:43:48（Asia/Shanghai）')
})
