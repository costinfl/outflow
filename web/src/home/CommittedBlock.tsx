import { Link } from 'react-router'
import type { MonthSummary } from '../api/types'
import { formatMoney } from '../lib/format'
import { recurringLink } from '../lib/links'
import { useAccountsFilter } from '../lib/accounts'
import { Card } from './Card'

/**
 * Block 3: committed every month: the monthly equivalents of the confirmed recurring payments active in this month.
 * Opens the Recurring screen for the same month, whose total is the same number. With confirmed recurring income, it
 * also says what comes in every month and what that leaves after the commitments.
 */
export function CommittedBlock({ s }: { s: MonthSummary }) {
  const { withFilters } = useAccountsFilter()
  const months = s.availableMonths.length
  const { monthlyMinor, count, sharePct, incomeMonthlyMinor, incomeCount } = s.committed
  const money = (minor: number) => formatMoney(minor, s.currency)
  const left = incomeMonthlyMinor - monthlyMinor
  return (
    <Card title="Committed every month" id="committed">
      {count > 0 ? (
        <Link to={withFilters(recurringLink(s.month))} className="block hover:underline">
          <span className="text-2xl font-semibold text-ink tabular-nums">{formatMoney(monthlyMinor, s.currency)}</span>
          <span className="mt-1 block text-sm text-ink-2">
            {count} recurring {count === 1 ? 'payment' : 'payments'}
            {sharePct !== undefined && sharePct !== null ? ` · ${sharePct}% of ${s.months > 1 ? 'monthly' : "this month's"} spending` : ''} ›
          </span>
        </Link>
      ) : (
        <p className="text-sm text-ink-2">
          No confirmed recurring payments yet.{' '}
          <Link to="/review" className="text-bar underline">
            Review suggestions
          </Link>{' '}
          or see <Link to={withFilters(recurringLink())} className="text-bar underline">recurring payments</Link>.
        </p>
      )}
      {incomeCount > 0 && (
        <Link
          to={withFilters(recurringLink(s.month))}
          className="mt-3 block border-t border-hairline pt-3 text-sm text-ink-2 hover:underline"
        >
          Recurring income <span className="font-medium text-ink tabular-nums">{money(incomeMonthlyMinor)}</span> a month
          {incomeCount > 1 ? ` (${incomeCount} payments)` : ''}
          <span className="block">
            {left >= 0 ? 'Leaves' : 'Short by'}{' '}
            <span className="font-medium text-ink tabular-nums">{money(Math.abs(left))}</span> a month after commitments ›
          </span>
        </Link>
      )}
      {months < 3 && (
        <p className="mt-2 text-sm text-muted">
          Upload 3+ months to detect monthly payments, 12+ for yearly ones. You have {months} {months === 1 ? 'month' : 'months'}.
        </p>
      )}
    </Card>
  )
}
