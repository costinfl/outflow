import { api } from '../api/client'
import type { AccountImport, ImportSummary } from '../api/types'

/** POST /api/imports as multipart; the typed client serializes a FormData body. */
export async function uploadFiles(files: File[], opts: { accountId?: number; parserId?: string } = {}) {
  const query = {
    ...(opts.accountId != null ? { accountId: opts.accountId } : {}),
    ...(opts.parserId ? { parserId: opts.parserId } : {}),
  }
  return api.POST('/api/imports', {
    params: { query },
    body: { files: [] as string[] },
    bodySerializer: () => {
      const form = new FormData()
      for (const f of files) form.append('files', f, f.name)
      return form
    },
  })
}

/** Several upload rounds (first try, then per-file answers) add up to one summary per account. */
export function mergeSummaries(a: ImportSummary | null, b: ImportSummary): ImportSummary {
  if (!a) return b
  const byAccount = new Map<number, AccountImport>()
  for (const x of [...a.accounts, ...b.accounts]) {
    const prev = byAccount.get(x.account.id)
    byAccount.set(
      x.account.id,
      prev
        ? {
            account: x.account,
            created: prev.created || x.created,
            newTransactions: prev.newTransactions + x.newTransactions,
            alreadyImported: prev.alreadyImported + x.alreadyImported,
            periodFrom: [prev.periodFrom, x.periodFrom].filter(Boolean).sort()[0],
            periodTo: [prev.periodTo, x.periodTo].filter(Boolean).sort().at(-1),
          }
        : x,
    )
  }
  const files = [...a.files.filter((f) => !b.files.some((g) => g.fileName === f.fileName)), ...b.files]
  return {
    files,
    accounts: [...byAccount.values()],
    newTransactions: a.newTransactions + b.newTransactions,
    alreadyImported: a.alreadyImported + b.alreadyImported,
    transfers: a.transfers + b.transfers,
  }
}
