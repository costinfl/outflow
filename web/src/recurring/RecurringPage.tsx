import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { api, isDemo } from '../api/client'
import type { Category, RecurringOverview } from '../api/types'
import { cadenceWord, formatDay, formatMoney, formatMonth } from '../lib/format'
import { useApi } from '../lib/useApi'

type Item = RecurringOverview['groups'][number]['items'][number]

const GROUP_TITLES: Record<string, string> = { SUBSCRIPTIONS: 'Subscriptions', BILLS: 'Bills' }

/**
 * "What am I committed to?" (DESIGN: Detail screens, Recurring payments): highest monthly cost first, grouped, with
 * totals per month and per year. With ?month=, as it stood in that month, matching the home figure.
 */
export function RecurringPage() {
  const [params, setParams] = useSearchParams()
  const month = params.get('month') ?? undefined
  const [version, setVersion] = useState(0)
  const state = useApi<RecurringOverview>(`recurring:${month ?? ''}:${version}`, () =>
    api.GET('/api/subscriptions', { params: { query: month ? { month } : {} } }),
  )
  const categories = useApi<Category[]>('categories', () => api.GET('/api/categories'))

  if (state.kind === 'loading') return <p className="py-12 text-center text-muted">Loading…</p>
  if (state.kind === 'error')
    return (
      <p role="alert" className="py-12 text-center text-bad">
        Could not load recurring payments ({state.message}).
      </p>
    )
  const o = state.data
  const items = o.groups.flatMap((g) => g.items)
  const enoughHistory = o.coverage.some((c) => c.monthly)

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold text-ink">Recurring payments</h1>
        {month && (
          <p className="mt-2 flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => setParams({})}
              className="rounded-full bg-bar-track px-3 py-1 text-xs text-ink"
              aria-label={`Showing ${formatMonth(month)}; show today instead`}
            >
              As in {formatMonth(month)} ✕
            </button>
          </p>
        )}
      </div>

      <section aria-label="Totals" className="grid grid-cols-1 gap-3 min-[360px]:grid-cols-2">
        <Total label="Per month" value={formatMoney(o.monthlyMinor, o.currency)} />
        <Total label="Per year" value={formatMoney(o.yearlyMinor, o.currency)} />
      </section>
      <p className="text-xs text-muted">
        {o.countedCount} active {o.countedCount === 1 ? 'payment' : 'payments'}; yearly ones count as a twelfth per month.
      </p>

      {o.suggestionCount > 0 && (
        <Link to="/review" className="flex items-center justify-between rounded-lg bg-bar-track px-3 py-2 text-sm font-medium text-ink hover:underline">
          <span>
            {o.suggestionCount} {o.suggestionCount === 1 ? 'suggestion' : 'suggestions'} waiting for your answer
          </span>
          <span aria-hidden="true">›</span>
        </Link>
      )}

      {items.length === 0 ? (
        <p className="rounded-2xl bg-surface p-4 text-sm text-ink-2 ring-1 ring-hairline">
          {enoughHistory
            ? 'No confirmed recurring payments yet. Confirm suggestions in Review and they appear here.'
            : 'Upload 3+ months to detect monthly payments, 12+ for yearly ones.'}
        </p>
      ) : (
        o.groups.map((g) => (
          <section key={g.kind} aria-labelledby={`group-${g.kind}`} className="rounded-2xl bg-surface p-4 shadow-sm ring-1 ring-hairline">
            <div className="flex items-baseline justify-between">
              <h2 id={`group-${g.kind}`} className="text-sm font-medium text-ink-2">
                {GROUP_TITLES[g.kind] ?? g.kind}
              </h2>
              <span className="text-sm text-ink tabular-nums">{formatMoney(g.monthlyMinor, o.currency)} / month</span>
            </div>
            <ul className="mt-2 divide-y divide-hairline">
              {g.items.map((i) => (
                <Row
                  key={i.id}
                  item={i}
                  currency={o.currency}
                  categories={categories.kind === 'ok' ? categories.data : []}
                  onChanged={() => setVersion((v) => v + 1)}
                />
              ))}
            </ul>
          </section>
        ))
      )}

      <section aria-labelledby="coverage" className="rounded-2xl bg-surface p-4 ring-1 ring-hairline">
        <h2 id="coverage" className="text-sm font-medium text-ink-2">
          History per account
        </h2>
        <ul className="mt-2 space-y-1.5 text-sm">
          {o.coverage.map((c) => (
            <li key={c.accountId} className="text-ink">
              <span className="font-medium">{c.accountName}</span>{' '}
              <span className="text-ink-2">
                {c.from && c.to ? `${formatDay(c.from)} ${c.from.slice(0, 4)} – ${formatDay(c.to)} ${c.to.slice(0, 4)} (${c.months} ${c.months === 1 ? 'month' : 'months'})` : 'no transactions yet'}
              </span>
              <span className="block text-xs text-muted">
                {c.yearly
                  ? 'Monthly and yearly payments can be detected.'
                  : c.monthly
                    ? 'Monthly payments can be detected; yearly ones need 13+ months.'
                    : 'Upload 3+ months to detect monthly payments, 12+ for yearly ones.'}
              </span>
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}

function Total({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-2xl bg-surface px-3 py-4 shadow-sm ring-1 ring-hairline">
      <p className="text-xs text-ink-2">{label}</p>
      <p className="mt-1 text-[clamp(1rem,4.6vw,1.25rem)] font-semibold whitespace-nowrap text-ink tabular-nums">{value}</p>
    </div>
  )
}

function Row({
  item,
  currency,
  categories,
  onChanged,
}: {
  item: Item
  currency: string
  categories: Category[]
  onChanged: () => void
}) {
  const [open, setOpen] = useState(false)
  const [name, setName] = useState(item.name)
  const [category, setCategory] = useState(item.categoryId ? String(item.categoryId) : '')
  const [status, setStatus] = useState<string | null>(null)

  async function run(label: string, call: () => Promise<{ response: Response }>) {
    setStatus(`${label}…`)
    const { response } = await call()
    if (response.ok) {
      setStatus(null)
      onChanged()
    } else {
      setStatus(isDemo ? 'Changes need the real app: the demo has no server.' : `${label} failed (HTTP ${response.status}).`)
    }
  }

  const amount = `${item.amountKind === 'VARIABLE' ? 'about ' : ''}${formatMoney(item.expectedAmountMinor, currency)} ${cadenceWord(item.cadence)}`
  const field = 'mt-1 block w-full rounded-lg bg-page px-2 py-1.5 text-sm text-ink ring-1 ring-hairline'
  return (
    <li className={item.counted ? '' : 'opacity-70'}>
      <button type="button" onClick={() => setOpen(!open)} aria-expanded={open} className="flex w-full items-start justify-between gap-3 py-2.5 text-left">
        <span className="min-w-0">
          <span className="flex items-center gap-2">
            <span className="truncate text-sm font-medium text-ink">{item.name}</span>
            <span className={`shrink-0 rounded-full px-2 py-0.5 text-[11px] ${item.status === 'ENDED' ? 'bg-page text-muted ring-1 ring-hairline' : 'bg-bar-track text-ink'}`}>
              {item.status === 'ENDED' ? 'Ended' : 'Active'}
            </span>
          </span>
          <span className="block text-xs text-muted">
            {amount}
            {item.nextExpectedDate ? ` · next ~ ${formatDay(item.nextExpectedDate)}` : ''}
          </span>
        </span>
        <span className="shrink-0 text-right">
          <span className="block text-sm text-ink tabular-nums">{formatMoney(item.monthlyMinor, currency)}</span>
          <span className="block text-[11px] text-muted">{item.counted ? 'per month' : 'not counted'}</span>
        </span>
      </button>
      {open && (
        <div className="space-y-2 pb-3 text-sm">
          <form
            className="flex items-end gap-2"
            onSubmit={(e) => {
              e.preventDefault()
              if (name.trim())
                void run('Rename', () => api.PATCH('/api/subscriptions/{id}', { params: { path: { id: item.id } }, body: { name: name.trim() } }))
            }}
          >
            <label className="block flex-1 text-xs text-ink-2">
              Name
              <input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} className={field} />
            </label>
            <button type="submit" disabled={!name.trim() || name.trim() === item.name} className="rounded-lg px-3 py-1.5 text-sm text-ink ring-1 ring-hairline disabled:opacity-40">
              Rename
            </button>
          </form>
          <div className="flex items-end gap-2">
            <label className="block flex-1 text-xs text-ink-2">
              Category (all from this merchant)
              <select value={category} onChange={(e) => setCategory(e.target.value)} className={field}>
                {!item.categoryId && <option value="">Choose a category</option>}
                {categories.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            </label>
            <button
              type="button"
              disabled={category === '' || Number(category) === item.categoryId}
              onClick={() =>
                void run('Category', () =>
                  api.POST('/api/review/merchants/{merchantId}/category', {
                    params: { path: { merchantId: item.merchantId } },
                    body: { categoryId: Number(category) },
                  }),
                )
              }
              className="rounded-lg px-3 py-1.5 text-sm text-ink ring-1 ring-hairline disabled:opacity-40"
            >
              Apply
            </button>
          </div>
          {item.status === 'ACTIVE' && (
            <button
              type="button"
              onClick={() => void run('Mark ended', () => api.POST('/api/subscriptions/{id}/end', { params: { path: { id: item.id } } }))}
              className="rounded-lg px-3 py-1.5 text-sm text-bad ring-1 ring-hairline"
            >
              Mark ended
            </button>
          )}
          {status && <p className="text-xs text-ink-2">{status}</p>}
        </div>
      )}
    </li>
  )
}
