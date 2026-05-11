import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');

  const adminWebBase = env.VITE_ADMIN_WEB_BASE || '/admin/';
  const adminApiBase = env.VITE_ADMIN_API_BASE || '/api/admin';
  const backendOrigin = env.VITE_BACKEND_ORIGIN || 'http://127.0.0.1:8787';

  return {
    plugins: [react()],
    base: adminWebBase,
    server: {
      port: Number(env.VITE_ADMIN_PORT || 5173),
      proxy: {
        [adminApiBase]: {
          target: backendOrigin,
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/etzredpacket/, ''),
        },
      },
    },
  };
});