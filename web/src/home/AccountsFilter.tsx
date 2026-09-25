import { useSearchParams } from 'react-router'
import { api } from '../api/client'
import type { Account } from '../api/types'
import { useAccountsFilter } from '../lib/accounts'
import { useApi } from '../lib/useApi'

/** Chips at the top of home: all accounts by default; tap accounts to narrow every number to them. */
export function AccountsFilter() {
  const [params, setParams] = useSearchParams()
  const { ids } = useAccountsFilter()
  const accounts = useApi<Account[]>('accounts', () => api.GET('/api/accounts'))
  if (accounts.kind !== 'ok' || accounts.data.length < 2) return null
  const all = accounts.data

  const select = (next: number[]) => {
    const p = new URLSearchParams(params)
    // Every account selected means no filter.
    if (next.length === 0 || next.length === all.length) p.delete('accounts')
    else p.set('accounts', [...next].sort((a, b) => a - b).join(','))
    setParams(p)
  }
  const toggle = (id: number) => select(ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id])
  const chip = (active: boolean) =>
    `shrink-0 rounded-full px-3 py-1 text-xs ${active ? 'bg-ink text-page' : 'bg-surface text-ink ring-1 ring-hairline'}`

  return (
    <div role="group" aria-label="Accounts" className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1">
      <button type="button" aria-pressed={ids.length === 0} className={chip(ids.length === 0)} onClick={() => select([])}>
        All accounts
      </button>
      {all.map((a) => (
        <button key={a.id} type="button" aria-pressed={ids.includes(a.id)} className={chip(ids.includes(a.id))} onClick={() => toggle(a.id)}>
          {a.name}
          {a.ibanMasked ? <span className="ml-1 opacity-60">{a.ibanMasked.slice(-4)}</span> : null}
        </button>
      ))}
    </div>
  )
}
