import type { components, paths } from './schema.gen'

export type Schemas = components['schemas']
export type HealthResponse = Schemas['HealthResponse']
export type MonthSummary = Schemas['MonthSummary']
export type CategorySpend = Schemas['CategorySpend']

/** Paths that have a GET operation. */
export type GetPath = { [P in keyof paths]: paths[P] extends { get: object } ? P : never }[keyof paths]

/** The JSON body of a GET path's 200 response. */
export type GetResponse<P extends GetPath> = paths[P] extends {
  get: { responses: { 200: { content: { 'application/json': infer R } } } }
}
  ? R
  : never
export type TransactionView = Schemas['TransactionView']
export type TransactionList = Schemas['TransactionList']
export type CategoryDetail = Schemas['CategoryDetail']
export type Category = Schemas['Category']
export type ImportSummary = Schemas['ImportSummary']
export type FileOutcome = Schemas['FileOutcome']
export type AccountImport = Schemas['AccountImport']
export type Account = Schemas['Account']
export type Inbox = Schemas['Inbox']
export type ReviewCard = Schemas['ReviewCard']
export type Subscription = Schemas['Subscription']
export type RecurringOverview = Schemas['RecurringOverview']
