import { useState } from 'react'
import { api, isDemo } from '../api/client'
import type { ExpenseSplit, ExpenseSplitList, TransactionView } from '../api/types'
import { decimalToMinor, formatMoney, minorToDecimal } from '../lib/format'
import { allocate, parsePercent, parseShares, type SplitDraft, type SplitMode } from '../lib/split'
import { useApi } from '../lib/useApi'

const MODES: { mode: SplitMode; label: string; hint: string }[] = [
  { mode: 'EQUAL', label: 'Equally', hint: 'You and each person pay the same.' },
  { mode: 'PERCENT', label: 'By percentage', hint: 'Each person’s percentage; the rest is yours.' },
  { mode: 'FIXED', label: 'Fixed amounts', hint: 'Each person’s amount; the rest is yours.' },
  { mode: 'SHARES', label: 'By shares', hint: 'Weights, yours included: 2 for a couple, 1 each for the others.' },
]

type Row = { name: string; value: string }

/**
 * "Split this expense" (CP6.21): money you paid for something shared. Your figures then count only your share; the
 * row keeps the bank's amount and says what the others owe.
 */
export function SplitExpense({ t, onChanged }: { t: TransactionView; onChanged: () => void }) {
  const [editing, setEditing] = useState(false)
  const [status, setStatus] = useState<string | null>(null)
  const split = useApi<ExpenseSplit | null>(`split:${t.id}:${t.sharedMinor}`, () =>
    t.sharedMinor > 0
      ? api.GET('/api/transactions/{id}/split', { params: { path: { id: t.id } } })
      : Promise.resolve({ data: null, response: new Response() }),
  )
  if (t.amountMinor >= 0 || t.status === 'PENDING' || t.transferState) return null
  const total = -t.amountMinor
  const money = (minor: number) => formatMoney(minor, t.currency)
  const current = split.kind === 'ok' ? split.data : null

  async function remove() {
    setStatus('Saving…')
    const { response } = await api.DELETE('/api/transactions/{id}/split', { params: { path: { id: t.id } } })
    if (response.ok) {
      setStatus(null)
      onChanged()
    } else
      setStatus(
        response.status === 409
          ? 'Someone already paid part of this back: undo that in Splits to settle first.'
          : `Could not remove the split (HTTP ${response.status}).`,
      )
  }

  if (editing)
    return (
      <SplitForm
        t={t}
        current={current}
        onCancel={() => setEditing(false)}
        onSaved={() => {
          setEditing(false)
          onChanged()
        }}
      />
    )
  if (current)
    return (
      <section className="space-y-1 rounded-lg p-2 text-xs text-ink-2 ring-1 ring-hairline" aria-label="Split">
        <p>
          Split with {current.people.map((p) => p.name).join(', ')}: they owe {money(current.othersMinor)}, your share is{' '}
          <span className="font-medium text-ink">{money(current.mineMinor)}</span>. Only your share counts as spending.
        </p>
        <ul className="pl-3">
          {current.people.map((p) => (
            <li key={p.name}>
              {p.name}: {money(p.amountMinor)}
            </li>
          ))}
        </ul>
        <div className="flex items-center gap-3">
          <button type="button" onClick={() => setEditing(true)} className="text-bar underline">
            Change split
          </button>
          <button type="button" onClick={() => void remove()} className="text-muted underline">
            Not split
          </button>
        </div>
        {status && <p>{status}</p>}
      </section>
    )
  return (
    <button type="button" onClick={() => setEditing(true)} className="block text-xs text-bar underline">
      Split this expense ({money(total)})
    </button>
  )
}

