# A2A (Agent-to-Agent) Protocol - Design Document

## Contexte

Ce document décrit le design pour l'intégration du protocole A2A (Agent-to-Agent, initié par Google, maintenant sous Linux Foundation) dans l'extension LLM d'Otoroshi. L'objectif est double :

1. **A2A Server** : exposer des agents locaux (définis via `AgentConfig` ou des workflows) comme des serveurs A2A accessibles par des clients/agents externes
2. **A2A Connector** : se connecter à des agents A2A distants et les utiliser comme des outils (tools) dans les agents locaux, de la même manière que les `McpConnector`

---

## Le protocole A2A en bref

### Principes fondamentaux

- Les agents communiquent sans exposer leur état interne, mémoire, outils ou plans
- La découverte se fait via des **Agent Cards** (documents JSON de métadonnées)
- La communication utilise **JSON-RPC 2.0 sur HTTPS**
- Le travail est organisé autour de **Tasks** (unités de travail avec état) et **Messages** (tours de conversation individuels)
- Les **contextId** regroupent logiquement plusieurs Tasks pour la cohérence multi-turn

### Agent Card

Publié à l'URI bien connue : `https://{domain}/.well-known/agent.json`

```json
{
  "name": "Mon Agent",
  "description": "Description de l'agent",
  "version": "1.0.0",
  "supportedInterfaces": [
    {
      "url": "https://mon-agent.example.com/a2a",
      "protocolBinding": "JSONRPC",
      "protocolVersion": "0.3.0"
    }
  ],
  "provider": {
    "organization": "Cloud APIM",
    "url": "https://www.cloud-apim.com"
  },
  "capabilities": {
    "streaming": true,
    "pushNotifications": false,
    "extendedAgentCard": false
  },
  "securitySchemes": {
    "bearer": {
      "httpAuthSecurityScheme": {
        "scheme": "Bearer"
      }
    }
  },
  "security": [{"bearer": []}],
  "defaultInputModes": ["text/plain", "application/json"],
  "defaultOutputModes": ["text/plain", "application/json"],
  "skills": [
    {
      "id": "math-tutor",
      "name": "Math Tutor",
      "description": "Aide avec les problèmes de mathématiques",
      "tags": ["math", "tutoring"],
      "examples": ["Résous cette équation : 2x + 3 = 7"],
      "inputModes": ["text/plain"],
      "outputModes": ["text/plain"]
    }
  ]
}
```

### Méthodes JSON-RPC

| Méthode | Description | Priorité |
|---|---|---|
| `message/send` | Envoi synchrone d'un message, retourne un Task ou Message | **P0** |
| `message/stream` | Envoi avec streaming SSE | **P0** |
| `tasks/get` | Récupérer l'état d'une tâche | **P0** |
| `tasks/cancel` | Annuler une tâche active | **P1** |
| `tasks/list` | Lister les tâches (avec filtres) | **P2** |
| `tasks/resubscribe` | Reprendre le streaming d'une tâche existante | **P2** |
| `tasks/pushNotificationConfig/*` | Gestion des webhooks de notification | **P3** |
| `agent/getAuthenticatedExtendedCard` | Agent Card étendu avec auth | **P3** |

### Cycle de vie des Tasks

```
            ┌──────────┐
            │ submitted │
            └─────┬─────┘
                  │
            ┌─────▼─────┐
     ┌──────│  working   │──────┐
     │      └─────┬─────┘      │
     │            │             │
┌────▼────┐ ┌────▼─────┐ ┌────▼───┐
│  failed │ │completed │ │canceled│
└─────────┘ └──────────┘ └────────┘
     ▲            ▲
     │      ┌─────┴──────────┐
     └──────│ input_required │
            └────────────────┘
```

**États terminaux** : `completed`, `failed`, `canceled`, `rejected`
**États interactifs** : `input_required`, `auth_required` (le client peut renvoyer un message)

### Format des Messages et Parts

