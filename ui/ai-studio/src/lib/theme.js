import { useEffect, useState } from 'react';
import { api } from './api';
import { bootstrap } from './bootstrap';

// The theme preference (light, dark or system) is stored in the otoroshi preferences of the
// backoffice user, so it follows the user across browsers. `system` follows the OS setting live.

const PREFERENCE = '/bo/api/me/preferences/ai_studio_theme';
export const THEMES = ['light', 'dark', 'system'];

function systemTheme() {
  return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

function initialPreference() {
  return THEMES.includes(bootstrap.theme) ? bootstrap.theme : 'system';
}

export function useTheme() {
  const [preference, setPreference] = useState(initialPreference);
  const [system, setSystem] = useState(systemTheme);

  useEffect(() => {
    if (!window.matchMedia) return;
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const onChange = () => setSystem(systemTheme());
    media.addEventListener('change', onChange);
    return () => media.removeEventListener('change', onChange);
  }, []);

  const theme = preference === 'system' ? system : preference;

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
  }, [theme]);

  const choose = (value) => {
    setPreference(value);
    bootstrap.theme = value;
    api.post(PREFERENCE, value).catch(() => {});
  };

  return { theme, preference, choose };
}
