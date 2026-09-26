import { Link } from 'react-router'
import { api } from '../api/client'
import type { Inbox, MonthSummary } from '../api/types'
import { formatMoney } from '../lib/format'
import { transactionsLink } from '../lib/links'
import { useApi } from '../lib/useApi'
import { useAccountsFilter } from '../lib/accounts'
import { Card } from './Card'

/** Block 4: how far to trust the numbers, and what still needs a decision. */
export function AttentionBlock({ s }: { s: MonthSummary }) {
  const { withFilters } = useAccountsFilter()
  const inbox = useApi<Inbox>('review-count', () => api.GET('/api/review'))
  const questions = inbox.kind === 'ok' ? inbox.data.count : 0
  return (
    <Card title="Needs your attention" id="attention">
      {questions > 0 && (
        <Link
          to="/review"
          className="mb-3 flex items-center justify-between rounded-lg bg-bar-track px-3 py-2 text-sm font-medium text-ink hover:underline"
        >
          <span>
            {questions} {questions === 1 ? 'question' : 'questions'} to review
          </span>
          <span aria-hidden="true">›</span>
        </Link>
      )}
      <div className="flex items-baseline justify-between text-sm">
        <span className="text-ink-2">Accuracy</span>
        <span className="font-medium text-ink tabular-nums">{s.reviewedPct}%</span>
      </div>
      <div
        role="meter"
        aria-label="Accuracy"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={s.reviewedPct}
        className="mt-1.5 h-2 rounded-full bg-bar-track"
      >
        <div className="h-2 rounded-full bg-bar" style={{ width: `${s.reviewedPct}%` }} />
      </div>
      <p className="mt-1 text-xs text-muted">
        How much of {s.months > 1 ? "these months'" : "this month's"} spending is categorized and reviewed by you ({s.categorizedPct}% has a category). Review questions raise it.
      </p>
      {s.uncategorizedCount > 0 ? (
        <Link
          to={withFilters(transactionsLink(s.month, 'spend', { uncategorized: true }))}
          className="mt-3 flex items-center justify-between rounded-lg bg-page px-3 py-2 text-sm hover:underline"
        >
          <span className="text-ink">
            {s.uncategorizedCount} uncategorized {s.uncategorizedCount === 1 ? 'payment' : 'payments'}
          </span>
          <span className="text-ink tabular-nums">{formatMoney(s.uncategorizedMinor, s.currency)} ›</span>
        </Link>
      ) : (
        <p className="mt-3 text-sm text-ink-2">Every payment {s.months > 1 ? 'in these months' : 'this month'} has a category.</p>
      )}
    </Card>
  )
}