```json
{
  "messageId": "msg-001",
  "role": "user",
  "parts": [
    {"type": "text", "text": "Bonjour, aide-moi avec ce problème"},
    {"type": "file", "file": {"uri": "https://...", "mimeType": "application/pdf"}},
    {"type": "data", "data": {"key": "value"}}
  ]
}
```

### Format d'une requête JSON-RPC `message/send`

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "message/send",
  "params": {
    "message": {
      "messageId": "msg-001",
      "role": "user",
      "parts": [{"type": "text", "text": "Planifie un trajet de Paris à Lyon"}],
      "contextId": "ctx-optional",
      "taskId": "task-optional"
    },
    "configuration": {
      "acceptedOutputModes": ["text/plain"],
      "historyLength": 10
    }
  }
}
```

### Format d'une réponse JSON-RPC (Task)

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "id": "task-123",
    "contextId": "ctx-456",
    "status": {
      "state": "completed",
      "message": {
        "role": "agent",
        "messageId": "msg-resp-001",
        "parts": [{"type": "text", "text": "Voici le trajet planifié..."}]
      },
      "timestamp": "2026-03-12T10:00:00.000Z"
    },
    "artifacts": [],
    "history": []
  }
}
```

### Streaming (SSE)

Le `message/stream` retourne des Server-Sent Events. Chaque ligne `data:` contient une réponse JSON-RPC complète wrappant un `StreamResponse` qui peut être :
- `task` : objet Task complet
- `message` : message intermédiaire
- `statusUpdate` : `TaskStatusUpdateEvent`
- `artifactUpdate` : `TaskArtifactUpdateEvent`

```
data: {"jsonrpc":"2.0","id":1,"result":{"statusUpdate":{"taskId":"task-123","contextId":"ctx-456","status":{"state":"working","message":{"role":"agent","messageId":"m1","parts":[{"type":"text","text":"Traitement en cours..."}]}}}}}

data: {"jsonrpc":"2.0","id":1,"result":{"artifactUpdate":{"taskId":"task-123","contextId":"ctx-456","artifact":{"artifactId":"art-1","parts":[{"type":"text","text":"chunk..."}]},"append":true,"lastChunk":false}}}

data: {"jsonrpc":"2.0","id":1,"result":{"statusUpdate":{"taskId":"task-123","contextId":"ctx-456","status":{"state":"completed"}}}}
```

### Codes d'erreur A2A

| Erreur | Code JSON-RPC | Description |
|---|---|---|
| TaskNotFoundError | -32001 | Tâche inexistante |
| TaskNotCancelableError | -32002 | Tâche non annulable |
| PushNotificationNotSupportedError | -32003 | Notifications push non supportées |
| UnsupportedOperationError | -32004 | Opération non implémentée |
| ContentTypeNotSupportedError | -32005 | Type de contenu non supporté |
| InvalidAgentResponseError | -32006 | Réponse agent invalide |

---

## Design proposé

### Vue d'ensemble

```
┌───────────────────────────────────────────────────────────────────┐
│                        Otoroshi                                   │
│                                                                   │
│  ┌──────────────────┐    ┌─────────────────────────────────────┐  │
│  │  A2A Server      │    │  Agents locaux                      │  │
│  │  (NgBackendCall) │───▶│  AgentConfig / Workflow             │  │
│  │                  │    │                                     │  │
│  │  - Agent Card    │    │  ┌───────────┐  ┌───────────────┐   │  │
│  │  - message/send  │    │  │ Provider  │  │ A2A Connector │   │  │
│  │  - message/stream│    │  │ (LLM)     │  │ (tool)        │───┼──┼──▶ Agent A2A distant
│  │  - tasks/get     │    │  └───────────┘  └───────────────┘   │  │
│  │  - tasks/cancel  │    │                                     │  │
│  └────────┬─────────┘    └─────────────────────────────────────┘  │
│           │                                                       │
│  ┌────────▼─────────┐    ┌─────────────────────────────────────┐  │
│  │  A2AServer Entity│    │  A2AConnector Entity                │  │
│  │  (config)        │    │  (config connexion distante)        │  │
│  └──────────────────┘    └─────────────────────────────────────┘  │
│                                                                   │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │  A2ATask Store (Redis, TTL)                                │   │
│  │  Stockage des tâches en cours et terminées                 │   │
│  └────────────────────────────────────────────────────────────┘   │
└───────────────────────────────────────────────────────────────────┘
```

