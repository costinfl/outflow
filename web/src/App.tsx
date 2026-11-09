import { HashRouter, Link, Route, Routes } from 'react-router'
import { AnonymizePage } from './anonymize/AnonymizePage'
import { CategoryPage } from './categories/CategoryPage'
import { HomePage } from './home/HomePage'
import { RecurringPage } from './recurring/RecurringPage'
import { MorePage, Shell } from './shell/Shell'
import { SplitsPage } from './split/SplitsPage'
import { ReviewPage } from './review/ReviewPage'
import { StatusPage } from './system/StatusPage'
import { TransactionsPage } from './transactions/TransactionsPage'
import { UploadPage } from './upload/UploadPage'

// Hash routing: GitHub Pages has no server-side SPA fallback, so deep links must live after the '#'.
export function App() {
  return (
    <HashRouter>
      <Routes>
        <Route element={<Shell />}>
          <Route index element={<HomePage />} />
          <Route path="transactions" element={<TransactionsPage />} />
          <Route path="categories/:id" element={<CategoryPage />} />
          <Route path="recurring" element={<RecurringPage />} />
          <Route path="review" element={<ReviewPage />} />
          <Route path="splits" element={<SplitsPage />} />
          <Route path="upload" element={<UploadPage />} />
          <Route path="anonymize" element={<AnonymizePage />} />
          <Route path="status" element={<StatusPage />} />
          <Route path="more" element={<MorePage />} />
          <Route path="*" element={<NotFound />} />
        </Route>
      </Routes>
    </HashRouter>
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
