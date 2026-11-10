/**
 * One question at a time (CP7.2): which card is on screen, given the inbox (refetched after every answer) and what the
 * user did this session. Pure, so it is unit-tested; the page keeps the state.
 *
 * - Cards come in the inbox's order (most money first); a card skipped here goes to the end of the line, for this
 *   session only (the list view's Skip hides it until the next upload).
 * - An answered card leaves the inbox, so the next one shows by itself.
 * - Back brings up the last card shown that is still open (skipped, or answered but not gone).
 */
export type QueueState = { skipped: string[]; history: string[]; pinned: string | null }

export const START: QueueState = { skipped: [], history: [], pinned: null }

/** The open cards in the order they come up: not skipped first, then skipped ones in the order they were skipped. */
export function order(keys: string[], s: QueueState): string[] {
  const skipped = s.skipped.filter((k) => keys.includes(k))
  return [...keys.filter((k) => !skipped.includes(k)), ...skipped]
}

export function currentKey(keys: string[], s: QueueState): string | null {
  if (s.pinned && keys.includes(s.pinned)) return s.pinned
  return order(keys, s)[0] ?? null
}

/** "Question 3 of 41": the current card's place among the open ones (1-based); 0 when nothing is open. */
export function position(keys: string[], s: QueueState): number {
  const key = currentKey(keys, s)
  return key === null ? 0 : order(keys, s).indexOf(key) + 1
}

export function skip(s: QueueState, key: string): QueueState {
  return { skipped: [...s.skipped.filter((k) => k !== key), key], history: [...s.history, key], pinned: null }
}

export function answered(s: QueueState, key: string): QueueState {
  return { skipped: s.skipped.filter((k) => k !== key), history: [...s.history, key], pinned: null }
}

export function canGoBack(keys: string[], s: QueueState): boolean {
  const current = currentKey(keys, s)
  return s.history.some((k) => k !== current && keys.includes(k))
}

export function back(keys: string[], s: QueueState): QueueState {
  const current = currentKey(keys, s)
  for (let i = s.history.length - 1; i >= 0; i--) {
    const k = s.history[i]!
    if (k !== current && keys.includes(k)) {
      return { skipped: s.skipped.filter((x) => x !== k), history: s.history.slice(0, i), pinned: k }
    }
  }
  return s
}

type Kind = 'SPEND' | 'INCOME' | 'TRANSFER'

/**
 * The one-tap category buttons for money sent (OUT) or received (IN): the most used first (the inbox's order), with
 * the kinds that fit the direction ahead of the others (spending and transfers for money sent, income and transfers
 * for money received).
 */
export function orderCategories<C extends { id: number; kind: Kind }>(categories: C[], usage: number[], direction: 'IN' | 'OUT'): C[] {
  const rank = new Map(usage.map((id, i) => [id, i]))
  const fits = (c: C) => (direction === 'OUT' ? c.kind !== 'INCOME' : c.kind !== 'SPEND')
  return [...categories].sort(
    (a, b) =>
      Number(fits(b)) - Number(fits(a)) ||
      (rank.get(a.id) ?? Number.MAX_SAFE_INTEGER) - (rank.get(b.id) ?? Number.MAX_SAFE_INTEGER) ||
      a.id - b.id,
  )
}
