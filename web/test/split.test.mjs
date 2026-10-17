import assert from 'node:assert/strict'
import { test } from 'node:test'
import { allocate, parsePercent, parseShares } from '../src/lib/split.ts'

// The same cases as the backend's SplitCalculatorTest: the preview must show what will be saved.
const amounts = (r) => r.people.map((p) => p.amountMinor)

test('equal: the odd cents are yours', () => {
  const r = allocate(10000, 'EQUAL', [{ name: 'Ana' }, { name: 'Dan' }])
  assert.deepEqual(amounts(r), [3333, 3333])
  assert.equal(r.mineMinor, 3334)
})

test('percentages, fixed amounts and shares', () => {
  const pct = allocate(1000, 'PERCENT', [{ name: 'Ana', percentHundredths: 3333 }, { name: 'Dan', percentHundredths: 3333 }])
  assert.deepEqual(amounts(pct), [333, 333])
  assert.equal(pct.mineMinor, 334)
  assert.equal(pct.people[0].percent, 33.33)

  const fixed = allocate(30000, 'FIXED', [{ name: 'Ana', fixedMinor: 12000 }, { name: 'Dan', fixedMinor: 8050 }])
  assert.equal(fixed.mineMinor, 9950)

  const shares = allocate(10000, 'SHARES', [{ name: 'Ana and Dan', shares: 2 }, { name: 'Ion', shares: 1 }], 1)
  assert.deepEqual(amounts(shares), [5000, 2500])
  assert.equal(shares.mineMinor, 2500)
  assert.equal(allocate(10000, 'SHARES', [{ name: 'Ana', shares: 1 }], 0).mineMinor, 0)
})

test('splits that do not add up say why', () => {
  const over = allocate(30000, 'PERCENT', [{ name: 'Ana', percentHundredths: 6000 }, { name: 'Dan', percentHundredths: 5000 }])
  assert.match(over.error, /more than 100%/)
  assert.match(allocate(30000, 'FIXED', [{ name: 'Ana', fixedMinor: 30001 }]).error, /more than the expense/)
  assert.match(allocate(30000, 'EQUAL', [{ name: 'Ana' }, { name: ' ana ' }]).error, /twice/)
  assert.match(allocate(30000, 'EQUAL', []).error, /at least one/)
  assert.match(allocate(1, 'EQUAL', [{ name: 'Ana' }]).error, /round to zero/)
})

test('typed values', () => {
  assert.equal(parsePercent('33,33'), 3333)
  assert.equal(parsePercent('100'), 10000)
  assert.equal(parsePercent('10.555'), null)
  assert.equal(parsePercent('abc'), null)
  assert.equal(parseShares('2'), 2)
  assert.equal(parseShares('1.5'), null)
})
