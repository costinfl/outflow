/**
 * More than one currency, one at a time (CP6.17): figures are never converted, so the home screen switches between the
 * currencies the selected accounts hold, most used first. Shown only when there is more than one.
 */
export function CurrencySwitch({
  currency,
  currencies,
  onChange,
}: {
  currency: string
  currencies: string[]
  onChange: (currency: string) => void
}) {
  if (currencies.length < 2) return null
  return (
    <div role="group" aria-label="Currency" className="mx-auto flex w-fit gap-1 rounded-full bg-hairline/60 p-1">
      {currencies.map((c) => (
        <button
          key={c}
          type="button"
          aria-pressed={currency === c}
          onClick={() => onChange(c)}
          className={`rounded-full px-3 py-1 text-sm ${currency === c ? 'bg-surface font-medium text-ink shadow-sm ring-1 ring-hairline' : 'text-ink-2 hover:text-ink'}`}
        >
          {c}
        </button>
      ))}
    </div>
  )
}
