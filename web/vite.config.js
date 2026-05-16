import { defineConfig } from 'vite'
import { svelte } from '@sveltejs/vite-plugin-svelte'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [svelte(), tailwindcss()],
  build: {
    outDir: '../internal/ui/static',
    emptyOutDir: true,
  },
  resolve: {
    alias: {
      $lib: path.resolve('./src/lib'),
    },
    extensions: ['.js', '.ts', '.svelte', '.svelte.ts'],
  },
})
