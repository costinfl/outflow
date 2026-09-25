import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { api } from '../api/client'
import type { CategoryDetail } from '../api/types'
import { formatMoney, formatMonth, formatMonthShort } from '../lib/format'
import { useAccountsFilter } from '../lib/accounts'
import { useApi } from '../lib/useApi'

/** "What exactly is in this category?" (DESIGN: Detail screens): 12-month trend with its average, then merchants. */
export function CategoryPage() {
  const { id } = useParams()
  const [params] = useSearchParams()
  const month = params.get('month') ?? ''
  const accounts = useAccountsFilter()
  const state = useApi<CategoryDetail>(`cat:${id}:${month}:${accounts.key}`, () =>
    api.GET('/api/insights/categories/{id}', { params: { path: { id: Number(id) }, query: { month, ...accounts.query } } }),
  )
  if (state.kind === 'loading') return <p className="py-12 text-center text-muted">Loading…</p>
  if (state.kind === 'error')
    return (
      <p role="alert" className="py-12 text-center text-bad">
        Could not load this category ({state.message}).
      </p>
    )
  const d = state.data
  const money = (m: number) => formatMoney(m, d.currency)
  const scope = d.category.kind === 'INCOME' ? 'income' : d.category.kind === 'SPEND' ? 'spend' : ''
  const txLink = (extra: Record<string, string> = {}) =>
    accounts.withAccounts(
      `/transactions?${new URLSearchParams({ month: d.month, ...(scope ? { scope } : {}), category: String(d.category.id), ...extra })}`,
    )
  return (
    <div className="space-y-4">
      <header>
        <p className="text-sm text-ink-2">
          <Link to={accounts.withAccounts(`/?month=${d.month}`)} className="underline">
            {formatMonth(d.month)}
          </Link>
        </p>
        <h1 className="text-2xl font-semibold text-ink">{d.category.name}</h1>
        <Link to={txLink()} className="mt-1 block text-4xl font-semibold tracking-tight text-ink hover:underline">
          {money(d.amountMinor)}
        </Link>
        {d.averageMinor != null && (
          <p className="mt-1 text-sm text-ink-2">
            Average {money(d.averageMinor)} over {d.trend.filter((t) => t.hasData).length} months with data
          </p>
        )}
      </header>
      <Trend d={d} />
      <section className="rounded-2xl bg-surface p-4 ring-1 ring-hairline" aria-labelledby="merchants">
        <h2 id="merchants" className="text-sm font-medium text-ink-2">
          Merchants in {formatMonth(d.month)}
        </h2>
        {d.merchants.length === 0 ? (
          <p className="mt-2 text-sm text-muted">Nothing in this category this month.</p>
        ) : (
          <ul className="mt-2 divide-y divide-hairline">
            {d.merchants.map((m) => (
              <li key={m.merchantId}>
                <Link to={txLink({ merchant: String(m.merchantId) })} className="flex items-baseline justify-between gap-3 py-2 hover:underline">
                  <span className="min-w-0 truncate text-sm text-ink">
                    {m.name} <span className="text-muted">· {m.transactionCount} {m.transactionCount === 1 ? 'time' : 'times'}</span>
                  </span>
                  <span className="shrink-0 text-sm text-ink tabular-nums">{money(m.amountMinor)}</span>
                </Link>
              </li>
            ))}
          </ul>
        )}
        <Link to={txLink()} className="mt-3 block text-sm text-bar underline">
          All {d.category.name} transactions
        </Link>
      </section>
      <p className="text-xs text-muted">Recurring payments in this category will be listed separately once detection is in place.</p>
    </div>
  )
}

/**
 * Twelve monthly columns, one series: the selected month in the accent, the others in the de-emphasis gray; the
 * average as a hairline across. Only the selected month is labelled with its value; every column has a tooltip and
 * opens its month.
 */
function Trend({ d }: { d: CategoryDetail }) {
  const navigate = useNavigate()
  const { withAccounts } = useAccountsFilter()
  const max = Math.max(...d.trend.map((t) => t.amountMinor), d.averageMinor ?? 0, 1)
  const height = 120
  const avgY = d.averageMinor != null ? (d.averageMinor / max) * height : null
  return (
    <section className="rounded-2xl bg-surface p-4 ring-1 ring-hairline" aria-label="Last 12 months">
      <h2 className="text-sm font-medium text-ink-2">Last 12 months</h2>
      <div className="relative mt-6" style={{ height }}>
        {avgY != null && (
          <div aria-hidden className="absolute inset-x-0 border-t border-ink-2/60" style={{ bottom: avgY }}>
            <span className="absolute -top-4 right-0 text-[10px] text-ink-2">avg</span>
          </div>
        )}
        <ol className="absolute inset-0 flex items-end gap-[2px] border-b border-hairline">
          {d.trend.map((t) => {
            const current = t.month === d.month
            const h = Math.max(0, t.amountMinor / max) * height
            return (
              <li key={t.month} className="relative flex h-full flex-1 items-end justify-center">
                {current && t.amountMinor > 0 && (
                  <span className="absolute text-[10px] font-medium whitespace-nowrap text-ink" style={{ bottom: h + 2 }}>
                    {formatMoney(t.amountMinor, d.currency).replace(/[.,]\d{2}(?=\D*$)/, '')}
                  </span>
                )}
                <button
                  type="button"
                  onClick={() => navigate(withAccounts(`/categories/${d.category.id}?month=${t.month}`))}
                  title={`${formatMonth(t.month)}: ${t.hasData ? formatMoney(t.amountMinor, d.currency) : 'no data'}`}
                  aria-label={`${formatMonth(t.month)}: ${t.hasData ? formatMoney(t.amountMinor, d.currency) : 'no data'}`}
                  className="flex h-full w-full max-w-6 items-end"
                >
                  <span
                    className={`block w-full rounded-t-[4px] ${current ? 'bg-bar' : 'bg-bar-neutral/60'}`}
                    style={{ height: t.hasData ? Math.max(h, t.amountMinor > 0 ? 2 : 0) : 0 }}
                  />
                </button>
              </li>
            )
          })}
        </ol>
      </div>
      <ol aria-hidden className="mt-1 flex gap-[2px] text-center text-[10px] text-muted">
        {d.trend.map((t) => (
          <li key={t.month} className={`flex-1 ${t.month === d.month ? 'font-medium text-ink' : ''}`}>
            {formatMonthShort(t.month).slice(0, 3)}
          </li>
        ))}
      </ol>
    </section>
  )
}
