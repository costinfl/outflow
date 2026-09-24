/**
 * A change vs. a reference, as an arrow plus words: never color alone. For spending, up is bad and down is good.
 */
export function Delta({ pct, against, upIsGood = false }: { pct: number; against: string; upIsGood?: boolean }) {
  if (pct === 0) return <span className="text-ink-2">Same as {against}</span>
  const up = pct > 0
  const good = up === upIsGood
  return (
    <span className="text-ink-2">
      <span aria-hidden className={good ? 'text-good' : 'text-bad'}>
        {up ? '▲' : '▼'}
      </span>{' '}
      {Math.abs(pct)}% {up ? 'more' : 'less'} than {against}
    </span>
  )
}
