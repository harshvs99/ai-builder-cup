import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  worker: { format: 'es' },
  server: { port: 5173 },
  build: { chunkSizeWarningLimit: 1600 }, // maplibre-gl is ~1 MB by itself
  test: { environment: 'node', include: ['src/**/*.test.ts'] },
});
