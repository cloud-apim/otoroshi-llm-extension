import http from 'node:http';
import { URL } from 'node:url';

const PORT = process.env.PORT ? Number(process.env.PORT) : 3123;

const html = '<!doctype html><html><head><meta charset="utf-8"><title>Hello with md</title></head><body>hello with md</body></html>';
const withouthtml = '<!doctype html><html><head><meta charset="utf-8"><title>Hello without md</title></head><body>hello without md</body></html>';
const markdown = '# hello\n';

const server = http.createServer((req, res) => {
  try {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const path = url.pathname;

    if (path === '/withmd') {
      res.statusCode = 200;
      res.setHeader('Content-Type', 'text/html; charset=utf-8');
      res.setHeader('Content-Length', Buffer.byteLength(html));
      res.end(html);
      return;
    }

    if (path === '/withmd.md') {
      res.statusCode = 200;
      res.setHeader('Content-Type', 'text/markdown; charset=utf-8');
      res.setHeader('Content-Length', Buffer.byteLength(markdown));
      res.end(markdown);
      return;
    }

    if (path === '/withoutmd') {
      res.statusCode = 200;
      res.setHeader('Content-Type', 'text/html; charset=utf-8');
      res.setHeader('Content-Length', Buffer.byteLength(withouthtml));
      res.end(withouthtml);
      return;
    }

    res.statusCode = 404;
    res.setHeader('Content-Type', 'text/plain; charset=utf-8');
    res.end('Not Found');
  } catch (err) {
    res.statusCode = 500;
    res.setHeader('Content-Type', 'text/plain; charset=utf-8');
    res.end('Internal Server Error');
  }
});

server.listen(PORT, () => {
  console.log(`HTTP server listening on http://localhost:${PORT}`);
});

