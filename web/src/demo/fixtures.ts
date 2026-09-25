import type { GetPath, GetResponse } from '../api/types'
import { demoAccountsInclude, demoCategory, demoTransactions } from './ledger'
import { demoCommitted, demoRecurring } from './recurring'

/**
 * Synthetic responses for the GitHub Pages demo. Never put real statement data here.
 * Typed against the generated schema: every GET endpoint must have a fixture, and it must
 * match the API contract, or `npm run typecheck` fails.
 */
/** A fixture is a fixed response or a function of the request URL (e.g. the month query parameter). */
export type Fixture<T> = T | ((url: URL) => T)

type MonthSummary = GetResponse<'/api/insights/month'>

export const fixtures: { [P in GetPath]: Fixture<GetResponse<P>> } = {
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
    { id: 19, code: 'INSURANCE', name: 'Insurance', kind: 'SPEND' },
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
  '/api/transactions': demoTransactions,
  '/api/subscriptions': (url) =>
    demoRecurring(url.searchParams.get('month') ?? undefined, demoAccountsInclude(url, 1), demoAccountsInclude(url, 2)),
  // Uncategorized cards are the demo ledger's two uncategorized merchants. The subscription cards are illustrative:
  // the ledger holds one March charge per merchant, the cards describe what four months of them would look like.
  '/api/review': {
    cards: [
      {
        key: 'subscription:2', kind: 'SUBSCRIPTION', affectedMinor: 84004, currency: 'RON', merchantId: 7, name: 'Enel',
        subscriptionId: 2, cadence: 'MONTHLY', expectedAmountMinor: 21001, amountKind: 'VARIABLE', since: '2025-12-08',
        occurrences: 4, confidence: 0.93, nextExpectedDate: '2026-04-08',
      },
      {
        key: 'subscription:1', kind: 'SUBSCRIPTION', affectedMinor: 19996, currency: 'RON', merchantId: 5, name: 'Netflix',
        subscriptionId: 1, cadence: 'MONTHLY', expectedAmountMinor: 4999, amountKind: 'FIXED', since: '2025-12-06',
        occurrences: 4, confidence: 0.93, nextExpectedDate: '2026-04-06',
      },
      {
        key: 'merchant:8:RON', kind: 'UNCATEGORIZED_MERCHANT', affectedMinor: 4000, currency: 'RON', merchantId: 8,
        name: 'Zz Widgets', transactionCount: 1,
      },
      {
        key: 'merchant:11:RON', kind: 'UNCATEGORIZED_MERCHANT', affectedMinor: 3000, currency: 'RON', merchantId: 11,
        name: 'Zz Inflow', transactionCount: 1,
      },
    ],
    possible: [
      {
        key: 'subscription:3', kind: 'SUBSCRIPTION', affectedMinor: 24000, currency: 'RON', merchantId: 3, name: 'Bolt',
        subscriptionId: 3, cadence: 'MONTHLY', expectedAmountMinor: 8000, amountKind: 'FIXED', since: '2026-01-04',
        occurrences: 3, confidence: 0.55, nextExpectedDate: '2026-04-04',
      },
    ],
    count: 4,
  },
  '/api/insights/categories/{id}': demoCategory,
  // All demo transactions are the Main account's: a filter without it has no data, as the real API would answer.
  '/api/insights/month': (url) => {
    const month = url.searchParams.get('month') ?? '2026-03'
    if (!demoAccountsInclude(url, 1)) return noData(month)
    return url.searchParams.get('months') === '3' ? lastThreeMonths(month) : (demoMonths[month] ?? demoMonths['2026-03']!)
  },

}

const MONTHS = ['2025-12', '2026-01', '2026-02', '2026-03']

function noData(month: string): MonthSummary {
  return {
    month, currency: 'RON', spentMinor: 0, baselineMonths: 0, incomeMinor: 0, netMinor: 0, accuracyPct: 0,
    categorizedPct: 0, uncategorizedMinor: 0, uncategorizedCount: 0, categories: [],
    rest: { spentMinor: 0, sharePct: 0, categoryCount: 0 }, committed: { monthlyMinor: 0, count: 0 }, availableMonths: [],
    months: 1, periodFrom: month, monthsWithData: 0,
  }
}

/** Only groceries in Dec–Feb, as in the InsightServiceTest ledger. */
function groceriesOnly(month: string, spentMinor: number, baseline: number[]): MonthSummary {
  const average = baseline.length ? Math.round(baseline.reduce((a, b) => a + b, 0) / baseline.length) : undefined
  const delta = average ? Math.round(((spentMinor - average) * 100) / average) : undefined
  return {
    month,
    currency: 'RON',
    spentMinor,
    baselineMonths: baseline.length,
    averageSpentMinor: average,
    deltaPct: delta,
    incomeMinor: 0,
    netMinor: -spentMinor,
    accuracyPct: 70,
    categorizedPct: 100,
    uncategorizedMinor: 0,
    uncategorizedCount: 0,
    categories: [
      { categoryId: 1, code: 'GROCERIES', name: 'Groceries', spentMinor, sharePct: 100, usualMinor: average ?? 0, deltaPct: delta, transactionCount: 1 },
    ],
    rest: { spentMinor: 0, sharePct: 0, categoryCount: 0 },
    committed: demoCommitted(month, spentMinor),
    availableMonths: MONTHS,
    months: 1,
    periodFrom: month,
    monthsWithData: 1,
  }
}

