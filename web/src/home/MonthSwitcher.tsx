import { formatMonth } from '../lib/format'

/** One month at a time: previous / next, or pick from the months that have data. */
export function MonthSwitcher({
  month,
  available,
  onChange,
}: {
  month: string
  available: readonly string[]
  onChange: (month: string) => void
}) {
  const i = available.indexOf(month)
  const prev = i > 0 ? available[i - 1] : undefined
  const next = i >= 0 && i < available.length - 1 ? available[i + 1] : undefined
  return (
    <nav aria-label="Month" className="flex items-center justify-between gap-2">
      <StepButton label="Previous month" disabled={!prev} onClick={() => prev && onChange(prev)}>
        ‹
      </StepButton>
      <label className="relative">
        <span className="sr-only">Month</span>
        <select
          value={month}
          onChange={(e) => onChange(e.target.value)}
          className="appearance-none rounded-lg bg-transparent px-2 py-1 text-center text-lg font-semibold text-ink focus-visible:outline-2 focus-visible:outline-bar"
        >
          {(available.includes(month) ? available : [month, ...available]).map((m) => (
            <option key={m} value={m}>
              {formatMonth(m)}
            </option>
          ))}
        </select>
      </label>
      <StepButton label="Next month" disabled={!next} onClick={() => next && onChange(next)}>
        ›
      </StepButton>
    </nav>
  )
}

function StepButton({
  label,
  disabled,
  onClick,
  children,
}: {
  label: string
  disabled: boolean
  onClick: () => void
  children: string
}) {
  return (
    <button
      type="button"
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className="size-11 rounded-full text-2xl leading-none text-ink-2 hover:bg-hairline disabled:opacity-30 disabled:hover:bg-transparent"
    >
      {children}
    </button>
  )
}
