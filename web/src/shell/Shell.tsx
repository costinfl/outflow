import { Link, NavLink, Outlet, useLocation } from 'react-router'
import { api, isDemo } from '../api/client'
import type { Inbox } from '../api/types'
import { useApi } from '../lib/useApi'
import { DESTINATIONS, MORE_ICON } from './nav'
import { ThemeSwitch } from './ThemeSwitch'

/**
 * The app frame (CP7.1). Phones: a bottom tab bar (Home, Review, Recurring, Upload, More). Desktop: a sidebar with
 * every destination and the theme switch, and a wider page. The Review badge is the number of open questions.
 */
export function Shell() {
  const { pathname } = useLocation()
  // Refreshed on every navigation, so answering questions lowers the badge.
  const inbox = useApi<Inbox>(`shell-review:${pathname}`, () => api.GET('/api/review'))
  const questions = inbox.kind === 'ok' ? inbox.data.count : 0
  const wide = pathname === '/'
  const badge = (to: string) =>
    to === '/review' && questions > 0 ? (
      <span className="rounded-full bg-bar px-1.5 text-[11px] leading-4 font-medium text-white tabular-nums" aria-label={`${questions} open questions`}>
        {questions > 99 ? '99+' : questions}
      </span>
    ) : null

  return (
    <div className="min-h-dvh bg-page text-ink lg:flex">
      <aside className="sticky top-0 hidden h-dvh w-60 shrink-0 flex-col border-r border-hairline bg-surface px-3 py-5 lg:flex">
        <Link to="/" className="px-3 text-xl font-semibold tracking-tight text-ink">
          Outflow
        </Link>
        <nav aria-label="Main" className="mt-6 flex flex-1 flex-col gap-1">
          {DESTINATIONS.map((d) => (
            <NavLink
              key={d.to}
              to={d.to}
              end={d.end}
              className={({ isActive }) =>
                `flex items-center gap-3 rounded-xl px-3 py-2 text-sm ${isActive ? 'bg-bar-track font-medium text-accent-ink' : 'text-ink-2 hover:bg-page hover:text-ink'}`
              }
            >
              {d.icon}
              <span className="flex-1">{d.label}</span>
              {badge(d.to)}
            </NavLink>
          ))}
        </nav>
        <div className="px-1">
          <p className="mb-1.5 px-2 text-xs text-muted">Theme</p>
          <ThemeSwitch />
        </div>
      </aside>

      <div className="min-w-0 flex-1">
        {isDemo && (
          <div className="bg-banner px-4 py-2 text-center text-xs text-banner-ink">
            Demo mode: synthetic data, no server. Nothing you do here leaves your browser.
          </div>
        )}
        <header className="mx-auto flex max-w-2xl items-center px-4 pt-4 lg:hidden">
          <Link to="/" className="text-lg font-semibold tracking-tight text-ink">
            Outflow
          </Link>
        </header>
        <main className={`mx-auto w-full px-4 pt-3 pb-28 lg:px-8 lg:pt-8 lg:pb-12 ${wide ? 'max-w-2xl lg:max-w-5xl' : 'max-w-2xl'}`}>
          <Outlet />
        </main>
      </div>

      <nav
        aria-label="Main"
        className="fixed inset-x-0 bottom-0 z-10 border-t border-hairline bg-surface/95 pb-[env(safe-area-inset-bottom)] backdrop-blur lg:hidden"
      >
        <ul className="mx-auto flex max-w-2xl">
          {[...DESTINATIONS.filter((d) => d.tab), { to: '/more', label: 'More', icon: MORE_ICON, end: false }].map((d) => (
            <li key={d.to} className="flex-1">
              <NavLink
                to={d.to}
                end={d.end}
                className={({ isActive }) =>
                  `relative flex flex-col items-center gap-0.5 px-1 pt-2 pb-1.5 text-[11px] ${isActive ? 'font-medium text-accent-ink' : 'text-muted'}`
                }
              >
                <span className="relative">
                  {d.icon}
                  <span className="absolute -top-1 left-4">{badge(d.to)}</span>
                </span>
                {d.label}
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
    </div>
  )
}

/** Phones: everything that is not on the tab bar, and the theme. */
export function MorePage() {
  return (
    <div className="space-y-4">
      <h1 className="text-xl font-semibold text-ink">More</h1>
      <ul className="overflow-hidden rounded-2xl bg-surface shadow-card ring-1 ring-hairline">
        {DESTINATIONS.filter((d) => !d.tab).map((d) => (
          <li key={d.to} className="border-b border-hairline last:border-0">
            <Link to={d.to} className="flex items-center gap-3 px-4 py-3 text-ink">
              <span className="text-muted">{d.icon}</span>
              <span className="flex-1">{d.label}</span>
              <span aria-hidden className="text-muted">
                ›
              </span>
            </Link>
          </li>
        ))}
      </ul>
      <section className="rounded-2xl bg-surface p-4 shadow-card ring-1 ring-hairline">
        <h2 className="text-sm font-medium text-ink-2">Theme</h2>
        <div className="mt-3">
          <ThemeSwitch />
        </div>
        <p className="mt-2 text-xs text-muted">System follows your device. Your choice is remembered in this browser.</p>
      </section>
    </div>
  )
}
