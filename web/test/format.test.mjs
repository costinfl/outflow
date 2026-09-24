import assert from 'node:assert/strict'
import { test } from 'node:test'
import { decimalToMinor } from '../src/lib/format.ts'

test('decimalToMinor parses typed amounts exactly', () => {
  assert.equal(decimalToMinor('49.99', 'RON'), 4999)
  assert.equal(decimalToMinor('49,99', 'RON'), 4999)
  assert.equal(decimalToMinor(' 50 ', 'RON'), 5000)
  assert.equal(decimalToMinor('0.1', 'RON'), 10)
  assert.equal(decimalToMinor('1234567.89', 'RON'), 123456789)
  assert.equal(decimalToMinor('500', 'JPY'), 500)
})

test('decimalToMinor rejects what it cannot read exactly', () => {
  for (const bad of ['', 'abc', '1.234,56', '49.999', '-5', '1e3', '12.']) {
    assert.equal(decimalToMinor(bad, 'RON'), null, bad)
  }
  assert.equal(decimalToMinor('5.5', 'JPY'), null)
})
