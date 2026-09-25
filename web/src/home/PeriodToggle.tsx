/** One month, or the last 3 months averaged per month (DESIGN: "a 'last 3 months' toggle for smoothing"). */
export function PeriodToggle({ months, onChange }: { months: number; onChange: (months: 1 | 3) => void }) {
  const option = (value: 1 | 3, label: string) => (
    <button
      type="button"
      aria-pressed={months === value}
      onClick={() => onChange(value)}
      className={`rounded-full px-3 py-1 text-sm ${months === value ? 'bg-surface font-medium text-ink shadow-sm ring-1 ring-hairline' : 'text-ink-2 hover:text-ink'}`}
    >
      {label}
    </button>
  )
  return (
    <div role="group" aria-label="Period" className="mx-auto flex w-fit gap-1 rounded-full bg-hairline/60 p-1">
      {option(1, 'This month')}
      {option(3, 'Last 3 months')}
    </div>
  )
}
