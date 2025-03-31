"use strict";(self.webpackChunkdocumentation=self.webpackChunkdocumentation||[]).push([[8582],{1691:(e,n,t)=>{t.r(n),t.d(n,{assets:()=>r,contentTitle:()=>l,default:()=>p,frontMatter:()=>a,metadata:()=>s,toc:()=>c});var o=t(4848),i=t(8453);t(9229);const a={sidebar_position:4},l="\ud83d\udc40 Expose your LLM Provider",s={id:"llm-gateway/expose",title:"\ud83d\udc40 Expose your LLM Provider",description:"now that your provider is fully setup, you can expose it to your organization. The idea here is to do it through an Otoroshi route",source:"@site/docs/llm-gateway/expose.mdx",sourceDirName:"llm-gateway",slug:"/llm-gateway/expose",permalink:"/otoroshi-llm-extension/docs/llm-gateway/expose",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:4,frontMatter:{sidebar_position:4},sidebar:"tutorialSidebar",previous:{title:"\u2699\ufe0f Setup a new LLM Provider",permalink:"/otoroshi-llm-extension/docs/llm-gateway/setup-provider"},next:{title:"\ud83d\udd10 Secure your LLM provider endpoint",permalink:"/otoroshi-llm-extension/docs/llm-gateway/secure-provider"}},r={},c=[{value:"OpenAI compatible plugins",id:"openai-compatible-plugins",level:2},{value:"LLM OpenAI compatible chat/completions proxy",id:"llm-openai-compatible-chatcompletions-proxy",level:3},{value:"LLM OpenAI compatible models list",id:"llm-openai-compatible-models-list",level:3},{value:"Full configuration",id:"full-configuration",level:3}];function d(e){const n={a:"a",code:"code",h1:"h1",h2:"h2",h3:"h3",img:"img",p:"p",pre:"pre",...(0,i.R)(),...e.components};return(0,o.jsxs)(o.Fragment,{children:[(0,o.jsx)(n.h1,{id:"-expose-your-llm-provider",children:"\ud83d\udc40 Expose your LLM Provider"}),"\n",(0,o.jsxs)(n.p,{children:["now that your provider is fully setup, you can expose it to your organization. The idea here is to do it through an ",(0,o.jsx)(n.a,{href:"https://maif.github.io/otoroshi/manual/entities/routes.html",children:"Otoroshi route"}),"\nwith a plugin of type ",(0,o.jsx)(n.code,{children:"backend"})," that will handle passing incoming request to the actual LLM provider."]}),"\n",(0,o.jsx)(n.h2,{id:"openai-compatible-plugins",children:"OpenAI compatible plugins"}),"\n",(0,o.jsxs)(n.p,{children:["we provide a set of plugin capable of exposing any supported LLM provider through a ",(0,o.jsx)(n.a,{href:"https://platform.openai.com/docs/api-reference/chat",children:"compatible OpenAI API"}),".\nThis is the official way of doing LLM exposition."]}),"\n",(0,o.jsx)(n.h3,{id:"llm-openai-compatible-chatcompletions-proxy",children:"LLM OpenAI compatible chat/completions proxy"}),"\n",(0,o.jsxs)(n.p,{children:["this plugins is compatible with the ",(0,o.jsx)(n.a,{href:"https://platform.openai.com/docs/api-reference/chat/create",children:"OpenAI chat completion API"}),", also in ",(0,o.jsx)(n.a,{href:"https://platform.openai.com/docs/api-reference/chat-streaming",children:"streaming style"})]}),"\n",(0,o.jsx)(n.p,{children:(0,o.jsx)(n.img,{src:t(9077).A+"",width:"1054",height:"803"})}),"\n",(0,o.jsx)(n.p,{children:"you can directly call it this way:"}),"\n",(0,o.jsx)(n.pre,{children:(0,o.jsx)(n.code,{className:"language-sh",children:'curl https://my-own-llm-endpoint.on.otoroshi/v1/chat/completions \\\n  -H "Content-Type: application/json" \\\n  -H "Authorization: Bearer $OTOROSHI_API_KEY" \\\n  -d \'{\n    "model": "gpt-4o",\n    "messages": [\n      {\n        "role": "user",\n        "content": "Hello how are you!"\n      }\n    ]\n  }\'\n'})}),"\n",(0,o.jsx)(n.p,{children:"with a response looking like"}),"\n",(0,o.jsx)(n.pre,{children:(0,o.jsx)(n.code,{className:"language-js",children:'{\n  "id": "chatcmpl-B9MBs8CjcvOU2jLn4n570S5qMJKcT",\n  "object": "chat.completion",\n  "created": 1741569952,\n  "model": "gpt-4o",\n  "choices": [\n    {\n      "index": 0,\n      "message": {\n        "role": "assistant",\n        "content": "Hello! How can I assist you today?",\n        "refusal": null,\n        "annotations": []\n      },\n      "logprobs": null,\n      "finish_reason": "stop"\n    }\n  ],\n  "usage": {\n    "prompt_tokens": 19,\n    "completion_tokens": 10,\n    "total_tokens": 29\n  }\n}\n'})}),"\n",(0,o.jsx)(n.h3,{id:"llm-openai-compatible-models-list",children:"LLM OpenAI compatible models list"}),"\n",(0,o.jsxs)(n.p,{children:["there is also a plugin to expose the provider models list compatible with the ",(0,o.jsx)(n.a,{href:"https://platform.openai.com/docs/api-reference/models",children:"OpenAI API"})]}),"\n",(0,o.jsx)(n.p,{children:(0,o.jsx)(n.img,{src:t(5348).A+"",width:"1059",height:"802"})}),"\n",(0,o.jsx)(n.p,{children:"you can directly call it this way:"}),"\n",(0,o.jsx)(n.pre,{children:(0,o.jsx)(n.code,{className:"language-shell",children:'curl https://my-own-llm-endpoint.on.otoroshi/v1/models \\\n  -H "Authorization: Bearer $OTOROSHI_API_KEY"\n'})}),"\n",(0,o.jsx)(n.p,{children:"with a response looking like"}),"\n",(0,o.jsx)(n.pre,{children:(0,o.jsx)(n.code,{className:"language-js",children:'{\n  "object": "list",\n  "data": [\n    {\n      "id": "o1-mini",\n      "object": "model",\n      "created": 1686935002,\n      "owned_by": "openai"\n    },\n    {\n      "id": "gpt-4",\n      "object": "model",\n      "created": 1686935002,\n      "owned_by": "openai"\n    }\n  ]\n}\n'})}),"\n",(0,o.jsx)(n.p,{children:"We can see in our API Key that the quotas for our API keys has been decreased due our request"}),"\n",(0,o.jsx)(n.p,{children:(0,o.jsx)(n.img,{src:t(9220).A+"",width:"1626",height:"325"})}),"\n",(0,o.jsx)(n.h3,{id:"full-configuration",children:"Full configuration"}),"\n",(0,o.jsx)(n.p,{children:"Configuration for the API Key"}),"\n",(0,o.jsx)(n.pre,{children:(0,o.jsx)(n.code,{className:"language-js",children:'{\n  "_loc": {\n    "tenant": "default",\n    "teams": [\n      "default"\n    ]\n  },\n  "clientId": "urjyypqbif8gr8wx",\n  "clientSecret": "5icbe4o94dbwere7nupoecfmgr5kbo3cgpp0iv94gf1kxak04ocfw9tvu4f9nrmd",\n  "clientName": "Hilario Flatley\'s api-key",\n  "description": "",\n  "authorizedGroup": null,\n  "authorizedEntities": [\n    "route_route_ca396e0a1-2bc6-41c5-a682-2a95c4ed0c24"\n  ],\n  "authorizations": [\n    {\n      "kind": "route",\n      "id": "route_ca396e0a1-2bc6-41c5-a682-2a95c4ed0c24"\n    }\n  ],\n  "enabled": true,\n  "readOnly": false,\n  "allowClientIdOnly": false,\n  "throttlingQuota": 10000000,\n  "dailyQuota": 10000000,\n  "monthlyQuota": 10000000,\n  "constrainedServicesOnly": false,\n  "restrictions": {\n    "enabled": false,\n    "allowLast": true,\n    "allowed": [],\n    "forbidden": [],\n    "notFound": []\n  },\n  "rotation": {\n    "enabled": false,\n    "rotationEvery": 744,\n    "gracePeriod": 168,\n    "nextSecret": null\n  },\n  "validUntil": null,\n  "tags": [],\n  "metadata": {\n    "created_at": "2025-03-31T12:42:40.194+02:00"\n  },\n  "bearer": "otoapk_urjyypqbif8gr8wx_701cd2e99c0e2707e44bf6a7bb75518c90700a867d6c22f624020b22434cf7f9",\n  "kind": "apim.otoroshi.io/ApiKey"\n}\n'})}),"\n",(0,o.jsx)(n.p,{children:"Full route configuration with API Keys plugin :"}),"\n",(0,o.jsx)(n.pre,{children:(0,o.jsx)(n.code,{className:"language-js",children:'{\n  "_loc": {\n    "tenant": "default",\n    "teams": [\n      "default"\n    ]\n  },\n  "id": "route_ca396e0a1-2bc6-41c5-a682-2a95c4ed0c24",\n  "name": "OpenAI Demo",\n  "description": "OpenAI Demo",\n  "tags": [],\n  "metadata": {},\n  "enabled": true,\n  "debug_flow": false,\n  "export_reporting": false,\n  "capture": false,\n  "groups": [\n    "default"\n  ],\n  "bound_listeners": [],\n  "frontend": {\n    "domains": [\n      "openai-demo.oto.tools"\n    ],\n    "strip_path": true,\n    "exact": false,\n    "headers": {},\n    "query": {},\n    "methods": []\n  },\n  "backend": {\n    "targets": [\n      {\n        "id": "target_1",\n        "hostname": "request.otoroshi.io",\n        "port": 443,\n        "tls": true,\n        "weight": 1,\n        "predicate": {\n          "type": "AlwaysMatch"\n        },\n        "protocol": "HTTP/1.1",\n        "ip_address": null,\n        "tls_config": {\n          "certs": [],\n          "trusted_certs": [],\n          "enabled": false,\n          "loose": false,\n          "trust_all": false\n        }\n      }\n    ],\n    "root": "/",\n    "rewrite": false,\n    "load_balancing": {\n      "type": "RoundRobin"\n    },\n    "client": {\n      "retries": 1,\n      "max_errors": 20,\n      "retry_initial_delay": 50,\n      "backoff_factor": 2,\n      "call_timeout": 30000,\n      "call_and_stream_timeout": 120000,\n      "connection_timeout": 10000,\n      "idle_timeout": 60000,\n      "global_timeout": 30000,\n      "sample_interval": 2000,\n      "proxy": {},\n      "custom_timeouts": [],\n      "cache_connection_settings": {\n        "enabled": false,\n        "queue_size": 2048\n      }\n    },\n    "health_check": {\n      "enabled": false,\n      "url": "",\n      "timeout": 5000,\n      "healthyStatuses": [],\n      "unhealthyStatuses": []\n    }\n  },\n  "backend_ref": null,\n  "plugins": [\n    {\n      "enabled": true,\n      "debug": false,\n      "plugin": "cp:otoroshi.next.plugins.OverrideHost",\n      "include": [],\n      "exclude": [],\n      "config": {},\n      "bound_listeners": [],\n      "plugin_index": {\n        "transform_request": 0\n      }\n    },\n    {\n      "enabled": true,\n      "debug": false,\n      "plugin": "cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProxy",\n      "include": [],\n      "exclude": [],\n      "config": {\n        "refs": [\n          "provider_7e618efb-9c2c-42ed-870a-58b1ec3c6264"\n        ]\n      },\n      "bound_listeners": [],\n      "plugin_index": {}\n    },\n    {\n      "enabled": true,\n      "debug": false,\n      "plugin": "cp:otoroshi.next.plugins.ApikeyCalls",\n      "include": [],\n      "exclude": [],\n      "config": {\n        "extractors": {\n          "basic": {\n            "enabled": true,\n            "header_name": null,\n            "query_name": null\n          },\n          "custom_headers": {\n            "enabled": true,\n            "client_id_header_name": null,\n            "client_secret_header_name": null\n          },\n          "client_id": {\n            "enabled": true,\n            "header_name": null,\n            "query_name": null\n          },\n          "jwt": {\n            "enabled": true,\n            "secret_signed": true,\n            "keypair_signed": true,\n            "include_request_attrs": false,\n            "max_jwt_lifespan_sec": null,\n            "header_name": null,\n            "query_name": null,\n            "cookie_name": null\n          }\n        },\n        "routing": {\n          "enabled": false\n        },\n        "validate": true,\n        "mandatory": true,\n        "pass_with_user": false,\n        "wipe_backend_request": true,\n        "update_quotas": true\n      },\n      "bound_listeners": [],\n      "plugin_index": {\n        "validate_access": 0,\n        "transform_request": 1,\n        "match_route": 0\n      }\n    }\n  ],\n  "kind": "proxy.otoroshi.io/Route"\n}\n'})})]})}function p(e={}){const{wrapper:n}={...(0,i.R)(),...e.components};return n?(0,o.jsx)(n,{...e,children:(0,o.jsx)(d,{...e})}):d(e)}},9229:(e,n,t)=>{t(6540),t(4848)},9220:(e,n,t)=>{t.d(n,{A:()=>o});const o=t.p+"assets/images/apikeys-quota-consumption-81f8d904ef8e41174452d637a27b0f2b.png"},9077:(e,n,t)=>{t.d(n,{A:()=>o});const o=t.p+"assets/images/openai-chat-completion-6809a051b121976a328181f6e07c2f04.png"},5348:(e,n,t)=>{t.d(n,{A:()=>o});const o=t.p+"assets/images/openai-models-cb96400fc59cf69c5afb3f4022bd6fb6.png"},8453:(e,n,t)=>{t.d(n,{R:()=>l,x:()=>s});var o=t(6540);const i={},a=o.createContext(i);function l(e){const n=o.useContext(a);return o.useMemo((function(){return"function"==typeof e?e(n):{...n,...e}}),[n,e])}function s(e){let n;return n=e.disableParentContext?"function"==typeof e.components?e.components(i):e.components||i:l(e.components),o.createElement(a.Provider,{value:n},e.children)}}}]);