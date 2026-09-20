import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const rootPackage = JSON.parse(
  readFileSync(fileURLToPath(new URL('../../package.json', import.meta.url)), 'utf8'),
) as { version: string }
export default defineConfig({
  plugins: [react()],
  base: './',
  define: { __MELORA_VERSION__: JSON.stringify(rootPackage.version) },
  server: {
    port: Number(process.env.MELORA_WEB_PORT) || 5173,
    strictPort: true,
    proxy: { '/api': { target: 'http://127.0.0.1:3780', changeOrigin: false } },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
    restoreMocks: true,
  },
})
