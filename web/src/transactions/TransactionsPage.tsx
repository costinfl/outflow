import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { api, isDemo } from '../api/client'
import type { Category, TransactionList, TransactionView } from '../api/types'
import { formatMoney, formatMonth } from '../lib/format'
import { useApi } from '../lib/useApi'

type Scope = 'SPEND' | 'INCOME' | 'ALL'

/**
 * The raw data behind any number (DESIGN: Detail screens, Transactions). Arrives pre-filtered; every filter is a chip
 * that can be removed; the total always equals the rows listed.
 */
export function TransactionsPage() {
  const [params, setParams] = useSearchParams()
  const month = params.get('month') ?? ''
  const scope = (params.get('scope')?.toUpperCase() ?? 'ALL') as Scope
  const category = params.get('category')
  const uncategorized = params.get('uncategorized') === '1'
  const merchant = params.get('merchant')
  const q = params.get('q') ?? ''
  const [search, setSearch] = useState(q)
  const [version, setVersion] = useState(0)

  const categories = useApi<Category[]>('categories', () => api.GET('/api/categories'))
  const state = useApi<TransactionList>(`tx:${params.toString()}:${version}`, () =>
    api.GET('/api/transactions', {
      params: {
        query: {
          month,
          scope,
          ...(category ? { category: Number(category) } : {}),
          ...(uncategorized ? { uncategorized: true } : {}),
          ...(merchant ? { merchant: Number(merchant) } : {}),
          ...(q ? { q } : {}),
        },
      },
    }),
  )

  if (!/^\d{4}-\d{2}$/.test(month)) {
    return (
      <p className="text-ink-2">
        Pick a number on the <Link to="/" className="text-bar underline">home screen</Link> to see its transactions.
      </p>
    )
  }

  const categoryList = categories.kind === 'ok' ? categories.data : []
  const categoryName = (id: number | null | undefined) => categoryList.find((c) => c.id === id)?.name
  const remove = (...keys: string[]) => {
    const next = new URLSearchParams(params)
    keys.forEach((k) => next.delete(k))
    setParams(next)
  }
  const chips: { label: string; keys: string[] }[] = []
  if (scope === 'SPEND') chips.push({ label: 'Spending', keys: ['scope'] })
  if (scope === 'INCOME') chips.push({ label: 'Income', keys: ['scope'] })
  if (category) chips.push({ label: categoryName(Number(category)) ?? `Category ${category}`, keys: ['category'] })
  if (uncategorized) chips.push({ label: 'Uncategorized', keys: ['uncategorized'] })
  if (merchant) {
    const name = state.kind === 'ok' ? state.data.items[0]?.merchantName : undefined
    chips.push({ label: name ?? 'One merchant', keys: ['merchant'] })
  }
  if (q) chips.push({ label: `“${q}”`, keys: ['q'] })

  return (
    <div className="space-y-4">
      <header className="flex items-baseline justify-between gap-3">
        <h1 className="text-2xl font-semibold text-ink">{formatMonth(month)}</h1>
        <Link to={`/?month=${month}`} className="text-sm text-bar underline">
          Overview
        </Link>
      </header>

      {chips.length > 0 && (
        <ul aria-label="Filters" className="flex flex-wrap gap-2">
          {chips.map((c) => (
            <li key={c.label}>
              <button
                type="button"
                onClick={() => remove(...c.keys)}
                className="inline-flex items-center gap-1 rounded-full bg-surface px-3 py-1 text-sm text-ink ring-1 ring-hairline hover:bg-hairline"
                aria-label={`Remove filter ${c.label}`}
              >
                {c.label} <span aria-hidden className="text-muted">×</span>
              </button>
            </li>
          ))}
        </ul>
      )}

      <form
        role="search"
        onSubmit={(e) => {
          e.preventDefault()
          const next = new URLSearchParams(params)
          if (search.trim()) next.set('q', search.trim())
          else next.delete('q')
          setParams(next)
        }}
      >
        <label className="sr-only" htmlFor="tx-search">
          Search by merchant or amount
        </label>
        <input
          id="tx-search"
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search merchant or amount"
          className="w-full rounded-lg bg-surface px-3 py-2 text-sm text-ink ring-1 ring-hairline placeholder:text-muted"
        />
      </form>

      {state.kind === 'loading' && <p className="text-sm text-muted">Loading…</p>}
      {state.kind === 'error' && (
        <p role="alert" className="text-sm text-bad">
          Could not load transactions ({state.message}).
        </p>
      )}
      {state.kind === 'ok' && (
        <>
          <p className="text-sm text-ink-2">
            {state.data.count} {state.data.count === 1 ? 'transaction' : 'transactions'} ·{' '}
            {scope === 'SPEND' ? 'spent' : scope === 'INCOME' ? 'received' : 'net'}{' '}
            <span className="font-semibold text-ink tabular-nums">{formatMoney(state.data.totalMinor, state.data.currency)}</span>
          </p>
          <DayGroups items={state.data.items} categories={categoryList} onChanged={() => setVersion((v) => v + 1)} />
        </>
      )}
    </div>
  )
}

