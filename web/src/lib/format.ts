/** Display formatting. Amounts arrive as integer minor units and are never turned into floats for arithmetic. */

const fractionDigits = new Map<string, number>()

function digitsOf(currency: string): number {
  let d = fractionDigits.get(currency)
  if (d === undefined) {
    d = new Intl.NumberFormat(undefined, { style: 'currency', currency }).resolvedOptions().maximumFractionDigits ?? 2
    fractionDigits.set(currency, d)
  }
  return d
}

/** Minor units → exact decimal string, e.g. -21001 → "-210.01" (for RON). */
export function minorToDecimal(minor: number, currency: string): Intl.StringNumericLiteral {
  const digits = digitsOf(currency)
  const abs = Math.abs(minor).toString().padStart(digits + 1, '0')
  const body = digits === 0 ? abs : `${abs.slice(0, -digits)}.${abs.slice(-digits)}`
  return `${minor < 0 ? '-' : ''}${body}` as Intl.StringNumericLiteral
}

/** Formatted per the browser's locale, e.g. "RON 1,300.00" or "1.300,00 RON". */
export function formatMoney(minor: number, currency: string): string {
  return new Intl.NumberFormat(undefined, { style: 'currency', currency }).format(minorToDecimal(minor, currency))
}

export function formatMonth(yearMonth: string): string {
  const [y, m] = yearMonth.split('-').map(Number)
  return new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric' }).format(new Date(y!, m! - 1, 1))
}

export function formatMonthShort(yearMonth: string): string {
  const [y, m] = yearMonth.split('-').map(Number)
  return new Intl.DateTimeFormat(undefined, { month: 'short' }).format(new Date(y!, m! - 1, 1))
}

/**
 * A typed amount → minor units, exactly (string arithmetic, no floats): "49.99", "49,99", "50" → 4999, 4999, 5000.
 * No thousands separators, at most the currency's decimals; anything else → null.
 */
export function decimalToMinor(text: string, currency: string): number | null {
  const digits = digitsOf(currency)
  const m = /^(\d{1,12})(?:[.,](\d+))?$/.exec(text.trim())
  if (!m || (m[2] ?? '').length > digits) return null
  return Number(m[1]! + (m[2] ?? '').padEnd(digits, '0'))
}

const CADENCE_WORDS: Record<string, string> = {
  DAILY: 'daily',
  WEEKLY: 'weekly',
  BIWEEKLY: 'every two weeks',
  MONTHLY: 'monthly',
  QUARTERLY: 'quarterly',
  YEARLY: 'yearly',
}

export function cadenceWord(cadence: string): string {
  return CADENCE_WORDS[cadence] ?? cadence.toLowerCase()
}

/** "2026-03-15" → "March 2026". */
export function formatSince(isoDate: string): string {
  return formatMonth(isoDate.slice(0, 7))
}

/** "2026-08-03" → "3 Aug" (locale order). */
export function formatDay(isoDate: string): string {
  const [y, m, d] = isoDate.split('-').map(Number)
  return new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short' }).format(new Date(y!, m! - 1, d!))
}
