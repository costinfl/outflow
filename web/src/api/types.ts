import type { components, paths } from './schema.gen'

export type Schemas = components['schemas']
export type HealthResponse = Schemas['HealthResponse']

/** Paths that have a GET operation. */
export type GetPath = { [P in keyof paths]: paths[P] extends { get: object } ? P : never }[keyof paths]

/** The JSON body of a GET path's 200 response. */
export type GetResponse<P extends GetPath> = paths[P] extends {
  get: { responses: { 200: { content: { 'application/json': infer R } } } }
}
  ? R
  : never
