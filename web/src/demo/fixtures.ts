import type { GetPath, GetResponse } from '../api/types'

/**
 * Synthetic responses for the GitHub Pages demo. Never put real statement data here.
 * Typed against the generated schema: every GET endpoint must have a fixture, and it must
 * match the API contract, or `npm run typecheck` fails.
 */
export const fixtures: { [P in GetPath]: GetResponse<P> } = {
  '/api/health': { status: 'UP', database: 'UP', schemaVersion: 'demo' },
}
