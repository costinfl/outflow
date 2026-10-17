/**
 * The split preview (CP6.21): the same arithmetic as the backend's SplitCalculator, so the form shows what each
 * person will owe before saving. Each person's part is rounded down to the minor unit; the odd cents are yours.
 */
export type SplitMode = 'EQUAL' | 'PERCENT' | 'FIXED' | 'SHARES'

/** One person as computed: percent / fixedMinor / shares carry the mode's value for the request body. */
export type SplitPart = { name: string; percent?: number; fixedMinor?: number; shares?: number; amountMinor: number }

export type SplitResult = { ok: true; people: SplitPart[]; mineMinor: number } | { ok: false; error: string }

/** A person as typed in the form: the value is a percentage, an amount (minor units) or a weight, by mode. */
export type SplitDraft = { name: string; percentHundredths?: number | null; fixedMinor?: number | null; shares?: number | null }

/** "33.33" or "33,33" → 3333 hundredths of a percent; anything else (or more than 2 decimals) → null. */
export function parsePercent(text: string): number | null {
  const s = text.trim().replace(',', '.')
  if (!/^\d{1,3}(\.\d{1,2})?$/.test(s)) return null
  const [whole, frac = ''] = s.split('.')
  return Number(whole) * 100 + Number((frac + '00').slice(0, 2))
}

/** "2" → 2; whole numbers from 0 to 1000 only. */
export function parseShares(text: string): number | null {
  const s = text.trim()
  return /^\d{1,4}$/.test(s) && Number(s) <= 1000 ? Number(s) : null
}

export function allocate(totalMinor: number, mode: SplitMode, people: SplitDraft[], myShares = 1): SplitResult {
  if (people.length === 0) return { ok: false, error: 'Add at least one person to split with' }
  const seen = new Set<string>()
  const names: string[] = []
  for (const p of people) {
    const name = p.name.trim().replace(/\s+/g, ' ')
    if (!name || name.length > 60) return { ok: false, error: 'Each person needs a name' }
    if (seen.has(name.toLowerCase())) return { ok: false, error: `${name} is listed twice` }
    seen.add(name.toLowerCase())
    names.push(name)
  }
  const parts: SplitPart[] = []
  if (mode === 'EQUAL') {
    const each = Math.floor(totalMinor / (people.length + 1))
    names.forEach((name) => parts.push({ name, amountMinor: each }))
  } else if (mode === 'PERCENT') {
    let sum = 0
    for (const [i, p] of people.entries()) {
      const h = p.percentHundredths
      if (h == null || h <= 0 || h > 10000) return { ok: false, error: `${names[i]}: a percentage above 0, at most 100` }
      sum += h
      parts.push({ name: names[i]!, percent: h / 100, amountMinor: Math.floor((totalMinor * h) / 10000) })
    }
    if (sum > 10000) return { ok: false, error: `The percentages add up to ${sum / 100}%, more than 100%` }
  } else if (mode === 'FIXED') {
    let sum = 0
    for (const [i, p] of people.entries()) {
      if (p.fixedMinor == null || p.fixedMinor <= 0) return { ok: false, error: `${names[i]}: an amount above 0` }
      sum += p.fixedMinor
      parts.push({ name: names[i]!, fixedMinor: p.fixedMinor, amountMinor: p.fixedMinor })
    }
    if (sum > totalMinor) return { ok: false, error: 'The amounts add up to more than the expense' }
  } else {
    if (!Number.isInteger(myShares) || myShares < 0 || myShares > 1000) return { ok: false, error: 'Your shares: 0 to 1000' }
    let weights = myShares
    for (const [i, p] of people.entries()) {
      if (p.shares == null || p.shares <= 0 || p.shares > 1000) return { ok: false, error: `${names[i]}: shares from 1 to 1000` }
      weights += p.shares
    }
    people.forEach((p, i) =>
      parts.push({ name: names[i]!, shares: p.shares!, amountMinor: Math.floor((totalMinor * p.shares!) / weights) }),
    )
  }
  const others = parts.reduce((s, p) => s + p.amountMinor, 0)
  if (others === 0) return { ok: false, error: 'Nobody would owe anything: the parts round to zero' }
  return { ok: true, people: parts, mineMinor: totalMinor - others }
}
