// What the endpoint of a workspace serves, as the API page and the quickstart of the overview tell it: every
// path of the OpenAI compatible plugin of the route (`plugins/openaiapi.scala`), what it is for, what a
// request can carry and a snippet to start from. A guide, not a reference: the full one is in the docs.

import { chatUsable, endpointsOf } from './modelmeta';

export const DOCS_URL = 'https://cloud-apim.github.io/otoroshi-llm-extension/docs';

export const LANGUAGES = {
  curl: 'curl',
  python: 'Python',
  typescript: 'TypeScript',
  claude: 'Claude Code',
};

export const API_GROUPS = [
  { id: 'text', label: 'Text', description: 'Three formats for the same models: pick the one your SDK or your agent speaks.' },
  { id: 'embedding', label: 'Embeddings', description: 'Vectors for search and retrieval.' },
  { id: 'image', label: 'Images', description: 'Draw and edit images.' },
  { id: 'audio', label: 'Audio', description: 'Speak, transcribe and translate.' },
  { id: 'safety', label: 'Moderation', description: 'Rate a content before it reaches a model or a user.' },
  { id: 'document', label: 'Documents', description: 'Read scans and PDF documents.' },
  { id: 'decision', label: 'Decisions', description: 'Typed questions about a state, answered with probabilities.' },
  { id: 'tools', label: 'Tools', description: 'The tools of the workspace, for MCP clients.' },
  { id: 'discovery', label: 'Discovery', description: 'What the endpoint can be asked for.' },
];

const auth = '-H "Authorization: Bearer $API_KEY"';
const json = '-H "Content-Type: application/json"';

const openaiPython = (baseUrl) => `from openai import OpenAI

client = OpenAI(
    base_url="${baseUrl}",
    api_key="$API_KEY",
)`;

const openaiTypescript = (baseUrl) => `import OpenAI from 'openai';

const client = new OpenAI({
  baseURL: '${baseUrl}',
  apiKey: process.env.API_KEY,
});`;

// The SDKs of Anthropic and TypeSafe add `/v1` themselves: they take the base URL without it, and cannot
// be pointed at an endpoint exposed on another path
const rootOf = (baseUrl) => (baseUrl.endsWith('/v1') ? baseUrl.substring(0, baseUrl.length - 3) : null);

const QUESTION = 'What is the meaning of life?';

