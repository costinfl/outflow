/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** "true" in the GitHub Pages build: API calls are served from bundled synthetic fixtures. */
  readonly VITE_DEMO?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
