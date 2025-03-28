"use strict";(self.webpackChunkdocumentation=self.webpackChunkdocumentation||[]).push([[1807],{4510:(e,n,i)=>{i.r(n),i.d(n,{assets:()=>o,contentTitle:()=>t,default:()=>u,frontMatter:()=>s,metadata:()=>l,toc:()=>c});var r=i(4848),a=i(8453);i(9229);const s={sidebar_position:0},t="Overview",l={id:"guardrails/overview",title:"Overview",description:"\ud83d\udea7 Enforcing Usage Limits",source:"@site/docs/guardrails/overview.mdx",sourceDirName:"guardrails",slug:"/guardrails/overview",permalink:"/otoroshi-llm-extension/docs/guardrails/overview",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:0,frontMatter:{sidebar_position:0},sidebar:"tutorialSidebar",previous:{title:"\ud83d\udee1\ufe0f Guardrails",permalink:"/otoroshi-llm-extension/docs/category/\ufe0f-guardrails"},next:{title:"\ud83d\udcca Character count validation",permalink:"/otoroshi-llm-extension/docs/guardrails/characters"}},o={},c=[{value:"\ud83d\udea7 Enforcing Usage Limits",id:"-enforcing-usage-limits",level:2},{value:"Example:",id:"example",level:3},{value:"\ud83d\udd10 Security and Compliance",id:"-security-and-compliance",level:2},{value:"Example:",id:"example-1",level:3},{value:"Enable guardrails",id:"enable-guardrails",level:2},{value:"Available Guardrails",id:"available-guardrails",level:3},{value:"Guardrail configuration",id:"guardrail-configuration",level:3}];function d(e){const n={a:"a",code:"code",h1:"h1",h2:"h2",h3:"h3",img:"img",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,a.R)(),...e.components};return(0,r.jsxs)(r.Fragment,{children:[(0,r.jsx)(n.h1,{id:"overview",children:"Overview"}),"\n",(0,r.jsx)(n.h2,{id:"-enforcing-usage-limits",children:"\ud83d\udea7 Enforcing Usage Limits"}),"\n",(0,r.jsx)(n.p,{children:"Our guardrails help maintain fair usage and system stability by enforcing limits:"}),"\n",(0,r.jsxs)(n.ul,{children:["\n",(0,r.jsxs)(n.li,{children:["\ud83d\udcca ",(0,r.jsx)(n.strong,{children:"Quota thresholds"})," prevent excessive API usage"]}),"\n",(0,r.jsxs)(n.li,{children:["\u23f3 ",(0,r.jsx)(n.strong,{children:"Rate limiting"})," ensures smooth traffic flow"]}),"\n",(0,r.jsxs)(n.li,{children:["\ud83d\udea8 ",(0,r.jsx)(n.strong,{children:"Automatic blocking"})," for anomalous behavior"]}),"\n",(0,r.jsxs)(n.li,{children:["\ud83d\udd04 ",(0,r.jsx)(n.strong,{children:"Webhook support"})," for real-time monitoring and enforcement"]}),"\n"]}),"\n",(0,r.jsx)(n.h3,{id:"example",children:"Example:"}),"\n",(0,r.jsx)(n.p,{children:"If a user exceeds their allocated quota, an automatic webhook can trigger an alert or temporarily restrict access until limits reset."}),"\n",(0,r.jsx)(n.h2,{id:"-security-and-compliance",children:"\ud83d\udd10 Security and Compliance"}),"\n",(0,r.jsx)(n.p,{children:'Optimize the privacy of your organization\'s data and the security of your users through a set of "guardrails" applied to prompts and/or responses. Our system ensures compliance with legal and organizational rules by leveraging:'}),"\n",(0,r.jsxs)(n.ul,{children:["\n",(0,r.jsxs)(n.li,{children:["\ud83d\udd11 ",(0,r.jsx)(n.strong,{children:"Authentication & authorization"})," to prevent unauthorized access"]}),"\n",(0,r.jsxs)(n.li,{children:["\ud83d\udd0d ",(0,r.jsx)(n.strong,{children:"Anomaly detection"})," for real-time threat monitoring"]}),"\n",(0,r.jsxs)(n.li,{children:["\ud83d\udee0\ufe0f ",(0,r.jsx)(n.strong,{children:"Custom security rules"})," for industry-standard compliance"]}),"\n",(0,r.jsxs)(n.li,{children:["\ud83d\udd0e ",(0,r.jsx)(n.strong,{children:"Regex, character count, word count, and sentence count validation"})," to filter inappropriate content"]}),"\n",(0,r.jsxs)(n.li,{children:["\ud83d\udcdc ",(0,r.jsx)(n.strong,{children:"Semantic filtering"})," to detect and prevent harmful or sensitive data sharing"]}),"\n",(0,r.jsxs)(n.li,{children:["\ud83d\udeab ",(0,r.jsx)(n.strong,{children:"No gibberish, no secret leakage, and no Personally Identifiable Information (PIF)"})]}),"\n",(0,r.jsxs)(n.li,{children:["\ud83c\udf10 ",(0,r.jsx)(n.strong,{children:"Language moderation and semantic matching"})," for content accuracy"]}),"\n"]}),"\n",(0,r.jsx)(n.h3,{id:"example-1",children:"Example:"}),"\n",(0,r.jsx)(n.p,{children:"A user prompt containing a credit card number or personal email can be automatically redacted or rejected before reaching the LLM."}),"\n",(0,r.jsx)(n.h2,{id:"enable-guardrails",children:"Enable guardrails"}),"\n",(0,r.jsxs)(n.p,{children:["To enable guardrails, select a LLM provider, then go to ",(0,r.jsx)(n.code,{children:"Guardrails validation"})," section."]}),"\n",(0,r.jsx)(n.p,{children:(0,r.jsx)(n.img,{src:i(6589).A+"",width:"1315",height:"162"})}),"\n",(0,r.jsxs)(n.p,{children:["Click on ",(0,r.jsx)(n.code,{children:"+"})," button to display the list of all available Guardrails."]}),"\n",(0,r.jsx)(n.p,{children:(0,r.jsx)(n.img,{src:i(4822).A+"",width:"1315",height:"162"})}),"\n",(0,r.jsx)(n.p,{children:"You can now choose from the list of available Guardrails."}),"\n",(0,r.jsx)(n.p,{children:(0,r.jsx)(n.img,{src:i(8623).A+"",width:"1634",height:"532"})}),"\n",(0,r.jsx)(n.h3,{id:"available-guardrails",children:"Available Guardrails"}),"\n",(0,r.jsxs)(n.ul,{children:["\n",(0,r.jsx)(n.li,{children:(0,r.jsx)(n.a,{href:"/docs/guardrails/secrets_leakage",children:"Secrets Leakage"})}),"\n"]}),"\n",(0,r.jsx)(n.h3,{id:"guardrail-configuration",children:"Guardrail configuration"}),"\n",(0,r.jsx)(n.pre,{children:(0,r.jsx)(n.code,{className:"language-js",children:'"guardrails": [\n  {\n    "enabled": true,\n    "before": true,\n    "after": true,\n    "id": "secrets_leakage",\n    "config": {\n      "provider": "provider_480ec0b7-bc9e-487f-8376-b9b8111bfe5e",\n      "secrets_leakage_items": [\n        "APIKEYS",\n        "PASSWORDS",\n        "TOKENS",\n        "JWT_TOKENS",\n        "PRIVATE_KEYS",\n        "HUGE_RANDOM_VALUES"\n      ]\n    }\n  }\n]\n'})})]})}function u(e={}){const{wrapper:n}={...(0,a.R)(),...e.components};return n?(0,r.jsx)(n,{...e,children:(0,r.jsx)(d,{...e})}):d(e)}},9229:(e,n,i)=>{i(6540),i(4848)},6589:(e,n,i)=>{i.d(n,{A:()=>r});const r=i.p+"assets/images/guardrails-setup-1-d55c08b2ee4eeb8994af810abb7280ae.png"},4822:(e,n,i)=>{i.d(n,{A:()=>r});const r=i.p+"assets/images/guardrails-setup-2-af38a3bb132a9488b7b3802b93ff31b1.png"},8623:(e,n,i)=>{i.d(n,{A:()=>r});const r=i.p+"assets/images/guardrails-setup-3-08667e8843cce938c54611bd9b310d19.png"},8453:(e,n,i)=>{i.d(n,{R:()=>t,x:()=>l});var r=i(6540);const a={},s=r.createContext(a);function t(e){const n=r.useContext(s);return r.useMemo((function(){return"function"==typeof e?e(n):{...n,...e}}),[n,e])}function l(e){let n;return n=e.disableParentContext?"function"==typeof e.components?e.components(a):e.components||a:t(e.components),r.createElement(s.Provider,{value:n},e.children)}}}]);