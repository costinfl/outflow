import type { GetPath, GetResponse } from '../api/types'

/**
 * Synthetic responses for the GitHub Pages demo. Never put real statement data here.
 * Typed against the generated schema: every GET endpoint must have a fixture, and it must
 * match the API contract, or `npm run typecheck` fails.
 */
export const fixtures: { [P in GetPath]: GetResponse<P> } = {
  '/api/health': { status: 'UP', database: 'UP', schemaVersion: 'demo' },
  '/api/accounts': [
    { id: 1, name: 'Main', ibanMasked: 'RO49 •••• 0000', currency: 'RON', kind: 'CURRENT' },
    { id: 2, name: 'Savings', currency: 'RON', kind: 'SAVINGS' },
  ],
  // Mirrors the seeded tree in V4__categories.sql.
  '/api/categories': [
    { id: 1, code: 'GROCERIES', name: 'Groceries', kind: 'SPEND' },
    { id: 2, code: 'RESTAURANTS', name: 'Restaurants & cafés', kind: 'SPEND' },
    { id: 3, code: 'TRANSPORT', name: 'Transport', kind: 'SPEND' },
    { id: 4, code: 'FUEL', name: 'Fuel', kind: 'SPEND' },
    { id: 5, code: 'UTILITIES', name: 'Utilities', kind: 'SPEND' },
    { id: 6, code: 'TELECOM', name: 'Telecom & internet', kind: 'SPEND' },
    { id: 7, code: 'HOUSING', name: 'Housing', kind: 'SPEND' },
    { id: 8, code: 'HEALTH', name: 'Health & pharmacy', kind: 'SPEND' },
    { id: 9, code: 'ENTERTAINMENT', name: 'Entertainment', kind: 'SPEND' },
    { id: 10, code: 'SUBSCRIPTIONS', name: 'Subscriptions & software', kind: 'SPEND' },
    { id: 11, code: 'SHOPPING', name: 'Shopping', kind: 'SPEND' },
    { id: 12, code: 'TRAVEL', name: 'Travel', kind: 'SPEND' },
    { id: 13, code: 'EDUCATION', name: 'Education', kind: 'SPEND' },
    { id: 14, code: 'FEES', name: 'Fees & interest', kind: 'SPEND' },
    { id: 15, code: 'INCOME', name: 'Income', kind: 'INCOME' },
    { id: 16, code: 'TRANSFER', name: 'Transfer', kind: 'TRANSFER' },
    { id: 17, code: 'CASH', name: 'Cash withdrawal', kind: 'SPEND' },
    { id: 18, code: 'OTHER', name: 'Other', kind: 'SPEND' },
  ],
  '/api/merchants': [
    {
      id: 1,
      key: 'KAUFLAND',
      displayName: 'Kaufland',
      transactionCount: 16,
      categoryCode: 'GROCERIES',
      sampleDescriptions: ['CUMPARARE POS KAUFLAND BUCURESTI 381 card ****4412 autorizare 356787'],
    },
    {
      id: 2,
      key: 'SPOTIFY',
      displayName: 'Spotify',
      transactionCount: 4,
      categoryCode: 'SUBSCRIPTIONS',
      sampleDescriptions: ['CUMPARARE POS SPOTIFY P770487 STOCKHOLM SE card ****4412'],
    },
    {
      id: 3,
      key: 'CONT ECONOMII',
      displayName: 'Cont Economii',
      transactionCount: 4,
      categoryCode: 'TRANSFER',
      sampleDescriptions: ['TRANSFER CATRE CONT ECONOMII RO49 •••• 0000'],
    },
  ],
  '/api/merchants/explain': {
    steps: [
      { name: 'BasicCleanup', output: 'CUMPARARE POS SPOTIFY P770487 STOCKHOLM SE CARD ****4412' },
      { name: 'ChannelPrefix', output: 'SPOTIFY P770487 STOCKHOLM SE CARD ****4412' },
      { name: 'WebAddress', output: 'SPOTIFY P770487 STOCKHOLM SE CARD ****4412' },
      { name: 'VolatileTokens', output: 'SPOTIFY STOCKHOLM SE' },
      { name: 'FillerWords', output: 'SPOTIFY STOCKHOLM SE' },
      { name: 'TrailingLocation', output: 'SPOTIFY' },
      { name: 'AliasStep', output: 'SPOTIFY' },
    ],
    key: 'SPOTIFY',
    categoryCode: 'SUBSCRIPTIONS',
    categorySource: 'KEYWORD',
  },
  // The hand-computed month from InsightServiceTest, so the demo shows numbers that add up.
  '/api/insights/month': {
    month: '2026-03',
    currency: 'RON',
    spentMinor: 130000,
    baselineMonths: 3,
    averageSpentMinor: 110000,
    deltaPct: 18,
    incomeMinor: 500000,
    netMinor: 370000,
    accuracyPct: 68,
    categorizedPct: 97,
    categories: [
      { categoryId: 1, code: 'GROCERIES', name: 'Groceries', spentMinor: 45000, sharePct: 35, usualMinor: 110000, deltaPct: -59, transactionCount: 3 },
      { categoryId: 4, code: 'FUEL', name: 'Fuel', spentMinor: 25000, sharePct: 19, usualMinor: 0, transactionCount: 1 },
      { categoryId: 5, code: 'UTILITIES', name: 'Utilities', spentMinor: 21001, sharePct: 16, usualMinor: 0, transactionCount: 1 },
      { categoryId: 11, code: 'SHOPPING', name: 'Shopping', spentMinor: 12000, sharePct: 9, usualMinor: 0, transactionCount: 1 },
      { categoryId: 2, code: 'RESTAURANTS', name: 'Restaurants & cafés', spentMinor: 10000, sharePct: 8, usualMinor: 0, transactionCount: 1 },
    ],
    rest: { spentMinor: 16999, sharePct: 13, categoryCount: 3 },
    availableMonths: ['2025-12', '2026-01', '2026-02', '2026-03'],
  },
}
