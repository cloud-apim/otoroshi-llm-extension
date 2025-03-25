"use strict";(self.webpackChunkdocumentation=self.webpackChunkdocumentation||[]).push([[474],{7109:(e,i,n)=>{n.r(i),n.d(i,{assets:()=>c,contentTitle:()=>a,default:()=>h,frontMatter:()=>o,metadata:()=>r,toc:()=>l});var t=n(4848),s=n(8453);n(9229);const o={sidebar_position:7},a="Cost optimization",r={id:"cost-optimization",title:"Cost optimization",description:"Quotas",source:"@site/docs/cost-optimization.mdx",sourceDirName:".",slug:"/cost-optimization",permalink:"/otoroshi-llm-extension/docs/cost-optimization",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:7,frontMatter:{sidebar_position:7},sidebar:"tutorialSidebar",previous:{title:"Observability & Reporting",permalink:"/otoroshi-llm-extension/docs/observability-reporting"},next:{title:"\ud83d\udee1\ufe0f Guardrails",permalink:"/otoroshi-llm-extension/docs/guardrails"}},c={},l=[{value:"Quotas",id:"quotas",level:2},{value:"\u26a1 Cache: Fast and Efficient",id:"-cache-fast-and-efficient",level:2},{value:"Simple cache",id:"simple-cache",level:2},{value:"Semantic cache",id:"semantic-cache",level:2},{value:"\ud83d\udcca Reporting",id:"-reporting",level:2},{value:"Tokens rate limiting",id:"tokens-rate-limiting",level:2}];function d(e){const i={h1:"h1",h2:"h2",img:"img",li:"li",p:"p",strong:"strong",ul:"ul",...(0,s.R)(),...e.components};return(0,t.jsxs)(t.Fragment,{children:[(0,t.jsx)(i.h1,{id:"cost-optimization",children:"Cost optimization"}),"\n",(0,t.jsx)(i.p,{children:(0,t.jsx)(i.img,{src:n(8577).A+"",width:"741",height:"597"})}),"\n",(0,t.jsx)(i.h2,{id:"quotas",children:"Quotas"}),"\n",(0,t.jsx)(i.p,{children:"Our highly flexible quota management system ensures optimal resource allocation."}),"\n",(0,t.jsx)(i.p,{children:"With Otoroshi LLM extension, you can:"}),"\n",(0,t.jsxs)(i.ul,{children:["\n",(0,t.jsx)(i.li,{children:"\ud83d\udccf Define quotas based on any attribute in the HTTP request"}),"\n",(0,t.jsx)(i.li,{children:"\ud83c\udff7\ufe0f Group quotas by users, API keys, or any custom identifier"}),"\n",(0,t.jsx)(i.li,{children:"\u23f3 Set time windows (per second, minute, hour, or custom intervals)"}),"\n"]}),"\n",(0,t.jsx)(i.p,{children:"This enables precise control over token consumption and prevents overuse, ensuring smooth operation for all users."}),"\n",(0,t.jsx)(i.h2,{id:"-cache-fast-and-efficient",children:"\u26a1 Cache: Fast and Efficient"}),"\n",(0,t.jsx)(i.p,{children:"We provide a simple yet powerful caching mechanism :"}),"\n",(0,t.jsxs)(i.ul,{children:["\n",(0,t.jsxs)(i.li,{children:["\u2705 ",(0,t.jsx)(i.strong,{children:"Semantic caching"})," for intelligent retrieval"]}),"\n",(0,t.jsxs)(i.li,{children:["\ud83d\udd0d ",(0,t.jsx)(i.strong,{children:"Embeddings-based storage"})," for high-relevance matches"]}),"\n",(0,t.jsxs)(i.li,{children:["\ud83d\uddc3\ufe0f ",(0,t.jsx)(i.strong,{children:"Vector search database"})," ensures ultra-fast lookups"]}),"\n"]}),"\n",(0,t.jsx)(i.p,{children:"This results in reduced latency and improved response times for frequently accessed data."}),"\n",(0,t.jsx)(i.p,{children:"You can activate caching on any provider"}),"\n",(0,t.jsx)(i.h2,{id:"simple-cache",children:"Simple cache"}),"\n",(0,t.jsx)(i.p,{children:"simple cache works on prompts word per word"}),"\n",(0,t.jsx)(i.p,{children:(0,t.jsx)(i.img,{alt:"simple-cache",src:n(4578).A+"",width:"444",height:"250"})}),"\n",(0,t.jsx)(i.h2,{id:"semantic-cache",children:"Semantic cache"}),"\n",(0,t.jsx)(i.p,{children:"semantic cache uses an embedding datastore to find prompt with the same semantic"}),"\n",(0,t.jsx)(i.p,{children:(0,t.jsx)(i.img,{alt:"semantic-cache",src:n(4420).A+"",width:"484",height:"338"})}),"\n",(0,t.jsx)(i.h2,{id:"-reporting",children:"\ud83d\udcca Reporting"}),"\n",(0,t.jsx)(i.p,{children:"You can use the audit events generated by your LLM usage to make some reporting dashboard and follow your metrics in live."}),"\n",(0,t.jsx)(i.h2,{id:"tokens-rate-limiting",children:"Tokens rate limiting"}),"\n",(0,t.jsx)(i.p,{children:(0,t.jsx)(i.img,{src:n(7242).A+"",width:"507",height:"490"})})]})}function h(e={}){const{wrapper:i}={...(0,s.R)(),...e.components};return i?(0,t.jsx)(i,{...e,children:(0,t.jsx)(d,{...e})}):d(e)}},9229:(e,i,n)=>{n(6540),n(4848)},8577:(e,i,n)=>{n.d(i,{A:()=>t});const t=n.p+"assets/images/cost-optimization-e6823ba498d8f2f40c1f55b9c6e24f6a.svg"},4420:(e,i,n)=>{n.d(i,{A:()=>t});const t=n.p+"assets/images/semantic-cache-cdba2beb5d457b1822ac7075ec16b028.png"},4578:(e,i,n)=>{n.d(i,{A:()=>t});const t=n.p+"assets/images/simple-cache-39608441df447882d66dc53b97b587bb.png"},7242:(e,i,n)=>{n.d(i,{A:()=>t});const t=n.p+"assets/images/tokens-rate-limit-cost-optimization-8b38c78dd3562da372355fea1d6590cf.png"},8453:(e,i,n)=>{n.d(i,{R:()=>a,x:()=>r});var t=n(6540);const s={},o=t.createContext(s);function a(e){const i=t.useContext(o);return t.useMemo((function(){return"function"==typeof e?e(i):{...i,...e}}),[i,e])}function r(e){let i;return i=e.disableParentContext?"function"==typeof e.components?e.components(s):e.components||s:a(e.components),t.createElement(o.Provider,{value:i},e.children)}}}]);