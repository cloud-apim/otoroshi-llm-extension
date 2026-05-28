const workflowNodes = [
  {
    name: 'extensions.com.cloud-apim.llm-extension.ai_agent',
    kind: 'extensions.com.cloud-apim.llm-extension.ai_agent',
    description: 'AI Agent to create agentic workflows',
    display_name: "AI Agent",
    icon: 'fas fa-robot',
    type: 'group',
    sourcesIsArray: true,
    sourcesField: 'inline_tools',
    modalEditorRawJson: true,
    handlePrefix: "tool",
    sources: ['output'],
    form_schema: {
      "provider": {
        "type": "select",
        "label": "LLM provider",
        "props": {
          "description": "The LLM provider",
          "optionsFrom": "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/providers",
          "optionsTransformer": {
            "label": "name",
            "value": "id"
          }
        }
      },
      "name": {
        "type": "string",
        "label": "Name",
        "props": {
          "description": "Name"
        }
      },
      "description": {
        "type": "any",
        "label": "Description",
        "props": {
          "height": "200px",
          "description": "Description of the agent (useful for handoff/delegation)"
        }
      },
      "instructions": {
        "type": "any",
        "label": "Instructions",
        "props": {
          "height": "200px"
        }
      },
      "input": {
        "type": "any",
        "label": "Agent input",
        "props": {
          "height": "200px"
        }
      },
      "model": {
        "type": "string",
        "label": "Model",
        "props": {
          "description": "Override the default model declared on the provider (optional)"
        }
      },
      "model_options": {
        "type": "any",
        "label": "Model options",
        "props": {
          "height": "200px",
          "language": "json",
          "description": "Extra model options merged with provider options (JSON)"
        }
      },
      "run_config": {
        "label": "Run config",
        "type": "form",
        "collapsable": true,
        "collapsed": true,
        "schema": {
          "max_turns": {
            "label": "Max turns",
            "type": "number",
            "help": "Maximum number of LLM iterations the agent loop will perform (default: 10)"
          }
        },
        "flow": ["max_turns"]
      },
      "tools": {
        "type": "select",
        "array": true,
        "label": "Tools",
        "props": {
          "description": "Tools",
          "optionsFrom": "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/tool-functions",
          "optionsTransformer": {
            "label": "name",
            "value": "id"
          }
        }
      },
      "mcp_connectors": {
        "type": "select",
        "array": true,
        "label": "MCP Connectors",
        "props": {
          "description": "MCP Connector",
          "optionsFrom": "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/mcp-connectors",
          "optionsTransformer": {
            "label": "name",
            "value": "id"
          }
        }
      },
      "memory": {
        "type": "select",
        "label": "Persistent memory",
        "props": {
          "description": "Persistent memory",
          "optionsFrom": "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/persistent-memories",
          "optionsTransformer": {
            "label": "name",
            "value": "id"
          }
        }
      },
      "built_in_tools": {
        "label": "Built-in tools",
        "type": "form",
        "collapsable": true,
        "collapsed": true,
        "schema": {
          "all": {
            "label": "Enable all built-in tools",
            "type": "box-bool",
            "props": {
              "description": "Shortcut to enable every category of built-in tools at once"
            }
          },
          "workspace": {
            "label": "Workspace tools",
            "type": "box-bool",
            "props": {
              "description": "Enables list_files, read_file, write_file, append_file, search_in_files (requires allowed_paths)"
            }
          },
          "shell": {
            "label": "Shell tool",
            "type": "box-bool",
            "props": {
              "description": "Enables run_command (requires allowed_paths)"
            }
          },
          "tasks": {
            "label": "Tasks tools",
            "type": "box-bool",
            "props": {
              "description": "Enables task_create, task_update, task_get, task_list (scratchpad)"
            }
          },
          "plan": {
            "label": "Plan tools",
            "type": "box-bool",
            "props": {
              "description": "Enables plan_set, plan_get (scratchpad)"
            }
          },
          "memory": {
            "label": "Scratchpad memory tools",
            "type": "box-bool",
            "props": {
              "description": "Enables memory_set, memory_get, memory_list (in-run scratchpad, distinct from persistent memory)"
            }
          },
          "agent": {
            "label": "Delegate tool",
            "type": "box-bool",
            "props": {
              "description": "Enables 'delegate' to call configured handoff agents"
            }
          },
          "http": {
            "label": "HTTP tool",
            "type": "box-bool",
            "props": {
              "description": "Enables http_call to perform outbound HTTP requests"
            }
          },
          "content_to_markdown": {
            "label": "Content to Markdown tool",
            "type": "box-bool",
            "props": {
              "description": "Enables content_to_markdown to convert HTML/PDF/etc to markdown"
            }
          },
          "control": {
            "label": "Final answer tool",
            "type": "box-bool",
            "props": {
              "description": "Enables final_answer to let the agent explicitly terminate the loop"
            }
          },
          "persistent_kv": {
            "label": "Persistent KV tools",
            "type": "box-bool",
            "props": {
              "description": "Enables persistent_kv_set/get/delete/list (requires persistent_kv_uri)"
            }
          },
          "persistent_kv_uri": {
            "label": "Persistent KV URI",
            "type": "string",
            "help": "Datastore URI for the persistent KV (e.g. redis://...)"
          },
          "persistent_kv_namespace": {
            "label": "Persistent KV namespace",
            "type": "string",
            "help": "Optional namespace/prefix for persistent KV keys"
          },
          "allowed_paths": {
            "label": "Allowed paths",
            "type": "array",
            "array": true,
            "format": null,
            "help": "Filesystem paths the workspace/shell tools are allowed to operate on"
          },
          "command_timeout": {
            "label": "Command timeout (ms)",
            "type": "number",
            "help": "Timeout for run_command in milliseconds (default: 30000)"
          },
          "include": {
            "label": "Force-include tools",
            "type": "select",
            "array": true,
            "props": {
              "isClearable": true,
              "createOption": true,
              "description": "Force-enable specific tool names regardless of categories",
              "possibleValues": [
                { "label": "list_files", "value": "list_files" },
                { "label": "read_file", "value": "read_file" },
                { "label": "write_file", "value": "write_file" },
                { "label": "append_file", "value": "append_file" },
                { "label": "search_in_files", "value": "search_in_files" },
                { "label": "run_command", "value": "run_command" },
                { "label": "task_create", "value": "task_create" },
                { "label": "task_update", "value": "task_update" },
                { "label": "task_get", "value": "task_get" },
                { "label": "task_list", "value": "task_list" },
                { "label": "plan_set", "value": "plan_set" },
                { "label": "plan_get", "value": "plan_get" },
                { "label": "memory_set", "value": "memory_set" },
                { "label": "memory_get", "value": "memory_get" },
                { "label": "memory_list", "value": "memory_list" },
                { "label": "persistent_kv_set", "value": "persistent_kv_set" },
                { "label": "persistent_kv_get", "value": "persistent_kv_get" },
                { "label": "persistent_kv_delete", "value": "persistent_kv_delete" },
                { "label": "persistent_kv_list", "value": "persistent_kv_list" },
                { "label": "delegate", "value": "delegate" },
                { "label": "spawn_agent", "value": "spawn_agent" },
                { "label": "http_call", "value": "http_call" },
                { "label": "content_to_markdown", "value": "content_to_markdown" },
                { "label": "final_answer", "value": "final_answer" }
              ]
            }
          },
          "exclude": {
            "label": "Exclude tools",
            "type": "select",
            "array": true,
            "props": {
              "isClearable": true,
              "createOption": true,
              "description": "Force-disable specific tool names (wins over categories and include)",
              "possibleValues": [
                { "label": "list_files", "value": "list_files" },
                { "label": "read_file", "value": "read_file" },
                { "label": "write_file", "value": "write_file" },
                { "label": "append_file", "value": "append_file" },
                { "label": "search_in_files", "value": "search_in_files" },
                { "label": "run_command", "value": "run_command" },
                { "label": "task_create", "value": "task_create" },
                { "label": "task_update", "value": "task_update" },
                { "label": "task_get", "value": "task_get" },
                { "label": "task_list", "value": "task_list" },
                { "label": "plan_set", "value": "plan_set" },
                { "label": "plan_get", "value": "plan_get" },
                { "label": "memory_set", "value": "memory_set" },
                { "label": "memory_get", "value": "memory_get" },
                { "label": "memory_list", "value": "memory_list" },
                { "label": "persistent_kv_set", "value": "persistent_kv_set" },
                { "label": "persistent_kv_get", "value": "persistent_kv_get" },
                { "label": "persistent_kv_delete", "value": "persistent_kv_delete" },
                { "label": "persistent_kv_list", "value": "persistent_kv_list" },
                { "label": "delegate", "value": "delegate" },
                { "label": "spawn_agent", "value": "spawn_agent" },
                { "label": "http_call", "value": "http_call" },
                { "label": "content_to_markdown", "value": "content_to_markdown" },
                { "label": "final_answer", "value": "final_answer" }
              ]
            }
          },
          "sub_agents": {
            "label": "Sub-agents (spawn_agent)",
            "type": "any",
            "props": {
              "height": "300px",
              "language": "json",
              "description": "Profiles available to the spawn_agent tool. JSON array of { name, description, instructions, provider, model, model_options, built_in_tools }"
            }
          }
        },
        "flow": [
          "all",
          "workspace",
          "shell",
          "tasks",
          "plan",
          "memory",
          "agent",
          "http",
          "content_to_markdown",
          "control",
          "persistent_kv",
          "persistent_kv_uri",
          "persistent_kv_namespace",
          "allowed_paths",
          "command_timeout",
          "include",
          "exclude",
          "sub_agents"
        ]
      },
      "handoffs": {
        "label": "Handoffs",
        "type": "any",
        "props": {
          "height": "300px",
          "language": "json",
          "description": "Handoff targets used by the 'delegate' built-in tool. JSON array of { agent: AgentConfig, enabled, tool_name_override, tool_description_override }"
        }
      },
      "guardrails": {
        "label": "Guardrails",
        "type": "array",
        "array": true,
        "format": "form",
        "schema": {
          "id": {
            "label": "Guardrail",
            "type": "select",
            "props": {
              "possibleValues": [{
                "label": "Regex",
                "value": "regex"
              }, {
                "label": "Webhook",
                "value": "webhook"
              }, {
                "label": "LLM",
                "value": "llm"
              }, {
                "label": "Secrets leakage",
                "value": "secrets_leakage"
              }, {
                "label": "Auto Secrets leakage",
                "value": "auto_secrets_leakage"
              }, {
                "label": "No gibberish",
                "value": "gibberish"
              }, {
                "label": "No personal information",
                "value": "pif"
              }, {
                "label": "Language moderation",
                "value": "moderation"
              }, {
                "label": "Moderation model",
                "value": "moderation_model"
              }, {
                "label": "No toxic language",
                "value": "toxic_language"
              }, {
                "label": "No racial bias",
                "value": "racial_bias"
              }, {
                "label": "No gender bias",
                "value": "gender_bias"
              }, {
                "label": "No personal health information",
                "value": "personal_health_information"
              }, {
                "label": "No prompt injection/prompt jailbreak",
                "value": "prompt_injection"
              }, {
                "label": "Faithfulness",
                "value": "faithfulness"
              }, {
                "label": "Sentences count",
                "value": "sentences"
              }, {
                "label": "Words count",
                "value": "words"
              }, {
                "label": "Characters count",
                "value": "characters"
              }, {
                "label": "Text contains",
                "value": "contains"
              }, {
                "label": "Semantic contains",
                "value": "semantic_contains"
              }, {
                "label": "QuickJS",
                "value": "quickjs"
              }, {
                "label": "Wasm",
                "value": "wasm"
              }]
            }
          },
          "before": {
            "type": "boolean",
            "label": "Before",
            "props": {}
          },
          "after": {
            "type": "boolean",
            "label": "After",
            "props": {}
          },
          "config": {
            "type": "any",
            "label": "Config",
            "props": {
              "height": "200px"
            }
          }
        },
        "flow": ["id", "before", "after", "config"]
      }
    },
    flow: [
      "name",
      "description",
      "provider",
      "model",
      "model_options",
      "instructions",
      "input",
      "tools",
      "mcp_connectors",
      "memory",
      "guardrails",
      "built_in_tools",
      "handoffs",
      "run_config"
    ],
    height: (data) => `${110 + 20 * data?.sourceHandles?.length}px`,
    nodeToJson: ({
                   edges,
                   nodes,
                   node,
                   alreadySeen,
                   connections,
                   nodeToJson,
                   removeReturnedFromWorkflow,
                   emptyWorkflow }) => {
      const { kind } = node.data;

      console.log(node.data.sourceHandles)

      const out = node.data.sourceHandles
        .filter(source => source.id.startsWith('tool-'))
        .reduce(
          (acc, source, idx) => {
            const connection = connections.find((conn) => conn.sourceHandle === source.id);

            if (!connection) {
              // keep all fields except previous node
              const rest = Object.fromEntries(
                Object.entries(node.data.content.inline_tools[idx])
              );
              return {
                ...acc,
                inline_tools: [...acc.inline_tools, rest],
              };
            }

            const target = nodes.find((n) => n.id === connection.target);
            const [pathNode, seen] = removeReturnedFromWorkflow(
              nodeToJson(target, emptyWorkflow, false, alreadySeen)
            );

            alreadySeen = alreadySeen.concat([seen]).flat();

            const isSubFlowEmpty = pathNode.kind === 'workflow' && pathNode.steps.length === 0;
            const hasNode = pathNode.kind === 'workflow' && (pathNode.steps.length > 1 || pathNode.steps[0]?.kind === 'extensions.com.cloud-apim.llm-extension.ai_agent_mcp_tools');

            return {
              ...acc,
              inline_tools: [
                ...acc.inline_tools,
                {
                  ...node.data.content.inline_tools[idx],
                  ...(hasNode ? pathNode.steps[0] : {}),
                  node: isSubFlowEmpty ? undefined : hasNode ? pathNode.steps[1] : undefined,
                },
              ],
            };
          },
          {
            ...node.data.content,
            inline_tools: [],
            kind,
            id: node.id,
          }
        );

      console.log(out)
      return [out, alreadySeen]
    },
    buildGraph: ({ workflow, addInformationsToNode, targetId, handleId, buildGraph, current, me }) => {
      let nodes = []
      let edges = []

      let inline_tools = [];

      if (workflow.inline_tools) {
        for (let i = 0; i < workflow.inline_tools.length; i++) {
          const tool = workflow.inline_tools[i].mcp_ref ? {
            ...workflow.inline_tools[i],
            kind: 'extensions.com.cloud-apim.llm-extension.ai_agent_mcp_tools',
            customDisplayName: 'MCP Tools',
            id: uuid(),
            customNodeRenderer: (props) => React.createElement('div', { style: {
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  position: 'absolute',
                  inset: '30px 24px 0px 24px',
                }},
              React.createElement('i', { className: 'fas fa-wrench fa-2xl' })
            )
          } : {
            ...workflow.inline_tools[i],
            kind: 'inline_tool',
            customDisplayName: workflow?.inline_tools[i]?.name || 'Tool',
            id: uuid(),
            customNodeRenderer: (props) => React.createElement('div', { style: {
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              position: 'absolute',
              inset: '30px 24px 0px 24px',
            }},
              React.createElement('i', { className: 'fas fa-wrench fa-2xl' })
            )
          };

          console.log(tool)

          const toolSubflow = tool.node

          let toolSubflowPath
          if (toolSubflow) {
            toolSubflowPath = buildGraph([toolSubflow], addInformationsToNode);

            nodes = nodes.concat(toolSubflowPath.nodes);
            edges = edges.concat(toolSubflowPath.edges);
          }

          if (tool) {
            const nestedPath = buildGraph([tool], addInformationsToNode, toolSubflowPath?.nodes[0].id);

            inline_tools.push({
              idx: i,
              nestedPath,
            });
          }
        }

        current.customSourceHandles = [...Array(workflow.inline_tools.length)].map((_, i) => ({
          id: `tool-${i}`,
        }));

        inline_tools.forEach((path) => {
          if (path.nestedPath.nodes.length > 0)
            edges.push({
              id: `${me}-tool-${path.idx}`,
              source: me,
              sourceHandle: `tool-${path.idx}`,
              target: path.nestedPath.nodes[0].id,
              targetHandle: `input-${path.nestedPath.nodes[0].id}`,
              type: 'customEdge',
              animated: true,
            });

          nodes = nodes.concat(path.nestedPath.nodes);
          edges = edges.concat(path.nestedPath.edges);
        })
      }

      return { nodes, edges }
    }
  },
  {
    kind: 'extensions.com.cloud-apim.llm-extension.ai_agent_mcp_tools',
    name: 'extensions.com.cloud-apim.llm-extension.ai_agent_mcp_tools',
    display_name: 'MCP Tools',
    icon: 'fas fa-wrench',
    description: 'This node let you select an MCP connector for your agent',
    flow: ["mcp_ref"],
    form_schema: {
      mcp_ref: {
        "type": "select",
        "label": "MCP Connectors",
        "props": {
          "description": "MCP Connector",
          "optionsFrom": "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/mcp-connectors",
          "optionsTransformer": {
            "label": "name",
            "value": "id"
          }
        }
      }
    },
    sources: [],
    nodeRenderer: (props) => {
      return props.data.content.customNodeRenderer ? props.data.content.customNodeRenderer(props) : (
        React.createElement('div', { className: "node-text-renderer" },
          props.data.content?.values?.map((value) => {
            return React.createElement('span', { key: value.name }, value.name);
          })
        )
      );
    },
  },
  {
    kind: 'inline_tool',
    name: 'inline_tool',
    display_name: 'Agent Tool',
    icon: 'fas fa-wrench',
    description: 'AI Agent tool made of workflow nodes.',
    flow: ["name", 'strict', 'parameters', 'required', 'input_json_parse'],
    form_schema: {
      input_json_parse: {
        type: 'bool',
        description: 'Parse input as json',
      },
      name: {
        type: 'string',
        label: 'Name',
      },
      description: {
        "type": "any",
        "label": "Description",
        "props": {
          "height": "200px"
        }
      },
      parameters: {
        "type": "any",
        "label": "Parameters",
        "props": {
          "height": "200px",
          language: 'json',
        }
      },
      required: {
        "type": "any",
        "label": "Required params.",
        "props": {
          "height": "200px",
          language: 'json',
        }
      },
      strict: {
        "type": "bool",
        "label": "Strict"
      }
    },
    sources: ['output'],
    nodeRenderer: (props) => {
      return props.data.content.customNodeRenderer ? props.data.content.customNodeRenderer(props) : (
        React.createElement('div', { className: "node-text-renderer" },
          props.data.content?.values?.map((value) => {
            return React.createElement('span', { key: value.name }, value.name);
          })
        )
      );
    },
  },
  {
    name: 'extensions.com.cloud-apim.llm-extension.router',
    kind: 'extensions.com.cloud-apim.llm-extension.router',
    description: 'AI agent router',
    display_name: "AI agent router",
    icon: 'fas fa-robot',
    type: 'group',
    flow: ['provider', 'input', 'instructions'],
    form_schema: {
      provider: {
        type: 'select',
        label: 'Provider',
        props: {
          optionsFrom: '/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/providers',
          optionsTransformer: {
            label: 'name',
            value: 'id',
          },
        },
      },
      input: {
        type: 'any',
        label: 'input',
        props: {
          height: '200px'
        }
      },
      instructions: {
        type: 'any',
        label: 'Instructions',
        props: {
          height: '200px'
        }
      }
    },
    sourcesIsArray: true,
    handlePrefix: "Path",
    sources: [],
    height: (data) => `${110 + 20 * data?.sourceHandles?.length}px`,
    nodeToJson: ({
                   edges,
                   nodes,
                   node,
                   alreadySeen,
                   connections,
                   nodeToJson,
                   removeReturnedFromWorkflow,
                   emptyWorkflow }) => {
      const { kind } = node.data;
      return node.data.sourceHandles.reduce(
        (acc, source, idx) => {
          const connection = connections.find((conn) => conn.sourceHandle === source.id);

          if (!connection) {
            // keep all fields except previous node
            const rest = Object.fromEntries(
              Object.entries(node.data.content.paths[idx])
            );
            return {
              ...acc,
              paths: [...acc.paths, rest],
            };
          }

          const target = nodes.find((n) => n.id === connection.target);
          const [pathNode, seen] = removeReturnedFromWorkflow(
            nodeToJson(target, emptyWorkflow, false, alreadySeen)
          );

          alreadySeen = alreadySeen.concat([seen]);

          const isSubFlowEmpty = pathNode.kind === 'workflow' && pathNode.steps.length === 0;
          const isOneNodeSubFlow = pathNode.kind === 'workflow' && pathNode.steps.length === 1;

          return {
            ...acc,
            paths: [
              ...acc.paths,
              {
                ...node.data.content.paths[idx],
                node: isSubFlowEmpty ? undefined : isOneNodeSubFlow ? pathNode.steps[0] : pathNode,
              },
            ],
          };
        },
        {
          ...node.data.content,
          paths: [],
          kind,
          id: node.id,
        }
      );
    },
    buildGraph: ({ workflow, addInformationsToNode, targetId, handleId, buildGraph, current, me }) => {
      let nodes = []
      let edges = []

      let paths = [];

      if (workflow.paths) {
        for (let i = 0; i < workflow.paths.length; i++) {
          const subflow = workflow.paths[i];

          if (subflow) {
            const nestedPath = buildGraph([subflow], addInformationsToNode, targetId, handleId);

            paths.push({
              idx: i,
              nestedPath,
            });
          }
        }

        current.customSourceHandles = [...Array(workflow.paths.length)].map((_, i) => ({
          id: `path-${i}`,
        }));

        paths.forEach((path) => {
          if (path.nestedPath.nodes.length > 0)
            edges.push({
              id: `${me}-path-${path.idx}`,
              source: me,
              sourceHandle: `path-${path.idx}`,
              target: path.nestedPath.nodes[0].id,
              targetHandle: `input-${path.nestedPath.nodes[0].id}`,
              type: 'customEdge',
              animated: true,
            });

          nodes = nodes.concat(path.nestedPath.nodes);
          edges = edges.concat(path.nestedPath.edges);
        })
      }

      return { nodes, edges }
    }
  }
]