---

### Partie 1 : A2A Server

#### Nouvelle entité : `A2AServer`

Stockée en Redis, configurée via l'admin UI. Définit comment un agent est exposé en A2A.

```json
{
  "id": "a2a-server_xxx",
  "name": "Mon Agent A2A",
  "description": "Un agent exposé via le protocole A2A",
  "tags": [],
  "metadata": {},

  "agent_card": {
    "version": "1.0.0",
    "provider": {
      "organization": "Cloud APIM",
      "url": "https://www.cloud-apim.com"
    },
    "default_input_modes": ["text/plain", "application/json"],
    "default_output_modes": ["text/plain", "application/json"],
    "skills": [
      {
        "id": "main",
        "name": "Main Skill",
        "description": "Le skill principal de l'agent",
        "tags": ["general"],
        "examples": ["Exemple de requête"]
      }
    ]
  },

  "backend": {
    "kind": "agent",
    "agent": {
      "name": "Mon Agent",
      "description": "...",
      "instructions": ["..."],
      "provider": "provider_xxx",
      "model": "gpt-4",
      "tools": [],
      "mcp_connectors": [],
      "a2a_connectors": [],
      "handoffs": []
    }
  }
}
```

**Alternative pour le backend** : au lieu d'inline l'`AgentConfig`, on pourrait référencer un workflow :

```json
{
  "backend": {
    "kind": "workflow",
    "workflow_ref": "workflow_xxx"
  }
}
```

Ou même référencer une route existante (pour réutiliser un LLMProxy déjà configuré) :

```json
{
  "backend": {
    "kind": "route",
    "route_ref": "route_xxx"
  }
}
```

**Question ouverte** : est-ce qu'on veut supporter les 3 modes (agent inline, workflow, route) ou commencer simple avec juste agent inline + workflow ?

#### Plugin : `A2AServerPlugin` (NgBackendCall)

Ce plugin serait attaché à une route Otoroshi et gérerait toutes les requêtes A2A.

**Routage interne** :
- `GET /.well-known/agent.json` → retourne l'Agent Card
- `POST /` avec `Content-Type: application/json` → dispatch JSON-RPC

**Configuration du plugin** :
```json
{
  "ref": "a2a-server_xxx"
}
```

**Logique de dispatch JSON-RPC** :

```
method == "message/send"    → handleMessageSend(params)
method == "message/stream"  → handleMessageStream(params)
method == "tasks/get"       → handleTaskGet(params)
method == "tasks/cancel"    → handleTaskCancel(params)
method == "tasks/list"      → handleTaskList(params)
sinon                       → erreur -32601 (Method not found)
```

#### Gestion des Tasks

**Stockage** : en Redis avec un TTL configurable (par défaut 24h). Clé : `{storageRoot}:extensions:{extId}:a2a-tasks:{taskId}`

**Structure Task en Redis** :

```json
{
  "id": "task-uuid",
  "context_id": "ctx-uuid",
  "a2a_server_id": "a2a-server_xxx",
  "status": {
    "state": "completed",
    "timestamp": "2026-03-12T10:00:00.000Z"
  },
  "history": [
    {"messageId": "msg-1", "role": "user", "parts": [...]},
    {"messageId": "msg-2", "role": "agent", "parts": [...]}
  ],
  "artifacts": [],
  "created_at": 1741776000000,
  "updated_at": 1741776005000
}
```

