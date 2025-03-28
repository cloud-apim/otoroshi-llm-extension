"use strict";(self.webpackChunkdocumentation=self.webpackChunkdocumentation||[]).push([[8977],{8080:(e,t,n)=>{n.r(t),n.d(t,{assets:()=>a,contentTitle:()=>l,default:()=>h,frontMatter:()=>s,metadata:()=>r,toc:()=>c});var i=n(4848),o=n(8453);n(9229);const s={sidebar_position:1},l="Wasm Functions",r={id:"function-calling/function-wasm-calling",title:"Wasm Functions",description:"\ud83e\udd16 What Are Tool Calls in LLMs?",source:"@site/docs/function-calling/function-wasm-calling.mdx",sourceDirName:"function-calling",slug:"/function-calling/function-wasm-calling",permalink:"/otoroshi-llm-extension/docs/function-calling/function-wasm-calling",draft:!1,unlisted:!1,tags:[],version:"current",sidebarPosition:1,frontMatter:{sidebar_position:1},sidebar:"tutorialSidebar",previous:{title:"Overview",permalink:"/otoroshi-llm-extension/docs/function-calling/overview"},next:{title:"Http Functions",permalink:"/otoroshi-llm-extension/docs/function-calling/http-functions"}},a={},c=[{value:"\ud83e\udd16 What Are Tool Calls in LLMs?",id:"-what-are-tool-calls-in-llms",level:2},{value:"\ud83d\udd39 How Do Tool Calls Work?",id:"-how-do-tool-calls-work",level:3},{value:"\u2708\ufe0f What is <code>get_flight_times</code>?",id:"\ufe0f-what-is-get_flight_times",level:2},{value:"\ud83d\udee0\ufe0f How Does it Work?",id:"\ufe0f-how-does-it-work",level:2},{value:"\ud83d\udcdd Example Usage",id:"-example-usage",level:2}];function d(e){const t={code:"code",h1:"h1",h2:"h2",h3:"h3",img:"img",li:"li",p:"p",pre:"pre",strong:"strong",ul:"ul",...(0,o.R)(),...e.components};return(0,i.jsxs)(i.Fragment,{children:[(0,i.jsx)(t.h1,{id:"wasm-functions",children:"Wasm Functions"}),"\n",(0,i.jsx)(t.h2,{id:"-what-are-tool-calls-in-llms",children:"\ud83e\udd16 What Are Tool Calls in LLMs?"}),"\n",(0,i.jsx)(t.p,{children:"Tool calls in Large Language Models (LLMs) allow the model to interact with external tools, APIs, or functions to fetch real-world data or perform tasks beyond its native capabilities."}),"\n",(0,i.jsxs)(t.p,{children:["Instead of relying solely on pre-trained knowledge, LLMs can dynamically query functions like ",(0,i.jsx)(t.code,{children:"get_flight_times"})," to provide accurate, real-time information."]}),"\n",(0,i.jsx)(t.h3,{id:"-how-do-tool-calls-work",children:"\ud83d\udd39 How Do Tool Calls Work?"}),"\n",(0,i.jsx)(t.p,{children:"When an LLM identifies a request that matches a tool\u2019s function, it formats the request into a structured call (like an API request) and processes the response before delivering it to the user."}),"\n",(0,i.jsxs)(t.h2,{id:"\ufe0f-what-is-get_flight_times",children:["\u2708\ufe0f What is ",(0,i.jsx)(t.code,{children:"get_flight_times"}),"?"]}),"\n",(0,i.jsxs)(t.p,{children:["The ",(0,i.jsx)(t.code,{children:"get_flight_times"})," function is a tool call that helps you retrieve flight durations between two locations. By providing a departure and arrival destination, you can get accurate estimates of how long your flight will take."]}),"\n",(0,i.jsx)(t.h2,{id:"\ufe0f-how-does-it-work",children:"\ud83d\udee0\ufe0f How Does it Work?"}),"\n",(0,i.jsx)(t.p,{children:"The function operates by taking in two key parameters:"}),"\n",(0,i.jsxs)(t.ul,{children:["\n",(0,i.jsxs)(t.li,{children:[(0,i.jsx)(t.strong,{children:"departure"})," \ud83d\udeeb \u2013 The airport or city where your flight begins."]}),"\n",(0,i.jsxs)(t.li,{children:[(0,i.jsx)(t.strong,{children:"arrival"})," \ud83d\udeec \u2013 The airport or city where your flight ends."]}),"\n"]}),"\n",(0,i.jsxs)(t.p,{children:["Once you provide these details, ",(0,i.jsx)(t.code,{children:"get_flight_times"})," fetches the estimated duration of direct and indirect flights between the specified locations. The function considers factors like average flight speeds, distances between airports, and typical air traffic conditions."]}),"\n",(0,i.jsx)(t.h2,{id:"-example-usage",children:"\ud83d\udcdd Example Usage"}),"\n",(0,i.jsxs)(t.p,{children:["Imagine you want to check the flight duration from ",(0,i.jsx)(t.strong,{children:"New York (JFK)"})," to ",(0,i.jsx)(t.strong,{children:"Los Angeles (LAX)"}),":"]}),"\n",(0,i.jsx)(t.pre,{children:(0,i.jsx)(t.code,{className:"language-python",children:'get_flight_times(departure="JFK", arrival="LAX")\n'})}),"\n",(0,i.jsxs)(t.p,{children:["The function will return the estimated flight duration, such as ",(0,i.jsx)(t.code,{children:"5h 30m"})," for a direct flight or longer if layovers are involved."]}),"\n",(0,i.jsx)(t.p,{children:(0,i.jsx)(t.img,{src:n(3493).A+"",width:"974",height:"347"})}),"\n",(0,i.jsx)(t.p,{children:(0,i.jsx)(t.img,{src:n(1694).A+"",width:"1024",height:"316"})}),"\n",(0,i.jsx)(t.p,{children:(0,i.jsx)(t.img,{src:n(4711).A+"",width:"720",height:"427"})})]})}function h(e={}){const{wrapper:t}={...(0,o.R)(),...e.components};return t?(0,i.jsx)(t,{...e,children:(0,i.jsx)(d,{...e})}):d(e)}},9229:(e,t,n)=>{n(6540),n(4848)},3493:(e,t,n)=>{n.d(t,{A:()=>i});const i=n.p+"assets/images/tool-calls-1-adc5eae3b7f9967b2dfb3e704088f3b0.png"},1694:(e,t,n)=>{n.d(t,{A:()=>i});const i=n.p+"assets/images/tool-calls-2-a54507f5a6dde25f4862ffd9129e65c4.png"},4711:(e,t,n)=>{n.d(t,{A:()=>i});const i=n.p+"assets/images/tool-calls-3-d2ad517b3a67baebc1f8350d653e357b.png"},8453:(e,t,n)=>{n.d(t,{R:()=>l,x:()=>r});var i=n(6540);const o={},s=i.createContext(o);function l(e){const t=i.useContext(s);return i.useMemo((function(){return"function"==typeof e?e(t):{...t,...e}}),[t,e])}function r(e){let t;return t=e.disableParentContext?"function"==typeof e.components?e.components(o):e.components||o:l(e.components),i.createElement(s.Provider,{value:t},e.children)}}}]);