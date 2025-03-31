"use strict";(self.webpackChunkdocumentation=self.webpackChunkdocumentation||[]).push([[2347],{3913:(e,n,t)=>{t.r(n),t.d(n,{assets:()=>l,contentTitle:()=>i,default:()=>h,frontMatter:()=>o,metadata:()=>s,toc:()=>d});var r=t(4848),a=t(8453);t(9229);const o={},i="Personal Health information",s={id:"guardrails/personal_health_information",title:"Personal Health information",description:"It's a safeguard that ensures the LLM does not process, store, or share any personal health-related details, protecting user privacy and compliance with regulations.",source:"@site/docs/guardrails/personal_health_information.mdx",sourceDirName:"guardrails",slug:"/guardrails/personal_health_information",permalink:"/otoroshi-llm-extension/docs/guardrails/personal_health_information",draft:!1,unlisted:!1,tags:[],version:"current",frontMatter:{},sidebar:"tutorialSidebar",previous:{title:"Language moderation",permalink:"/otoroshi-llm-extension/docs/guardrails/moderation"},next:{title:"Personal information",permalink:"/otoroshi-llm-extension/docs/guardrails/pif"}},l={},d=[{value:"Guardrail example",id:"guardrail-example",level:3},{value:"Configuration",id:"configuration",level:3}];function c(e){const n={code:"code",h1:"h1",h3:"h3",img:"img",p:"p",pre:"pre",...(0,a.R)(),...e.components};return(0,r.jsxs)(r.Fragment,{children:[(0,r.jsx)(n.h1,{id:"personal-health-information",children:"Personal Health information"}),"\n",(0,r.jsx)(n.p,{children:(0,r.jsx)(n.img,{src:t(8110).A+"",width:"1634",height:"464"})}),"\n",(0,r.jsx)(n.p,{children:"It's a safeguard that ensures the LLM does not process, store, or share any personal health-related details, protecting user privacy and compliance with regulations."}),"\n",(0,r.jsx)(n.p,{children:"It can be applied before the LLM processes the request (blocking medical-related queries) and after to prevent responses that include health data."}),"\n",(0,r.jsx)(n.h3,{id:"guardrail-example",children:"Guardrail example"}),"\n",(0,r.jsx)(n.p,{children:'If a user asks, "I have a medical condition, what medication should I take ?", the LLM will refuse to process the request.'}),"\n",(0,r.jsx)(n.p,{children:"If the LLM generates a response with health advice, it will be filtered before reaching the user."}),"\n",(0,r.jsx)(n.h3,{id:"configuration",children:"Configuration"}),"\n",(0,r.jsx)(n.pre,{children:(0,r.jsx)(n.code,{className:"language-js",children:'"guardrails": [\n  {\n    "enabled": true,\n    "before": true,\n    "after": true,\n    "id": "personal_health_information",\n    "config": {\n      "provider": null\n    }\n  }\n]\n'})})]})}function h(e={}){const{wrapper:n}={...(0,a.R)(),...e.components};return n?(0,r.jsx)(n,{...e,children:(0,r.jsx)(c,{...e})}):c(e)}},9229:(e,n,t)=>{t(6540),t(4848)},8110:(e,n,t)=>{t.d(n,{A:()=>r});const r=t.p+"assets/images/guardrails-personal-health-info-7b111e892c1ce351c78c88db296fdf77.png"},8453:(e,n,t)=>{t.d(n,{R:()=>i,x:()=>s});var r=t(6540);const a={},o=r.createContext(a);function i(e){const n=r.useContext(o);return r.useMemo((function(){return"function"==typeof e?e(n):{...n,...e}}),[n,e])}function s(e){let n;return n=e.disableParentContext?"function"==typeof e.components?e.components(a):e.components||a:i(e.components),r.createElement(o.Provider,{value:n},e.children)}}}]);