// The hand-computed month from InsightServiceTest, and its three baseline months, so the demo adds up.
const demoMonths: Record<string, MonthSummary> = {
  '2025-12': groceriesOnly('2025-12', 100000, []),
  '2026-01': groceriesOnly('2026-01', 120000, [100000]),
  '2026-02': groceriesOnly('2026-02', 110000, [100000, 120000]),
  '2026-03': {
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
    uncategorizedMinor: 4000,
    uncategorizedCount: 1,
    categories: [
      { categoryId: 1, code: 'GROCERIES', name: 'Groceries', spentMinor: 45000, sharePct: 35, usualMinor: 110000, deltaPct: -59, transactionCount: 3 },
      { categoryId: 4, code: 'FUEL', name: 'Fuel', spentMinor: 25000, sharePct: 19, usualMinor: 0, transactionCount: 1 },
      { categoryId: 5, code: 'UTILITIES', name: 'Utilities', spentMinor: 21001, sharePct: 16, usualMinor: 0, transactionCount: 1 },
      { categoryId: 11, code: 'SHOPPING', name: 'Shopping', spentMinor: 12000, sharePct: 9, usualMinor: 0, transactionCount: 1 },
      { categoryId: 2, code: 'RESTAURANTS', name: 'Restaurants & cafés', spentMinor: 10000, sharePct: 8, usualMinor: 0, transactionCount: 1 },
    ],
    rest: { spentMinor: 16999, sharePct: 13, categoryCount: 3 },
    committed: demoCommitted('2026-03', 130000),
    availableMonths: ['2025-12', '2026-01', '2026-02', '2026-03'],
    months: 1,
    periodFrom: '2026-03',
    monthsWithData: 1,
    insight: {
      categoryId: 1, code: 'GROCERIES', name: 'Groceries', deltaPct: -59, differenceMinor: -65000, perMonthMinor: 45000,
      usualMinor: 110000, transactionCount: 3,
    },
  },
}

const shiftMonth = (month: string, by: number) => {
  const [y, m] = month.split('-').map(Number)
  const d = new Date(y!, m! - 1 + by, 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}
const halfUp = (a: number, b: number) => Math.floor((2 * a + b) / (2 * b))

/**
 * The "last 3 months" view, merged from the demo months as InsightService does: totals over the window, compared per
 * month with the average of the 3 months before it (only those with data). March gives LastThreeMonthsTest's numbers.
 */
function lastThreeMonths(month: string): MonthSummary {
  const window = [-2, -1, 0].map((i) => shiftMonth(month, i)).filter((m) => demoMonths[m])
  const before = [-5, -4, -3].map((i) => shiftMonth(month, i)).filter((m) => demoMonths[m])
  const divisor = Math.max(1, window.length)
  const total = (ms: string[], f: (s: MonthSummary) => number) => ms.reduce((a, m) => a + f(demoMonths[m]!), 0)
  const spent = total(window, (s) => s.spentMinor)
  const income = total(window, (s) => s.incomeMinor)
  const average = before.length ? halfUp(total(before, (s) => s.spentMinor), before.length) : undefined
  const perMonth = halfUp(spent, divisor)
  const byCategory = new Map<number, MonthSummary['categories'][number]>()
  for (const m of window) {
    for (const c of demoMonths[m]!.categories) {
      const prev = byCategory.get(c.categoryId!)
      byCategory.set(c.categoryId!, { ...c, spentMinor: (prev?.spentMinor ?? 0) + c.spentMinor,
        transactionCount: (prev?.transactionCount ?? 0) + c.transactionCount })
    }
  }
  const usualOf = (id: number) => before.length
    ? halfUp(total(before, (s) => s.categories.find((c) => c.categoryId === id)?.spentMinor ?? 0), before.length) : 0
  const categories = [...byCategory.values()].sort((a, b) => b.spentMinor - a.spentMinor).map((c) => {
    const usual = usualOf(c.categoryId!)
    const per = halfUp(c.spentMinor, divisor)
    return { ...c, sharePct: spent ? Math.round((c.spentMinor * 100) / spent) : 0, usualMinor: usual,
      deltaPct: usual ? Math.round(((per - usual) * 100) / usual) : undefined }
  })
  const rest = total(window, (s) => s.rest.spentMinor)
  const last = demoMonths[month] ?? demoMonths['2026-03']!
  return {
    ...last,
    month,
    spentMinor: spent,
    baselineMonths: before.length,
    averageSpentMinor: average,
    deltaPct: average ? Math.round(((perMonth - average) * 100) / average) || undefined : undefined,
    incomeMinor: income,
    netMinor: income - spent,
    uncategorizedMinor: total(window, (s) => s.uncategorizedMinor),
    uncategorizedCount: total(window, (s) => s.uncategorizedCount),
    categories,
    rest: { spentMinor: rest, sharePct: spent ? Math.round((rest * 100) / spent) : 0, categoryCount: last.rest.categoryCount },
    committed: demoCommitted(month, perMonth),
    months: 3,
    periodFrom: shiftMonth(month, -2),
    monthsWithData: window.length,
    insight: undefined, // nothing moves more than 25% and 100 RON per month here
  }
}

