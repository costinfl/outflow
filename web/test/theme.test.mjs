import assert from 'node:assert/strict'
import { test } from 'node:test'
import { parseTheme, resolveTheme } from '../src/lib/theme.ts'

test('only a stored light or dark overrides the device', () => {
  assert.equal(parseTheme('light'), 'light')
  assert.equal(parseTheme('dark'), 'dark')
  for (const other of [null, undefined, '', 'system', 'blue']) assert.equal(parseTheme(other), 'system')
})

test('system follows the device; a choice wins over it', () => {
  assert.equal(resolveTheme('system', true), 'dark')
  assert.equal(resolveTheme('system', false), 'light')
  assert.equal(resolveTheme('light', true), 'light')
  assert.equal(resolveTheme('dark', false), 'dark')
})
