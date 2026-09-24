import { useParams, useSearchParams } from 'react-router'
import { formatMonth } from '../lib/format'
import { Placeholder } from '../transactions/TransactionsPage'

/** Category detail (trend, top merchants): arrives in CP3.3. */
export function CategoryPage() {
  const { id } = useParams()
  const [params] = useSearchParams()
  const month = params.get('month')
  return <Placeholder title="Category" filter={`Category #${id}${month ? ` · ${formatMonth(month)}` : ''}`} month={month} />
}
