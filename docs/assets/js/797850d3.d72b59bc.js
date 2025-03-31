"use strict";(self.webpackChunkdocumentation=self.webpackChunkdocumentation||[]).push([[6690],{8628:(e,r,n)=>{n.r(r),n.d(r,{assets:()=>l,contentTitle:()=>a,default:()=>u,frontMatter:()=>s,metadata:()=>o,toc:()=>d});var t=n(4848),i=n(8453);n(9229);const s={},a="Prompt contains gibberish guardrail",o={id:"guardrails/gibberish",title:"Prompt contains gibberish guardrail",description:"This Guardrail acts like a filter that detects and manages inputs that are nonsensical, random, or meaningless, preventing the AI from generating irrelevant or low-quality responses.",source:"@site/docs/guardrails/gibberish.mdx",sourceDirName:"guardrails",slug:"/guardrails/gibberish",permalink:"/otoroshi-llm-extension/docs/guardrails/gibberish",draft:!1,unlisted:!1,tags:[],version:"current",frontMatter:{},sidebar:"tutorialSidebar",previous:{title:"Prompt contains gender bias guardrail",permalink:"/otoroshi-llm-extension/docs/guardrails/gender_bias"},next:{title:"LLM guardrails",permalink:"/otoroshi-llm-extension/docs/guardrails/llm"}},l={},d=[{value:"Guardrail example",id:"guardrail-example",level:3}];function c(e){const r={h1:"h1",h3:"h3",img:"img",p:"p",...(0,i.R)(),...e.components};return(0,t.jsxs)(t.Fragment,{children:[(0,t.jsx)(r.h1,{id:"prompt-contains-gibberish-guardrail",children:"Prompt contains gibberish guardrail"}),"\n",(0,t.jsx)(r.p,{children:(0,t.jsx)(r.img,{src:n(807).A+"",width:"1634",height:"446"})}),"\n",(0,t.jsx)(r.p,{children:"This Guardrail acts like a filter that detects and manages inputs that are nonsensical, random, or meaningless, preventing the AI from generating irrelevant or low-quality responses."}),"\n",(0,t.jsx)(r.p,{children:"This can be applied before the LLM processes the input (blocking nonsense prompts) and after to filter out meaningless responses."}),"\n",(0,t.jsx)(r.h3,{id:"guardrail-example",children:"Guardrail example"}),"\n",(0,t.jsx)(r.p,{children:'If a user inputs, "asdkjfhasd lkj3r2lkkj!!", the LLM will recognize it as gibberish and block the request.'}),"\n",(0,t.jsx)(r.p,{children:"If the LLM generates a nonsensical response, it will be flagged and removed."})]})}function u(e={}){const{wrapper:r}={...(0,i.R)(),...e.components};return r?(0,t.jsx)(r,{...e,children:(0,t.jsx)(c,{...e})}):c(e)}},9229:(e,r,n)=>{n(6540),n(4848)},807:(e,r,n)=>{n.d(r,{A:()=>t});const t=n.p+"assets/images/guardrails-no-gibberish-257a1117b0199a4cf7831d5da2eb8357.png"},8453:(e,r,n)=>{n.d(r,{R:()=>a,x:()=>o});var t=n(6540);const i={},s=t.createContext(i);function a(e){const r=t.useContext(s);return t.useMemo((function(){return"function"==typeof e?e(r):{...r,...e}}),[r,e])}function o(e){let r;return r=e.disableParentContext?"function"==typeof e.components?e.components(i):e.components||i:a(e.components),t.createElement(s.Provider,{value:r},e.children)}}}]);