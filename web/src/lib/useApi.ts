import { useEffect, useState } from 'react'

export type Loadable<T> =
  | { kind: 'loading' }
  | { kind: 'ok'; data: T }
  | { kind: 'error'; message: string }

/** Runs an openapi-fetch call whenever `key` changes; ignores responses that arrive after a newer request. */
export function useApi<T>(key: string, call: () => Promise<{ data?: T; response: Response }>): Loadable<T> {
  const [state, setState] = useState<Loadable<T>>({ kind: 'loading' })
  useEffect(() => {
    let current = true
    setState({ kind: 'loading' })
    call()
      .then(({ data, response }) => {
        if (current) setState(data !== undefined ? { kind: 'ok', data } : { kind: 'error', message: `HTTP ${response.status}` })
      })
      .catch((e: unknown) => {
        if (current) setState({ kind: 'error', message: e instanceof Error ? e.message : String(e) })
      })
    return () => {
      current = false
    }
    // `call` is recreated every render; `key` says when the request actually changes.
  }, [key])
  return state
}
