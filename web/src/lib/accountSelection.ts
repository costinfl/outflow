/**
 * The accounts filter's rules, without React (unit-tested in test/accountSelection.test.mjs). A selection is a sorted
 * list of account ids; the empty list means every account, and selecting every account is the same as no filter.
 */

export type AccountKind = 'CURRENT' | 'SAVINGS' | 'CARD'

export interface PickAccount {
  id: number
  name: string
  kind: AccountKind
}

/** Up to this many accounts fit as chips on a phone; more open the picker sheet. */
export const CHIPS_MAX = 4

/** Current accounts first (that is where spending happens), then cards, then savings. */
const ORDER: AccountKind[] = ['CURRENT', 'CARD', 'SAVINGS']
const LABELS: Record<AccountKind, string> = { CURRENT: 'Current accounts', CARD: 'Cards', SAVINGS: 'Savings' }

export function normalize(ids: readonly number[], all: readonly PickAccount[]): number[] {
  const known = [...new Set(ids)].filter((id) => all.some((a) => a.id === id)).sort((a, b) => a - b)
  return known.length === all.length ? [] : known
}

export function isSelected(ids: readonly number[], id: number): boolean {
  return ids.length === 0 || ids.includes(id)
}

/** The ids actually selected: every account when there is no filter. */
export function selectedIds(ids: readonly number[], all: readonly PickAccount[]): number[] {
  return ids.length === 0 ? all.map((a) => a.id) : [...ids]
}

/**
 * A chip tap: from "all accounts" it narrows to that one account; otherwise it adds or removes it. Removing the last
 * selected account goes back to all accounts.
 */
export function tapChip(ids: readonly number[], id: number, all: readonly PickAccount[]): number[] {
  if (ids.length === 0) return normalize([id], all)
  return normalize(ids.includes(id) ? ids.filter((x) => x !== id) : [...ids, id], all)
}

/** A checkbox in the sheet: every box starts checked for "all accounts"; the last checked box cannot be unchecked. */
export function toggleBox(ids: readonly number[], id: number, all: readonly PickAccount[]): number[] {
  const current = selectedIds(ids, all)
  if (current.includes(id)) {
    return current.length === 1 ? normalize(current, all) : normalize(current.filter((x) => x !== id), all)
  }
  return normalize([...current, id], all)
}

/** "Only this one". */
export function only(id: number, all: readonly PickAccount[]): number[] {
  return normalize([id], all)
}

/** A group's checkbox: all of the group when any of it is unchecked, else none of it (unless that leaves nothing). */
export function toggleGroup(ids: readonly number[], kind: AccountKind, all: readonly PickAccount[]): number[] {
  const current = selectedIds(ids, all)
  const group = all.filter((a) => a.kind === kind).map((a) => a.id)
  if (group.every((id) => current.includes(id))) {
    const rest = current.filter((id) => !group.includes(id))
    return rest.length === 0 ? normalize(current, all) : normalize(rest, all)
  }
  return normalize([...current, ...group], all)
}

export type GroupState = 'all' | 'some' | 'none'

export function groupState(ids: readonly number[], kind: AccountKind, all: readonly PickAccount[]): GroupState {
  const group = all.filter((a) => a.kind === kind)
  const on = group.filter((a) => isSelected(ids, a.id)).length
  return on === 0 ? 'none' : on === group.length ? 'all' : 'some'
}

export function groups(all: readonly PickAccount[]): { kind: AccountKind; label: string; accounts: PickAccount[] }[] {
  return ORDER.map((kind) => ({ kind, label: LABELS[kind], accounts: all.filter((a) => a.kind === kind) })).filter(
    (g) => g.accounts.length > 0,
  )
}

/** The picker button's text: "All accounts", one or two names ("Main + Card"), or "3 accounts". */
export function summary(ids: readonly number[], all: readonly PickAccount[]): string {
  if (ids.length === 0) return 'All accounts'
  const names = all.filter((a) => ids.includes(a.id)).map((a) => a.name)
  return names.length <= 2 ? names.join(' + ') : `${names.length} accounts`
}
