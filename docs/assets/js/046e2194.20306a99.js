"use strict";(self.webpackChunkdocumentation=self.webpackChunkdocumentation||[]).push([[3568],{7607:(o,e,t)=>{t.r(e),t.d(e,{assets:()=>a,contentTitle:()=>r,default:()=>c,frontMatter:()=>i,metadata:()=>l,toc:()=>h});var n=t(4848),s=t(8453);t(9229);const i={sidebar_position:2},r="Install",l={id:"install",title:"Install",description:"Download Otoroshi",source:"@site/docs/install.mdx",sourceDirName:".",slug:"/install",permalink:"/otoroshi-llm-extension/docs/install",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:2,frontMatter:{sidebar_position:2},sidebar:"tutorialSidebar",previous:{title:"Overview",permalink:"/otoroshi-llm-extension/docs/overview"},next:{title:"LLM Gateway",permalink:"/otoroshi-llm-extension/docs/category/llm-gateway"}},a={},h=[{value:"Download Otoroshi",id:"download-otoroshi",level:2},{value:"Download the LLM Extension",id:"download-the-llm-extension",level:2},{value:"Run Otoroshi with the LLM Extension",id:"run-otoroshi-with-the-llm-extension",level:2}];function d(o){const e={a:"a",code:"code",h1:"h1",h2:"h2",img:"img",p:"p",pre:"pre",strong:"strong",...(0,s.R)(),...o.components};return(0,n.jsxs)(n.Fragment,{children:[(0,n.jsx)(e.h1,{id:"install",children:"Install"}),"\n",(0,n.jsx)(e.h2,{id:"download-otoroshi",children:"Download Otoroshi"}),"\n",(0,n.jsx)(e.p,{children:(0,n.jsxs)(e.a,{href:"https://github.com/MAIF/otoroshi/releases/download/v17.0.0/otoroshi.jar",children:[" ",(0,n.jsx)(e.img,{src:"https://img.shields.io/github/release/MAIF/otoroshi.svg",alt:"Download"})," "]})}),"\n",(0,n.jsx)(e.p,{children:"First, download the Otoroshi jar file:"}),"\n",(0,n.jsx)(e.pre,{children:(0,n.jsx)(e.code,{className:"language-sh",children:"curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v17.0.0/otoroshi.jar'\n"})}),"\n",(0,n.jsx)(e.h2,{id:"download-the-llm-extension",children:"Download the LLM Extension"}),"\n",(0,n.jsx)(e.p,{children:(0,n.jsxs)(e.a,{href:"https://github.com/cloud-apim/otoroshi-llm-extension/releases/download/0.0.43/otoroshi-llm-extension_2.12-0.0.43.jar",children:[" ",(0,n.jsx)(e.img,{src:"https://img.shields.io/github/release/cloud-apim/otoroshi-llm-extension.svg",alt:"Download the latest release of the Otoroshi LLM Extension"})," "]})}),"\n",(0,n.jsxs)(e.p,{children:["Download the latest release of the ",(0,n.jsx)(e.code,{children:"Otoroshi LLM Extension"})," from ",(0,n.jsx)(e.a,{href:"https://github.com/cloud-apim/otoroshi-llm-extension/releases/latest",children:"here"}),"."]}),"\n",(0,n.jsx)(e.pre,{children:(0,n.jsx)(e.code,{className:"language-sh",children:"curl -L -o otoroshi-llm-extension.jar 'https://github.com/cloud-apim/otoroshi-llm-extension/releases/download/0.0.43/otoroshi-llm-extension_2.12-0.0.43.jar'\n"})}),"\n",(0,n.jsx)(e.h2,{id:"run-otoroshi-with-the-llm-extension",children:"Run Otoroshi with the LLM Extension"}),"\n",(0,n.jsxs)(e.p,{children:[(0,n.jsx)(e.strong,{children:"WARNING"}),": the Otoroshi LLM Extension only run on ",(0,n.jsx)(e.strong,{children:"JDK 17"})," and above"]}),"\n",(0,n.jsx)(e.p,{children:"Run Otoroshi with the LLM extension by executing the following command :"}),"\n",(0,n.jsx)(e.pre,{children:(0,n.jsx)(e.code,{className:"language-sh",children:'java -cp "./otoroshi-llm-extension.jar:./otoroshi.jar" -Dotoroshi.adminLogin=admin -Dotoroshi.adminPassword=password -Dotoroshi.storage=file play.core.server.ProdServerStart\n'})}),"\n",(0,n.jsx)(e.p,{children:"This will start the Otoroshi API Gateway."}),"\n",(0,n.jsxs)(e.p,{children:["You can access the Otoroshi UI by opening ",(0,n.jsx)(e.a,{href:"http://otoroshi.oto.tools:8080/",children:"http://otoroshi.oto.tools:8080/"})," in your browser."]}),"\n",(0,n.jsxs)(e.p,{children:["If you want to know more about Otoroshi configuration, just take a look at ",(0,n.jsx)(e.a,{href:"https://maif.github.io/otoroshi/manual/install/setup-otoroshi.html",children:"the documentation"})]})]})}function c(o={}){const{wrapper:e}={...(0,s.R)(),...o.components};return e?(0,n.jsx)(e,{...o,children:(0,n.jsx)(d,{...o})}):d(o)}},9229:(o,e,t)=>{t(6540),t(4848)},8453:(o,e,t)=>{t.d(e,{R:()=>r,x:()=>l});var n=t(6540);const s={},i=n.createContext(s);function r(o){const e=n.useContext(i);return n.useMemo((function(){return"function"==typeof o?o(e):{...e,...o}}),[e,o])}function l(o){let e;return e=o.disableParentContext?"function"==typeof o.components?o.components(s):o.components||s:r(o.components),n.createElement(i.Provider,{value:e},o.children)}}}]);