function DayGroups({ items, categories, onChanged }: { items: TransactionView[]; categories: Category[]; onChanged: () => void }) {
  if (items.length === 0) return <p className="text-sm text-muted">No transactions match these filters.</p>
  const days = new Map<string, TransactionView[]>()
  for (const t of items) days.set(t.bookingDate, [...(days.get(t.bookingDate) ?? []), t])
  const dayFormat = new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short' })
  return (
    <div className="space-y-4">
      {[...days].map(([day, rows]) => (
        <section key={day} aria-label={day}>
          <h2 className="mb-1 text-xs font-medium tracking-wide text-muted uppercase">{dayFormat.format(new Date(day + 'T00:00'))}</h2>
          <ul className="divide-y divide-hairline rounded-2xl bg-surface ring-1 ring-hairline">
            {rows.map((t) => (
              <Row key={t.id} t={t} categories={categories} onChanged={onChanged} />
            ))}
          </ul>
        </section>
      ))}
    </div>
  )
}

const SOURCE: Record<string, string> = {
  USER: 'set by you',
  RULE: 'by your rule for this merchant',
  LEARNED: 'learned from your choices',
  KEYWORD: 'guessed from the name',
  MCC: 'from the card network code',
  SYSTEM: 'automatic',
}

function Row({ t, categories, onChanged }: { t: TransactionView; categories: Category[]; onChanged: () => void }) {
  const [open, setOpen] = useState(false)
  const [choice, setChoice] = useState(t.categoryId ?? '')
  const [applyToMerchant, setApplyToMerchant] = useState(false)
  const [status, setStatus] = useState<string | null>(null)
  const category = categories.find((c) => c.id === t.categoryId)

  async function save() {
    if (choice === '') return
    setStatus('Saving…')
    const { data, response } = await api.PUT('/api/transactions/{id}/category', {
      params: { path: { id: t.id } },
      body: { categoryId: Number(choice), applyToMerchant },
    })
    if (data) {
      setStatus(null)
      onChanged()
    } else {
      setStatus(isDemo ? 'Changing categories needs the real app (the demo has no server).' : `Could not save (HTTP ${response.status}).`)
    }
  }

  return (
    <li>
      <button type="button" onClick={() => setOpen(!open)} aria-expanded={open} className="flex w-full items-center justify-between gap-3 px-3 py-2.5 text-left">
        <span className="min-w-0">
          <span className="flex items-center gap-1.5">
            <span className="truncate text-sm font-medium text-ink">{t.merchantName}</span>
            {t.status === 'PENDING' && (
              <span className="shrink-0 rounded-full px-1.5 text-[11px] text-muted ring-1 ring-hairline">Pending</span>
            )}
          </span>
          <span className={`block truncate text-xs ${category ? 'text-muted' : 'text-bad'}`}>{category?.name ?? 'Uncategorized'}</span>
        </span>
        <span className="shrink-0 text-sm text-ink tabular-nums">
          {t.amountMinor > 0 ? '+' : ''}
          {formatMoney(t.amountMinor, t.currency)}
        </span>
      </button>
      {open && (
        <div className="space-y-2 px-3 pb-3 text-sm">
          <p className="rounded-lg bg-page px-2 py-1.5 font-mono text-xs break-words text-ink-2">{t.description}</p>
          {t.transferState && (
            <p className="text-xs text-ink-2">
              {t.amountMinor < 0 ? 'Transfer to' : 'Transfer from'} your {t.transferAccountName ?? 'other'} account
              {t.transferState === 'PAIRED'
                ? ': both sides found, not counted as spending.'
                : ` (recognised by its IBAN; upload the ${t.transferAccountName ?? 'other'} statement to confirm). Not counted as spending.`}
            </p>
          )}
          {t.categorySource && <p className="text-xs text-muted">Category {SOURCE[t.categorySource] ?? t.categorySource}.</p>}
          <label className="block text-xs text-ink-2">
            Category
            <select
              value={choice}
              onChange={(e) => setChoice(e.target.value === '' ? '' : Number(e.target.value))}
              className="mt-1 block w-full rounded-lg bg-page px-2 py-1.5 text-sm text-ink ring-1 ring-hairline"
            >
              {!t.categoryId && <option value="">Choose a category</option>}
              {categories.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </select>
          </label>
          <label className="flex items-center gap-2 text-xs text-ink-2">
            <input type="checkbox" checked={applyToMerchant} onChange={(e) => setApplyToMerchant(e.target.checked)} />
            Apply to all from {t.merchantName}, now and in future imports
          </label>
          <button
            type="button"
            onClick={() => void save()}
            disabled={choice === '' || (choice === t.categoryId && !applyToMerchant)}
            className="rounded-lg bg-bar px-3 py-1.5 text-sm font-medium text-white disabled:opacity-40"
          >
            Save
          </button>
          {status && <p className="text-xs text-ink-2">{status}</p>}
        </div>
      )}
    </li>
  )
}
