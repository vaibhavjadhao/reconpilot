import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // The browser sees one origin (localhost:5173) and Vite forwards /api to
    // the Spring app. Without this the browser would block every request:
    // localhost:5173 and localhost:8080 are different origins, and the browser
    // refuses cross-origin calls unless the server explicitly allows them
    // (CORS). Proxying sidesteps the problem entirely in development, and
    // mirrors production, where the same reverse proxy serves both.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/actuator': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
