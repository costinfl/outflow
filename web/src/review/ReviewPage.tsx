import { useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import { api, isDemo } from '../api/client'
import type { Category, Inbox, ReviewCard } from '../api/types'
import { cadenceWord, decimalToMinor, formatDay, formatMoney, formatSince, minorToDecimal } from '../lib/format'
import { useApi } from '../lib/useApi'

type Answer = (label: string, call: () => Promise<{ response: Response }>) => Promise<void>

/**
 * The review inbox (DESIGN: Review inbox): every decision the system needs, one card per merchant, most money first.
 * Every card is skippable (it returns after the next upload); swipe right accepts, left rejects or skips.
 */
export function ReviewPage() {
  const [version, setVersion] = useState(0)
  const [status, setStatus] = useState<string | null>(null)
  const inbox = useApi<Inbox>(`review:${version}`, () => api.GET('/api/review'))
  const categories = useApi<Category[]>('categories', () => api.GET('/api/categories'))

  const answer: Answer = async (label, call) => {
    setStatus(`${label}…`)
    const { response } = await call()
    if (response.ok) {
      setStatus(`${label}: done.`)
      setVersion((v) => v + 1)
    } else {
      setStatus(isDemo ? 'Answering needs the real app: the demo has no server.' : `${label} failed (HTTP ${response.status}).`)
    }
  }
  const skip = (card: ReviewCard) => answer('Skipped', () => api.POST('/api/review/skip', { body: { key: card.key } }))

  if (inbox.kind === 'loading') return <p className="py-12 text-center text-muted">Loading…</p>
  if (inbox.kind === 'error')
    return (
      <p role="alert" className="py-12 text-center text-bad">
        Could not load the review inbox ({inbox.message}).
      </p>
    )
  const { cards, possible } = inbox.data
  const categoryList = categories.kind === 'ok' ? categories.data : []

  const render = (card: ReviewCard) =>
    card.kind === 'SUBSCRIPTION' ? (
      <SubscriptionCard key={card.key} card={card} answer={answer} skip={() => void skip(card)} />
    ) : (
      <MerchantCard key={card.key} card={card} categories={categoryList} answer={answer} skip={() => void skip(card)} />
    )

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-xl font-semibold text-ink">Review</h1>
        <p className="mt-1 text-sm text-ink-2">
          {cards.length === 0
            ? 'Nothing needs your attention.'
            : `${cards.length} ${cards.length === 1 ? 'question' : 'questions'}, most money first. A few answers fix most of the picture.`}
        </p>
      </div>
      <p aria-live="polite" className="min-h-5 text-sm text-ink-2">
        {status}
      </p>
      {cards.length > 0 && <ul className="space-y-3">{cards.map(render)}</ul>}
      {possible.length > 0 && (
        <details className="rounded-2xl bg-surface p-4 ring-1 ring-hairline">
          <summary className="cursor-pointer text-sm font-medium text-ink-2">
            Possible recurring payments ({possible.length})
          </summary>
          <p className="mt-2 text-xs text-muted">Weaker patterns: fewer charges or less regular. Confirm the ones you recognise.</p>
          <ul className="mt-3 space-y-3">{possible.map(render)}</ul>
        </details>
      )}
      <Link to="/" className="inline-block text-sm text-bar underline">
        Back to the overview
      </Link>
    </div>
  )
}

/** A card that follows the finger: past 80 px right accepts, left rejects; buttons do the same without swiping. */
function Swipeable({ onRight, onLeft, children }: { onRight?: () => void; onLeft: () => void; children: ReactNode }) {
  const start = useRef<number | null>(null)
  const [dx, setDx] = useState(0)
  const end = () => {
    if (dx > 80 && onRight) onRight()
    else if (dx < -80) onLeft()
    start.current = null
    setDx(0)
  }
  return (
    <li
      onPointerDown={(e) => {
        if (e.pointerType !== 'mouse' && !(e.target as HTMLElement).closest('button, input, select, label')) start.current = e.clientX
      }}
      onPointerMove={(e) => start.current !== null && setDx(e.clientX - start.current)}
      onPointerUp={end}
      onPointerCancel={() => {
        start.current = null
        setDx(0)
      }}
      style={{ transform: dx ? `translateX(${dx}px)` : undefined, touchAction: 'pan-y' }}
      className="rounded-2xl bg-surface p-4 shadow-sm ring-1 ring-hairline transition-transform"
    >
      {children}
    </li>
  )
}

const primary = 'rounded-lg bg-bar px-3 py-1.5 text-sm font-medium text-white disabled:opacity-40'
const secondary = 'rounded-lg px-3 py-1.5 text-sm text-ink ring-1 ring-hairline'
const quiet = 'ml-auto px-1 py-1.5 text-sm text-muted hover:underline'