export const API_ENDPOINTS = [
  {
    id: 'chat-completions',
    group: 'text',
    method: 'POST',
    path: '/chat/completions',
    title: 'Chat completions',
    summary: 'The OpenAI chat format: messages in, an answer out. What most SDKs, frameworks and agents speak.',
    needs: { modality: 'text' },
    quickstart: 'Chat',
    features: [
      'Streaming with "stream": true, as server-sent events',
      'Tool calling and structured outputs, with the models that support them',
      'Images, audio and PDF documents in the messages, as content parts',
      'A preset of the workspace applied with the "context" field',
      'Every provider answers it: the gateway translates for the ones with an API of their own',
    ],
    docs: '/llm-gateway/openai-compat-api#post-chatcompletions',
    snippets: ({ baseUrl, model }) => ({
      curl: `curl ${baseUrl}/chat/completions \\
  ${auth} \\
  ${json} \\
  -d '{
    "model": "${model}",
    "messages": [{"role": "user", "content": "${QUESTION}"}]
  }'`,
      python: `${openaiPython(baseUrl)}

completion = client.chat.completions.create(
    model="${model}",
    messages=[{"role": "user", "content": "${QUESTION}"}],
)
print(completion.choices[0].message.content)`,
      typescript: `${openaiTypescript(baseUrl)}

const completion = await client.chat.completions.create({
  model: '${model}',
  messages: [{ role: 'user', content: '${QUESTION}' }],
});
console.log(completion.choices[0].message.content);`,
    }),
  },
  {
    id: 'responses',
    group: 'text',
    method: 'POST',
    path: '/responses',
    title: 'Responses',
    summary: 'The newer OpenAI format, with an input and instructions instead of messages.',
    needs: { modality: 'text' },
    quickstart: 'Responses',
    features: [
      'An input as a plain text, a list of messages, or content mixing text, images and files',
      'Streaming with the events of the Responses API',
      'Function calls and their outputs',
      'Also served on /open-responses, following the Open Responses specification',
    ],
    docs: '/llm-gateway/openai-compat-api#post-responses',
    snippets: ({ baseUrl, model }) => ({
      curl: `curl ${baseUrl}/responses \\
  ${auth} \\
  ${json} \\
  -d '{
    "model": "${model}",
    "instructions": "You are a helpful assistant.",
    "input": "${QUESTION}"
  }'`,
      python: `${openaiPython(baseUrl)}

response = client.responses.create(
    model="${model}",
    instructions="You are a helpful assistant.",
    input="${QUESTION}",
)
print(response.output_text)`,
      typescript: `${openaiTypescript(baseUrl)}

const response = await client.responses.create({
  model: '${model}',
  instructions: 'You are a helpful assistant.',
  input: '${QUESTION}',
});
console.log(response.output_text);`,
    }),
  },
  {
    id: 'messages',
    group: 'text',
    method: 'POST',
    path: '/messages',
    title: 'Messages',
    summary: 'The Anthropic format, for the Anthropic SDKs and Claude Code: any model of the workspace answers it.',
    needs: { modality: 'text' },
    quickstart: 'Messages (Anthropic)',
    features: [
      'Streaming with the Anthropic events',
      'Tools, system prompt and thinking, translated for the model that answers',
      'Claude Code running on the models of the workspace',
    ],
    docs: '/llm-gateway/openai-compat-api#post-messages',
    snippets: ({ baseUrl, model }) => {
      const root = rootOf(baseUrl);
      const curl = `curl ${baseUrl}/messages \\
  ${auth} \\
  ${json} \\
  -H "anthropic-version: 2023-06-01" \\
  -d '{
    "model": "${model}",
    "max_tokens": 1024,
    "messages": [{"role": "user", "content": "${QUESTION}"}]
  }'`;
      if (root === null) return { curl };
      return {
        curl,
        python: `from anthropic import Anthropic

client = Anthropic(
    base_url="${root}",
    auth_token="$API_KEY",
)

message = client.messages.create(
    model="${model}",
    max_tokens=1024,
    messages=[{"role": "user", "content": "${QUESTION}"}],
)
print(message.content[0].text)`,
        typescript: `import Anthropic from '@anthropic-ai/sdk';

const client = new Anthropic({
  baseURL: '${root}',
  authToken: process.env.API_KEY,
});

const message = await client.messages.create({
  model: '${model}',
  max_tokens: 1024,
  messages: [{ role: 'user', content: '${QUESTION}' }],
});
console.log(message.content[0].text);`,
        claude: `export ANTHROPIC_BASE_URL=${root}
export ANTHROPIC_AUTH_TOKEN=$API_KEY
export ANTHROPIC_API_KEY=""
claude --model ${model}`,
      };
    },
  },
  {
    id: 'embeddings',
    group: 'embedding',
    method: 'POST',
    path: '/embeddings',
    title: 'Embeddings',
    summary: 'Turns texts into vectors, to search, cluster and retrieve them.',
    needs: { modality: 'embedding', endpoint: 'embeddings' },
    quickstart: 'Embeddings',
    features: ['One text, or a list of texts embedded in a single call'],
    docs: '/llm-gateway/openai-compat-api#post-embeddings',
    snippets: ({ baseUrl, model }) => ({
      curl: `curl ${baseUrl}/embeddings \\
  ${auth} \\
  ${json} \\
  -d '{
    "model": "${model}",
    "input": "The quick brown fox jumps over the lazy dog"
  }'`,
      python: `${openaiPython(baseUrl)}

result = client.embeddings.create(
    model="${model}",
    input="The quick brown fox jumps over the lazy dog",
)
print(len(result.data[0].embedding))`,
      typescript: `${openaiTypescript(baseUrl)}

const result = await client.embeddings.create({
  model: '${model}',
  input: 'The quick brown fox jumps over the lazy dog',
});
console.log(result.data[0].embedding.length);`,
    }),
  },
  {
    id: 'images-generations',
    group: 'image',
    method: 'POST',
    path: '/images/generations',
    title: 'Image generation',
    summary: 'Draws images from a prompt.',
    needs: { modality: 'image', endpoint: 'images_generations' },
    quickstart: 'Image generation',
    features: ['Number of images, size, quality and response format, as in the OpenAI images API'],
    docs: '/llm-gateway/openai-compat-api#post-imagesgenerations',
    snippets: ({ baseUrl, model }) => ({
      curl: `curl ${baseUrl}/images/generations \\
  ${auth} \\
  ${json} \\
  -d '{
    "model": "${model}",
    "prompt": "A red panda coding on a laptop, watercolor",
    "size": "1024x1024"
  }'`,
      python: `${openaiPython(baseUrl)}

result = client.images.generate(
    model="${model}",
    prompt="A red panda coding on a laptop, watercolor",
    size="1024x1024",
)
print(result.data[0])`,
      typescript: `${openaiTypescript(baseUrl)}

const result = await client.images.generate({
  model: '${model}',
  prompt: 'A red panda coding on a laptop, watercolor',
  size: '1024x1024',
});
console.log(result.data[0]);`,
    }),
  },
  {
    id: 'images-edits',
    group: 'image',
    method: 'POST',
    path: '/images/edits',
    title: 'Image edition',
    summary: 'Changes an image you send, following a prompt.',
    needs: { modality: 'image', endpoint: 'images_edits' },
    features: ['The image is uploaded as multipart/form-data'],
    docs: '/llm-gateway/openai-compat-api#post-imagesedits',
    snippets: ({ baseUrl, model }) => ({
      curl: `curl ${baseUrl}/images/edits \\
  ${auth} \\
  -F model="${model}" \\
  -F image=@photo.png \\
  -F prompt="Make it snow"`,
      python: `${openaiPython(baseUrl)}

result = client.images.edit(
    model="${model}",
    image=open("photo.png", "rb"),
    prompt="Make it snow",
)
print(result.data[0])`,
    }),
  },
  {
    id: 'audio-speech',
    group: 'audio',
    method: 'POST',
    path: '/audio/speech',
    title: 'Speech',
    summary: 'Reads a text out loud: the answer is the audio file.',
    needs: { modality: 'audio', endpoint: 'audio_speech' },
    quickstart: 'Speech',
    features: ['The voice of the connection, or the one the request names with "voice"', 'The audio format is an option of the request, "response_format"'],
    docs: '/llm-gateway/openai-compat-api#post-audiospeech',
    snippets: ({ baseUrl, model }) => ({
      curl: `curl ${baseUrl}/audio/speech \\
  ${auth} \\
  ${json} \\
  -d '{
    "model": "${model}",
    "input": "Hello, this voice comes from the AI gateway."
  }' --output speech.mp3`,
    }),
  },
  {
    id: 'audio-transcriptions',
    group: 'audio',
    method: 'POST',
    path: '/audio/transcriptions',
    title: 'Transcription',
    summary: 'Writes down what an audio file says.',
    needs: { modality: 'audio', endpoint: 'audio_transcriptions' },
    quickstart: 'Transcription',
    features: ['The audio file is uploaded as multipart/form-data'],
    docs: '/llm-gateway/openai-compat-api#post-audiotranscriptions',
    snippets: ({ baseUrl, model }) => ({
      curl: `curl ${baseUrl}/audio/transcriptions \\
  ${auth} \\
  -F model="${model}" \\
  -F file=@meeting.mp3`,
      python: `${openaiPython(baseUrl)}

transcription = client.audio.transcriptions.create(
    model="${model}",
    file=open("meeting.mp3", "rb"),
)
print(transcription.text)`,
    }),
  },
  {
    id: 'audio-translations',
    group: 'audio',
    method: 'POST',
    path: '/audio/translations',
    title: 'Translation',
    summary: 'Writes down in English what an audio file says in another language.',
    needs: { modality: 'audio', endpoint: 'audio_transcriptions' },
    features: ['The audio file is uploaded as multipart/form-data'],
    docs: '/llm-gateway/openai-compat-api#post-audiotranslations',
    snippets: ({ baseUrl, model }) => ({
      curl: `curl ${baseUrl}/audio/translations \\
  ${auth} \\
  -F model="${model}" \\
  -F file=@interview.mp3`,
      python: `${openaiPython(baseUrl)}

translation = client.audio.translations.create(
    model="${model}",
    file=open("interview.mp3", "rb"),
)
print(translation.text)`,
    }),
  },
  {
    id: 'moderations',
    group: 'safety',
    method: 'POST',
    path: '/moderations',
    title: 'Moderation',
    summary: 'Rates a content against the categories of a moderation model.',
    needs: { modality: 'moderation', endpoint: 'moderations' },
    quickstart: 'Moderation',
    features: ['A flag and a score for each category'],
    docs: '/llm-gateway/openai-compat-api#post-moderations',
    snippets: ({ baseUrl, model }) => ({
      curl: `curl ${baseUrl}/moderations \\
  ${auth} \\
  ${json} \\
  -d '{
    "model": "${model}",
    "input": "Some text to check"
  }'`,
      python: `${openaiPython(baseUrl)}

result = client.moderations.create(
    model="${model}",
    input="Some text to check",
)
print(result.results[0].flagged)`,
      typescript: `${openaiTypescript(baseUrl)}

const result = await client.moderations.create({
  model: '${model}',
  input: 'Some text to check',
});
console.log(result.results[0].flagged);`,
    }),
  },
  {
    id: 'ocr',
    group: 'document',
    method: 'POST',
    path: '/ocr',
    title: 'OCR',
    summary: 'Extracts the text of a PDF or of a picture of a document, page by page.',
    needs: { modality: 'ocr', endpoint: 'ocr' },
    quickstart: 'OCR',
    features: ['A document named by its URL in a JSON body', 'Or a file uploaded as multipart/form-data', 'The whole text, and the markdown of each page'],
    docs: '/llm-gateway/openai-compat-api#post-ocr',
    snippets: ({ baseUrl, model }) => ({
      curl: `curl ${baseUrl}/ocr \\
  ${auth} \\
  ${json} \\
  -d '{
    "model": "${model}",
    "document": {"type": "document_url", "document_url": "https://example.com/scan.pdf"}
  }'`,
    }),
  },
  {
    id: 'decisions',
    group: 'decision',
    method: 'POST',
    path: '/systemone',
    title: 'Decisions',
    summary: 'The System One format: closed questions about a state, each answered with the probability of its outcomes.',
    needs: { modality: 'decision', endpoint: 'systemone' },
    quickstart: 'Decision',
    features: [
      'Yes or no questions (noul), choices among options (choice) and scores on a scale (score)',
      'Several questions about the same state in one call',
      'A confidence with every choice and every score',
      'The path the TypeSafe SDKs call',
    ],
    docs: '/decision-models/plugins',
    snippets: ({ baseUrl, model }) => {
      const root = rootOf(baseUrl);
      const curl = `curl ${baseUrl}/systemone \\
  ${auth} \\
  ${json} \\
  -d '{
    "model": "${model}",
    "state": "The checkout has been failing for every customer for the last hour.",
    "questions": {
      "urgent": {"type": "noul", "instructions": "Is this support request urgent?"},
      "team": {
        "type": "choice",
        "instructions": "Which team should handle this request?",
        "criteria": {"billing": "Invoices and payments", "technical": "Outages and bugs"}
      }
    }
  }'`;
      if (root === null) return { curl };
      return {
        curl,
        python: `from typesafe_sdk import TypeSafeClient, Noul, Choice

client = TypeSafeClient(
    api_key="$API_KEY",
    base_url="${root}",
)

result = client.system_one(
    "The checkout has been failing for every customer for the last hour.",
    {
        "urgent": Noul(instructions="Is this support request urgent?"),
        "team": Choice(
            instructions="Which team should handle this request?",
            criteria={"billing": "Invoices and payments", "technical": "Outages and bugs"},
        ),
    },
)
print(result.nouls["urgent"].noul)
print(result.choices["team"].choice)`,
      };
    },
  },
  {
    id: 'openai-decisions',
    group: 'decision',
    method: 'POST',
    path: '/decisions',
    title: 'Decisions (OpenAI)',
    summary: 'The decisions format of OpenAI, for the same decision models: ask them from the OpenAI SDKs.',
    needs: { modality: 'decision', endpoint: 'decisions' },
    features: [
      'Predicates, choices among typed values and scores on labelled levels',
      'The answers in the order of the questions, a refusal for one a model would not answer',
      'Text or user messages as input, with pictures for the decision models that look at them',
    ],
    docs: '/decision-models/openai-api',
    snippets: ({ baseUrl, model }) => ({
      curl: `curl ${baseUrl}/decisions \\
  ${auth} \\
  ${json} \\
  -d '{
    "model": "${model}",
    "input": "The checkout has been failing for every customer for the last hour.",
    "questions": [
      {"type": "predicate", "name": "urgent", "instructions": "Is this support request urgent?"},
      {
        "type": "choice",
        "name": "team",
        "instructions": "Which team should handle this request?",
        "choices": [
          {"value": "billing", "description": "Invoices and payments"},
          {"value": "technical", "description": "Outages and bugs"}
        ]
      }
    ]
  }'`,
      python: `${openaiPython(baseUrl)}

result = client.decisions.create(
    model="${model}",
    input="The checkout has been failing for every customer for the last hour.",
    questions=[
        {"type": "predicate", "name": "urgent", "instructions": "Is this support request urgent?"},
        {
            "type": "choice",
            "name": "team",
            "instructions": "Which team should handle this request?",
            "choices": [
                {"value": "billing", "description": "Invoices and payments"},
                {"value": "technical", "description": "Outages and bugs"},
            ],
        },
    ],
)
urgent, team = result.answers
print(urgent.probability)
print(team.choice)`,
      typescript: `${openaiTypescript(baseUrl)}

const result = await client.decisions.create({
  model: '${model}',
  input: 'The checkout has been failing for every customer for the last hour.',
  questions: [
    { type: 'predicate', name: 'urgent', instructions: 'Is this support request urgent?' },
    {
      type: 'choice',
      name: 'team',
      instructions: 'Which team should handle this request?',
      choices: [
        { value: 'billing', description: 'Invoices and payments' },
        { value: 'technical', description: 'Outages and bugs' },
      ],
    },
  ],
});
// one answer per question, in their order
console.log(result.answers);`,
    }),
  },
  {
    id: 'mcp',
    group: 'tools',
    method: 'POST',
    path: '/mcp',
    title: 'MCP server',
    summary: 'The tools of the workspace for MCP clients, over Streamable HTTP, with the same API keys as the models.',
    needs: { mcp: true },
    features: ['Tools, resources and prompts of the workspace', 'A tool call counts for the owner of the key, like a model call'],
    docs: '/llm-gateway/openai-compat-api#post-mcp',
    snippets: ({ baseUrl }) => ({
      curl: `curl ${baseUrl}/mcp \\
  ${auth} \\
  ${json} \\
  -d '{"jsonrpc": "2.0", "id": 1, "method": "tools/list"}'`,
    }),
  },
  {
    id: 'models',
    group: 'discovery',
    method: 'GET',
    path: '/models',
    title: 'Models',
    summary: 'The models of the text providers of the workspace, with the ids to send.',
    features: [
      '?enriched=true adds what each model can do, its limits and its prices',
      '?kind= keeps one type of model: text, image, audio, embedding, moderation, ocr, video, decision',
      '?endpoint= keeps the models served on one endpoint, chat_completions or responses for instance',
      '?has_cost=true keeps the models whose calls are priced',
    ],
    docs: '/llm-gateway/openai-compat-api#get-models',
    snippets: ({ baseUrl }) => ({
      curl: `curl "${baseUrl}/models?enriched=true" \\
  ${auth}`,
      python: `${openaiPython(baseUrl)}

for model in client.models.list():
    print(model.id)`,
      typescript: `${openaiTypescript(baseUrl)}

for await (const model of client.models.list()) {
  console.log(model.id);
}`,
    }),
  },
  {
    id: 'contexts',
    group: 'discovery',
    method: 'GET',
    path: '/contexts',
    title: 'Presets',
    summary: 'The presets of the workspace: a request applies one by naming it, or its id, in its "context" field.',
    features: [],
    docs: '/llm-gateway/openai-compat-api#get-contexts',
    snippets: ({ baseUrl }) => ({
      curl: `curl ${baseUrl}/contexts \\
  ${auth}`,
    }),
  },
  {
    id: 'providers',
    group: 'discovery',
    method: 'GET',
    path: '/providers',
    title: 'Provider catalog',
    summary: 'Every kind of provider the gateway can talk to, with what each one can do.',
    features: ['?capabilities=image,text keeps the providers able to do all of them', '/model-capabilities is the same catalog the other way round: the providers of each type of model'],
    docs: '/llm-gateway/openai-compat-api#get-providers',
    snippets: ({ baseUrl }) => ({
      curl: `curl "${baseUrl}/providers?capabilities=image,text" \\
  ${auth}`,
    }),
  },
];

