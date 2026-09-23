import { useEffect, useState } from 'react'
import { api } from '../api/client'
import type { HealthResponse } from '../api/types'

type HealthState =
  | { kind: 'loading' }
  | { kind: 'ok'; health: HealthResponse }
  | { kind: 'unreachable'; detail: string }

/** Placeholder home (CP0.2). The real home screen arrives in M3. */
export function HomePage() {
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
        <h1 className="text-3xl font-semibold tracking-tight">Outflow</h1>
        <p className="mt-1 text-slate-600">Where does my money go?</p>
      </header>

      <section className="rounded-2xl bg-white p-5 shadow-sm ring-1 ring-slate-200">
        <h2 className="text-sm font-medium text-slate-500">System status</h2>
        <HealthView state={state} />
      </section>

      <p className="text-sm text-slate-500">
        Scaffold only. Statement import, categories and the spending overview arrive in the next milestones.
      </p>
    </div>
  )
}

function HealthView({ state }: { state: HealthState }) {
  if (state.kind === 'loading') return <p className="mt-2 text-slate-500">Checking the API…</p>
  if (state.kind === 'unreachable')
    return (
      <p className="mt-2 text-rose-700">
        API unreachable <span className="text-slate-500">({state.detail})</span>
      </p>
    )
  const { status, database, schemaVersion } = state.health
  return (
    <dl className="mt-3 grid grid-cols-2 gap-y-2 text-sm">
      <dt className="text-slate-500">API</dt>
      <dd><Badge up={status === 'UP'} /></dd>
      <dt className="text-slate-500">Database</dt>
      <dd><Badge up={database === 'UP'} /></dd>
      <dt className="text-slate-500">Schema version</dt>
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
