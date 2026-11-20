const test = require('node:test')
const assert = require('node:assert/strict')
const { otp } = require('../workspace.cjs')
// RFC 6238 SHA-1 vectors, truncated to the six digits required by the server.
test('development MFA uses standard 30-second TOTP counters', () => {
  const secret = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ'
  for (const [seconds, expected] of [[59, '287082'], [1111111109, '081804'], [1111111111, '050471'], [1234567890, '005924'], [2000000000, '279037'], [20000000000, '353130']]) assert.equal(otp(secret, seconds * 1000), expected)
})
test('development MFA rotates on the same boundary as administrator login', () => {
  const secret = 'GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ'
  assert.equal(otp(secret, 30000), otp(secret, 59999))
  assert.notEqual(otp(secret, 59999), otp(secret, 60000))
})
