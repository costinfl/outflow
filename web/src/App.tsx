import { HashRouter, Link, Outlet, Route, Routes } from 'react-router'
import { isDemo } from './api/client'
import { CategoryPage } from './categories/CategoryPage'
import { HomePage } from './home/HomePage'
import { StatusPage } from './system/StatusPage'
import { TransactionsPage } from './transactions/TransactionsPage'

// Hash routing: GitHub Pages has no server-side SPA fallback, so deep links must live after the '#'.
export function App() {
  return (
    <HashRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route index element={<HomePage />} />
          <Route path="transactions" element={<TransactionsPage />} />
          <Route path="categories/:id" element={<CategoryPage />} />
          <Route path="status" element={<StatusPage />} />
          <Route path="*" element={<NotFound />} />
        </Route>
      </Routes>
    </HashRouter>
  )
}

function Layout() {
  return (
    <div className="min-h-dvh bg-page text-ink">
      {isDemo && (
        <div className="bg-banner px-4 py-2 text-center text-xs text-banner-ink">
          Demo mode: synthetic data, no server. Nothing you do here leaves your browser.
        </div>
      )}
      <header className="mx-auto flex max-w-md items-center justify-between px-4 pt-4">
        <Link to="/" className="text-lg font-semibold tracking-tight text-ink">
          Outflow
        </Link>
        <Link to="/status" className="text-xs text-muted hover:underline">
          Status
        </Link>
      </header>
      <main className="mx-auto max-w-md px-4 pt-3 pb-10">
        <Outlet />
      </main>
    </div>
  )
}

function NotFound() {
  return (
    <div className="space-y-3">
      <h1 className="text-xl font-semibold">Page not found</h1>
      <Link to="/" className="text-bar underline">
        Back to home
      </Link>
    </div>
  )
}
