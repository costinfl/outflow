import { useEffect, useState } from 'react'
import { applyTheme, storedTheme, type Theme } from '../lib/theme'

const OPTIONS: { value: Theme; label: string }[] = [
  { value: 'light', label: 'Light' },
  { value: 'dark', label: 'Dark' },
  { value: 'system', label: 'System' },
]

/** Light, Dark, or whatever the device uses. Remembered in this browser. */
export function ThemeSwitch() {
  const [theme, setTheme] = useState<Theme>(storedTheme)
  useEffect(() => {
    applyTheme(theme)
    if (theme !== 'system') return
    // Following the device: keep the browser chrome colour in step when it changes.
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => applyTheme('system')
    media.addEventListener('change', onChange)
    return () => media.removeEventListener('change', onChange)
  }, [theme])
  return (
    <div role="group" aria-label="Theme" className="flex w-fit gap-1 rounded-full bg-hairline/60 p-1">
      {OPTIONS.map((o) => (
        <button
          key={o.value}
          type="button"
          aria-pressed={theme === o.value}
          onClick={() => setTheme(o.value)}
          className={`rounded-full px-3 py-1 text-sm ${theme === o.value ? 'bg-surface font-medium text-ink shadow-sm ring-1 ring-hairline' : 'text-ink-2 hover:text-ink'}`}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}
