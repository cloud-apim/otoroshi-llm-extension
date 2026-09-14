import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// The bundle is served by the extension asset route and mounted by the html page returned by the
// `/extensions/cloud-apim/ai-studio` backoffice route (see studio/studio.scala). The build output is
// committed in the extension resources so `sbt assembly` never needs node.
export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const otoroshi = env.VITE_OTOROSHI_URL || 'http://otoroshi.oto.tools:9999';
  return {
    plugins: [react()],
    base: command === 'build' ? '/extensions/assets/cloud-apim/extensions/ai-extension/studio/' : '/',
    build: {
      outDir: path.resolve(import.meta.dirname, '../../src/main/resources/cloudapim/extensions/ai/studio'),
      emptyOutDir: true,
      manifest: 'manifest.json',
      assetsInlineLimit: 0,
      rollupOptions: {
        input: path.resolve(import.meta.dirname, 'src/main.jsx'),
      },
    },
    server: {
      // open http://studio-dev.oto.tools:5173/extensions/cloud-apim/ai-studio: the otoroshi session
      // cookie is set on `.oto.tools` so the proxied api calls are authenticated
      host: '127.0.0.1',
      port: 5173,
      strictPort: true,
      allowedHosts: ['.oto.tools'],
      proxy: Object.fromEntries(
        ['/bo/api', '/extensions/cloud-apim/extensions', '/extensions/assets'].map((prefix) => [
          prefix,
          {
            target: otoroshi,
            changeOrigin: true,
            // the backoffice rejects writes coming from another origin
            configure: (proxy) =>
              proxy.on('proxyReq', (proxyReq) => {
                if (proxyReq.getHeader('origin')) proxyReq.setHeader('origin', otoroshi);
                if (proxyReq.getHeader('referer')) proxyReq.setHeader('referer', `${otoroshi}/bo/dashboard`);
              }),
          },
        ])
      ),
    },
  };
});
