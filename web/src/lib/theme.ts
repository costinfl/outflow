/**
 * Light / Dark / System (CP7.1). The choice is a per-browser convenience kept in localStorage; "system" stores nothing
 * and follows the device. The colours themselves are CSS tokens in index.css, switched by `data-theme` on <html>;
 * index.html applies a stored choice before first paint, so there is no flash.
 */
export type Theme = 'light' | 'dark' | 'system'

export const THEME_KEY = 'outflow-theme'
/** The page colour of each scheme, for the browser's own chrome (<meta name="theme-color">). */
export const PAGE_COLOR = { light: '#f7f7f5', dark: '#0e0e0d' } as const

export function parseTheme(stored: string | null | undefined): Theme {
  return stored === 'light' || stored === 'dark' ? stored : 'system'
}

/** What is actually shown. */
export function resolveTheme(theme: Theme, systemDark: boolean): 'light' | 'dark' {
  return theme === 'system' ? (systemDark ? 'dark' : 'light') : theme
}

export function storedTheme(): Theme {
  try {
    return parseTheme(localStorage.getItem(THEME_KEY))
  } catch {
    return 'system' // private windows and blocked storage: follow the device
  }
}

export function applyTheme(theme: Theme): void {
  const root = document.documentElement
  if (theme === 'system') delete root.dataset.theme
  else root.dataset.theme = theme
  const dark = resolveTheme(theme, window.matchMedia('(prefers-color-scheme: dark)').matches)
  document.querySelectorAll('meta[name="theme-color"]').forEach((m) => m.setAttribute('content', PAGE_COLOR[dark ? 'dark' : 'light']))
  try {
    if (theme === 'system') localStorage.removeItem(THEME_KEY)
    else localStorage.setItem(THEME_KEY, theme)
  } catch {
    // The choice still holds for this page.
  }
}
