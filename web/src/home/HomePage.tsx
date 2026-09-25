import { Link, useSearchParams } from 'react-router'
import { api } from '../api/client'
import type { MonthSummary } from '../api/types'
import { useApi } from '../lib/useApi'
import { useAccountsFilter } from '../lib/accounts'
import { AccountsFilter } from './AccountsFilter'
import { AttentionBlock } from './AttentionBlock'
import { CommittedBlock } from './CommittedBlock'
import { MonthSwitcher } from './MonthSwitcher'
import { PeriodToggle } from './PeriodToggle'
import { SpentBlock } from './SpentBlock'
import { WhereItWentBlock } from './WhereItWentBlock'

/** The answer first (DESIGN: Home screen): four blocks for one month, most important first, no transaction list. */
export function HomePage() {
  const [params, setParams] = useSearchParams()
  const month = params.get('month') ?? undefined
  const accounts = useAccountsFilter()
  const state = useApi<MonthSummary>(`month:${month ?? 'latest'}:${accounts.months}:${accounts.key}`, () =>
    api.GET('/api/insights/month', {
      params: { query: { ...(month ? { month } : {}), ...accounts.monthsQuery, ...accounts.query } },
    }),
  )

  if (state.kind === 'loading') return <p className="py-12 text-center text-muted">Loading…</p>
  if (state.kind === 'error')
    return (
      <p role="alert" className="py-12 text-center text-bad">
        Could not load this month ({state.message}).
      </p>
    )
  const s = state.data
  if (s.availableMonths.length === 0 && accounts.ids.length > 0) {
    return (
      <div className="space-y-4">
        <AccountsFilter />
        <p className="py-8 text-center text-ink-2">No transactions in the selected accounts yet.</p>
      </div>
    )
  }
  if (s.availableMonths.length === 0) {
    return (
      <div className="py-12 text-center">
        <h1 className="text-xl font-semibold text-ink">Where does your money go?</h1>
        <p className="mt-2 text-ink-2">Upload a bank statement to see the answer.</p>
        <Link to="/upload" className="mt-4 inline-block rounded-lg bg-bar px-4 py-2 font-medium text-white">
          Upload statements
        </Link>
      </div>
    )
  }
  return (
    <div className="space-y-4">
      <h1 className="sr-only">Outflow: where your money went</h1>
      <AccountsFilter />
      <MonthSwitcher
        month={s.month}
        available={s.availableMonths}
        onChange={(m) => {
          const next = new URLSearchParams(params)
          next.set('month', m)
          setParams(next)
        }}
      />
      <PeriodToggle
        months={s.months}
        onChange={(m) => {
          const next = new URLSearchParams(params)
          if (m === 3) next.set('months', '3')
          else next.delete('months')
          setParams(next)
        }}
      />
      <SpentBlock s={s} />
      <WhereItWentBlock s={s} />
      <CommittedBlock s={s} />
      <AttentionBlock s={s} />
    </div>
  )
}
