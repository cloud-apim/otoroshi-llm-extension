import '@fontsource-variable/plus-jakarta-sans';
import './styles/app.css';
import { createRoot } from 'react-dom/client';
import { bootstrap, loadBootstrap } from './lib/bootstrap';
import { installBackend, localBackend } from './lib/backend';
import { installPlatform } from './lib/platform';

loadBootstrap().then(async () => {
  // the studio of the Otoroshi backoffice: the admin api through the session of the backoffice user
  installBackend(localBackend());
  installPlatform({ edition: 'oss', experimental: true, links: { admin: bootstrap.adminUrl } });
  const { App } = await import('./App');
  createRoot(document.getElementById('root')).render(<App />);
});
