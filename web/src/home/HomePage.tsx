import { useSearchParams } from 'react-router'
import { api } from '../api/client'
import type { MonthSummary } from '../api/types'
import { useApi } from '../lib/useApi'
import { AttentionBlock } from './AttentionBlock'
import { CommittedBlock } from './CommittedBlock'
import { MonthSwitcher } from './MonthSwitcher'
import { SpentBlock } from './SpentBlock'
import { WhereItWentBlock } from './WhereItWentBlock'

/** The answer first (DESIGN: Home screen): four blocks for one month, most important first, no transaction list. */
export function HomePage() {
  const [params, setParams] = useSearchParams()
  const month = params.get('month') ?? undefined
  const state = useApi<MonthSummary>(`month:${month ?? 'latest'}`, () =>
    api.GET('/api/insights/month', { params: { query: month ? { month } : {} } }),
  )

  if (state.kind === 'loading') return <p className="py-12 text-center text-muted">Loading…</p>
  if (state.kind === 'error')
    return (
      <p role="alert" className="py-12 text-center text-bad">
        Could not load this month ({state.message}).
      </p>
    )
  const s = state.data
  if (s.availableMonths.length === 0) {
    return (
      <div className="py-12 text-center">
        <h1 className="text-xl font-semibold text-ink">Where does your money go?</h1>
        <p className="mt-2 text-ink-2">Upload a bank statement to see the answer.</p>
      </div>
    )
  }
  return (
    <div className="space-y-4">
      <h1 className="sr-only">Outflow: where your money went</h1>
      <MonthSwitcher month={s.month} available={s.availableMonths} onChange={(m) => setParams({ month: m })} />
      <SpentBlock s={s} />
      <WhereItWentBlock s={s} />
      <CommittedBlock s={s} />
      <AttentionBlock s={s} />
    </div>
  )
}
