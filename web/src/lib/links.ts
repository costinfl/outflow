/** Drill-through targets. Every number on the home screen links to the transactions that make it up. */

export type TransactionScope = 'spend' | 'income'

export function transactionsLink(month: string, scope?: TransactionScope, extra?: { category?: number; uncategorized?: boolean }) {
  const p = new URLSearchParams({ month })
  if (scope) p.set('scope', scope)
  if (extra?.category !== undefined) p.set('category', String(extra.category))
  if (extra?.uncategorized) p.set('uncategorized', '1')
  return `/transactions?${p}`
}

export function categoryLink(categoryId: number, month: string) {
  return `/categories/${categoryId}?${new URLSearchParams({ month })}`
}
