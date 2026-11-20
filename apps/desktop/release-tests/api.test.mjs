import { test } from 'node:test'
import assert from 'node:assert/strict'
import { normalizeServer } from '../src/release/api.mjs'
test('commercial server URL is HTTPS and cannot embed credentials or paths',()=>{assert.equal(normalizeServer('https://example.com/'),'https://example.com');for(const value of ['http://example.com','https://key@example.com','https://example.com/api','https://example.com/?key=secret'])assert.throws(()=>normalizeServer(value));assert.equal(normalizeServer('http://127.0.0.1:8081',true),'http://127.0.0.1:8081')})
