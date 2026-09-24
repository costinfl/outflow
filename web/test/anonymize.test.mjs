// Runs with Node's built-in test runner and type stripping: `npm test`. No dependencies.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { anonymize, ibanValid, luhn, cnpValid } from '../src/anonymize/anonymize.ts'

const dir = new URL('./anonymize/', import.meta.url)
const bytes = (name) => new Uint8Array(readFileSync(new URL(name, dir)))
const names = readFileSync(new URL('names.txt', dir), 'utf8').split('\n')

// The expected files were produced by tools/Anonymize.java with the same seed and names:
// the browser version must write exactly the same bytes (AnonymizeToolTest checks the Java side).
for (const [raw, expected, encoding] of [
  ['raw-ro-cp1250.csv', 'expected-ro-cp1250.csv', 'windows-1250'],
  ['raw-generic-utf8-bom.csv', 'expected-generic-utf8-bom.csv', 'utf-8'],
  ['raw-ing-utf8.csv', 'expected-ing-utf8.csv', 'utf-8'],
]) {
  test(`byte-identical to the Java tool: ${raw}`, async () => {
    const r = await anonymize(bytes(raw), { seed: 'parity', names })
    assert.equal(r.encoding, encoding)
    assert.deepEqual(Buffer.from(r.bytes), Buffer.from(bytes(expected)))
  })
}

test('the report counts each replacement once and never shows originals', async () => {
  const r = await anonymize(bytes('raw-ro-cp1250.csv'), { seed: 'parity', names })
  assert.deepEqual(r.counts, { CNP: 1, IBAN: 2, 'card digits': 1, 'card number': 1, email: 1, 'long reference': 1, name: 3, phone: 1 })
  const report = JSON.stringify([r.examples, r.leftovers, r.transferLines])
  for (const secret of ['RO49AAAA1B31007593840000', '1800101221144', 'stefan.ionescu', '0722 123 456', 'Ionescu']) {
    assert.ok(!report.includes(secret), `report leaks ${secret}`)
  }
  assert.ok(r.transferLines.length >= 2)
  assert.equal(r.seedWasRandom, false)
})

test('an invalid IBAN is kept and flagged for review', async () => {
  const r = await anonymize(bytes('raw-generic-utf8-bom.csv'), { seed: 'parity', names })
  assert.ok(r.text.includes('RO12AAAA1B31007593840000'))
  assert.ok(r.leftovers.some((l) => l.startsWith('IBAN-like text kept')))
})

test('without a seed, fakes are random but still valid', async () => {
  const a = await anonymize(bytes('raw-ro-cp1250.csv'))
  const b = await anonymize(bytes('raw-ro-cp1250.csv'))
  assert.equal(a.seedWasRandom, true)
  assert.notDeepEqual(a.bytes, b.bytes)
  const iban = /IBAN:;(\S+)/.exec(a.text)[1]
  assert.ok(ibanValid(iban) && iban.slice(4, 8) === 'ANON')
})

test('checksum helpers', () => {
  assert.ok(ibanValid('RO49 AAAA 1B31 0075 9384 0000'))
  assert.ok(!ibanValid('RO48AAAA1B31007593840000'))
  assert.ok(luhn('4111111111111111') && !luhn('4111111111111112'))
  assert.ok(cnpValid('1800101221144') && !cnpValid('1800101221145'))
})

test('people in Beneficiar / Ordonator fields are replaced everywhere; organisations are kept and listed', async () => {
  const r = await anonymize(bytes('raw-ing-utf8.csv'), { seed: 'parity', names })
  for (const person of ['Andrei Fictiv', 'FICTIV ANDREI', 'Maria-Elena Exemplu', 'Ion Testescu', '4323', '2625']) {
    assert.ok(!r.text.includes(person), `still contains ${person}`)
  }
  assert.equal(r.detectedNames, 3)
  assert.deepEqual(r.keptCounterparties, ['DIGI ROMANIA SA', 'EXEMPLU SOFTWARE S.R.L.', 'Revolut'])
})
