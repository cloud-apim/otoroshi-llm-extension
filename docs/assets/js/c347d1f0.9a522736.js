"use strict";(self.webpackChunkdocumentation=self.webpackChunkdocumentation||[]).push([[1393],{6906:(n,e,t)=>{t.r(e),t.d(e,{assets:()=>a,contentTitle:()=>o,default:()=>m,frontMatter:()=>s,metadata:()=>r,toc:()=>l});var i=t(4848),c=t(8453);t(9229);const s={sidebar_position:10},o="Mailgun",r={id:"function-calling/mcp-connectors-examples/mailgun-mcp",title:"Mailgun",description:"Prerequisites",source:"@site/docs/function-calling/mcp-connectors-examples/mailgun-mcp.mdx",sourceDirName:"function-calling/mcp-connectors-examples",slug:"/function-calling/mcp-connectors-examples/mailgun-mcp",permalink:"/otoroshi-llm-extension/docs/function-calling/mcp-connectors-examples/mailgun-mcp",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:10,frontMatter:{sidebar_position:10},sidebar:"tutorialSidebar",previous:{title:"Grafana",permalink:"/otoroshi-llm-extension/docs/function-calling/mcp-connectors-examples/grafana-mcp"},next:{title:"ScreenshotOne",permalink:"/otoroshi-llm-extension/docs/function-calling/mcp-connectors-examples/screenshotone-mcp"}},a={},l=[{value:"Prerequisites",id:"prerequisites",level:3},{value:"Configuration",id:"configuration",level:3}];function u(n){const e={a:"a",code:"code",h1:"h1",h3:"h3",p:"p",pre:"pre",...(0,c.R)(),...n.components};return(0,i.jsxs)(i.Fragment,{children:[(0,i.jsx)(e.h1,{id:"mailgun",children:"Mailgun"}),"\n",(0,i.jsx)(e.h3,{id:"prerequisites",children:"Prerequisites"}),"\n",(0,i.jsxs)(e.p,{children:["Follow ",(0,i.jsx)(e.a,{href:"https://github.com/grafana/mcp-grafana",children:"this guide"})," to install the MCP server."]}),"\n",(0,i.jsx)(e.pre,{children:(0,i.jsx)(e.code,{className:"language-sh",children:"git clone https://github.com/mailgun/mailgun-mcp-server.git\ncd mailgun-mcp-server\n"})}),"\n",(0,i.jsx)(e.p,{children:"Then, install the dependencies"}),"\n",(0,i.jsx)(e.pre,{children:(0,i.jsx)(e.code,{className:"language-sh",children:"npm install\n"})}),"\n",(0,i.jsx)(e.h3,{id:"configuration",children:"Configuration"}),"\n",(0,i.jsx)(e.pre,{children:(0,i.jsx)(e.code,{className:"language-js",children:'{\n  "command": "node",\n  "args": [\n    "CHANGE/THIS/PATH/TO/mailgun-mcp-server/src/mailgun-mcp.js"\n  ],\n  "env": {\n      "MAILGUN_API_KEY": "${vault://local/MAILGUN_API_KEY}"\n  }\n}\n'})})]})}function m(n={}){const{wrapper:e}={...(0,c.R)(),...n.components};return e?(0,i.jsx)(e,{...n,children:(0,i.jsx)(u,{...n})}):u(n)}},9229:(n,e,t)=>{t(6540),t(4848)},8453:(n,e,t)=>{t.d(e,{R:()=>o,x:()=>r});var i=t(6540);const c={},s=i.createContext(c);function o(n){const e=i.useContext(s);return i.useMemo((function(){return"function"==typeof n?n(e):{...e,...n}}),[e,n])}function r(n){let e;return e=n.disableParentContext?"function"==typeof n.components?n.components(c):n.components||c:o(n.components),i.createElement(s.Provider,{value:e},n.children)}}}]);