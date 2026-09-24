import type { GetResponse } from '../api/types'

/**
 * The hand-computed ledger of InsightServiceTest / TransactionControllerTest, as the demo's transactions. Filters mirror
 * the backend's txn.Scope, so every number in the demo drills to rows that add up to it, like the real app.
 */
type TransactionView = GetResponse<'/api/transactions'>['items'][number]

const CATEGORIES: Record<number, { code: string; name: string; kind: 'SPEND' | 'INCOME' | 'TRANSFER' }> = {
  1: { code: 'GROCERIES', name: 'Groceries', kind: 'SPEND' },
  2: { code: 'RESTAURANTS', name: 'Restaurants & cafés', kind: 'SPEND' },
  3: { code: 'TRANSPORT', name: 'Transport', kind: 'SPEND' },
  4: { code: 'FUEL', name: 'Fuel', kind: 'SPEND' },
  5: { code: 'UTILITIES', name: 'Utilities', kind: 'SPEND' },
  10: { code: 'SUBSCRIPTIONS', name: 'Subscriptions & software', kind: 'SPEND' },
  11: { code: 'SHOPPING', name: 'Shopping', kind: 'SPEND' },
  15: { code: 'INCOME', name: 'Income', kind: 'INCOME' },
  16: { code: 'TRANSFER', name: 'Transfer', kind: 'TRANSFER' },
}

// [date, merchant id, merchant key, display name, amount minor, category id | null, bank text]
const ROWS: [string, number, string, string, number, number | null, string][] = [
  ['2025-12-05', 1, 'LIDL', 'Lidl', -100000, 1, 'CUMPARARE POS LIDL'],
  ['2026-01-05', 1, 'LIDL', 'Lidl', -120000, 1, 'CUMPARARE POS LIDL'],
  ['2026-02-05', 1, 'LIDL', 'Lidl', -110000, 1, 'CUMPARARE POS LIDL'],
  ['2026-03-02', 1, 'LIDL', 'Lidl', -30000, 1, 'CUMPARARE POS LIDL'],
  ['2026-03-09', 1, 'LIDL', 'Lidl', -20000, 1, 'CUMPARARE POS LIDL'],
  ['2026-03-10', 1, 'LIDL', 'Lidl', 5000, 1, 'CUMPARARE POS LIDL'],
  ['2026-03-03', 2, 'STARBUCKS', 'Starbucks', -10000, 2, 'CUMPARARE POS STARBUCKS'],
  ['2026-03-04', 3, 'BOLT', 'Bolt', -8000, 3, 'BOLT.EU/R/1'],
  ['2026-03-05', 4, 'OMV', 'Omv', -25000, 4, 'CUMPARARE POS OMV'],
  ['2026-03-06', 5, 'NETFLIX', 'Netflix', -4999, 10, 'NETFLIX.COM'],
  ['2026-03-07', 6, 'EMAG', 'Emag', -12000, 11, 'PLATA CARD EMAG.RO'],
  ['2026-03-08', 7, 'ENEL', 'Enel', -21001, 5, 'ENEL ENERGIE FACTURA 1'],
  ['2026-03-11', 8, 'ZZ WIDGETS', 'Zz Widgets', -4000, null, 'CUMPARARE POS ZZ WIDGETS'],
  ['2026-03-10', 9, 'SALARIU ACME SRL', 'Salariu Acme Srl', 500000, 15, 'INCASARE SALARIU ACME SRL'],
  ['2026-03-12', 10, 'CONT ECONOMII', 'Cont Economii', -100000, 16, 'TRANSFER CATRE CONT ECONOMII'],
  ['2026-03-13', 11, 'ZZ INFLOW', 'Zz Inflow', 3000, null, 'INCASARE ZZ INFLOW'],
]

const LEDGER: TransactionView[] = ROWS.map(([date, merchantId, key, name, amountMinor, categoryId, description], i) => ({
  id: i + 1,
  accountId: 1,
  bookingDate: date,
  amountMinor,
  currency: 'RON',
  merchantName: name,
  merchantKey: key,
  description,
  ...(categoryId != null
    ? { categoryId, categoryCode: CATEGORIES[categoryId]!.code, categorySource: 'KEYWORD', categoryConfidence: 0.7 }
    : {}),
  // merchant id is not part of TransactionView; kept for the merchant filter below
  ...({ merchantId } as object),
}))

