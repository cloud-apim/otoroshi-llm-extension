"use strict";(self.webpackChunkdocumentation=self.webpackChunkdocumentation||[]).push([[9967],{6594:(n,e,r)=>{r.r(e),r.d(e,{assets:()=>c,contentTitle:()=>a,default:()=>u,frontMatter:()=>i,metadata:()=>s,toc:()=>l});var t=r(4848),o=r(8453);r(9229);const i={},a="Personal information",s={id:"guardrails/pif",title:"Personal information",description:"Configuration",source:"@site/docs/guardrails/pif.mdx",sourceDirName:"guardrails",slug:"/guardrails/pif",permalink:"/otoroshi-llm-extension/docs/guardrails/pif",draft:!1,unlisted:!1,tags:[],version:"current",frontMatter:{},sidebar:"tutorialSidebar",previous:{title:"Personal Health information",permalink:"/otoroshi-llm-extension/docs/guardrails/personal_health_information"},next:{title:"Personal information",permalink:"/otoroshi-llm-extension/docs/guardrails/prompt_injection"}},c={},l=[{value:"Configuration",id:"configuration",level:3}];function d(n){const e={code:"code",h1:"h1",h3:"h3",img:"img",p:"p",pre:"pre",...(0,o.R)(),...n.components};return(0,t.jsxs)(t.Fragment,{children:[(0,t.jsx)(e.h1,{id:"personal-information",children:"Personal information"}),"\n",(0,t.jsx)(e.p,{children:(0,t.jsx)(e.img,{src:r(3905).A+"",width:"1634",height:"826"})}),"\n",(0,t.jsx)(e.h3,{id:"configuration",children:"Configuration"}),"\n",(0,t.jsx)(e.pre,{children:(0,t.jsx)(e.code,{className:"language-js",children:'"guardrails": [\n  {\n    "enabled": true,\n    "before": true,\n    "after": true,\n    "id": "pif",\n    "config": {\n      "provider": "provider_28ccfcf1-85b0-4e8d-b505-6793d07c0ee3",\n      "pif_items": [\n        "EMAIL_ADDRESS",\n        "PHONE_NUMBER",\n        "LOCATION_ADDRESS",\n        "NAME",\n        "IP_ADDRESS",\n        "CREDIT_CARD",\n        "SSN"\n      ]\n    }\n  }\n]\n'})})]})}function u(n={}){const{wrapper:e}={...(0,o.R)(),...n.components};return e?(0,t.jsx)(e,{...n,children:(0,t.jsx)(d,{...n})}):d(n)}},9229:(n,e,r)=>{r(6540),r(4848)},3905:(n,e,r)=>{r.d(e,{A:()=>t});const t=r.p+"assets/images/guardrails-pif-3761375f91678acc6ea983cae284f057.png"},8453:(n,e,r)=>{r.d(e,{R:()=>a,x:()=>s});var t=r(6540);const o={},i=t.createContext(o);function a(n){const e=t.useContext(i);return t.useMemo((function(){return"function"==typeof n?n(e):{...e,...n}}),[e,n])}function s(n){let e;return e=n.disableParentContext?"function"==typeof n.components?n.components(o):n.components||o:a(n.components),t.createElement(i.Provider,{value:e},n.children)}}}]);