import type { GetResponse, Schemas } from '../api/types'

/**
 * Demo recurring payments. The home "Committed every month" figure and the Recurring screen are both computed here
 * with the backend's rule (RecurringService), so the demo figure equals its drill-through like the real app.
 */
type Overview = GetResponse<'/api/subscriptions'>
type Item = Overview['groups'][number]['items'][number]

interface DemoSubscription {
  item: Omit<Item, 'counted' | 'monthlyMinor' | 'yearlyMinor'>
  group: 'SUBSCRIPTIONS' | 'BILLS' | 'INCOME'
  firstSeen: string
  lastSeen: string
}

const monthlyOf = (i: Pick<Item, 'cadence' | 'expectedAmountMinor'>) =>
  i.cadence === 'YEARLY' ? Math.floor((i.expectedAmountMinor + 6) / 12) : i.expectedAmountMinor
const yearlyOf = (i: Pick<Item, 'cadence' | 'expectedAmountMinor'>) =>
  i.cadence === 'YEARLY' ? i.expectedAmountMinor : i.expectedAmountMinor * 12

const SUBSCRIPTIONS: DemoSubscription[] = [
  {
    group: 'BILLS',
    firstSeen: '2025-12-08',
    lastSeen: '2026-03-08',
    item: {
      id: 2, name: 'Enel', merchantId: 7, cadence: 'MONTHLY', amountKind: 'VARIABLE', expectedAmountMinor: 21001,
      nextExpectedDate: '2026-04-08', status: 'ACTIVE', categoryId: 5, categoryName: 'Utilities',
    },
  },
  {
    group: 'SUBSCRIPTIONS',
    firstSeen: '2025-12-06',
    lastSeen: '2026-03-06',
    item: {
      id: 1, name: 'Netflix', merchantId: 5, cadence: 'MONTHLY', amountKind: 'FIXED', expectedAmountMinor: 4999,
      nextExpectedDate: '2026-04-06', status: 'ACTIVE', categoryId: 10, categoryName: 'Subscriptions & software',
    },
  },
  {
    group: 'SUBSCRIPTIONS',
    firstSeen: '2025-02-14',
    lastSeen: '2026-02-12',
    item: {
      id: 4, name: 'Domain renewal', merchantId: 12, cadence: 'YEARLY', amountKind: 'FIXED', expectedAmountMinor: 5500,
      nextExpectedDate: '2027-02-14', status: 'ACTIVE', categoryId: 10, categoryName: 'Subscriptions & software',
    },
  },
  {
    group: 'INCOME',
    firstSeen: '2025-12-10',
    lastSeen: '2026-03-10',
    item: {
      id: 5, name: 'Salariu Acme Srl', merchantId: 9, cadence: 'MONTHLY', amountKind: 'FIXED', expectedAmountMinor: 500000,
      nextExpectedDate: '2026-04-10', status: 'ACTIVE', categoryId: 15, categoryName: 'Income',
    },
  },
]

/**
 * Every demo subscription and all demo history belong to the Main account (id 1). Recurring income is its own group
 * and never part of the committed totals, as in RecurringService.
 */
export function demoRecurring(month?: string, includesMain = true, includesSavings = true): Overview {
  const groups: Overview['groups'] = []
  let monthlyMinor = 0
  let yearlyMinor = 0
  let countedCount = 0
  let incomeMonthlyMinor = 0
  for (const kind of ['SUBSCRIPTIONS', 'BILLS', 'INCOME'] as const) {
    const items: Item[] = []
    for (const s of SUBSCRIPTIONS.filter((x) => x.group === kind && includesMain)) {
      if (month && s.firstSeen.slice(0, 7) > month) continue
      const counted = !month || s.item.status === 'ACTIVE' || s.lastSeen.slice(0, 7) >= month
      items.push({ ...s.item, counted, monthlyMinor: monthlyOf(s.item), yearlyMinor: yearlyOf(s.item) })
    }
    if (items.length === 0) continue
    items.sort((a, b) => Number(b.counted) - Number(a.counted) || b.monthlyMinor - a.monthlyMinor)
    const counted = items.filter((i) => i.counted)
    const groupMonthly = counted.reduce((sum, i) => sum + i.monthlyMinor, 0)
    groups.push({ kind, monthlyMinor: groupMonthly, items })
    if (kind === 'INCOME') {
      incomeMonthlyMinor = groupMonthly
    } else {
      monthlyMinor += groupMonthly
      yearlyMinor += counted.reduce((sum, i) => sum + i.yearlyMinor, 0)
      countedCount += counted.length
    }
  }
  return {
    currency: 'RON',
    month,
    monthlyMinor,
    yearlyMinor,
    countedCount,
    groups,
    coverage: [
      ...(includesMain
        ? [{ accountId: 1, accountName: 'Main', from: '2025-12-05', to: '2026-03-13', months: 4, monthly: true, yearly: false }]
        : []),
      ...(includesSavings ? [{ accountId: 2, accountName: 'Savings', months: 0, monthly: false, yearly: false }] : []),
    ],
    suggestionCount: includesMain ? 2 : 0,
    incomeMonthlyMinor,
  }
}

export function demoCommitted(month: string, spentMinor: number): Schemas['Committed'] {
  const o = demoRecurring(month)
  return {
    monthlyMinor: o.monthlyMinor,
    count: o.countedCount,
    sharePct: spentMinor === 0 ? undefined : Math.round((o.monthlyMinor * 100) / spentMinor),
  }
}
