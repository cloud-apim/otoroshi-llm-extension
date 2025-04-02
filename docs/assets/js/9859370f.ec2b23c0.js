"use strict";(self.webpackChunkdocumentation=self.webpackChunkdocumentation||[]).push([[8582],{1691:(e,n,o)=>{o.r(n),o.d(n,{assets:()=>r,contentTitle:()=>a,default:()=>d,frontMatter:()=>s,metadata:()=>l,toc:()=>c});var t=o(4848),i=o(8453);o(9229);const s={sidebar_position:4},a="\ud83d\udc40 Expose your LLM Provider",l={id:"llm-gateway/expose",title:"\ud83d\udc40 Expose your LLM Provider",description:"now that your provider is fully setup, you can expose it to your organization. The idea here is to do it through an Otoroshi route",source:"@site/docs/llm-gateway/expose.mdx",sourceDirName:"llm-gateway",slug:"/llm-gateway/expose",permalink:"/otoroshi-llm-extension/docs/llm-gateway/expose",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:4,frontMatter:{sidebar_position:4},sidebar:"tutorialSidebar",previous:{title:"\u2699\ufe0f Setup a new LLM Provider",permalink:"/otoroshi-llm-extension/docs/llm-gateway/setup-provider"},next:{title:"\ud83d\udd10 Secure your LLM provider endpoint",permalink:"/otoroshi-llm-extension/docs/llm-gateway/secure-provider"}},r={},c=[{value:"OpenAI compatible plugins",id:"openai-compatible-plugins",level:2},{value:"LLM OpenAI compatible chat/completions proxy",id:"llm-openai-compatible-chatcompletions-proxy",level:3},{value:"LLM OpenAI compatible models list",id:"llm-openai-compatible-models-list",level:3}];function p(e){const n={a:"a",code:"code",h1:"h1",h2:"h2",h3:"h3",img:"img",p:"p",pre:"pre",...(0,i.R)(),...e.components};return(0,t.jsxs)(t.Fragment,{children:[(0,t.jsx)(n.h1,{id:"-expose-your-llm-provider",children:"\ud83d\udc40 Expose your LLM Provider"}),"\n",(0,t.jsxs)(n.p,{children:["now that your provider is fully setup, you can expose it to your organization. The idea here is to do it through an ",(0,t.jsx)(n.a,{href:"https://maif.github.io/otoroshi/manual/entities/routes.html",children:"Otoroshi route"}),"\nwith a plugin of type ",(0,t.jsx)(n.code,{children:"backend"})," that will handle passing incoming request to the actual LLM provider."]}),"\n",(0,t.jsx)(n.h2,{id:"openai-compatible-plugins",children:"OpenAI compatible plugins"}),"\n",(0,t.jsxs)(n.p,{children:["we provide a set of plugin capable of exposing any supported LLM provider through a ",(0,t.jsx)(n.a,{href:"https://platform.openai.com/docs/api-reference/chat",children:"compatible OpenAI API"}),".\nThis is the official way of doing LLM exposition."]}),"\n",(0,t.jsx)(n.h3,{id:"llm-openai-compatible-chatcompletions-proxy",children:"LLM OpenAI compatible chat/completions proxy"}),"\n",(0,t.jsxs)(n.p,{children:["this plugins is compatible with the ",(0,t.jsx)(n.a,{href:"https://platform.openai.com/docs/api-reference/chat/create",children:"OpenAI chat completion API"}),", also in ",(0,t.jsx)(n.a,{href:"https://platform.openai.com/docs/api-reference/chat-streaming",children:"streaming style"})]}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{src:o(55).A+"",width:"1054",height:"803"})}),"\n",(0,t.jsx)(n.p,{children:"you can directly call it this way:"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-sh",children:'curl https://my-own-llm-endpoint.on.otoroshi/v1/chat/completions \\\n  -H "Content-Type: application/json" \\\n  -H "Authorization: Bearer $OTOROSHI_API_KEY" \\\n  -d \'{\n    "model": "gpt-4o",\n    "messages": [\n      {\n        "role": "user",\n        "content": "Hello how are you!"\n      }\n    ]\n  }\'\n'})}),"\n",(0,t.jsx)(n.p,{children:"with a response looking like"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-js",children:'{\n  "id": "chatcmpl-B9MBs8CjcvOU2jLn4n570S5qMJKcT",\n  "object": "chat.completion",\n  "created": 1741569952,\n  "model": "gpt-4o",\n  "choices": [\n    {\n      "index": 0,\n      "message": {\n        "role": "assistant",\n        "content": "Hello! How can I assist you today?",\n        "refusal": null,\n        "annotations": []\n      },\n      "logprobs": null,\n      "finish_reason": "stop"\n    }\n  ],\n  "usage": {\n    "prompt_tokens": 19,\n    "completion_tokens": 10,\n    "total_tokens": 29\n  }\n}\n'})}),"\n",(0,t.jsx)(n.h3,{id:"llm-openai-compatible-models-list",children:"LLM OpenAI compatible models list"}),"\n",(0,t.jsxs)(n.p,{children:["there is also a plugin to expose the provider models list compatible with the ",(0,t.jsx)(n.a,{href:"https://platform.openai.com/docs/api-reference/models",children:"OpenAI API"})]}),"\n",(0,t.jsx)(n.p,{children:(0,t.jsx)(n.img,{src:o(574).A+"",width:"1059",height:"802"})}),"\n",(0,t.jsx)(n.p,{children:"you can directly call it this way:"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-shell",children:'curl https://my-own-llm-endpoint.on.otoroshi/v1/models \\\n  -H "Authorization: Bearer $OTOROSHI_API_KEY"\n'})}),"\n",(0,t.jsx)(n.p,{children:"with a response looking like"}),"\n",(0,t.jsx)(n.pre,{children:(0,t.jsx)(n.code,{className:"language-js",children:'{\n  "object": "list",\n  "data": [\n    {\n      "id": "o1-mini",\n      "object": "model",\n      "created": 1686935002,\n      "owned_by": "openai"\n    },\n    {\n      "id": "gpt-4",\n      "object": "model",\n      "created": 1686935002,\n      "owned_by": "openai"\n    }\n  ]\n}\n'})})]})}function d(e={}){const{wrapper:n}={...(0,i.R)(),...e.components};return n?(0,t.jsx)(n,{...e,children:(0,t.jsx)(p,{...e})}):p(e)}},9229:(e,n,o)=>{o(6540),o(4848)},55:(e,n,o)=>{o.d(n,{A:()=>t});const t=o.p+"assets/images/openai-chat-completion-6809a051b121976a328181f6e07c2f04.png"},574:(e,n,o)=>{o.d(n,{A:()=>t});const t=o.p+"assets/images/openai-models-cb96400fc59cf69c5afb3f4022bd6fb6.png"},8453:(e,n,o)=>{o.d(n,{R:()=>a,x:()=>l});var t=o(6540);const i={},s=t.createContext(i);function a(e){const n=t.useContext(s);return t.useMemo((function(){return"function"==typeof e?e(n):{...n,...e}}),[n,e])}function l(e){let n;return n=e.disableParentContext?"function"==typeof e.components?e.components(i):e.components||i:a(e.components),t.createElement(s.Provider,{value:n},e.children)}}}]);