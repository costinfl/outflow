import { useRef, useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import { api, isDemo } from '../api/client'
import type { Category, Inbox, ReviewCard } from '../api/types'
import { cadenceWord, decimalToMinor, formatDay, formatMoney, formatSince, minorToDecimal } from '../lib/format'
import { useApi } from '../lib/useApi'

type Cadence = NonNullable<ReviewCard['cadence']>

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
    ) : card.kind === 'POSSIBLE_DUPLICATE' ? (
      <DuplicateCard key={card.key} card={card} answer={answer} skip={() => void skip(card)} />
    ) : card.kind === 'UPCOMING_CHARGE' ? (
      <ReminderCard key={card.key} card={card} answer={answer} skip={() => void skip(card)} />
    ) : card.kind === 'PRICE_CHANGE' || card.kind === 'MISSED_CHARGE' ? (
      <AlertCard key={card.key} card={card} answer={answer} skip={() => void skip(card)} />
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
  const confirm = (body: { name?: string; cadence?: Cadence; expectedAmountMinor?: number } = {}) =>
    void answer(`${body.name ?? card.name} confirmed`, () =>
      api.POST('/api/subscriptions/{id}/confirm', { params: { path: { id } }, body }),
    )
  const reject = () =>
    void answer(`${card.name} is not recurring`, () => api.POST('/api/subscriptions/{id}/reject', { params: { path: { id } } }))
  const income = card.direction === 'IN'
  // A salary in two parts is two cards: the day of month tells them apart.
  const day =
    card.cadence === 'MONTHLY' && card.nextExpectedDate ? ` around the ${ordinal(Number(card.nextExpectedDate.slice(8, 10)))}` : ''

  return (
    <Swipeable onRight={() => confirm()} onLeft={reject}>
      <p className="text-xs font-medium tracking-wide text-muted uppercase">{income ? 'Recurring income?' : 'Subscription?'}</p>
      <p className="mt-1 text-ink">
        <span className="font-medium">{card.name}</span>, {card.amountKind === 'VARIABLE' ? 'about ' : ''}
        {amount} {cadenceWord(card.cadence!)}
        {income ? day : ''} since {formatSince(card.since!)}.{' '}
        {income ? 'Is this regular income, like a salary?' : 'Is this a subscription?'}
      </p>
      <p className="mt-1 text-xs text-muted">
        {card.occurrences} {income ? 'payments received' : 'charges'}, {formatMoney(card.affectedMinor, card.currency)} so far
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
  onSave: (body: { name?: string; cadence?: Cadence; expectedAmountMinor?: number }) => void
  onCancel: () => void
}) {
  const [name, setName] = useState(card.name)
  const [amount, setAmount] = useState(minorToDecimal(card.expectedAmountMinor!, card.currency) as string)
  const [cadence, setCadence] = useState<Cadence>(card.cadence ?? 'MONTHLY')
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
          <select value={cadence} onChange={(e) => setCadence(e.target.value as Cadence)} className={field}>
            <option value="DAILY">Daily</option>
            <option value="WEEKLY">Weekly</option>
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

type AlertAction = 'GOT_IT' | 'END' | 'STILL_ACTIVE' | 'CANCELLED'

/** "Netflix went from 49.99 to 59.99 RON" · "Gym usually charges around the 5th: nothing this time". */
function AlertCard({ card, answer, skip }: { card: ReviewCard; answer: Answer; skip: () => void }) {
  const id = card.alertId!
  const price = card.kind === 'PRICE_CHANGE'
  const act = (action: AlertAction, label: string) =>
    void answer(`${card.name}: ${label}`, () => api.POST('/api/review/alerts/{id}', { params: { path: { id } }, body: { action } }))
  const income = card.direction === 'IN'
  const [accept, reject] = price
    ? ([['GOT_IT', 'Got it'], ['END', 'Mark ended']] as const)
    : ([['STILL_ACTIVE', 'Still active'], ['CANCELLED', income ? 'Stopped' : 'Cancelled']] as const)
  // DESIGN: "usually charges around the 5th" for monthly payments; the due date itself for the other cadences.
  const due = !card.dueDate ? '' : card.cadence === 'MONTHLY' ? `the ${ordinal(Number(card.dueDate.slice(8, 10)))}` : formatDay(card.dueDate)
  return (
    <Swipeable onRight={() => act(accept[0], accept[1].toLowerCase())} onLeft={() => act(reject[0], reject[1].toLowerCase())}>
      <p className="text-xs font-medium tracking-wide text-muted uppercase">
        {price ? (income ? 'Income changed' : 'Price change') : income ? 'Missed income' : 'Missed charge'}
      </p>
      <p className="mt-1 text-ink">
        {price ? (
          <>
            <span className="font-medium">{card.name}</span> went from {formatMoney(card.previousAmountMinor!, card.currency)} to{' '}
            {formatMoney(card.newAmountMinor!, card.currency)}.
          </>
        ) : (
          <>
            <span className="font-medium">{card.name}</span> usually {income ? 'pays you' : 'charges'} around {due} ({cadenceWord(card.cadence!)}).
            Nothing has arrived since {formatDay(card.dueDate!)}. {income ? 'Has it stopped?' : 'Cancelled?'}
          </>
        )}
      </p>
      <p className="mt-1 text-xs text-muted">
        {price
          ? `Got it keeps it with the new ${income ? 'amount' : 'price'}.`
          : `Still active skips this one; ${income ? 'Stopped' : 'Cancelled'} ends it.`}
      </p>
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <button type="button" className={primary} onClick={() => act(accept[0], accept[1].toLowerCase())}>
          {accept[1]}
        </button>
        <button type="button" className={secondary} onClick={() => act(reject[0], reject[1].toLowerCase())}>
          {reject[1]}
        </button>
        <button type="button" className={quiet} onClick={skip}>
          Skip
        </button>
      </div>
    </Swipeable>
  )
}

/** "Netflix charges 49.99 RON on Aug 15 (in 2 days)": the reminder the user asked for on the Recurring screen. */
function ReminderCard({ card, answer, skip }: { card: ReviewCard; answer: Answer; skip: () => void }) {
  const id = card.subscriptionId!
  const due = card.dueDate!
  const today = new Date()
  const days = Math.round(
    (Date.UTC(Number(due.slice(0, 4)), Number(due.slice(5, 7)) - 1, Number(due.slice(8, 10))) -
      Date.UTC(today.getFullYear(), today.getMonth(), today.getDate())) /
      86_400_000,
  )
  const when = days <= 0 ? 'today' : days === 1 ? 'tomorrow' : `in ${days} days`
  const gotIt = () =>
    void answer(`${card.name}: reminder answered`, () => api.POST('/api/review/reminders/{subscriptionId}', { params: { path: { subscriptionId: id } } }))
  return (
    <Swipeable onRight={gotIt} onLeft={skip}>
      <p className="text-xs font-medium tracking-wide text-muted uppercase">Upcoming charge</p>
      <p className="mt-1 text-ink">
        <span className="font-medium">{card.name}</span> charges {card.amountKind === 'VARIABLE' ? 'about ' : ''}
        {formatMoney(card.expectedAmountMinor!, card.currency)} on {formatDay(due)} ({when}).
      </p>
      <p className="mt-1 text-xs text-muted">The reminder you set on the Recurring screen. Got it hides it until the next charge.</p>
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <button type="button" className={primary} onClick={gotIt}>
          Got it
        </button>
        <button type="button" className={quiet} onClick={skip}>
          Skip
        </button>
      </div>
    </Swipeable>
  )
}

/** 1 → "1st", 22 → "22nd", 13 → "13th". */
function ordinal(n: number): string {
  const suffix = n % 100 >= 11 && n % 100 <= 13 ? 'th' : ({ 1: 'st', 2: 'nd', 3: 'rd' } as Record<number, string>)[n % 10] ?? 'th'
  return `${n}${suffix}`
}

/** "Same 120 RON at Emag, pending and posted": the pending one is replaced, or both stay. */
function DuplicateCard({ card, answer, skip }: { card: ReviewCard; answer: Answer; skip: () => void }) {
  const id = card.duplicateId!
  const decide = (same: boolean) =>
    void answer(same ? `${card.name}: same payment` : `${card.name}: different payments`, () =>
      api.POST('/api/review/duplicates/{id}', { params: { path: { id } }, body: { same } }),
    )
  const pending = formatMoney(Math.abs(card.pendingAmountMinor!), card.currency)
  const posted = formatMoney(Math.abs(card.postedAmountMinor!), card.currency)
  return (
    <Swipeable onRight={() => decide(true)} onLeft={() => decide(false)}>
      <p className="text-xs font-medium tracking-wide text-muted uppercase">Possible duplicate</p>
      <p className="mt-1 text-ink">
        {pending === posted ? `Same ${pending}` : `${pending} then ${posted}`} at <span className="font-medium">{card.name}</span>,
        pending on {formatDay(card.pendingDate!)} and posted on {formatDay(card.postedDate!)}. Is it one payment?
      </p>
      <p className="mt-1 text-xs text-muted">If it is, only the posted one counts.</p>
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <button type="button" className={primary} onClick={() => decide(true)}>
          Same
        </button>
        <button type="button" className={secondary} onClick={() => decide(false)}>
          Different
        </button>
        <button type="button" className={quiet} onClick={skip}>
          Skip
        </button>
      </div>
    </Swipeable>
  )
}

type Direction = 'IN' | 'OUT'

/**
 * An uncategorized merchant or person. Money sent and money received are answered separately (a person you pay rent
 * to may also pay you back, and that is not rent); a card with only one of them asks only about that one.
 */
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
  const [sent, setSent] = useState('')
  const [received, setReceived] = useState('') // a category id, or 'same': paid back, nets the sent category
  const hasSent = (card.sentCount ?? 0) > 0
  const hasReceived = (card.receivedCount ?? 0) > 0
  const both = hasSent && hasReceived
  const money = (minor: number | undefined) => formatMoney(minor ?? 0, card.currency)
  const byId = (id: string) => categories.find((c) => c.id === Number(id))
  const sentCategory = byId(sent)
  const receivedCategory = received === 'same' ? sentCategory : byId(received)
  const transfer = categories.find((c) => c.kind === 'TRANSFER')

  const post = (categoryId: number, direction?: Direction) =>
    api.POST('/api/review/merchants/{merchantId}/category', {
      params: { path: { merchantId: card.merchantId } },
      body: { categoryId, ...(direction ? { direction } : {}) },
    })
  const apply = (out: Category | undefined, into: Category | undefined) => {
    const paidBack = received === 'same' && into !== undefined && into.kind === 'SPEND'
    const parts = [out && `sent → ${out.name}`, into && (paidBack ? 'received → paying me back' : `received → ${into.name}`)]
      .filter(Boolean)
    if (parts.length === 0) return
    void answer(`${card.name}: ${parts.join(', ')}`, async () => {
      if (out && into && out.id === into.id) return post(out.id)
      if (out) {
        const r = await post(out.id, 'OUT')
        if (!into || !r.response.ok) return r
      }
      return post(into!.id, 'IN')
    })
  }

  const picker = (label: string, value: string, onChange: (v: string) => void, extra?: ReactNode) => (
    <label className="block text-xs text-ink-2">
      {label}
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-label={`${label} (${card.name})`}
        className="mt-1 block w-full rounded-lg bg-page px-2 py-1.5 text-sm text-ink ring-1 ring-hairline"
      >
        <option value="">Choose a category</option>
        {extra}
        {categories.map((c) => (
          <option key={c.id} value={c.id}>
            {c.name}
          </option>
        ))}
      </select>
    </label>
  )

  return (
    <Swipeable onLeft={skip}>
      <p className="text-xs font-medium tracking-wide text-muted uppercase">{both ? 'Money both ways' : 'Uncategorized'}</p>
      <p className="mt-1 text-ink">
        <span className="font-medium">{card.name}</span>
        {card.firstDate && <span className="text-ink-2"> · since {formatSince(card.firstDate)}</span>}
      </p>
      <dl className="mt-2 grid grid-cols-[auto_1fr_auto] gap-x-3 gap-y-0.5 text-sm">
        {hasSent && (
          <>
            <dt className="text-ink-2">Sent</dt>
            <dd className="text-ink-2">
              {card.sentCount} {card.sentCount === 1 ? 'payment' : 'payments'}
            </dd>
            <dd className="text-right text-ink tabular-nums">{money(card.sentMinor)}</dd>
          </>
        )}
        {hasReceived && (
          <>
            <dt className="text-ink-2">Received</dt>
            <dd className="text-ink-2">
              {card.receivedCount} {card.receivedCount === 1 ? 'payment' : 'payments'}
            </dd>
            <dd className="text-right text-ink tabular-nums">{money(card.receivedMinor)}</dd>
          </>
        )}
      </dl>
      <div className="mt-3 grid gap-2 sm:grid-cols-2">
        {hasSent && picker('Money sent is', sent, setSent)}
        {hasReceived &&
          picker(
            'Money received is',
            received,
            setReceived,
            sentCategory?.kind === 'SPEND' && <option value="same">Paying me back: less {sentCategory.name}</option>,
          )}
      </div>
      {both && transfer && (
        <p className="mt-2 text-xs text-ink-2">
          Moving your own money, or the household&apos;s?{' '}
          <button type="button" className="text-bar underline" onClick={() => apply(transfer, transfer)}>
            Both ways are a transfer
          </button>{' '}
          (neither spending nor income).
        </p>
      )}
      <div className="mt-3 flex flex-wrap gap-2">
        <button
          type="button"
          className={primary}
          disabled={!(hasSent && sentCategory) && !(hasReceived && receivedCategory)}
          onClick={() => apply(hasSent ? sentCategory : undefined, hasReceived ? receivedCategory : undefined)}
        >
          Apply
        </button>
        <button type="button" className={quiet} onClick={skip}>
          Skip
        </button>
      </div>
    </Swipeable>
  )
}
