import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The browser talks only to this dev server; /api and /actuator are proxied to the Spring Boot app,
// so there is no CORS to configure and every response header (X-Served-By, X-Cache) is readable.
const api = process.env.API_URL || 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': api,
      '/actuator': api,
    },
  },
});
