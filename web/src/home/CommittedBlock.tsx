import type { MonthSummary } from '../api/types'
import { Card } from './Card'

/**
 * Block 3: committed every month. Needs recurring-payment detection (M4); until then it says so plainly, with the
 * DESIGN history nudge when there is too little history to detect anything.
 */
export function CommittedBlock({ s }: { s: MonthSummary }) {
  const months = s.availableMonths.length
  return (
    <Card title="Committed every month" id="committed">
      <p className="text-sm text-ink-2">Subscriptions and recurring bills will appear here once detection is in place.</p>
      {months < 3 && (
        <p className="mt-2 text-sm text-muted">
          Upload 3+ months to detect monthly payments, 12+ for yearly ones. You have {months} {months === 1 ? 'month' : 'months'}.
        </p>
      )}
    </Card>
  )
}
