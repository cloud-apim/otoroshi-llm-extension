"use strict";(self.webpackChunkdocumentation=self.webpackChunkdocumentation||[]).push([[3278],{9379:(n,e,o)=>{o.r(e),o.d(e,{assets:()=>s,contentTitle:()=>a,default:()=>u,frontMatter:()=>i,metadata:()=>r,toc:()=>l});var t=o(4848),c=o(8453);o(9229);const i={sidebar_position:7},a="Make",r={id:"function-calling/mcp-connectors-examples/make-mcp",title:"Make",description:"Configuration",source:"@site/docs/function-calling/mcp-connectors-examples/make-mcp.mdx",sourceDirName:"function-calling/mcp-connectors-examples",slug:"/function-calling/mcp-connectors-examples/make-mcp",permalink:"/otoroshi-llm-extension/docs/function-calling/mcp-connectors-examples/make-mcp",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:7,frontMatter:{sidebar_position:7},sidebar:"tutorialSidebar",previous:{title:"Gitlab",permalink:"/otoroshi-llm-extension/docs/function-calling/mcp-connectors-examples/gitlab-mcp"},next:{title:"Firecrawl",permalink:"/otoroshi-llm-extension/docs/function-calling/mcp-connectors-examples/firecrawl-mcp"}},s={},l=[{value:"Configuration",id:"configuration",level:3}];function m(n){const e={code:"code",h1:"h1",h3:"h3",pre:"pre",...(0,c.R)(),...n.components};return(0,t.jsxs)(t.Fragment,{children:[(0,t.jsx)(e.h1,{id:"make",children:"Make"}),"\n",(0,t.jsx)(e.h3,{id:"configuration",children:"Configuration"}),"\n",(0,t.jsx)(e.pre,{children:(0,t.jsx)(e.code,{className:"language-js",children:'{\n "command": "npx",\n  "args": [\n    "-y",\n    "@makehq/mcp-server"\n  ],\n  "env": {\n      "MAKE_API_KEY": "${vault://local/MAKE_API_KEY}",\n      "MAKE_ZONE": "${vault://local/MAKE_ZONE}",\n      "MAKE_TEAM": "${vault://local/MAKE_TEAM}"\n  }\n}\n'})})]})}function u(n={}){const{wrapper:e}={...(0,c.R)(),...n.components};return e?(0,t.jsx)(e,{...n,children:(0,t.jsx)(m,{...n})}):m(n)}},9229:(n,e,o)=>{o(6540),o(4848)},8453:(n,e,o)=>{o.d(e,{R:()=>a,x:()=>r});var t=o(6540);const c={},i=t.createContext(c);function a(n){const e=t.useContext(i);return t.useMemo((function(){return"function"==typeof n?n(e):{...e,...n}}),[e,n])}function r(n){let e;return e=n.disableParentContext?"function"==typeof n.components?n.components(c):n.components||c:a(n.components),t.createElement(i.Provider,{value:e},n.children)}}}]);