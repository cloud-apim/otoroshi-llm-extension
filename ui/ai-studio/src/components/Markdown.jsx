import { Fragment, memo, useMemo } from 'react';
import { Lexer } from 'marked';
import { CopyButton } from './ui';

// Model answers rendered from the marked tokens as react elements, never as html: raw html shows as text
// and only web links and images are kept, so an answer can never run code in the backoffice session.

const LEXER_OPTIONS = { gfm: true, breaks: true };

const safeLink = (href) => (/^(https?:|mailto:)/i.test((href || '').trim()) ? href.trim() : null);
const safeImage = (src) => (/^(https?:|data:image\/(png|jpe?g|gif|webp);)/i.test((src || '').trim()) ? src.trim() : null);

function CodeBlock({ lang, code }) {
  return (
    <div className="md-code">
      <div className="md-code-head">
        <span>{lang || 'text'}</span>
        <CopyButton text={code} />
      </div>
      <pre>
        <code>{code}</code>
      </pre>
    </div>
  );
}

function inlines(tokens = []) {
  return tokens.map((t, i) => inline(t, i));
}

function inline(t, key) {
  switch (t.type) {
    case 'text':
      return <Fragment key={key}>{t.tokens ? inlines(t.tokens) : t.text}</Fragment>;
    case 'strong':
      return <strong key={key}>{inlines(t.tokens)}</strong>;
    case 'em':
      return <em key={key}>{inlines(t.tokens)}</em>;
    case 'del':
      return <del key={key}>{inlines(t.tokens)}</del>;
    case 'codespan':
      return <code key={key}>{t.text}</code>;
    case 'br':
      return <br key={key} />;
    case 'link': {
      const href = safeLink(t.href);
      return href ? (
        <a key={key} href={href} title={t.title || undefined} target="_blank" rel="noopener noreferrer">
          {inlines(t.tokens)}
        </a>
      ) : (
        <Fragment key={key}>{inlines(t.tokens)}</Fragment>
      );
    }
    case 'image': {
      const src = safeImage(t.href);
      return src ? <img key={key} src={src} alt={t.text} title={t.title || undefined} loading="lazy" /> : <Fragment key={key}>{t.text}</Fragment>;
    }
    default:
      return <Fragment key={key}>{t.text || ''}</Fragment>;
  }
}

function blocks(tokens = []) {
  return tokens.map((t, i) => block(t, i));
}

function listItem(item) {
  return item.tokens.map((t, i) => {
    if (t.type === 'checkbox') return <input key={i} type="checkbox" checked={!!t.checked} readOnly disabled />;
    // tight lists hold their content as text tokens, not paragraphs
    if (t.type === 'text') return <Fragment key={i}>{t.tokens ? inlines(t.tokens) : t.text}</Fragment>;
    return block(t, i);
  });
}

function block(t, key) {
  switch (t.type) {
    case 'space':
    case 'def':
      return null;
    case 'heading': {
      const Heading = `h${Math.min(Math.max(t.depth, 1), 6)}`;
      return <Heading key={key}>{inlines(t.tokens)}</Heading>;
    }
    case 'paragraph':
      return <p key={key}>{inlines(t.tokens)}</p>;
    case 'code':
      return <CodeBlock key={key} lang={t.lang} code={t.text} />;
    case 'blockquote':
      return <blockquote key={key}>{blocks(t.tokens)}</blockquote>;
    case 'hr':
      return <hr key={key} />;
    case 'list': {
      const List = t.ordered ? 'ol' : 'ul';
      const start = t.ordered && typeof t.start === 'number' && t.start !== 1 ? t.start : undefined;
      return (
        <List key={key} start={start}>
          {t.items.map((item, i) => (
            <li key={i} className={item.task ? 'task' : undefined}>
              {listItem(item)}
            </li>
          ))}
        </List>
      );
    }
    case 'table':
      return (
        <div key={key} className="md-table">
          <table>
            <thead>
              <tr>
                {t.header.map((cell, i) => (
                  <th key={i} style={cell.align ? { textAlign: cell.align } : undefined}>
                    {inlines(cell.tokens)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {t.rows.map((row, i) => (
                <tr key={i}>
                  {row.map((cell, j) => (
                    <td key={j} style={cell.align ? { textAlign: cell.align } : undefined}>
                      {inlines(cell.tokens)}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      );
    case 'text':
      return <p key={key}>{t.tokens ? inlines(t.tokens) : t.text}</p>;
    default:
      // html and unknown blocks are shown as they were written
      return t.text ? <p key={key}>{t.text}</p> : null;
  }
}

export const Markdown = memo(function Markdown({ text }) {
  const tokens = useMemo(() => {
    try {
      return Lexer.lex(text || '', LEXER_OPTIONS);
    } catch (e) {
      return null;
    }
  }, [text]);
  if (!tokens) return <div className="md plain">{text}</div>;
  return <div className="md">{blocks(tokens)}</div>;
});
