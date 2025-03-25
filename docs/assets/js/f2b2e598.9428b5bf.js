"use strict";(self.webpackChunkdocumentation=self.webpackChunkdocumentation||[]).push([[654],{8307:(e,t,r)=>{r.r(t),r.d(t,{assets:()=>c,contentTitle:()=>i,default:()=>d,frontMatter:()=>s,metadata:()=>a,toc:()=>l});var n=r(4848),o=r(8453);r(9229);const s={sidebar_position:4},i="Egress Proxy Pattern",a={id:"egress-proxy-pattern",title:"Egress Proxy Pattern",description:"The Egress Proxy Pattern allows organizations to securely route outgoing traffic from their infrastructure to external LLM providers while maintaining strict control over network access, security policies, and compliance requirements.",source:"@site/docs/egress-proxy-pattern.mdx",sourceDirName:".",slug:"/egress-proxy-pattern",permalink:"/otoroshi-llm-extension/docs/egress-proxy-pattern",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:4,frontMatter:{sidebar_position:4},sidebar:"tutorialSidebar",previous:{title:"Set Up a LLM Provider",permalink:"/otoroshi-llm-extension/docs/providers/setup-provider"},next:{title:"Resilience",permalink:"/otoroshi-llm-extension/docs/resilience"}},c={},l=[];function p(e){const t={h1:"h1",img:"img",p:"p",...(0,o.R)(),...e.components};return(0,n.jsxs)(n.Fragment,{children:[(0,n.jsx)(t.h1,{id:"egress-proxy-pattern",children:"Egress Proxy Pattern"}),"\n",(0,n.jsx)(t.p,{children:(0,n.jsx)(t.img,{src:r(6450).A+"",width:"1012",height:"381"})}),"\n",(0,n.jsx)(t.p,{children:"The Egress Proxy Pattern allows organizations to securely route outgoing traffic from their infrastructure to external LLM providers while maintaining strict control over network access, security policies, and compliance requirements."}),"\n",(0,n.jsx)(t.p,{children:"Key Benefits"}),"\n",(0,n.jsx)(t.p,{children:"Security & Compliance: Enforce security policies and restrict outbound traffic to authorized destinations."}),"\n",(0,n.jsx)(t.p,{children:"Logging & Auditing: Monitor all outgoing requests for auditing and compliance."}),"\n",(0,n.jsx)(t.p,{children:"Performance Optimization: Cache responses where applicable to reduce redundant network calls and improve speed."}),"\n",(0,n.jsx)(t.p,{children:"Centralized Control: Manage API keys, access control, and provider selection centrally."}),"\n",(0,n.jsx)(t.p,{children:"How It Works"}),"\n",(0,n.jsx)(t.p,{children:"All outgoing requests to LLM providers pass through an Egress Proxy."}),"\n",(0,n.jsx)(t.p,{children:"The proxy applies authentication, authorization, and logging before forwarding the request."}),"\n",(0,n.jsx)(t.p,{children:"Optionally, caching can be enabled for frequently accessed responses."}),"\n",(0,n.jsx)(t.p,{children:"The proxy integrates with Otoroshi\u2019s key vault to manage API keys securely."})]})}function d(e={}){const{wrapper:t}={...(0,o.R)(),...e.components};return t?(0,n.jsx)(t,{...e,children:(0,n.jsx)(p,{...e})}):p(e)}},9229:(e,t,r)=>{r(6540),r(4848)},6450:(e,t,r)=>{r.d(t,{A:()=>n});const n=r.p+"assets/images/egress-proxy-pattern-ff0b2ea724e656b35835288b9435347d.png"},8453:(e,t,r)=>{r.d(t,{R:()=>i,x:()=>a});var n=r(6540);const o={},s=n.createContext(o);function i(e){const t=n.useContext(s);return n.useMemo((function(){return"function"==typeof e?e(t):{...t,...e}}),[t,e])}function a(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(o):e.components||o:i(e.components),n.createElement(s.Provider,{value:t},e.children)}}}]);