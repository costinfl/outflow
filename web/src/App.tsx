import { isDemo } from './api/client'
import { HomePage } from './home/HomePage'

export function App() {
  return (
    <div className="min-h-dvh bg-slate-50 text-slate-900">
      {isDemo && (
        <div className="bg-amber-100 px-4 py-2 text-center text-xs text-amber-900">
          Demo mode: synthetic data, no server. Nothing you do here leaves your browser.
        </div>
      )}
      <main className="mx-auto max-w-md px-4 py-8">
        <HomePage />
      </main>
    </div>
  )
}
