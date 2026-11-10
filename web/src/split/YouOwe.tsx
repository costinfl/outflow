import { useState } from 'react'
import { api } from '../api/client'
import type { Category, IouCreditor, IouView } from '../api/types'
import { decimalToMinor, formatDay, formatMoney } from '../lib/format'
import { useApi } from '../lib/useApi'
import type { Settle } from './SplitsPage'

/**
 * "They paid, I owe" (CP6.23): what you owe people who paid for something shared. Nothing counts until you pay them
 * back; the payment you link then counts as what it was for (the IOU's category), not as money to a person.
 */
export function YouOwe({ version, act }: { version: number; act: Settle }) {
  const ious = useApi(`ious:${version}`, () => api.GET('/api/ious'))
  const [adding, setAdding] = useState(false)
  const people = ious.kind === 'ok' ? ious.data.people : []
  const open = people.filter((p) => p.openMinor > 0)
  return (
    <>
      <div className="pt-2">
        <h2 className="font-medium text-ink">You owe</h2>
        <p className="mt-1 text-sm text-ink-2">
          {people.length === 0
            ? 'When someone pays for something shared, note what you owe them. When you pay them back, link that payment: it then counts as what it was for.'
            : open.length === 0
              ? 'You have paid everyone back.'
              : 'Link the money you sent back: it then counts as what it was for, not as money to a person.'}
        </p>
      </div>
      {adding ? (
        <AddIou
          names={people.map((p) => p.name)}
          onCancel={() => setAdding(false)}
          onAdded={(save) =>
            void act('Added', async () => {
              const r = await save()
              if (r.response.ok) setAdding(false)
              return r
            })
          }
        />
      ) : (
        <button type="button" onClick={() => setAdding(true)} className="rounded-lg px-3 py-1.5 text-sm text-ink ring-1 ring-hairline">
          Add what you owe
        </button>
      )}
      {people.map((p) => (
        <CreditorCard key={`${p.name}|${p.currency}`} creditor={p} act={act} />
      ))}
    </>
  )
}

function CreditorCard({ creditor, act }: { creditor: IouCreditor; act: Settle }) {
  const money = (minor: number) => formatMoney(minor, creditor.currency)
  const open = creditor.ious.filter((i) => i.openMinor > 0)
  const paid = creditor.ious.filter((i) => i.openMinor === 0)
  return (
    <section aria-label={`You owe ${creditor.name}`} className="rounded-2xl bg-surface p-4 ring-1 ring-hairline">
      <div className="flex items-baseline justify-between gap-3">
        <h3 className="truncate font-medium text-ink">{creditor.name}</h3>
        <span className={`shrink-0 text-sm tabular-nums ${creditor.openMinor > 0 ? 'font-semibold text-ink' : 'text-muted'}`}>
          {creditor.openMinor > 0 ? `you owe ${money(creditor.openMinor)}` : 'all paid'}
        </span>
      </div>
      <ul className="mt-2 space-y-3">
        {open.map((i) => (
          <IouItem key={i.id} iou={i} money={money} act={act} />
        ))}
      </ul>
      {paid.length > 0 && (
        <details className="mt-3 text-sm">
          <summary className="cursor-pointer text-muted">{paid.length} paid</summary>
          <ul className="mt-2 space-y-3">
            {paid.map((i) => (
              <IouItem key={i.id} iou={i} money={money} act={act} />
            ))}
          </ul>
        </details>
      )}
    </section>
  )
}

function IouItem({ iou, money, act }: { iou: IouView; money: (m: number) => string; act: Settle }) {
  const pay = (transactionId?: number) =>
    act(transactionId ? 'Linked the payment' : 'Marked as paid outside the bank', () =>
      api.POST('/api/ious/payments', { body: { iouId: iou.id, ...(transactionId ? { transactionId } : {}) } }),
    )
  return (
    <li className="border-t border-hairline pt-2 text-sm">
      <p className="text-ink">
        {iou.description}, {formatDay(iou.incurredOn)}
      </p>
      <p className="text-xs text-ink-2">
        Your share {money(iou.amountMinor)}
        {iou.categoryName && ` · ${iou.categoryName}`}
        {iou.paidMinor > 0 && `, paid ${money(iou.paidMinor)}`}
        {iou.openMinor > 0 && (
          <>
            , <span className="font-medium text-ink">{money(iou.openMinor)} open</span>
          </>
        )}
        .
      </p>
      {iou.payments.length > 0 && (
        <ul className="mt-1 space-y-0.5 text-xs text-muted">
          {iou.payments.map((p) => (
            <li key={p.id} className="flex items-center gap-2">
              <span>
                {money(p.amountMinor)} {p.bookingDate ? `on ${formatDay(p.bookingDate)}` : 'outside the bank'}
              </span>
              <button
                type="button"
                onClick={() => void act('Undone', () => api.DELETE('/api/ious/payments/{id}', { params: { path: { id: p.id } } }))}
                className="underline"
              >
                Undo
              </button>
            </li>
          ))}
        </ul>
      )}
      {iou.openMinor > 0 && (
        <div className="mt-2 space-y-1.5">
          {iou.suggestions.map((s) => (
            <div key={s.transactionId} className="flex items-center justify-between gap-2 rounded-lg bg-page px-2 py-1.5">
              <span className="min-w-0 text-xs text-ink-2">
                <span className="block truncate text-ink">{s.name}</span>
                {money(s.amountMinor)} on {formatDay(s.bookingDate)}
                {s.namesPerson && s.sameAmount ? ' · names them, same amount' : s.namesPerson ? ` · names ${iou.person}` : ' · same amount'}
                {s.availableMinor < s.amountMinor && ` · ${money(s.availableMinor)} still unlinked`}
              </span>
              <button type="button" onClick={() => void pay(s.transactionId)} className="shrink-0 rounded-lg bg-bar px-2.5 py-1 text-xs font-medium text-white">
                Link
              </button>
            </div>
          ))}
          {iou.suggestions.length === 0 && <p className="text-xs text-muted">No payment to {iou.person} or of this amount yet.</p>}
          <div className="flex items-center gap-3">
            <button type="button" onClick={() => void pay()} className="text-xs text-bar underline">
              Paid outside the bank ({money(iou.openMinor)})
            </button>
            {iou.payments.length === 0 && (
              <button
                type="button"
                onClick={() => void act('Forgotten', () => api.DELETE('/api/ious/{id}', { params: { path: { id: iou.id } } }))}
                className="text-xs text-muted underline"
              >
                Forget it
              </button>
            )}
          </div>
        </div>
      )}
    </li>
  )
}

