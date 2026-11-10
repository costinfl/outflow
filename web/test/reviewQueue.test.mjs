import assert from 'node:assert/strict'
import { test } from 'node:test'
import { START, answered, back, canGoBack, currentKey, order, orderCategories, position, skip } from '../src/lib/reviewQueue.ts'

test('cards come in the inbox order; an answered card leaves and the next one shows', () => {
  let s = START
  assert.equal(currentKey(['a', 'b', 'c'], s), 'a')
  s = answered(s, 'a')
  assert.equal(currentKey(['b', 'c'], s), 'b') // the inbox was refetched without 'a'
  assert.equal(position(['b', 'c'], s), 1)
  assert.equal(currentKey([], s), null)
  assert.equal(position([], s), 0)
})

test('a skipped card goes to the end of the line', () => {
  const keys = ['a', 'b', 'c']
  let s = skip(START, 'a')
  assert.deepEqual(order(keys, s), ['b', 'c', 'a'])
  assert.equal(currentKey(keys, s), 'b')
  s = skip(s, 'b')
  assert.deepEqual(order(keys, s), ['c', 'a', 'b'])
  assert.equal(position(keys, s), 1)
})

test('back returns to the last card shown that is still open', () => {
  const keys = ['a', 'b', 'c']
  let s = skip(START, 'a')
  assert.equal(canGoBack(keys, s), true)
  s = back(keys, s)
  assert.equal(currentKey(keys, s), 'a')
  assert.deepEqual(order(keys, s), ['a', 'b', 'c']) // no longer at the end
  assert.equal(canGoBack(keys, s), false)
  // An answered card that left the inbox cannot be gone back to.
  s = answered(START, 'a')
  assert.equal(canGoBack(['b', 'c'], s), false)
})

test('category buttons: the most used first, the ones that fit the direction ahead', () => {
  const cats = [
    { id: 1, kind: 'SPEND', name: 'Groceries' },
    { id: 2, kind: 'SPEND', name: 'Restaurants' },
    { id: 15, kind: 'INCOME', name: 'Income' },
    { id: 16, kind: 'TRANSFER', name: 'Transfer' },
  ]
  const usage = [15, 2, 16, 1]
  assert.deepEqual(orderCategories(cats, usage, 'OUT').map((c) => c.id), [2, 16, 1, 15])
  assert.deepEqual(orderCategories(cats, usage, 'IN').map((c) => c.id), [15, 16, 2, 1])
})
