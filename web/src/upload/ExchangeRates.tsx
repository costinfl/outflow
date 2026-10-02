import { useRef, useState } from 'react'
import { api } from '../api/client'
import type { FxPair } from '../api/types'
import { formatDay } from '../lib/format'
import { useApi } from '../lib/useApi'

/**
 * The user's approximate exchange rates (CP6.19): how a transfer from a RON account to a EUR account is recognised by
 * its amounts. Used for nothing else: figures are never converted, and no rate is looked up online.
 */
export function ExchangeRates({ version }: { version: number }) {
  const [saved, setSaved] = useState(0)
  const loaded = useApi<FxPair[]>(`fx:${version}:${saved}`, () => api.GET('/api/fx-rates'))
  // Keep the rows while a save reloads the list, so their "Saved" note stays.
  const last = useRef<FxPair[]>([])
  if (loaded.kind === 'ok') last.current = loaded.data
  const pairs = last.current
  if (pairs.length === 0) return null
  return (
    <section aria-label="Exchange rates" className="space-y-2 rounded-2xl bg-surface p-4 ring-1 ring-hairline">
      <h2 className="text-sm font-medium text-ink">Transfers between currencies</h2>
      <p className="text-xs text-muted">
        Your accounts hold more than one currency. With an approximate rate, money moved between them is recognised as a
        transfer, not spending. It is only used for that: figures are never converted, and nothing is looked up online.
        Without a rate, only a transfer that names the other account's IBAN is recognised.
      </p>
      {pairs.map((p) => (
        <PairRow key={`${p.base}/${p.quote}`} pair={p} onSaved={() => setSaved((n) => n + 1)} />
      ))}
    </section>
  )
}

/** "4,97" or "4.97" → 4.97; anything else → null. */
export function parseDecimal(text: string): number | null {
  const s = text.trim().replace(',', '.')
  return /^\d+(\.\d+)?$/.test(s) ? Number(s) : null
}

function PairRow({ pair, onSaved }: { pair: FxPair; onSaved: () => void }) {
  const [rate, setRate] = useState(pair.rate != null ? String(pair.rate) : '')
  const [tolerance, setTolerance] = useState(String(pair.tolerancePercent ?? 3))
  const [status, setStatus] = useState<string | null>(null)
  const parsedRate = parseDecimal(rate)
  const parsedTolerance = parseDecimal(tolerance)
  const valid = parsedRate !== null && parsedRate > 0 && parsedTolerance !== null && parsedTolerance <= 10
  const path = { params: { path: { base: pair.base, quote: pair.quote } } }

  async function save() {
    const { data } = await api.PUT('/api/fx-rates/{base}/{quote}', { ...path, body: { rate: parsedRate!, tolerancePercent: parsedTolerance! } })
    setStatus(data ? 'Saved, transfers re-checked' : 'Could not save')
    if (data) onSaved()
  }
  async function remove() {
    const { response } = await api.DELETE('/api/fx-rates/{base}/{quote}', path)
    if (response.ok) {
      setRate('')
      setStatus('Removed')
      onSaved()
    } else setStatus('Could not remove')
  }

  return (
    <div className="space-y-1 border-t border-hairline pt-2">
      <div className="flex flex-wrap items-center gap-2 text-sm text-ink">
        <span>1 {pair.base} =</span>
        <input
          aria-label={`${pair.quote} per ${pair.base}`}
          inputMode="decimal"
          value={rate}
          placeholder="e.g. 4.97"
          onChange={(e) => {
            setRate(e.target.value)
            setStatus(null)
          }}
          className="w-24 rounded-lg bg-page px-2 py-1 text-ink tabular-nums ring-1 ring-hairline"
        />
        <span>{pair.quote}, ±</span>
        <input
          aria-label={`Tolerance for ${pair.base}/${pair.quote}, percent`}
          inputMode="decimal"
          value={tolerance}
          onChange={(e) => {
            setTolerance(e.target.value)
            setStatus(null)
          }}
          className="w-14 rounded-lg bg-page px-2 py-1 text-ink tabular-nums ring-1 ring-hairline"
        />
        <span>%</span>
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <button type="button" disabled={!valid} onClick={() => void save()} className="rounded-lg bg-bar px-3 py-1 text-sm font-medium text-white disabled:opacity-40">
          Save
        </button>
        {pair.rate != null && (
          <button type="button" onClick={() => void remove()} className="rounded-lg px-3 py-1 text-sm text-ink ring-1 ring-hairline">
            Remove
          </button>
        )}
        {status && <span className="text-xs text-muted">{status}</span>}
      </div>
      {pair.lastSeenRate != null && pair.lastSeenOn && (
        <p className="text-xs text-muted">
          Your latest transfer between them, on {formatDay(pair.lastSeenOn)}: 1 {pair.base} = {pair.lastSeenRate} {pair.quote}.{' '}
          {String(pair.lastSeenRate) !== rate && (
            <button type="button" className="underline" onClick={() => setRate(String(pair.lastSeenRate))}>
              Use it
            </button>
          )}
        </p>
      )}
    </div>
  )
}
