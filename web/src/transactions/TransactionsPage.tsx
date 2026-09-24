import { Link, useSearchParams } from 'react-router'
import { formatMonth } from '../lib/format'

/** Drill-through target for home-screen numbers. The list itself arrives in CP3.3. */
export function TransactionsPage() {
  const [params] = useSearchParams()
  const month = params.get('month')
  const scope = params.get('scope')
  const parts = [
    scope === 'income' ? 'Income' : scope === 'spend' ? 'Spending' : 'All transactions',
    month ? formatMonth(month) : null,
    params.get('uncategorized') ? 'uncategorized' : null,
    params.get('category') ? `category #${params.get('category')}` : null,
  ].filter(Boolean)
  return <Placeholder title="Transactions" filter={parts.join(' · ')} month={month} />
}

export function Placeholder({ title, filter, month }: { title: string; filter: string; month: string | null }) {
  return (
    <div className="space-y-3">
      <h1 className="text-2xl font-semibold text-ink">{title}</h1>
      <p className="rounded-lg bg-surface px-3 py-2 text-sm text-ink-2 ring-1 ring-hairline">{filter}</p>
      <p className="text-sm text-muted">This screen is being built (CP3.3). The filter above is what the number you tapped adds up.</p>
      <Link to={month ? `/?month=${month}` : '/'} className="text-sm text-bar underline">
        Back to {month ? formatMonth(month) : 'home'}
      </Link>
    </div>
  )
}
