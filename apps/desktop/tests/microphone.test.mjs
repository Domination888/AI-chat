import test from 'node:test'
import assert from 'node:assert/strict'
import { prepareMicrophone } from '../src/voice/microphone.js'

test('startup permission request shares one acquisition and releases the microphone', async () => {
  const original = Object.getOwnPropertyDescriptor(globalThis, 'navigator')
  let requests = 0
  let stops = 0
  Object.defineProperty(globalThis, 'navigator', {
    configurable: true,
    value: { mediaDevices: { getUserMedia: async ({ audio }) => {
      assert.equal(audio, true)
      requests++
      return { getTracks: () => [{ stop: () => { stops++ } }] }
    } } }
  })
  try {
    await Promise.all([prepareMicrophone(), prepareMicrophone()])
    assert.equal(requests, 1)
    assert.equal(stops, 1)
  } finally {
    if (original) Object.defineProperty(globalThis, 'navigator', original)
    else delete globalThis.navigator
  }
})
