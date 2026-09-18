// Files sent with a question in the chat: images, text files and PDFs.
//
// What a model accepts comes from the modalities of its metadata: images and PDFs are only offered to the
// models that read them, text files work everywhere because they are sent as text. Images are resized in
// the browser: a conversation is stored as one json document and every turn sends its files again, so what
// the gateway never uses (more pixels than any model reads) is not worth keeping.

import { inputsOf } from './modelmeta';

export const MAX_FILE_BYTES = 5 * 1024 * 1024;
// what a conversation may carry, files included: enough for a few documents, small enough to store and resend
export const MAX_CONVERSATION_BYTES = 10 * 1024 * 1024;
export const MAX_TEXT_CHARS = 200000;

// no model reads more than that, and both OpenAI and Anthropic downscale above it
const IMAGE_MAX_SIDE = 1568;
const IMAGE_QUALITY = 0.85;
// image types every provider accepts, kept as they are when they are already small
const IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'];
const KEEP_IMAGE_BYTES = 400 * 1024;

const TEXT_TYPES = ['application/json', 'application/xml', 'application/x-yaml', 'application/yaml', 'application/javascript', 'application/sql'];
const TEXT_EXTENSIONS = [
  'txt', 'md', 'markdown', 'csv', 'tsv', 'json', 'jsonl', 'xml', 'yaml', 'yml', 'html', 'htm', 'css', 'js', 'jsx', 'ts', 'tsx',
  'py', 'rb', 'go', 'rs', 'java', 'scala', 'kt', 'c', 'h', 'cpp', 'sh', 'sql', 'conf', 'ini', 'toml', 'log', 'env', 'properties',
];

export const ACCEPT = `image/*,application/pdf,text/*,${TEXT_TYPES.join(',')},${TEXT_EXTENSIONS.map((e) => `.${e}`).join(',')}`;

export const KIND_LABELS = { image: 'images', pdf: 'PDF files', text: 'text files' };

const extensionOf = (name) => (name || '').toLowerCase().split('.').slice(1).pop() || '';

// `image`, `pdf`, `text`, or null for a file no model could read (an archive, a spreadsheet, a video…)
export function kindOf(file) {
  const type = (file.type || '').toLowerCase();
  const extension = extensionOf(file.name);
  if (type === 'application/pdf' || extension === 'pdf') return 'pdf';
  // svg is xml a model reads better as text, and no provider accepts it as an image
  if (type.startsWith('image/') && type !== 'image/svg+xml') return 'image';
  if (type.startsWith('text/') || TEXT_TYPES.includes(type) || type === 'image/svg+xml') return 'text';
  if (TEXT_EXTENSIONS.includes(extension)) return 'text';
  return null;
}

// the kinds of files every model of the conversation reads: one model that does not, and the file is refused
export function acceptedKinds(models) {
  const list = models.filter(Boolean);
  const all = (modality) => list.length > 0 && list.every((m) => inputsOf(m).includes(modality));
  return ['text', ...(all('image') ? ['image'] : []), ...(all('pdf') ? ['pdf'] : [])];
}