function SplitForm({ t, current, onCancel, onSaved }: { t: TransactionView; current: ExpenseSplit | null; onCancel: () => void; onSaved: () => void }) {
  const total = -t.amountMinor
  const [mode, setMode] = useState<SplitMode>(current?.mode ?? 'EQUAL')
  const [rows, setRows] = useState<Row[]>(
    current
      ? current.people.map((p) => ({
          name: p.name,
          value:
            p.percent != null ? String(p.percent) : p.fixedMinor != null ? String(minorToDecimal(p.fixedMinor, t.currency)) : p.shares != null ? String(p.shares) : '',
        }))
      : [{ name: '', value: '' }],
  )
  const [myShares, setMyShares] = useState(String(current?.myShares ?? 1))
  const [status, setStatus] = useState<string | null>(null)
  const known = useApi<ExpenseSplitList>('splits:people', () => api.GET('/api/splits'))
  const suggestions = known.kind === 'ok' ? known.data.people : []

  const drafts: SplitDraft[] = rows.map((r) => ({
    name: r.name,
    percentHundredths: mode === 'PERCENT' ? parsePercent(r.value) : undefined,
    fixedMinor: mode === 'FIXED' ? decimalToMinor(r.value, t.currency) : undefined,
    shares: mode === 'SHARES' ? parseShares(r.value) : undefined,
  }))
  const mine = mode === 'SHARES' ? parseShares(myShares) : 1
  const preview = mine === null ? ({ ok: false, error: 'Your shares: 0 to 1000' } as const) : allocate(total, mode, drafts, mine)
  const money = (minor: number) => formatMoney(minor, t.currency)
  const update = (i: number, patch: Partial<Row>) => setRows(rows.map((r, j) => (j === i ? { ...r, ...patch } : r)))
  const valueLabel = mode === 'PERCENT' ? '%' : mode === 'FIXED' ? t.currency : 'shares'

  async function save() {
    if (!preview.ok) return
    setStatus('Saving…')
    const { data, response } = await api.PUT('/api/transactions/{id}/split', {
      params: { path: { id: t.id } },
      body: {
        mode,
        ...(mode === 'SHARES' ? { myShares: mine! } : {}),
        people: preview.people.map((p) => ({ name: p.name, percent: p.percent, fixedMinor: p.fixedMinor, shares: p.shares })),
      },
    })
    if (data) onSaved()
    else
      setStatus(
        isDemo
          ? 'Splitting needs the real app (the demo has no server).'
          : response.status === 409
            ? 'Someone already paid back more than this leaves them, or was left out: undo that repayment in Splits to settle first.'
            : `Could not save the split (HTTP ${response.status}).`,
      )
  }

  return (
    <section className="space-y-2 rounded-lg p-2 ring-1 ring-hairline" aria-label="Split this expense">
      <p className="text-xs text-ink-2">
        Split {money(total)} from {t.merchantName} with other people. Only your share will count as spending.
      </p>
      <label className="block text-xs text-ink-2">
        How
        <select
          value={mode}
          onChange={(e) => setMode(e.target.value as SplitMode)}
          className="mt-1 block w-full rounded-lg bg-page px-2 py-1.5 text-sm text-ink ring-1 ring-hairline"
        >
          {MODES.map((m) => (
            <option key={m.mode} value={m.mode}>
              {m.label}
            </option>
          ))}
        </select>
      </label>
      <p className="text-xs text-muted">{MODES.find((m) => m.mode === mode)!.hint}</p>
      <datalist id={`split-people-${t.id}`}>
        {suggestions.map((n) => (
          <option key={n} value={n} />
        ))}
      </datalist>
      {rows.map((r, i) => (
        <div key={i} className="flex items-center gap-2">
          <input
            aria-label={`Person ${i + 1}`}
            list={`split-people-${t.id}`}
            value={r.name}
            placeholder="Name"
            onChange={(e) => update(i, { name: e.target.value })}
            className="min-w-0 flex-1 rounded-lg bg-page px-2 py-1.5 text-sm text-ink ring-1 ring-hairline"
          />
          {mode !== 'EQUAL' && (
            <label className="flex items-center gap-1 text-xs text-muted">
              <input
                aria-label={`${valueLabel} for person ${i + 1}`}
                inputMode="decimal"
                value={r.value}
                onChange={(e) => update(i, { value: e.target.value })}
                className="w-20 rounded-lg bg-page px-2 py-1.5 text-sm text-ink tabular-nums ring-1 ring-hairline"
              />
              {valueLabel}
            </label>
          )}
          {rows.length > 1 && (
            <button type="button" aria-label={`Remove person ${i + 1}`} onClick={() => setRows(rows.filter((_, j) => j !== i))} className="px-1 text-muted">
              ×
            </button>
          )}
        </div>
      ))}
      <button type="button" onClick={() => setRows([...rows, { name: '', value: '' }])} className="text-xs text-bar underline">
        Add a person
      </button>
      {mode === 'SHARES' && (
        <label className="flex items-center gap-2 text-xs text-ink-2">
          Your shares
          <input
            aria-label="Your shares"
            inputMode="numeric"
            value={myShares}
            onChange={(e) => setMyShares(e.target.value)}
            className="w-16 rounded-lg bg-page px-2 py-1.5 text-sm text-ink tabular-nums ring-1 ring-hairline"
          />
          <span className="text-muted">0 if you only paid</span>
        </label>
      )}
      <div className="rounded-lg bg-page px-2 py-1.5 text-xs" aria-live="polite">
        {preview.ok ? (
          <>
            {preview.people.map((p) => (
              <p key={p.name} className="text-ink-2">
                {p.name} owes {money(p.amountMinor)}
              </p>
            ))}
            <p className="font-medium text-ink">Your share: {money(preview.mineMinor)}</p>
          </>
        ) : (
          <p className="text-muted">{rows.every((r) => r.name.trim() === '') ? 'Add who you shared it with.' : preview.error}</p>
        )}
      </div>
      <div className="flex items-center gap-2">
        <button type="button" disabled={!preview.ok} onClick={() => void save()} className="rounded-lg bg-bar px-3 py-1.5 text-sm font-medium text-white disabled:opacity-40">
          Save split
        </button>
        <button type="button" onClick={onCancel} className="px-1 py-1.5 text-sm text-muted hover:underline">
          Cancel
        </button>
      </div>
      {status && <p className="text-xs text-ink-2">{status}</p>}
    </section>
  )
}
