import { useState } from 'react'
import { anonymize, type AnonymizeResult } from './anonymize'

/**
 * Anonymize a bank export in the browser before sharing it (docs/anonymize.md). The file is read locally, processed
 * locally and handed back as a download: this page makes no network request with it.
 */
export function AnonymizePage() {
  const [file, setFile] = useState<File | null>(null)
  const [names, setNames] = useState('')
  const [seed, setSeed] = useState('')
  const [state, setState] = useState<{ kind: 'idle' } | { kind: 'working' } | { kind: 'done'; r: AnonymizeResult } | { kind: 'error'; message: string }>(
    { kind: 'idle' },
  )

  async function run(f: File) {
    setState({ kind: 'working' })
    try {
      const r = await anonymize(new Uint8Array(await f.arrayBuffer()), { seed: seed || undefined, names: names.split('\n') })
      setState({ kind: 'done', r })
    } catch (e) {
      setState({ kind: 'error', message: e instanceof Error ? e.message : String(e) })
    }
  }

  function download(r: AnonymizeResult) {
    const url = URL.createObjectURL(new Blob([r.bytes as BlobPart], { type: 'text/csv' }))
    const a = document.createElement('a')
    a.href = url
    a.download = (file?.name ?? 'statement.csv').replace(/(\.[^.]+)?$/, '-anonymized$1')
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }

  return (
    <div className="space-y-4">
      <header>
        <h1 className="text-2xl font-semibold text-ink">Anonymize a statement</h1>
        <p className="mt-1 text-sm text-ink-2">
          Removes IBANs, card numbers, names, CNPs, emails, phone numbers and long reference numbers. Amounts, dates
          and merchant text stay exactly as they are.
        </p>
        <p className="mt-2 rounded-lg bg-surface px-3 py-2 text-sm text-ink ring-1 ring-hairline">
          <span aria-hidden>🔒</span> Your file never leaves this browser: it is read and processed on this device, and
          nothing is uploaded or stored.
        </p>
      </header>

      <label
        className="flex cursor-pointer flex-col items-center justify-center rounded-2xl border-2 border-dashed border-hairline bg-surface px-4 py-8 text-center hover:border-bar"
        onDragOver={(e) => e.preventDefault()}
        onDrop={(e) => {
          e.preventDefault()
          const f = e.dataTransfer.files[0]
          if (f) {
            setFile(f)
            void run(f)
          }
        }}
      >
        <span className="font-medium text-ink">{file ? file.name : 'Choose a CSV export'}</span>
        <span className="mt-1 text-sm text-muted">or drop it here</span>
        <input
          type="file"
          accept=".csv,.txt,text/csv,text/plain"
          className="sr-only"
          onChange={(e) => {
            const f = e.target.files?.[0]
            if (f) {
              setFile(f)
              void run(f)
            }
          }}
        />
      </label>

      <details className="rounded-2xl bg-surface p-4 ring-1 ring-hairline">
        <summary className="cursor-pointer text-sm font-medium text-ink">Options: names and seed</summary>
        <label className="mt-3 block text-sm text-ink-2">
          People to hide, one per line (you, family, people you send money to). The account holder is found
          automatically.
          <textarea
            value={names}
            onChange={(e) => setNames(e.target.value)}
            rows={3}
            className="mt-1 block w-full rounded-lg bg-page p-2 text-ink ring-1 ring-hairline"
            placeholder={'Maria Pop\nIon Popescu'}
          />
        </label>
        <label className="mt-3 block text-sm text-ink-2">
          Private seed: reuse the same phrase for every export, so the same IBAN or name always gets the same fake.
          <input
            type="password"
            value={seed}
            onChange={(e) => setSeed(e.target.value)}
            autoComplete="off"
            className="mt-1 block w-full rounded-lg bg-page p-2 text-ink ring-1 ring-hairline"
          />
        </label>
        {file && (
          <button type="button" onClick={() => void run(file)} className="mt-3 rounded-lg bg-bar px-3 py-1.5 text-sm font-medium text-white">
            Run again with these options
          </button>
        )}
      </details>

      {state.kind === 'working' && <p className="text-sm text-muted">Anonymizing…</p>}
      {state.kind === 'error' && (
        <p role="alert" className="text-sm text-bad">
          Could not anonymize this file: {state.message}
        </p>
      )}
      {state.kind === 'done' && <Report r={state.r} onDownload={() => download(state.r)} />}
    </div>
  )
}

function Report({ r, onDownload }: { r: AnonymizeResult; onDownload: () => void }) {
  const kinds = Object.entries(r.counts)
  return (
    <section aria-label="Report" className="space-y-4">
      <div className="rounded-2xl bg-surface p-4 ring-1 ring-hairline">
        <div className="flex items-center justify-between gap-3">
          <h2 className="font-medium text-ink">Replaced</h2>
          <button type="button" onClick={onDownload} className="rounded-lg bg-bar px-3 py-1.5 text-sm font-medium text-white">
            Download
          </button>
        </div>
        {kinds.length === 0 ? (
          <p className="mt-2 text-sm text-ink-2">Nothing found to replace.</p>
        ) : (
          <ul className="mt-2 space-y-2 text-sm">
            {kinds.map(([kind, n]) => (
              <li key={kind}>
                <span className="text-ink">
                  {kind} <span className="text-muted">× {n}</span>
                </span>
                <ul className="mt-0.5 font-mono text-xs break-all text-ink-2">
                  {(r.examples[kind] ?? []).map((e) => (
                    <li key={e}>{e}</li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        )}
        <p className="mt-3 text-xs text-muted">
          {r.encoding} · {r.namesLookedFor} {r.namesLookedFor === 1 ? 'name' : 'names'} looked for
          {r.seedWasRandom && ' · random seed: fakes will differ next time'}
        </p>
      </div>

      {(r.leftovers.length > 0 || r.transferLines.length > 0) && (
        <div className="rounded-2xl bg-surface p-4 ring-1 ring-hairline">
          <h2 className="font-medium text-ink">Review before sharing</h2>
          <ul className="mt-2 list-disc space-y-1 pl-5 text-sm text-ink-2">
            {r.leftovers.map((l) => (
              <li key={l}>{l}</li>
            ))}
          </ul>
          {r.transferLines.length > 0 && (
            <>
              <p className="mt-2 text-sm text-ink-2">
                These lines look like transfers. Check them for names of people; add any to the names list and run again.
              </p>
              <pre className="mt-2 max-h-48 overflow-auto rounded-lg bg-page p-2 text-xs text-ink">{r.transferLines.join('\n')}</pre>
            </>
          )}
        </div>
      )}

      <div className="rounded-2xl bg-surface p-4 ring-1 ring-hairline">
        <h2 className="font-medium text-ink">Result (first 30 lines)</h2>
        <pre className="mt-2 max-h-96 overflow-auto rounded-lg bg-page p-2 text-xs text-ink">
          {r.text.split(/\r\n|\r|\n/).slice(0, 30).join('\n')}
        </pre>
        <p className="mt-2 text-xs text-muted">
          Read the whole file before sharing it: no tool finds every personal detail in free text.
        </p>
      </div>
    </section>
  )
}