export const endpointById = (id) => API_ENDPOINTS.find((e) => e.id === id) || null;

// the endpoints the quickstart of the overview offers: the ones a model is called on
export const QUICKSTARTS = API_ENDPOINTS.filter((e) => e.quickstart);

const PLACEHOLDER_MODEL = 'provider/model';

/**
 * Whether the workspace serves an endpoint, and the model to show it with. `listing` is the workspace models
 * listing (`{ providers, models }`): a connection serves an endpoint for every model of its kind, so one
 * connection with the capability is enough.
 */
export function servingOf(endpoint, workspace, listing) {
  const needs = endpoint.needs;
  if (!needs) return { available: true, model: null };
  if (needs.mcp) return { available: !!workspace.mcp_server_ref, model: null };
  const providers = (listing && listing.providers) || [];
  const models = ((listing && listing.models) || []).filter((m) => m.modality === needs.modality);
  if (needs.modality === 'text') {
    // the model a provider is set up with says more than the first one of its listing
    const preferred = (m) => providers.some((p) => p.modality === 'text' && p.slug === m.provider && p.default_model === m.model);
    const model = models.find((m) => preferred(m) && chatUsable(m)) || models.find(chatUsable) || models[0];
    return { available: providers.some((p) => p.modality === 'text'), model: (model && model.id) || PLACEHOLDER_MODEL };
  }
  const serving = providers.filter((p) => p.modality === needs.modality && (p.endpoints || []).includes(needs.endpoint));
  // a model known on that very endpoint first, the one a serving connection carries otherwise
  const model = models.find((m) => endpointsOf(m).includes(needs.endpoint)) || models.find((m) => serving.some((p) => p.slug === m.provider));
  return { available: serving.length > 0, model: (model && model.id) || PLACEHOLDER_MODEL };
}

// the snippets of an endpoint for this workspace, by language
export function snippetsOf(endpoint, workspace, model) {
  return endpoint.snippets({ baseUrl: workspace.base_url, model: model || PLACEHOLDER_MODEL });
}