type Save = () => Promise<{ response: Response }>

function AddIou({ names, onCancel, onAdded }: { names: string[]; onCancel: () => void; onAdded: (save: Save) => void }) {
  const accounts = useApi('accounts', () => api.GET('/api/accounts'))
  const categories = useApi<Category[]>('categories', () => api.GET('/api/categories'))
  const known = useApi('splits:people', () => api.GET('/api/splits'))
  const currencies = [...new Set(accounts.kind === 'ok' ? accounts.data.map((a) => a.currency) : [])]
  const [person, setPerson] = useState('')
  const [what, setWhat] = useState('')
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10))
  const [amount, setAmount] = useState('')
  const [currency, setCurrency] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const chosenCurrency = currency || currencies[0] || 'RON'
  const minor = decimalToMinor(amount, chosenCurrency)
  const ready = person.trim() !== '' && what.trim() !== '' && date !== '' && minor != null && minor > 0
  const suggestions = [...new Set([...names, ...(known.kind === 'ok' ? known.data.people : [])])]
  const spend = categories.kind === 'ok' ? categories.data.filter((c) => c.kind === 'SPEND') : []
  const field = 'mt-1 block w-full rounded-lg bg-page px-2 py-1.5 text-sm text-ink ring-1 ring-hairline'
  return (
    <section aria-label="Add what you owe" className="space-y-2 rounded-2xl bg-surface p-4 ring-1 ring-hairline">
      <datalist id="iou-people">
        {suggestions.map((n) => (
          <option key={n} value={n} />
        ))}
      </datalist>
      <label className="block text-xs text-ink-2">
        Who paid
        <input list="iou-people" value={person} onChange={(e) => setPerson(e.target.value)} placeholder="Name" className={field} />
      </label>
      <label className="block text-xs text-ink-2">
        For what
        <input value={what} onChange={(e) => setWhat(e.target.value)} placeholder="e.g. Dinner at La Mama" className={field} />
      </label>
      <div className="flex gap-2">
        <label className="block flex-1 text-xs text-ink-2">
          When
          <input type="date" value={date} onChange={(e) => setDate(e.target.value)} className={field} />
        </label>
        <label className="block w-28 text-xs text-ink-2">
          Your share
          <input inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="0.00" className={`${field} tabular-nums`} />
        </label>
        {currencies.length > 1 && (
          <label className="block text-xs text-ink-2">
            Currency
            <select value={chosenCurrency} onChange={(e) => setCurrency(e.target.value)} className={field}>
              {currencies.map((c) => (
                <option key={c}>{c}</option>
              ))}
            </select>
          </label>
        )}
      </div>
      <label className="block text-xs text-ink-2">
        What it was (the payment back will count as this)
        <select value={categoryId} onChange={(e) => setCategoryId(e.target.value)} className={field}>
          <option value="">Not sure</option>
          {spend.map((c) => (
            <option key={c.id} value={c.id}>
              {c.name}
            </option>
          ))}
        </select>
      </label>
      <div className="flex items-center gap-2">
        <button
          type="button"
          disabled={!ready}
          onClick={() =>
            onAdded(() =>
              api.POST('/api/ious', {
                body: {
                  person: person.trim(),
                  description: what.trim(),
                  incurredOn: date,
                  currency: chosenCurrency,
                  amountMinor: minor!,
                  ...(categoryId ? { categoryId: Number(categoryId) } : {}),
                },
              }),
            )
          }
          className="rounded-lg bg-bar px-3 py-1.5 text-sm font-medium text-white disabled:opacity-40"
        >
          Add
        </button>
        <button type="button" onClick={onCancel} className="px-1 py-1.5 text-sm text-muted hover:underline">
          Cancel
        </button>
      </div>
    </section>
  )
}
