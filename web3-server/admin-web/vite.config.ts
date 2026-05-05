import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  base: process.env.VITE_ADMIN_WEB_BASE || '/admin/',
  server: {
    port: Number(process.env.VITE_ADMIN_PORT || 5173),
    proxy: {
      '/api/admin': {
        target: process.env.VITE_BACKEND_ORIGIN || 'http://127.0.0.1:8787',
        changeOrigin: true,
      },
    },
  },
});
