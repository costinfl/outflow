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