**Question ouverte** : pour les tâches synchrones simples (l'agent répond en un seul appel), est-ce qu'on stocke quand même en Redis ? Ça permettrait le `tasks/get` après coup, mais c'est du overhead pour les cas simples. Option : stocker systématiquement mais avec un TTL court pour les tâches complétées.

#### Flow `message/send`

```
1. Parse JSON-RPC request
2. Extraire le message et la configuration
3. Créer un Task avec état "submitted", stocker en Redis
4. Mapper les Parts A2A vers des InputChatMessage :
   - TextPart → contenu texte
   - FilePart → InputImageContent ou InputFileContent selon le MIME type
   - DataPart → sérialiser en JSON string
5. Si taskId fourni dans le message → récupérer la Task existante et ajouter au contexte
6. Si contextId fourni → récupérer l'historique du contexte
7. Mettre à jour l'état de la Task à "working"
8. Appeler l'agent/workflow avec l'input mappé
9. Mapper la réponse vers des Parts A2A
10. Mettre à jour la Task à "completed" (ou "failed" en cas d'erreur)
11. Retourner la réponse JSON-RPC
```

#### Flow `message/stream`

```
1-7. Même chose que message/send
8. Envoyer un SSE statusUpdate avec state="working"
9. Appeler l'agent/workflow en mode streaming
10. Pour chaque chunk de réponse, envoyer un SSE artifactUpdate
11. Envoyer un SSE statusUpdate final avec state="completed"
```

#### Mapping A2A ↔ ChatMessage

**Entrée (A2A → ChatMessage)** :

```scala
// A2A Part → ChatMessage content
TextPart("hello")           → InputTextContent("hello")
FilePart(uri, "image/png")  → InputImageContent(url = uri)
FilePart(bytes, "image/png")→ InputImageContent(base64 = bytes)
DataPart({...})             → InputTextContent(json.stringify)
```

**Sortie (ChatMessage → A2A)** :

```scala
// ChatMessage → A2A Part
"response text"             → TextPart("response text")
// Si la réponse contient du JSON structuré, on pourrait aussi générer un DataPart
```

---

### Partie 2 : A2A Connector

#### Nouvelle entité : `A2AConnector`

Similaire à `McpConnector`. Stockée en Redis, permet de se connecter à un agent A2A distant.

```json
{
  "id": "a2a-connector_xxx",
  "name": "Agent de planification distant",
  "description": "Connecteur vers un agent A2A de planification",
  "tags": [],
  "metadata": {},

  "url": "https://remote-agent.example.com",
  "agent_card_path": "/.well-known/agent.json",

  "authentication": {
    "kind": "bearer",
    "token": "sk-xxx"
  },

  "skills_filter": [],

  "timeout": 30000,
  "streaming": false,

  "tls": {
    "enabled": false,
    "trust_all": false,
    "client_cert_ref": null
  }
}
```

**Schémas d'authentification supportés** :
- `none` : pas d'auth
- `bearer` : `Authorization: Bearer <token>`
- `apikey` : clé API dans un header, query param ou cookie configurable
- `basic` : Basic auth
- `oauth2_client_credentials` : OAuth2 client credentials flow
- `custom_headers` : headers personnalisés (pour les cas exotiques)

#### Comment le connector expose les skills comme tools

Quand un `A2AConnector` est configuré, au démarrage (ou au sync state), on :
1. Fetch l'Agent Card du serveur distant
2. Parse les skills
3. Pour chaque skill, on génère une tool function

**Mapping Skill → Tool Function** :

```json
{
  "type": "function",
  "function": {
    "name": "a2a_<connector_id>_<skill_id>",
    "description": "skill description (from Agent Card)",
    "parameters": {
      "type": "object",
      "properties": {
        "message": {
          "type": "string",
          "description": "The message to send to the remote agent"
        }
      },
      "required": ["message"]
    }
  }
}
```

**Question ouverte** : est-ce qu'on veut un mapping plus sophistiqué des paramètres ? Par exemple, si le skill accepte des `inputModes` spécifiques comme `application/json`, on pourrait enrichir le schéma des paramètres. Pour commencer, un simple champ `message` (texte) semble suffisant.

#### Exécution d'un appel A2A distant

Quand un agent local appelle un tool A2A :

```
1. Récupérer le connector et le skill correspondant
2. Construire la requête JSON-RPC message/send :
   {
     "jsonrpc": "2.0",
     "id": 1,
     "method": "message/send",
     "params": {
       "message": {
         "messageId": "<uuid>",
         "role": "user",
         "parts": [{"type": "text", "text": "<le message>"}]
       }
     }
   }
3. Envoyer la requête HTTP POST vers l'URL du connector
4. Parser la réponse JSON-RPC
5. Extraire le texte des Parts de la réponse
6. Retourner le résultat comme sortie du tool
```

**Pour le streaming** : si `streaming: true` dans la config du connector, on utilise `message/stream` au lieu de `message/send`. Les SSE sont consommés et le résultat final est retourné.

#### Intégration avec les agents

Les A2A connectors seraient utilisables comme les MCP connectors dans un `AgentConfig` :

```json
{
  "name": "Mon Agent",
  "provider": "provider_xxx",
  "instructions": ["..."],
  "mcp_connectors": ["mcp-connector_xxx"],
  "a2a_connectors": ["a2a-connector_xxx"],
  "tools": ["tool-function_xxx"]
}
```

Et dans le code de `AgentConfig`, on ajouterait la résolution des a2a_connectors de la même manière que les mcp_connectors.

#### Cache de l'Agent Card

L'Agent Card ne change pas souvent. On peut la cacher avec un TTL configurable (par défaut 5 min). Au sync state, on refresh les Agent Cards de tous les connectors actifs.

**Question ouverte** : est-ce qu'on refresh l'Agent Card à chaque sync state (toutes les 30s par défaut dans Otoroshi) ? C'est peut-être trop fréquent. Un TTL de cache séparé serait plus adapté.

---

### Partie 3 : Entités et stockage

#### Nouvelles entités à créer

| Entité | Prefix ID | Redis key | Description |
|---|---|---|---|
| `A2AServer` | `a2a-server_` | `...a2a-servers:{id}` | Configuration d'un serveur A2A |
| `A2AConnector` | `a2a-connector_` | `...a2a-connectors:{id}` | Configuration d'un connecteur A2A |

Les Tasks A2A ne sont **pas** des entités Otoroshi classiques (pas dans le state sync) car elles sont éphémères. Elles sont stockées directement en Redis avec TTL.

#### Registration dans l'extension

Dans `extension.scala`, ajouter :

```scala
override def entities(): Seq[AdminExtensionEntity[EntityLocationSupport]] = {
  Seq(
    // ... entités existantes ...
    AdminExtensionEntity(A2AServer.resource(env, datastores, states)),
    AdminExtensionEntity(A2AConnector.resource(env, datastores, states)),
  )
}
```

#### State management

Dans `AiGatewayExtensionState`, ajouter :

```scala
private val _a2aServers = new UnboundedTrieMap[String, A2AServer]()
private val _a2aConnectors = new UnboundedTrieMap[String, A2AConnector]()

def a2aServer(id: String): Option[A2AServer] = _a2aServers.get(id)
def allA2AServers(): Seq[A2AServer] = _a2aServers.values.toSeq
def updateA2AServers(values: Seq[A2AServer]): Unit = { ... }

def a2aConnector(id: String): Option[A2AConnector] = _a2aConnectors.get(id)
def allA2AConnectors(): Seq[A2AConnector] = _a2aConnectors.values.toSeq
def updateA2AConnectors(values: Seq[A2AConnector]): Unit = { ... }
```

---

### Partie 4 : Fichiers à créer/modifier

#### Nouveaux fichiers

| Fichier | Contenu |
|---|---|
| `entities/a2a.scala` | Entités `A2AServer` et `A2AConnector` avec Format, DataStore, resource() |
| `plugins/a2a.scala` | Plugin `A2AServerPlugin` (NgBackendCall) |
| `a2a/a2a.scala` | Modèles A2A (Task, Message, Part, AgentCard, erreurs JSON-RPC) |
| `a2a/client.scala` | Client HTTP A2A pour les connectors (envoi message/send, message/stream) |

#### Fichiers à modifier

| Fichier | Modification |
|---|---|
| `extension.scala` | Ajouter les entités, datastores, state management |
| `agents/agents.scala` | Ajouter `a2aConnectors` dans `AgentConfig`, résoudre les tools A2A |
| `workflows.scala` | Enregistrer les nouvelles fonctions workflow si pertinent |

---

### Partie 5 : Sécurité

#### Côté serveur (A2A Server)

La sécurité est gérée par Otoroshi au niveau de la route :
- API keys
- JWT tokens
- mTLS
- etc.

L'Agent Card déclare les `securitySchemes` correspondants à la configuration de la route. C'est informatif pour les clients A2A.

#### Côté connector (A2A Connector)

Le connector gère l'authentification vers le serveur distant :
- Le token/credentials sont stockés dans la config du connector
- Ils sont injectés dans les requêtes HTTP sortantes
- Support du secret vaulting d'Otoroshi pour ne pas stocker les secrets en clair

---

### Partie 6 : Plan d'implémentation

#### Phase 1 : Fondations (MVP)

1. **Modèles A2A** (`a2a/a2a.scala`)
   - Case classes pour Task, Message, Part, AgentCard, Status
   - Sérialization/désérialization JSON
   - Erreurs JSON-RPC A2A

2. **Entité A2AServer** (`entities/a2a.scala`)
   - Case class, Format, DataStore, resource()
   - Registration dans l'extension

3. **Plugin A2AServerPlugin** (`plugins/a2a.scala`)
   - Dispatch JSON-RPC
   - `message/send` synchrone uniquement
   - Mapping A2A ↔ ChatMessage basique (TextPart seulement)
   - Endpoint Agent Card
   - Stockage des Tasks en Redis

4. **Tests unitaires** pour les modèles et le mapping

#### Phase 2 : Streaming et multi-modal

5. **`message/stream`** avec SSE
   - Réutiliser le mécanisme de streaming existant des providers
   - Mapping vers les événements SSE A2A

6. **Support multi-modal**
   - FilePart (images, documents)
   - DataPart (données structurées)

7. **`tasks/get` et `tasks/cancel`**

#### Phase 3 : A2A Connector

8. **Entité A2AConnector** (`entities/a2a.scala`)
   - Case class, Format, DataStore, resource()
   - Registration dans l'extension

9. **Client A2A** (`a2a/client.scala`)
   - Fetch Agent Card
   - Appel `message/send`
   - Cache de l'Agent Card

10. **Intégration dans AgentConfig**
    - Résolution des skills en tool functions
    - Exécution des appels A2A distants

#### Phase 4 : Features avancées

11. **`tasks/list`**
12. **`tasks/resubscribe`**
13. **Push notifications** (webhooks)
14. **A2A Connector en streaming** (`message/stream` côté client)
15. **OAuth2 client credentials** pour les connectors
16. **Multi-tenant** (préfixe `/{tenant}/` dans les URLs)

---

### Partie 7 : Questions ouvertes (à trancher)

#### Q1 : Backend de l'A2A Server

**Options** :
- **(A)** Agent inline uniquement (`AgentConfig` dans la config de l'A2AServer)
- **(B)** Agent inline + référence workflow
- **(C)** Agent inline + workflow + référence route existante

**Recommandation** : commencer avec **(B)** (agent inline + workflow). La référence route est plus complexe et moins utile au début.

#### Q2 : Stockage des Tasks

**Options** :
- **(A)** Redis avec TTL (par défaut 24h), toutes les tâches
- **(B)** Redis avec TTL court pour completed/failed (1h), long pour working/input_required (24h)
- **(C)** En mémoire avec cache borné (ex: max 10000 tâches), pas de persistance

**Recommandation** : **(B)** - Redis avec TTL différencié. Les tâches terminées n'ont pas besoin d'être gardées longtemps.

#### Q3 : Mapping des skills en tools

**Options** :
- **(A)** Un tool function par skill, avec un paramètre `message` texte
- **(B)** Un tool function par skill, avec des paramètres extraits des inputModes/schema du skill
- **(C)** Un seul tool function par connector avec un paramètre `skill_id` + `message`

**Recommandation** : **(A)** pour commencer. Simple et fonctionnel. On peut enrichir plus tard.

#### Q4 : Refresh de l'Agent Card

**Options** :
- **(A)** À chaque sync state (toutes les ~30s)
- **(B)** Cache avec TTL configurable (par défaut 5 min)
- **(C)** Fetch uniquement au démarrage + endpoint pour forcer un refresh

**Recommandation** : **(B)** - cache avec TTL. Bon compromis entre fraîcheur et performance.

#### Q5 : Gestion du multi-turn / contextId

Quand un client A2A envoie un `contextId`, il s'attend à ce que l'agent ait le contexte des échanges précédents.

**Options** :
- **(A)** Stocker l'historique des messages par contextId en Redis, les passer comme input à l'agent
- **(B)** Utiliser le système de `PersistentMemory` existant, avec contextId comme sessionId
- **(C)** Ne pas supporter le multi-turn dans un premier temps (chaque message est indépendant)

**Recommandation** : **(A)** pour le MVP - stocker l'historique en Redis associé au contextId. **(B)** serait bien à terme pour bénéficier de la mémoire sémantique.

#### Q6 : Nommage des tools A2A dans les agents locaux

Quand un A2A Connector expose des skills comme tools, comment les nommer ?

**Options** :
- **(A)** `a2a_{connector_name_slug}_{skill_id}` (ex: `a2a_route_planner_optimize_route`)
- **(B)** `a2a_{connector_id}_{skill_id}` (ex: `a2a_a2a-connector_xxx_optimize_route`)
- **(C)** Configurable dans le connector (mapping skill_id → tool_name)

**Recommandation** : **(A)** avec possibilité de **(C)** via un champ optionnel de mapping.

---

### Partie 8 : Exemples d'utilisation

#### Exposer un agent math tutor en A2A

1. Créer un A2AServer :
```json
{
  "id": "a2a-server_math",
  "name": "Math Tutor A2A",
  "agent_card": {
    "version": "1.0.0",
    "skills": [{"id": "math", "name": "Math Help", "description": "Aide en maths", "tags": ["math"]}]
  },
  "backend": {
    "kind": "agent",
    "agent": {
      "name": "Math Tutor",
      "instructions": ["Tu aides avec les problèmes de maths..."],
      "provider": "provider_xxx"
    }
  }
}
```

2. Créer une route Otoroshi pointant vers ce plugin avec la config `{"ref": "a2a-server_math"}`

3. Un client A2A externe peut maintenant :
   - Découvrir l'agent via `GET https://my-otoroshi/math-tutor/.well-known/agent.json`
   - Envoyer des messages via `POST https://my-otoroshi/math-tutor/` avec JSON-RPC

#### Utiliser un agent A2A distant comme tool

1. Créer un A2AConnector :
```json
{
  "id": "a2a-connector_planner",
  "name": "Route Planner",
  "url": "https://route-planner.example.com",
  "authentication": {"kind": "bearer", "token": "sk-xxx"}
}
```

2. Dans un AgentConfig, référencer le connector :
```json
{
  "name": "Travel Assistant",
  "instructions": ["Tu aides à planifier des voyages..."],
  "provider": "provider_xxx",
  "a2a_connectors": ["a2a-connector_planner"]
}
```

3. L'agent local pourra appeler les skills du Route Planner comme des tools.

#### Combiner A2A Server + Connector pour le chaînage d'agents

Un agent exposé en A2A peut lui-même utiliser des A2A Connectors pour déléguer du travail à d'autres agents A2A, créant ainsi un réseau d'agents interopérables.

```
Client A2A ──▶ Otoroshi (A2A Server: Travel Agent)
                    │
                    ├──▶ A2A Connector → Agent Météo (externe)
                    ├──▶ A2A Connector → Agent Réservation (externe)
                    └──▶ MCP Connector → API Maps (local)
```
