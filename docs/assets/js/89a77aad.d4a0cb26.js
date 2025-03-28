"use strict";(self.webpackChunkdocumentation=self.webpackChunkdocumentation||[]).push([[5245],{6291:(n,e,r)=>{r.r(e),r.d(e,{assets:()=>c,contentTitle:()=>a,default:()=>u,frontMatter:()=>o,metadata:()=>s,toc:()=>l});var t=r(4848),i=r(8453);r(9229);const o={},a="Prompt contains gibberish guardrail",s={id:"guardrails/moderation",title:"Prompt contains gibberish guardrail",description:"Configuration",source:"@site/docs/guardrails/moderation.mdx",sourceDirName:"guardrails",slug:"/guardrails/moderation",permalink:"/otoroshi-llm-extension/docs/guardrails/moderation",draft:!1,unlisted:!1,tags:[],version:"current",frontMatter:{},sidebar:"tutorialSidebar",previous:{title:"LLM guardrails",permalink:"/otoroshi-llm-extension/docs/guardrails/llm"},next:{title:"Personal Health information",permalink:"/otoroshi-llm-extension/docs/guardrails/personal_health_information"}},c={},l=[{value:"Configuration",id:"configuration",level:3}];function d(n){const e={code:"code",h1:"h1",h3:"h3",img:"img",p:"p",pre:"pre",...(0,i.R)(),...n.components};return(0,t.jsxs)(t.Fragment,{children:[(0,t.jsx)(e.h1,{id:"prompt-contains-gibberish-guardrail",children:"Prompt contains gibberish guardrail"}),"\n",(0,t.jsx)(e.p,{children:(0,t.jsx)(e.img,{src:r(1255).A+"",width:"1634",height:"886"})}),"\n",(0,t.jsx)(e.h3,{id:"configuration",children:"Configuration"}),"\n",(0,t.jsx)(e.pre,{children:(0,t.jsx)(e.code,{className:"language-js",children:'"guardrails": [\n  {\n    "enabled": true,\n    "before": true,\n    "after": true,\n    "id": "moderation",\n    "config": {\n      "provider": "provider_28ccfcf1-85b0-4e8d-b505-6793d07c0ee3",\n      "moderation_items": [\n        "hate",\n        "hate/threatening",\n        "harassment",\n        "harassment/threatening",\n        "self-harm",\n        "self-harm/intent",\n        "self-harm/instructions",\n        "sexual",\n        "sexual/minors",\n        "violence",\n        "violence/graphic"\n      ]\n    }\n  }\n]\n'})})]})}function u(n={}){const{wrapper:e}={...(0,i.R)(),...n.components};return e?(0,t.jsx)(e,{...n,children:(0,t.jsx)(d,{...n})}):d(n)}},9229:(n,e,r)=>{r(6540),r(4848)},1255:(n,e,r)=>{r.d(e,{A:()=>t});const t=r.p+"assets/images/guardrails-language-moderation-76d99b7ec442e4672fa0f49ea8c4e4af.png"},8453:(n,e,r)=>{r.d(e,{R:()=>a,x:()=>s});var t=r(6540);const i={},o=t.createContext(i);function a(n){const e=t.useContext(o);return t.useMemo((function(){return"function"==typeof n?n(e):{...e,...n}}),[e,n])}function s(n){let e;return e=n.disableParentContext?"function"==typeof n.components?n.components(i):n.components||i:a(n.components),t.createElement(o.Provider,{value:e},n.children)}}}]);