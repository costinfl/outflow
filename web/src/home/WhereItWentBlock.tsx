import type { ReactNode } from 'react'
import { Link } from 'react-router'
import type { CategorySpend, MonthSummary } from '../api/types'
import { formatMoney, perMonth } from '../lib/format'
import { categoryLink, transactionsLink } from '../lib/links'
import { useAccountsFilter } from '../lib/accounts'
import { Card } from './Card'
import { Delta } from './Delta'
import { InsightLine } from './InsightLine'

/**
 * Block 2: which categories take most of it? Horizontal bars (one series, one hue), longest = largest category,
 * every row labelled in text and linking to what makes it up. Uncategorized and the folded rest are neutral gray.
 */
export function WhereItWentBlock({ s }: { s: MonthSummary }) {
  const { withFilters } = useAccountsFilter()
  if (s.categories.length === 0) {
    return (
      <Card title="Where it went" id="where">
        <p className="text-sm text-muted">No spending {s.months > 1 ? 'in these months' : 'this month'}.</p>
      </Card>
    )
  }
  const max = Math.max(...s.categories.map((c) => c.spentMinor), s.rest.spentMinor, 1)
  return (
    <Card title="Where it went" id="where">
      <ul className="space-y-3">
        {s.categories.map((c) => (
          <Row
            key={c.categoryId ?? 'uncategorized'}
            to={withFilters(
              c.categoryId == null
                ? transactionsLink(s.month, 'spend', { uncategorized: true })
                : s.months > 1 // the category screen shows one month; the row is these months' total
                  ? transactionsLink(s.month, 'spend', { category: c.categoryId })
                  : categoryLink(c.categoryId, s.month),
            )}
            name={c.name}
            amount={formatMoney(c.spentMinor, s.currency)}
            share={c.sharePct}
            width={c.spentMinor / max}
            neutral={c.categoryId == null}
            note={<CategoryNote c={c} s={s} />}
          />
        ))}
        {s.rest.categoryCount > 0 && (
          <Row
            to={withFilters(transactionsLink(s.month, 'spend'))}
            name={`Other (${s.rest.categoryCount} ${s.rest.categoryCount === 1 ? 'category' : 'categories'})`}
            amount={formatMoney(s.rest.spentMinor, s.currency)}
            share={s.rest.sharePct}
            width={s.rest.spentMinor / max}
            neutral
          />
        )}
      </ul>
      <InsightLine s={s} />
    </Card>
  )
}

function CategoryNote({ c, s }: { c: CategorySpend; s: MonthSummary }) {
  const money = (minor: number) => formatMoney(minor, s.currency)
  if (s.months > 1) {
    const monthly = <span className="text-muted">≈ {money(perMonth(c.spentMinor, s.monthsWithData))} a month</span>
    if (c.deltaPct != null)
      return (
        <>
          {monthly} · <Delta pct={c.deltaPct} against={`usual (${money(c.usualMinor)})`} />
        </>
      )
    if (c.categoryId == null) return <>{monthly} · <span className="text-muted">Tap to categorize</span></>
    return s.baselineMonths > 0 ? <>{monthly} · <span className="text-muted">New in these months</span></> : monthly
  }
  if (c.deltaPct != null) return <Delta pct={c.deltaPct} against={`usual (${money(c.usualMinor)})`} />
  if (c.categoryId == null) return <span className="text-muted">Tap to categorize</span>
  return s.baselineMonths > 0 ? <span className="text-muted">New this month</span> : null
}

function Row({
  to,
  name,
  amount,
  share,
  width,
  neutral = false,
  note,
}: {
  to: string
  name: string
  amount: string
  share: number
  width: number
  neutral?: boolean
  note?: ReactNode
}) {
  return (
    <li>
      <Link to={to} className="-mx-2 block rounded-lg px-2 py-1 hover:bg-page" title={`${name}: ${amount}, ${share}% of spending`}>
        <div className="flex items-baseline justify-between gap-3 text-sm">
          <span className="truncate font-medium text-ink">{name}</span>
          <span className="shrink-0 text-ink tabular-nums">
            {amount} <span className="text-muted">· {share}%</span>
          </span>
        </div>
        <div aria-hidden className="mt-1.5 h-3 rounded-r-[4px] bg-bar-track">
          <div
            className={`h-3 rounded-r-[4px] ${neutral ? 'bg-bar-neutral' : 'bg-bar'}`}
            style={{ width: `${Math.max(0, Math.min(1, width)) * 100}%` }}
          />
        </div>
        {note && <p className="mt-1 text-xs">{note}</p>}
      </Link>
    </li>
  )
}
