import createClient from 'openapi-fetch'
import type { paths } from './schema.gen'

export const isDemo = import.meta.env.VITE_DEMO === 'true'

// The demo branch is statically removed from the normal build, so fixtures never ship with the real app.
const fetchImpl = isDemo ? (await import('../demo/demoFetch')).demoFetch : undefined

/** Typed client generated from api/openapi.json (`npm run gen:api`). */
export const api = createClient<paths>({ baseUrl: '', fetch: fetchImpl })
