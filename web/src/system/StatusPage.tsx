import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { HealthResponse } from '../api/types'

type HealthState =
  | { kind: 'loading' }
  | { kind: 'ok'; health: HealthResponse }
  | { kind: 'unreachable'; detail: string }

/** System status: API and database health (the CP0.2 placeholder, kept at #/status). */
export function StatusPage() {
  const [state, setState] = useState<HealthState>({ kind: 'loading' })

  useEffect(() => {
    let cancelled = false
    api
      .GET('/api/health')
      .then(({ data, response }) => {
        if (cancelled) return
        setState(data ? { kind: 'ok', health: data } : { kind: 'unreachable', detail: `HTTP ${response.status}` })
      })
      .catch((e: unknown) => {
        if (!cancelled) setState({ kind: 'unreachable', detail: e instanceof Error ? e.message : String(e) })
      })
    return () => {
      cancelled = true
    }
  }, [])

  return (
    <div className="space-y-6">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight">System status</h1>
      </header>

      <section className="rounded-2xl bg-surface p-5 shadow-sm ring-1 ring-hairline">
        <h2 className="text-sm font-medium text-muted">System status</h2>
        <HealthView state={state} />
      </section>

    </div>
  )
}

function HealthView({ state }: { state: HealthState }) {
  if (state.kind === 'loading') return <p className="mt-2 text-muted">Checking the API…</p>
  if (state.kind === 'unreachable')
    return (
      <p className="mt-2 text-rose-700">
        API unreachable <span className="text-muted">({state.detail})</span>
      </p>
    )
  const { status, database, schemaVersion } = state.health
  return (
    <dl className="mt-3 grid grid-cols-2 gap-y-2 text-sm">
      <dt className="text-muted">API</dt>
      <dd><Badge up={status === 'UP'} /></dd>
      <dt className="text-muted">Database</dt>
      <dd><Badge up={database === 'UP'} /></dd>
      <dt className="text-muted">Schema version</dt>
      <dd className="font-mono">{schemaVersion ?? '–'}</dd>
    </dl>
  )
}

function Badge({ up }: { up: boolean }) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium ${
        up ? 'bg-emerald-50 text-emerald-700' : 'bg-rose-50 text-rose-700'
      }`}
    >
      <span className={`size-1.5 rounded-full ${up ? 'bg-emerald-500' : 'bg-rose-500'}`} />
      {up ? 'Up' : 'Down'}
    </span>
  )
}