export function fmtBytes(bytes) {
  const n = Number(bytes) || 0;
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${Math.round(n / 1024)} KB`;
  return `${(n / (1024 * 1024)).toFixed(1)} MB`;
}

export const blobDataUrl = (blob) =>
  new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(new Error('the file could not be read'));
    reader.readAsDataURL(blob);
  });

// The image as the gateway will send it: within `IMAGE_MAX_SIDE`, and as jpeg when it had to be redrawn.
// A small file of a type every provider accepts is kept as it is: no quality lost, transparency and
// animations intact.
async function imageData(file) {
  const keepAsIs = IMAGE_TYPES.includes((file.type || '').toLowerCase()) && file.size <= KEEP_IMAGE_BYTES;
  if (keepAsIs) return { data: await blobDataUrl(file), mediaType: file.type };
  try {
    const bitmap = await createImageBitmap(file);
    const ratio = Math.min(1, IMAGE_MAX_SIDE / Math.max(bitmap.width, bitmap.height));
    if (ratio === 1 && IMAGE_TYPES.includes((file.type || '').toLowerCase())) {
      bitmap.close && bitmap.close();
      return { data: await blobDataUrl(file), mediaType: file.type };
    }
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(bitmap.width * ratio));
    canvas.height = Math.max(1, Math.round(bitmap.height * ratio));
    canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    bitmap.close && bitmap.close();
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', IMAGE_QUALITY));
    if (!blob) throw new Error('the image could not be resized');
    return { data: await blobDataUrl(blob), mediaType: 'image/jpeg' };
  } catch (e) {
    // an exotic format the browser cannot draw: send it as it is and let the provider decide
    return { data: await blobDataUrl(file), mediaType: file.type || 'image/png' };
  }
}

// the kinds of files a conversation already carries and sends again at every turn: text files are sent as
// text, so only images and PDF need a model that reads them
export function carriedKinds(messages) {
  const kinds = new Set();
  for (const m of messages || []) for (const a of m.attachments || []) if (a.kind !== 'text') kinds.add(a.kind);
  return [...kinds];
}

// the bytes an attachment adds to the conversation, base64 included
export const bytesOf = (attachment) => ((attachment && attachment.data) || '').length;

export const attachmentsBytes = (list) => (list || []).reduce((total, a) => total + bytesOf(a), 0);

// what a conversation already carries, to keep it storable
export function conversationBytes(messages) {
  return (messages || []).reduce((total, m) => total + attachmentsBytes(m.attachments), 0);
}

const isImage = (a) => a.kind === 'image';

/**
 * Reads a file into an attachment: `{ id, name, kind, mediaType, size, data }`. `data` is a data url for
 * an image or a PDF, the text itself for a text file. Throws what to tell the user when the file cannot
 * be sent: too large, of a kind no model reads, or refused by the models of the conversation.
 */
export async function readAttachment(file, kinds) {
  const kind = kindOf(file);
  if (!kind) throw new Error(`${file.name}: only images, PDF and text files can be sent`);
  if (!kinds.includes(kind)) throw new Error(`${file.name}: the selected model${kinds.length > 1 ? 's do' : ' does'} not read ${KIND_LABELS[kind]}`);
  if (file.size > MAX_FILE_BYTES) throw new Error(`${file.name}: ${fmtBytes(file.size)} is too large, ${fmtBytes(MAX_FILE_BYTES)} at most`);
  const base = { id: `att_${Math.random().toString(36).substring(2, 10)}`, name: file.name || `file.${kind}`, kind, size: file.size };
  if (kind === 'text') {
    const text = await file.text();
    const truncated = text.length > MAX_TEXT_CHARS;
    return { ...base, mediaType: file.type || 'text/plain', data: truncated ? `${text.substring(0, MAX_TEXT_CHARS)}\n… truncated, the file has ${text.length} characters` : text, truncated };
  }
  if (kind === 'image') {
    const { data, mediaType } = await imageData(file);
    return { ...base, mediaType, data };
  }
  return { ...base, mediaType: 'application/pdf', data: await blobDataUrl(file) };
}

// the content parts of a user message: its text, then its files, as the chat completions api takes them
export function partsOf(message) {
  const attachments = (message.attachments || []).filter((a) => a.data);
  if (attachments.length === 0) return message.content || '';
  const parts = [];
  const text = (message.content || '').trim();
  if (text) parts.push({ type: 'text', text });
  for (const a of attachments) {
    if (a.kind === 'image') parts.push({ type: 'image_url', image_url: { url: a.data } });
    else if (a.kind === 'pdf') parts.push({ type: 'file', file: { filename: a.name, file_data: a.data } });
    // a text file is text: every model reads it, whatever its modalities
    else parts.push({ type: 'text', text: `File "${a.name}":\n\n${a.data}` });
  }
  return parts;
}

// an attachment of an imported conversation, or of an openai request pasted in: null when unusable
export function importedAttachment(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const kind = ['image', 'pdf', 'text'].includes(raw.kind) ? raw.kind : null;
  if (!kind || typeof raw.data !== 'string' || raw.data.length === 0 || raw.data.length > MAX_FILE_BYTES * 2) return null;
  if (kind !== 'text' && !raw.data.startsWith('data:')) return null;
  return {
    id: `att_${Math.random().toString(36).substring(2, 10)}`,
    name: typeof raw.name === 'string' && raw.name ? raw.name.substring(0, 200) : `file.${kind}`,
    kind,
    mediaType: typeof raw.mediaType === 'string' ? raw.mediaType : kind === 'pdf' ? 'application/pdf' : 'text/plain',
    size: Number(raw.size) || raw.data.length,
    data: raw.data,
  };
}

// the files of an openai content array (`image_url`, `file`), so a prompt copied from an application imports whole
export function attachmentsOfContent(content) {
  if (!Array.isArray(content)) return [];
  return content
    .map((part) => {
      if (!part || typeof part !== 'object') return null;
      if (part.type === 'image_url') return importedAttachment({ kind: 'image', name: 'image', data: part.image_url && part.image_url.url, mediaType: 'image/png' });
      if (part.type === 'file' && part.file) return importedAttachment({ kind: 'pdf', name: part.file.filename || 'document.pdf', data: part.file.file_data });
      if (part.type === 'input_file') return importedAttachment({ kind: 'pdf', name: part.filename || 'document.pdf', data: part.file_data });
      return null;
    })
    .filter(Boolean);
}

export const thumbnailOf = (a) => (isImage(a) && a.data ? a.data : null);
