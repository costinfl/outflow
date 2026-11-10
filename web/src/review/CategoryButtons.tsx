import { useEffect, useState } from 'react'

export type Choice = { key: string; label: string; onPick: () => void; selected?: boolean; accent?: boolean }

/**
 * One-tap answers (CP7.2): a button per category, the most used first; the first `limit` show and "More" shows the
 * rest. With `hotkeys` (the card on screen in the one-at-a-time review), keys 1-9 pick the numbered buttons; the
 * numbers only show on devices with a mouse or trackpad.
 */
export function CategoryButtons({ choices, hotkeys = false, limit = 8, label }: { choices: Choice[]; hotkeys?: boolean; limit?: number; label: string }) {
  const [all, setAll] = useState(false)
  const shown = all || choices.length <= limit + 1 ? choices : choices.slice(0, limit)
  useEffect(() => {
    if (!hotkeys) return
    const onKey = (e: KeyboardEvent) => {
      if (e.metaKey || e.ctrlKey || e.altKey || isTyping(e.target)) return
      const n = Number(e.key)
      if (Number.isInteger(n) && n >= 1 && n <= 9 && shown[n - 1]) {
        e.preventDefault()
        shown[n - 1]!.onPick()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [hotkeys, shown])
  return (
    <div role="group" aria-label={label} className="flex flex-wrap gap-2">
      {shown.map((c, i) => (
        <button
          key={c.key}
          type="button"
          aria-pressed={c.selected}
          onClick={c.onPick}
          className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1.5 text-sm ring-1 ${
            c.selected
              ? 'bg-bar font-medium text-white ring-bar'
              : c.accent
                ? 'bg-bar-track font-medium text-accent-ink ring-bar/40 hover:ring-bar'
                : 'bg-surface text-ink ring-hairline hover:bg-page hover:ring-ink-2/40'
          }`}
        >
          {hotkeys && i < 9 && (
            <kbd className="hidden rounded bg-hairline/70 px-1 font-sans text-[10px] leading-4 text-muted [@media(pointer:fine)]:inline">{i + 1}</kbd>
          )}
          {c.label}
        </button>
      ))}
      {shown.length < choices.length && (
        <button type="button" onClick={() => setAll(true)} className="rounded-full px-3 py-1.5 text-sm text-muted border border-dashed border-hairline hover:text-ink">
          More…
        </button>
      )}
    </div>
  )
}

/** Keys typed into a field are text, not shortcuts. */
export function isTyping(target: EventTarget | null): boolean {
  return target instanceof HTMLElement && (target.isContentEditable || ['INPUT', 'SELECT', 'TEXTAREA'].includes(target.tagName))
}
