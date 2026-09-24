import { fixtures } from './fixtures'

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

/** A fetch that answers API calls from bundled fixtures; nothing leaves the browser. */
export async function demoFetch(request: Request): Promise<Response> {
  const url = new URL(request.url)
  const path = url.pathname
  if (request.method === 'GET' && path in fixtures) {
    const fixture: unknown = fixtures[path as keyof typeof fixtures]
    return json(typeof fixture === 'function' ? (fixture as (u: URL) => unknown)(url) : fixture)
  }
  return json({ error: `Demo mode: no fixture for ${request.method} ${path}` }, 404)
}
