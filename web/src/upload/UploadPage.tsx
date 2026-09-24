import { useState } from 'react'
import { Link } from 'react-router'
import { api, isDemo } from '../api/client'
import type { Account, AccountImport, FileOutcome, ImportSummary } from '../api/types'
import { useApi } from '../lib/useApi'
import { mergeSummaries, uploadFiles } from './importApi'

/**
 * First-run flow (DESIGN): drop files → accounts detected (rename) → import summary → home. Questions are asked only
 * for the files that need an answer: which account (no IBAN in the file) or which format (detection unsure).
 */
export function UploadPage() {
  const [files, setFiles] = useState<File[]>([])
  const [summary, setSummary] = useState<ImportSummary | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [accountsVersion, setAccountsVersion] = useState(0)
  const accounts = useApi<Account[]>(`accounts:${accountsVersion}`, () => api.GET('/api/accounts'))

  async function send(batch: File[], opts: { accountId?: number; parserId?: string } = {}) {
    setBusy(true)
    setError(null)
    try {
      const { data, response } = await uploadFiles(batch, opts)
      if (!data) {
        setError(isDemo ? 'Uploading needs the real app: the demo has no server.' : `Upload failed (HTTP ${response.status}).`)
        return
      }
      setSummary((s) => mergeSummaries(s, data))
      setAccountsVersion((v) => v + 1)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  function choose(list: FileList | null) {
    const picked = [...(list ?? [])].slice(0, 20)
    if (picked.length === 0) return
    setFiles(picked)
    setSummary(null)
    void send(picked)
  }

  const pending = summary?.files.filter((f) => f.status === 'NEEDS_ACCOUNT' || f.status === 'NEEDS_PARSER') ?? []
  const fileOf = (name: string) => files.find((f) => f.name === name)

  return (
    <div className="space-y-4">
      <header>
        <h1 className="text-2xl font-semibold text-ink">Upload statements</h1>
        <p className="mt-1 text-sm text-ink-2">
          Any bank, several files at once, overlapping periods are fine: transactions already imported are skipped.
        </p>
      </header>

      <label
        className="flex cursor-pointer flex-col items-center justify-center rounded-2xl border-2 border-dashed border-hairline bg-surface px-4 py-8 text-center hover:border-bar"
        onDragOver={(e) => e.preventDefault()}
        onDrop={(e) => {
          e.preventDefault()
          choose(e.dataTransfer.files)
        }}
      >
        <span className="font-medium text-ink">{files.length ? `${files.length} ${files.length === 1 ? 'file' : 'files'}` : 'Choose statement files'}</span>
        <span className="mt-1 text-sm text-muted">or drop them here (CSV exports)</span>
        <input type="file" multiple accept=".csv,.txt,text/csv" className="sr-only" onChange={(e) => choose(e.target.files)} />
      </label>

      {busy && <p className="text-sm text-muted">Importing…</p>}
      {error && (
        <p role="alert" className="text-sm text-bad">
          {error}
        </p>
      )}

      {pending.map((f) => (
        <Question
          key={f.fileName}
          outcome={f}
          accounts={accounts.kind === 'ok' ? accounts.data : []}
          busy={busy}
          onAnswer={async (opts) => {
            const file = fileOf(f.fileName)
            if (!file) return
            let accountId = opts.accountId
            if (opts.newAccount) {
              const { data } = await api.POST('/api/accounts', { body: opts.newAccount })
              if (!data) {
                setError('Could not create the account.')
                return
              }
              accountId = data.id
            }
            await send([file], { accountId, parserId: opts.parserId })
          }}
        />
      ))}

      {summary && <Summary summary={summary} onRenamed={() => setAccountsVersion((v) => v + 1)} />}
    </div>
  )
}

type Answer = { accountId?: number; parserId?: string; newAccount?: { name: string; currency: string; kind: Account['kind'] } }

function Question({ outcome, accounts, busy, onAnswer }: { outcome: FileOutcome; accounts: Account[]; busy: boolean; onAnswer: (a: Answer) => Promise<void> }) {
  const [accountId, setAccountId] = useState<number | 'new' | ''>('')
  const [name, setName] = useState('')
  const [kind, setKind] = useState<Account['kind']>('CURRENT')
  const [parserId, setParserId] = useState('')
  const needsAccount = outcome.status === 'NEEDS_ACCOUNT'
  const ready = needsAccount ? accountId !== '' && (accountId !== 'new' || name.trim() !== '') : parserId !== ''
  return (
    <section className="rounded-2xl bg-surface p-4 ring-1 ring-hairline" aria-label={`Question about ${outcome.fileName}`}>
      <h2 className="text-sm font-medium text-ink">{needsAccount ? `Which account is ${outcome.fileName}?` : `Which bank format is ${outcome.fileName}?`}</h2>
      <p className="mt-1 text-xs text-muted">{outcome.message}</p>
      {needsAccount ? (
        <div className="mt-3 space-y-2">
          <select
            aria-label={`Account for ${outcome.fileName}`}
            value={accountId}
            onChange={(e) => setAccountId(e.target.value === 'new' ? 'new' : e.target.value === '' ? '' : Number(e.target.value))}
            className="block w-full rounded-lg bg-page px-2 py-1.5 text-sm text-ink ring-1 ring-hairline"
          >
            <option value="">Choose…</option>
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
                {a.ibanMasked ? ` (${a.ibanMasked})` : ''}
              </option>
            ))}
            <option value="new">New account…</option>
          </select>
          {accountId === 'new' && (
            <div className="flex gap-2">
              <input
                aria-label="New account name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="e.g. Main"
                className="min-w-0 flex-1 rounded-lg bg-page px-2 py-1.5 text-sm text-ink ring-1 ring-hairline"
              />
              <select aria-label="Kind" value={kind} onChange={(e) => setKind(e.target.value as Account['kind'])} className="rounded-lg bg-page px-2 text-sm text-ink ring-1 ring-hairline">
                <option value="CURRENT">Current</option>
                <option value="SAVINGS">Savings</option>
                <option value="CARD">Card</option>
              </select>
            </div>
          )}
        </div>
      ) : (
        <select
          aria-label={`Format of ${outcome.fileName}`}
          value={parserId}
          onChange={(e) => setParserId(e.target.value)}
          className="mt-3 block w-full rounded-lg bg-page px-2 py-1.5 text-sm text-ink ring-1 ring-hairline"
        >
          <option value="">Choose…</option>
          {outcome.candidates.map((c) => (
            <option key={c.parserId} value={c.parserId}>
              {c.name}: {c.reason}
            </option>
          ))}
        </select>
      )}
      <button
        type="button"
        disabled={!ready || busy}
        onClick={() =>
          void onAnswer(
            needsAccount
              ? accountId === 'new'
                ? { newAccount: { name: name.trim(), currency: 'RON', kind } }
                : { accountId: Number(accountId) }
              : { parserId },
          )
        }
        className="mt-3 rounded-lg bg-bar px-3 py-1.5 text-sm font-medium text-white disabled:opacity-40"
      >
        Import this file
      </button>
    </section>
  )
}

