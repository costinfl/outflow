import { useState } from 'react'
import { Link } from 'react-router'
import { api, isDemo } from '../api/client'
import type { SplitBalance, SplitDebt } from '../api/types'
import { formatDay, formatMoney } from '../lib/format'
import { YouOwe } from './YouOwe'
import { useApi } from '../lib/useApi'

export type Settle = (label: string, call: () => Promise<{ response: Response }>) => Promise<void>

/**
 * "Splits to settle" (CP6.22): who owes you what for shared expenses. Link the money they send back to what they owe;
 * that money is then neither income nor a refund, since only your share of the expense ever counted. Below, what you
 * owe people who paid for you (CP6.23, "they paid, I owe").
 */
export function SplitsPage() {
  const [version, setVersion] = useState(0)
  const [status, setStatus] = useState<string | null>(null)
  const balances = useApi(`balances:${version}`, () => api.GET('/api/splits/balances'))

  const act: Settle = async (label, call) => {
    setStatus(`${label}…`)
    const { response } = await call()
    if (response.ok) {
      setStatus(`${label}: done.`)
      setVersion((v) => v + 1)
    } else setStatus(isDemo ? 'Settling needs the real app: the demo has no server.' : `${label} failed (HTTP ${response.status}).`)
  }

  if (balances.kind === 'loading') return <p className="py-12 text-center text-muted">Loading…</p>
  if (balances.kind === 'error')
    return (
      <p role="alert" className="py-12 text-center text-bad">
        Could not load the splits ({balances.message}).
      </p>
    )
  const people = balances.data.people
  const owing = people.filter((p) => p.openMinor > 0)
  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold text-ink">Splits to settle</h1>
      {status && (
        <p role="status" className="text-sm text-ink-2">
          {status}
        </p>
      )}
      <div>
        <h2 className="font-medium text-ink">People owe you</h2>
        <p className="mt-1 text-sm text-ink-2">
          {people.length === 0
            ? 'Nothing is split yet. Open a payment in Transactions and choose “Split this expense”.'
            : owing.length === 0
              ? 'Everyone has paid you back.'
              : 'What people owe you for shared expenses. Link the money they send back: it is then neither income nor a refund.'}
        </p>
      </div>
      {people.map((p) => (
        <PersonCard key={`${p.name}|${p.currency}`} person={p} act={act} />
      ))}
      {people.length === 0 && (
        <Link to="/transactions" className="inline-block text-sm text-bar underline">
          Go to transactions
        </Link>
      )}
      <YouOwe version={version} act={act} />
    </div>
  )
}

function PersonCard({ person, act }: { person: SplitBalance; act: Settle }) {
  const money = (minor: number) => formatMoney(minor, person.currency)
  const open = person.debts.filter((d) => d.openMinor > 0)
  const settled = person.debts.filter((d) => d.openMinor === 0)
  return (
    <section aria-label={person.name} className="rounded-2xl bg-surface p-4 ring-1 ring-hairline">
      <div className="flex items-baseline justify-between gap-3">
        <h2 className="truncate font-medium text-ink">{person.name}</h2>
        <span className={`shrink-0 text-sm tabular-nums ${person.openMinor > 0 ? 'font-semibold text-ink' : 'text-muted'}`}>
          {person.openMinor > 0 ? `owes you ${money(person.openMinor)}` : 'all settled'}
        </span>
      </div>
      <ul className="mt-2 space-y-3">
        {open.map((d) => (
          <Debt key={d.splitPersonId} debt={d} person={person.name} money={money} act={act} />
        ))}
      </ul>
      {settled.length > 0 && (
        <details className="mt-3 text-sm">
          <summary className="cursor-pointer text-muted">
            {settled.length} settled {settled.length === 1 ? 'split' : 'splits'}
          </summary>
          <ul className="mt-2 space-y-3">
            {settled.map((d) => (
              <Debt key={d.splitPersonId} debt={d} person={person.name} money={money} act={act} />
            ))}
          </ul>
        </details>
      )}
    </section>
  )
}

function Debt({ debt, person, money, act }: { debt: SplitDebt; person: string; money: (m: number) => string; act: Settle }) {
  const settle = (transactionId?: number) =>
    act(transactionId ? 'Linked the repayment' : 'Marked as paid outside the bank', () =>
      api.POST('/api/splits/settlements', {
        body: { splitPersonId: debt.splitPersonId, ...(transactionId ? { transactionId } : {}) },
      }),
    )
  return (
    <li className="border-t border-hairline pt-2 text-sm">
      <p className="text-ink">
        {debt.merchantName}, {formatDay(debt.bookingDate)}
      </p>
      <p className="text-xs text-ink-2">
        Their part {money(debt.owedMinor)}
        {debt.settledMinor > 0 && `, paid back ${money(debt.settledMinor)}`}
        {debt.openMinor > 0 && (
          <>
            , <span className="font-medium text-ink">{money(debt.openMinor)} open</span>
          </>
        )}
        .
      </p>
      {debt.repayments.length > 0 && (
        <ul className="mt-1 space-y-0.5 text-xs text-muted">
          {debt.repayments.map((r) => (
            <li key={r.id} className="flex items-center gap-2">
              <span>
                {money(r.amountMinor)} {r.bookingDate ? `on ${formatDay(r.bookingDate)}` : 'outside the bank'}
              </span>
              <button
                type="button"
                onClick={() => void act('Undone', () => api.DELETE('/api/splits/settlements/{id}', { params: { path: { id: r.id } } }))}
                className="underline"
              >
                Undo
              </button>
            </li>
          ))}
        </ul>
      )}
      {debt.openMinor > 0 && (
        <div className="mt-2 space-y-1.5">
          {debt.suggestions.map((s) => (
            <div key={s.transactionId} className="flex items-center justify-between gap-2 rounded-lg bg-page px-2 py-1.5">
              <span className="min-w-0 text-xs text-ink-2">
                <span className="block truncate text-ink">{s.name}</span>
                {money(s.amountMinor)} on {formatDay(s.bookingDate)}
                {s.namesPerson && s.sameAmount ? ' · names them, same amount' : s.namesPerson ? ` · names ${person}` : ' · same amount'}
                {s.availableMinor < s.amountMinor && ` · ${money(s.availableMinor)} still unlinked`}
              </span>
              <button type="button" onClick={() => void settle(s.transactionId)} className="shrink-0 rounded-lg bg-bar px-2.5 py-1 text-xs font-medium text-white">
                Link
              </button>
            </div>
          ))}
          {debt.suggestions.length === 0 && <p className="text-xs text-muted">No money in from {person} or of this amount yet.</p>}
          <button type="button" onClick={() => void settle()} className="text-xs text-bar underline">
            Paid outside the bank ({money(debt.openMinor)})
          </button>
        </div>
      )}
    </li>
  )
}