const kindOf = (t: TransactionView) => (t.categoryId != null ? CATEGORIES[t.categoryId]!.kind : undefined)
const inSpend = (t: TransactionView) => kindOf(t) === 'SPEND' || (t.categoryId == null && t.amountMinor < 0)
const MONTHS_WITH_DATA = [...new Set(LEDGER.map((t) => t.bookingDate.slice(0, 7)))].sort()

function amountQuery(q: string): number | undefined {
  const s = q.replace(/ /g, '')
  if (!/^(\d{1,3}(?:[.,]\d{3})*|\d+)(?:[.,]\d{1,2})?$/.test(s)) return undefined
  const sep = Math.max(s.lastIndexOf('.'), s.lastIndexOf(','))
  const decimals = sep >= 0 && s.length - sep - 1 <= 2
  const units = (decimals ? s.slice(0, sep) : s).replace(/[.,]/g, '')
  return Number(units + (decimals ? (s.slice(sep + 1) + '00').slice(0, 2) : '00'))
}

export function demoTransactions(url: URL): GetResponse<'/api/transactions'> {
  const p = url.searchParams
  const month = p.get('month') ?? '2026-03'
  const scope = (p.get('scope') ?? 'ALL') as 'SPEND' | 'INCOME' | 'ALL'
  const q = p.get('q')?.trim()
  const amount = q ? amountQuery(q) : undefined
  const items = LEDGER.filter((t) => t.bookingDate.startsWith(month))
    .filter((t) => (scope === 'SPEND' ? inSpend(t) : scope === 'INCOME' ? kindOf(t) === 'INCOME' : true))
    .filter((t) => !p.get('category') || t.categoryId === Number(p.get('category')))
    .filter((t) => p.get('uncategorized') !== 'true' || t.categoryId == null)
    .filter((t) => !p.get('merchant') || (t as unknown as { merchantId: number }).merchantId === Number(p.get('merchant')))
    .filter((t) =>
      !q ? true
      : amount !== undefined ? Math.abs(t.amountMinor) === amount
      : `${t.merchantName} ${t.merchantKey} ${t.description}`.toLowerCase().includes(q.toLowerCase()),
    )
    .sort((a, b) => b.bookingDate.localeCompare(a.bookingDate) || b.id - a.id)
  const sum = items.reduce((s, t) => s + t.amountMinor, 0)
  return { month, currency: 'RON', scope, totalMinor: scope === 'SPEND' ? -sum : sum, count: items.length, items }
}

export function demoCategory(url: URL): GetResponse<'/api/insights/categories/{id}'> {
  const id = Number(url.pathname.split('/').pop())
  const month = url.searchParams.get('month') ?? '2026-03'
  const c = CATEGORIES[id] ?? { code: 'OTHER', name: 'Other', kind: 'SPEND' as const }
  const sign = c.kind === 'INCOME' ? 1 : -1
  const [y, m] = month.split('-').map(Number)
  const trend = Array.from({ length: 12 }, (_, i) => {
    const d = new Date(y!, m! - 1 - (11 - i), 1)
    const ym = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
    const amountMinor = LEDGER.filter((t) => t.categoryId === id && t.bookingDate.startsWith(ym)).reduce((s, t) => s + sign * t.amountMinor, 0)
    return { month: ym, amountMinor, hasData: MONTHS_WITH_DATA.includes(ym) }
  })
  const withData = trend.filter((t) => t.hasData)
  const byMerchant = new Map<string, { merchantId: number; name: string; amountMinor: number; transactionCount: number }>()
  for (const t of LEDGER.filter((t) => t.categoryId === id && t.bookingDate.startsWith(month))) {
    const e = byMerchant.get(t.merchantKey) ?? {
      merchantId: (t as unknown as { merchantId: number }).merchantId,
      name: t.merchantName,
      amountMinor: 0,
      transactionCount: 0,
    }
    e.amountMinor += sign * t.amountMinor
    e.transactionCount++
    byMerchant.set(t.merchantKey, e)
  }
  return {
    category: { id, code: c.code, name: c.name, kind: c.kind },
    month,
    currency: 'RON',
    amountMinor: trend[11]!.amountMinor,
    trend,
    averageMinor: withData.length ? Math.round(withData.reduce((s, t) => s + t.amountMinor, 0) / withData.length) : undefined,
    merchants: [...byMerchant.values()].sort((a, b) => b.amountMinor - a.amountMinor),
  }
}
