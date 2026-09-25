import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  groupState,
  groups,
  normalize,
  only,
  summary,
  tapChip,
  toggleBox,
  toggleGroup,
} from '../src/lib/accountSelection.ts'

// Two current accounts, a card and three savings accounts: more than fit as chips.
const ALL = [
  { id: 1, name: 'Main', kind: 'CURRENT' },
  { id: 2, name: 'Joint', kind: 'CURRENT' },
  { id: 3, name: 'Card', kind: 'CARD' },
  { id: 4, name: 'Savings', kind: 'SAVINGS' },
  { id: 5, name: 'Holiday', kind: 'SAVINGS' },
  { id: 6, name: 'Deposit', kind: 'SAVINGS' },
]

test('every account selected is no filter, and unknown ids are dropped', () => {
  assert.deepEqual(normalize([6, 5, 4, 3, 2, 1], ALL), [])
  assert.deepEqual(normalize([3, 1, 1, 99], ALL), [1, 3])
})

test('a chip tap narrows from all accounts, then adds and removes', () => {
  assert.deepEqual(tapChip([], 3, ALL), [3])
  assert.deepEqual(tapChip([3], 1, ALL), [1, 3])
  assert.deepEqual(tapChip([1, 3], 3, ALL), [1])
  assert.deepEqual(tapChip([1], 1, ALL), []) // the last one off: all accounts again
})

test('a checkbox starts from every account checked and never unchecks the last one', () => {
  assert.deepEqual(toggleBox([], 4, ALL), [1, 2, 3, 5, 6])
  assert.deepEqual(toggleBox([1, 2], 3, ALL), [1, 2, 3])
  assert.deepEqual(toggleBox([1], 1, ALL), [1])
  assert.deepEqual(toggleBox([1, 2, 3, 4, 5], 6, ALL), [])
})

test('only this one', () => {
  assert.deepEqual(only(5, ALL), [5])
})

test('a group checkbox takes the whole group in or out', () => {
  assert.deepEqual(toggleGroup([], 'SAVINGS', ALL), [1, 2, 3]) // all savings off
  assert.deepEqual(toggleGroup([1, 2, 3], 'SAVINGS', ALL), []) // all back on: every account
  assert.deepEqual(toggleGroup([1, 4], 'SAVINGS', ALL), [1, 4, 5, 6]) // partly on: completes the group
  assert.deepEqual(toggleGroup([4, 5, 6], 'SAVINGS', ALL), [4, 5, 6]) // would leave nothing: unchanged
  assert.equal(groupState([1, 4], 'SAVINGS', ALL), 'some')
  assert.equal(groupState([], 'SAVINGS', ALL), 'all')
  assert.equal(groupState([1], 'SAVINGS', ALL), 'none')
})

test('groups come current first, then cards, then savings; empty kinds are left out', () => {
  assert.deepEqual(groups(ALL).map((g) => [g.kind, g.accounts.length]), [['CURRENT', 2], ['CARD', 1], ['SAVINGS', 3]])
  assert.deepEqual(groups(ALL.filter((a) => a.kind !== 'CARD')).map((g) => g.label), ['Current accounts', 'Savings'])
})

test('the summary names one or two accounts, else counts them', () => {
  assert.equal(summary([], ALL), 'All accounts')
  assert.equal(summary([1], ALL), 'Main')
  assert.equal(summary([1, 3], ALL), 'Main + Card')
  assert.equal(summary([1, 4, 5], ALL), '3 accounts')
})
