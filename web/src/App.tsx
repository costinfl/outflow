import { HashRouter, Link, Outlet, Route, Routes } from 'react-router'
import { isDemo } from './api/client'
import { HomePage } from './home/HomePage'

// Hash routing: GitHub Pages has no server-side SPA fallback, so deep links must live after the '#'.
export function App() {
  return (
    <HashRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<HomePage />} />
          <Route path="*" element={<NotFound />} />
        </Route>
      </Routes>
    </HashRouter>
  )
}

function Layout() {
  return (
    <div className="min-h-dvh bg-slate-50 text-slate-900">
      {isDemo && (
        <div className="bg-amber-100 px-4 py-2 text-center text-xs text-amber-900">
          Demo mode: synthetic data, no server. Nothing you do here leaves your browser.
        </div>
      )}
      <main className="mx-auto max-w-md px-4 py-8">
        <Outlet />
      </main>
    </div>
  )
}

function NotFound() {
  return (
    <div className="space-y-3">
      <h1 className="text-xl font-semibold">Page not found</h1>
      <Link to="/" className="text-emerald-700 underline">
        Back to home
      </Link>
    </div>
  )
}
