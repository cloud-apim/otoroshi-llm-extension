# AI Studio

A simplified, OpenRouter-like console on top of the Otoroshi LLM extension. It is served by the
extension at `/extensions/cloud-apim/ai-studio` (backoffice session required) and has a
"Back to Otoroshi" button to return to the admin console.

- React 19, plain css (`src/styles/app.css`, light and dark themes), no ui library, no router library,
  hand drawn svg charts
- everything is a regular otoroshi entity created live through the admin api
  (`/bo/api/proxy/apis/...`), tagged with `metadata.ai_studio_workspace = <workspace id>` and read
  back with the in-memory filters of the admin api
- the few things the admin api cannot do live in `src/main/scala/.../studio/studio.scala`: the html
  page, the provider catalog, the models listing, the chat (the OpenAI compatible plugin of the
  workspace route invoked in process for the backoffice user, without api key) and the chat conversations storage
- usage and logs come from the otoroshi user analytics (LLM usage projection of the extension)

## Workspace mapping

| Studio concept | Otoroshi entities |
|---|---|
| workspace | a team `team_ai_studio_<id>` (owner of every entity) + a route `route_ai_studio_<id>` with `IpAddressAllowedList` / `IpAddressBlockList` (enabled when they have addresses), `MandatoryConsumerPreset` and `OpenAiCompatApi` |
| api key | an apikey authorized on the route, tagged `ai_studio_ws_<id>` |
| provider (BYOK) | one entity per enabled capability (`providers`, `embedding-models`, `image-models`, `audio-models`, `moderation-models`, `ocr-models`, `video-models`) sharing `metadata.ai_studio_connection` |
| guardrails, model access | `guardrails` / `models` of every provider of the workspace |
| routing | `provider_fallback`, `loadbalancer` and `otoroshi` (router) providers, order of the route `language_model_refs` |
| presets | `prompt-contexts` attached to providers `context.contexts` |
| tools | `tool-functions`, `mcp-connectors`, `search-engines` attached to providers options |
| credits | `ai-budgets` always scoped with the rule `$.provider.metadata.ai_studio_workspace`, then optionally narrowed to api keys, studio users, models and extra json path conditions |
| studio users | the studio chat calls the `OpenAiCompatApi` plugin of the workspace route in process, from a backoffice route: no api key, the backoffice user becomes the request user (`PrivateAppsUser`), so usage and budgets can be tracked per studio user |
| theme | the `ai_studio_theme` preference of the backoffice user (`light`, `dark` or `system`) |

## Development

```sh
npm install
npm run dev
```

Then open http://studio-dev.oto.tools:5173/extensions/cloud-apim/ai-studio while logged in the local
otoroshi (http://otoroshi.oto.tools:9999, override with `VITE_OTOROSHI_URL`). The otoroshi session
cookie is set on `.oto.tools`, so the api calls proxied by vite are authenticated. The extension jar
only needs to be rebuilt when the scala side changes.

## Build

```sh
npm run build
```

The bundle is written in `src/main/resources/cloudapim/extensions/ai/studio` and committed, so
`sbt assembly` never needs node. Run the build before committing front changes.
