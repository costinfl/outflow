import type { ReactNode } from 'react'

/** Every destination, in sidebar order. `tab`: also on the phone's bottom bar (the rest are under More). */
export type Destination = { to: string; label: string; icon: ReactNode; tab?: boolean; end?: boolean }

const icon = (path: ReactNode) => (
  <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {path}
  </svg>
)

export const DESTINATIONS: Destination[] = [
  { to: '/', label: 'Home', tab: true, end: true, icon: icon(<path d="M4 11.5 12 5l8 6.5V19a1 1 0 0 1-1 1h-4v-6h-6v6H5a1 1 0 0 1-1-1z" />) },
  {
    to: '/review',
    label: 'Review',
    tab: true,
    icon: icon(
      <>
        <rect x="4" y="4" width="16" height="16" rx="3" />
        <path d="m8.5 12 2.5 2.5 4.5-5" />
      </>,
    ),
  },
  { to: '/transactions', label: 'Transactions', icon: icon(<path d="M5 7h14M5 12h14M5 17h9" />) },
  {
    to: '/recurring',
    label: 'Recurring',
    tab: true,
    icon: icon(
      <>
        <path d="M5 9a7 7 0 0 1 12.5-3.5L19 7" />
        <path d="M19 4v3h-3" />
        <path d="M19 15a7 7 0 0 1-12.5 3.5L5 17" />
        <path d="M5 20v-3h3" />
      </>,
    ),
  },
  {
    to: '/splits',
    label: 'Splits',
    icon: icon(
      <>
        <circle cx="8" cy="9" r="3" />
        <circle cx="16.5" cy="10" r="2.5" />
        <path d="M3 19c.6-3 2.6-4.5 5-4.5s4.4 1.5 5 4.5M14.5 15c2.4 0 4 1.3 4.5 4" />
      </>,
    ),
  },
  {
    to: '/upload',
    label: 'Upload',
    tab: true,
    icon: icon(
      <>
        <path d="M12 15V5M8 9l4-4 4 4" />
        <path d="M5 15v3a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-3" />
      </>,
    ),
  },
  {
    to: '/anonymize',
    label: 'Anonymize',
    icon: icon(
      <>
        <path d="M3 12s3.5-6 9-6 9 6 9 6-3.5 6-9 6-9-6-9-6z" />
        <path d="m4 4 16 16" />
      </>,
    ),
  },
  {
    to: '/status',
    label: 'Status',
    icon: icon(<path d="M3 12h4l2.5-6 4 12 2.5-6h5" />),
  },
]

export const MORE_ICON = icon(
  <>
    <circle cx="5.5" cy="12" r="1.2" />
    <circle cx="12" cy="12" r="1.2" />
    <circle cx="18.5" cy="12" r="1.2" />
  </>,
)
