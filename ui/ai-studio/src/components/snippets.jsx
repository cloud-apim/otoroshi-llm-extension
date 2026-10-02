import { CopyButton, Tabs } from './ui';
import { LANGUAGES } from '../lib/apidocs';

/**
 * The snippets of an endpoint (`snippetsOf`), one tab per language it has one in. `lang` falls back to curl
 * for an endpoint without that language. `copy` puts a copy button on the code itself.
 */
export function Snippets({ snippets, lang, onLang, copy = true }) {
  const current = snippets[lang] ? lang : 'curl';
  const languages = Object.keys(snippets);
  return (
    <div className="snippet">
      {languages.length > 1 && <Tabs value={current} onChange={onLang} tabs={languages.map((l) => ({ value: l, label: LANGUAGES[l] || l }))} />}
      <div className="snippet-code">
        {copy && <CopyButton text={snippets[current]} title="Copy the code" />}
        <pre>
          <code>{snippets[current]}</code>
        </pre>
      </div>
    </div>
  );
}