function SubscriptionCard({ card, answer, skip }: { card: ReviewCard; answer: Answer; skip: () => void }) {
  const [editing, setEditing] = useState(false)
  const id = card.subscriptionId!
  const amount = formatMoney(card.expectedAmountMinor!, card.currency)
  const confirm = (body: { name?: string; cadence?: 'MONTHLY' | 'YEARLY'; expectedAmountMinor?: number } = {}) =>
    void answer(`${body.name ?? card.name} confirmed`, () =>
      api.POST('/api/subscriptions/{id}/confirm', { params: { path: { id } }, body }),
    )
  const reject = () =>
    void answer(`${card.name} is not recurring`, () => api.POST('/api/subscriptions/{id}/reject', { params: { path: { id } } }))

  return (
    <Swipeable onRight={() => confirm()} onLeft={reject}>
      <p className="text-xs font-medium tracking-wide text-muted uppercase">Subscription?</p>
      <p className="mt-1 text-ink">
        <span className="font-medium">{card.name}</span>, {card.amountKind === 'VARIABLE' ? 'about ' : ''}
        {amount} {cadenceWord(card.cadence!)} since {formatSince(card.since!)}. Is this a subscription?
      </p>
      <p className="mt-1 text-xs text-muted">
        {card.occurrences} charges, {formatMoney(card.affectedMinor, card.currency)} so far
        {card.nextExpectedDate ? ` · next around ${formatDay(card.nextExpectedDate)}` : ''}
      </p>
      {editing ? (
        <EditForm card={card} onSave={confirm} onCancel={() => setEditing(false)} />
      ) : (
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <button type="button" className={primary} onClick={() => confirm()}>
            Yes
          </button>
          <button type="button" className={secondary} onClick={reject}>
            Not recurring
          </button>
          <button type="button" className={secondary} onClick={() => setEditing(true)}>
            Edit
          </button>
          <button type="button" className={quiet} onClick={skip}>
            Skip
          </button>
        </div>
      )}
    </Swipeable>
  )
}

function EditForm({
  card,
  onSave,
  onCancel,
}: {
  card: ReviewCard
  onSave: (body: { name?: string; cadence?: 'MONTHLY' | 'YEARLY'; expectedAmountMinor?: number }) => void
  onCancel: () => void
}) {
  const [name, setName] = useState(card.name)
  const [amount, setAmount] = useState(minorToDecimal(card.expectedAmountMinor!, card.currency) as string)
  const [cadence, setCadence] = useState<'MONTHLY' | 'YEARLY'>(card.cadence === 'YEARLY' ? 'YEARLY' : 'MONTHLY')
  const minor = decimalToMinor(amount, card.currency)
  const valid = name.trim() !== '' && name.trim().length <= 100 && minor !== null && minor > 0
  const field = 'mt-1 block w-full rounded-lg bg-page px-2 py-1.5 text-sm text-ink ring-1 ring-hairline'
  return (
    <form
      className="mt-3 space-y-2"
      onSubmit={(e) => {
        e.preventDefault()
        if (valid) onSave({ name: name.trim(), cadence, expectedAmountMinor: minor })
      }}
    >
      <label className="block text-xs text-ink-2">
        Name
        <input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} className={field} />
      </label>
      <div className="flex gap-2">
        <label className="block flex-1 text-xs text-ink-2">
          Amount ({card.currency})
          <input
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            inputMode="decimal"
            aria-invalid={minor === null}
            className={field}
          />
        </label>
        <label className="block flex-1 text-xs text-ink-2">
          How often
          <select value={cadence} onChange={(e) => setCadence(e.target.value as 'MONTHLY' | 'YEARLY')} className={field}>
            <option value="MONTHLY">Monthly</option>
            <option value="YEARLY">Yearly</option>
          </select>
        </label>
      </div>
      {minor === null && <p className="text-xs text-bad">Type the amount like 49.99.</p>}
      <div className="flex gap-2">
        <button type="submit" className={primary} disabled={!valid}>
          Confirm
        </button>
        <button type="button" className={secondary} onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function MerchantCard({
  card,
  categories,
  answer,
  skip,
}: {
  card: ReviewCard
  categories: Category[]
  answer: Answer
  skip: () => void
}) {
  const [choice, setChoice] = useState('')
  const count = card.transactionCount ?? 0
  const apply = () => {
    const category = categories.find((c) => c.id === Number(choice))
    if (!category) return
    void answer(`${card.name} → ${category.name}`, () =>
      api.POST('/api/review/merchants/{merchantId}/category', {
        params: { path: { merchantId: card.merchantId } },
        body: { categoryId: category.id },
      }),
    )
  }
  return (
    <Swipeable onLeft={skip}>
      <p className="text-xs font-medium tracking-wide text-muted uppercase">Uncategorized</p>
      <p className="mt-1 text-ink">
        {count} {count === 1 ? 'transaction' : 'transactions'}, {formatMoney(card.affectedMinor, card.currency)} from{' '}
        <span className="font-medium">{card.name}</span>
      </p>
      <div className="mt-3 flex flex-wrap items-end gap-2">
        <label className="min-w-40 flex-1 text-xs text-ink-2">
          Category for all of them
          <select
            value={choice}
            onChange={(e) => setChoice(e.target.value)}
            aria-label={`Category for ${card.name}`}
            className="mt-1 block w-full rounded-lg bg-page px-2 py-1.5 text-sm text-ink ring-1 ring-hairline"
          >
            <option value="">Choose a category</option>
            {categories.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
        </label>
        <button type="button" className={primary} disabled={choice === ''} onClick={apply}>
          Apply
        </button>
        <button type="button" className={quiet} onClick={skip}>
          Skip
        </button>
      </div>
    </Swipeable>
  )
}