const period = (from?: string, to?: string) => {
  if (!from || !to) return ''
  const f = new Intl.DateTimeFormat(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
  return `${f.format(new Date(from + 'T00:00'))} – ${f.format(new Date(to + 'T00:00'))}`
}

/** DESIGN step 3: plain numbers; showing what was skipped builds trust in the de-duplication. */
function Summary({ summary, onRenamed }: { summary: ImportSummary; onRenamed: () => void }) {
  const failed = summary.files.filter((f) => f.status === 'FAILED')
  const duplicates = summary.files.filter((f) => f.status === 'DUPLICATE_FILE')
  const imported = summary.accounts.length > 0
  return (
    <section aria-label="Import summary" className="space-y-3">
      {imported && (
        <p className="text-lg text-ink">
          <span className="font-semibold">{summary.newTransactions}</span> new {summary.newTransactions === 1 ? 'transaction' : 'transactions'}.{' '}
          {summary.alreadyImported > 0 && (
            <span className="text-ink-2">
              {summary.alreadyImported} already imported, skipped.
            </span>
          )}
        </p>
      )}
      {summary.accounts.map((a) => (
        <AccountCard key={a.account.id} a={a} onRenamed={onRenamed} />
      ))}
      {duplicates.length > 0 && (
        <p className="text-sm text-ink-2">
          {duplicates.map((d) => d.fileName).join(', ')}: {duplicates.length === 1 ? 'this exact file was' : 'these exact files were'} imported
          before, nothing changed.
        </p>
      )}
      {failed.map((f) => (
        <p key={f.fileName} role="alert" className="rounded-lg bg-surface px-3 py-2 text-sm ring-1 ring-hairline">
          <span className="font-medium text-bad">{f.fileName}</span> <span className="text-ink-2">was not imported: {f.message}</span>
        </p>
      ))}
      {imported && (
        <Link to="/" className="block rounded-lg bg-bar px-4 py-2.5 text-center font-medium text-white">
          See where your money went
        </Link>
      )}
    </section>
  )
}

function AccountCard({ a, onRenamed }: { a: AccountImport; onRenamed: () => void }) {
  const [name, setName] = useState(a.account.name)
  const [saved, setSaved] = useState(a.account.name)
  const [status, setStatus] = useState<string | null>(null)
  async function rename() {
    const { data } = await api.PATCH('/api/accounts/{id}', { params: { path: { id: a.account.id } }, body: { name: name.trim() } })
    if (data) {
      setSaved(data.name)
      setStatus('Saved')
      onRenamed()
    } else setStatus('Could not rename')
  }
  return (
    <div className="rounded-2xl bg-surface p-4 ring-1 ring-hairline">
      <div className="flex items-center gap-2">
        <label className="min-w-0 flex-1">
          <span className="sr-only">Account name</span>
          <input
            value={name}
            onChange={(e) => {
              setName(e.target.value)
              setStatus(null)
            }}
            className="w-full rounded-lg bg-page px-2 py-1 font-medium text-ink ring-1 ring-hairline"
          />
        </label>
        {name.trim() !== saved && name.trim() !== '' && (
          <button type="button" onClick={() => void rename()} className="rounded-lg bg-bar px-3 py-1 text-sm font-medium text-white">
            Rename
          </button>
        )}
        {status && <span className="text-xs text-muted">{status}</span>}
      </div>
      <p className="mt-2 text-sm text-ink-2">
        {a.account.ibanMasked && <span className="font-mono">{a.account.ibanMasked} · </span>}
        {a.created && <span className="font-medium text-good">new account · </span>}
        {a.newTransactions} new, {a.alreadyImported} already imported
      </p>
      <p className="text-xs text-muted">{period(a.periodFrom, a.periodTo)}</p>
    </div>
  )
}
