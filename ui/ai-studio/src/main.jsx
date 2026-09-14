import '@fontsource-variable/plus-jakarta-sans';
import './styles/app.css';
import { createRoot } from 'react-dom/client';
import { loadBootstrap } from './lib/bootstrap';

loadBootstrap().then(async () => {
  const { App } = await import('./App');
  createRoot(document.getElementById('root')).render(<App />);
});
