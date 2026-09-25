import { useRef } from 'react'
import { useSearchParams } from 'react-router'
import { api } from '../api/client'
import type { Account } from '../api/types'
import { useAccountsFilter } from '../lib/accounts'
import {
  CHIPS_MAX,
  type AccountKind,
  type PickAccount,
  groupState,
  groups,
  isSelected,
  only,
  summary,
  tapChip,
  toggleBox,
  toggleGroup,
} from '../lib/accountSelection'
import { useApi } from '../lib/useApi'

/**
 * The accounts filter at the top of home: all accounts by default; narrowing it narrows every number. Up to
 * {@link CHIPS_MAX} accounts are chips; more open a sheet with a checkbox per account, grouped by kind.
 */
export function AccountsFilter() {
  const [params, setParams] = useSearchParams()
  const { ids } = useAccountsFilter()
  const accounts = useApi<Account[]>('accounts', () => api.GET('/api/accounts'))
  if (accounts.kind !== 'ok' || accounts.data.length < 2) return null
  const all: PickAccount[] = accounts.data

  const select = (next: number[]) => {
    const p = new URLSearchParams(params)
    if (next.length === 0) p.delete('accounts')
    else p.set('accounts', next.join(','))
    setParams(p)
  }
  return all.length <= CHIPS_MAX ? (
    <Chips all={accounts.data} ids={ids} select={select} />
  ) : (
    <Picker all={accounts.data} ids={ids} select={select} />
  )
}

type Props = { all: Account[]; ids: number[]; select: (ids: number[]) => void }

const chip = (active: boolean) =>
  `shrink-0 rounded-full px-3 py-1 text-xs ${active ? 'bg-ink text-page' : 'bg-surface text-ink ring-1 ring-hairline'}`

const last4 = (a: Account) => (a.ibanMasked ? <span className="ml-1 opacity-60">{a.ibanMasked.slice(-4)}</span> : null)

function Chips({ all, ids, select }: Props) {
  return (
    <div role="group" aria-label="Accounts" className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1">
      <button type="button" aria-pressed={ids.length === 0} className={chip(ids.length === 0)} onClick={() => select([])}>
        All accounts
      </button>
      {all.map((a) => (
        <button
          key={a.id}
          type="button"
          aria-pressed={ids.includes(a.id)}
          className={chip(ids.includes(a.id))}
          onClick={() => select(tapChip(ids, a.id, all))}
        >
          {a.name}
          {last4(a)}
        </button>
      ))}
    </div>
  )
}

const HINTS: Partial<Record<AccountKind, string>> = {
  SAVINGS: 'Mostly transfers in: they add little to where your money went.',
}

/** One button with the selection's summary; the sheet changes the filter as you tick, "Done" closes it. */
function Picker({ all, ids, select }: Props) {
  const dialog = useRef<HTMLDialogElement>(null)
  const label = summary(ids, all)
  return (
    <div>
      <button
        type="button"
        aria-haspopup="dialog"
        onClick={() => dialog.current?.showModal()}
        className={`${chip(ids.length > 0)} inline-flex items-center gap-1`}
      >
        {label}
        <span aria-hidden>▾</span>
      </button>
      <dialog
        ref={dialog}
        aria-labelledby="accounts-sheet-title"
        onClick={(e) => e.target === dialog.current && dialog.current?.close()}
        className="fixed inset-x-0 top-auto bottom-0 m-0 max-h-[85dvh] w-full max-w-none overflow-y-auto rounded-t-2xl bg-surface p-0 text-ink shadow-lg backdrop:bg-black/40 sm:mx-auto sm:max-w-md"
      >
        <div className="p-4">
          <div className="flex items-center justify-between gap-3">
            <h2 id="accounts-sheet-title" className="text-base font-semibold">
              Accounts
            </h2>
            <button type="button" onClick={() => select([])} disabled={ids.length === 0} className="text-sm text-bar underline disabled:no-underline disabled:opacity-40">
              All accounts
            </button>
          </div>
          {groups(all).map((g) => {
            const state = groupState(ids, g.kind, all)
            return (
              <fieldset key={g.kind} className="mt-4">
                <legend className="w-full">
                  <label className="flex items-center gap-3 text-sm font-medium text-ink-2">
                    <input
                      type="checkbox"
                      className="size-4 accent-[var(--color-bar)]"
                      checked={state === 'all'}
                      ref={(el) => {
                        if (el) el.indeterminate = state === 'some'
                      }}
                      onChange={() => select(toggleGroup(ids, g.kind, all))}
                    />
                    {g.label} ({g.accounts.length})
                  </label>
                </legend>
                {HINTS[g.kind] && <p className="mt-1 ml-7 text-xs text-muted">{HINTS[g.kind]}</p>}
                <ul className="mt-1 divide-y divide-hairline">
                  {g.accounts.map((a) => {
                    const account = all.find((x) => x.id === a.id)!
                    return (
                      <li key={a.id} className="flex items-center justify-between gap-3 py-2">
                        <label className="flex min-w-0 items-center gap-3 text-sm">
                          <input
                            type="checkbox"
                            className="size-4 accent-[var(--color-bar)]"
                            checked={isSelected(ids, a.id)}
                            onChange={() => select(toggleBox(ids, a.id, all))}
                          />
                          <span className="truncate">
                            {a.name}
                            {last4(account)}
                          </span>
                        </label>
                        <button
                          type="button"
                          onClick={() => select(only(a.id, all))}
                          aria-label={`Only ${a.name}`}
                          className="shrink-0 rounded-full px-2 py-0.5 text-xs text-bar ring-1 ring-hairline"
                        >
                          Only
                        </button>
                      </li>
                    )
                  })}
                </ul>
              </fieldset>
            )
          })}
          <button
            type="button"
            onClick={() => dialog.current?.close()}
            className="mt-4 w-full rounded-lg bg-bar px-4 py-2 text-sm font-medium text-white"
          >
            Done
          </button>
        </div>
      </dialog>
    </div>
  )
}
