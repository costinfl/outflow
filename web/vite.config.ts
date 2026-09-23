import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Demo mode (`--mode demo`, see .env.demo) is the static build published to GitHub Pages:
// no API, synthetic fixtures only, served under /outflow/ unless VITE_BASE says otherwise.
export default defineConfig(({ mode }) => ({
  base: process.env.VITE_BASE ?? (mode === 'demo' ? '/outflow/' : '/'),
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:8080' },
  },
}))
