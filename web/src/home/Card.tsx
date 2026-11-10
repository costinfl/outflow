import type { ReactNode } from 'react'

export function Card({ title, children, id }: { title: string; children: ReactNode; id?: string }) {
  return (
    <section aria-labelledby={id} className="rounded-2xl bg-surface p-4 shadow-card ring-1 ring-hairline lg:p-5">
      <h2 id={id} className="text-sm font-medium text-ink-2">
        {title}
      </h2>
      <div className="mt-3">{children}</div>
    </section>
  )
}
