const workflowNodeSwitch = {
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

const workflowAiAgentNode = {
  name: 'extensions.com.cloud-apim.llm-extension.ai_agent__',
  kind: 'extensions.com.cloud-apim.llm-extension.ai_agent__',
  description: 'AI Agent (node)',
  display_name: "AI Agent (node)",
  icon: 'fas fa-robot',
  type: 'group',
  sourcesIsArray: true,
  handlePrefix: "Tool",
  sources: [],
  flow: {
    "provider" : {
      "type" : "select",
      "label" : "LLM provider",
      "props" : {
        "description" : "The LLM provider",
        "optionsFrom" : "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/providers",
        "optionsTransformer" : {
          "label" : "name",
          "value" : "id"
        }
      }
    },
    "name" : {
      "type" : "string",
      "label" : "Name",
      "props" : {
        "description" : "Name"
      }
    },
    "description" : {
      "type" : "any",
      "label" : "Description",
      "props" : {
        "height" : "200px"
      }
    },
    "instructions" : {
      "type" : "any",
      "label" : "Instructions",
      "props" : {
        "height" : "200px"
      }
    },
    "input" : {
      "type" : "any",
      "label" : "Agent input",
      "props" : {
        "height" : "200px"
      }
    },
    "tools" : {
      "type" : "select",
      "array" : true,
      "label" : "Tools",
      "props" : {
        "description" : "Tools",
        "optionsFrom" : "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/tool-functions",
        "optionsTransformer" : {
          "label" : "name",
          "value" : "id"
        }
      }
    },
    "memory" : {
      "type" : "select",
      "label" : "Persistent memory",
      "props" : {
        "description" : "Persistent memory",
        "optionsFrom" : "/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/persistent-memories",
        "optionsTransformer" : {
          "label" : "name",
          "value" : "id"
        }
      }
    },
    "guardrails" : {
      "label" : "Guardrails",
      "type" : "array",
      "array" : true,
      "format" : "form",
      "schema" : {
        "id" : {
          "label" : "Guardrail",
          "type" : "select",
          "props" : {
            "possibleValues" : [ {
              "label" : "Regex",
              "value" : "regex"
            }, {
              "label" : "Webhook",
              "value" : "webhook"
            }, {
              "label" : "LLM",
              "value" : "llm"
            }, {
              "label" : "Secrets leakage",
              "value" : "secrets_leakage"
            }, {
              "label" : "Auto Secrets leakage",
              "value" : "auto_secrets_leakage"
            }, {
              "label" : "No gibberish",
              "value" : "gibberish"
            }, {
              "label" : "No personal information",
              "value" : "pif"
            }, {
              "label" : "Language moderation",
              "value" : "moderation"
            }, {
              "label" : "Moderation model",
              "value" : "moderation_model"
            }, {
              "label" : "No toxic language",
              "value" : "toxic_language"
            }, {
              "label" : "No racial bias",
              "value" : "racial_bias"
            }, {
              "label" : "No gender bias",
              "value" : "gender_bias"
            }, {
              "label" : "No personal health information",
              "value" : "personal_health_information"
            }, {
              "label" : "No prompt injection/prompt jailbreak",
              "value" : "prompt_injection"
            }, {
              "label" : "Faithfulness",
              "value" : "faithfulness"
            }, {
              "label" : "Sentences count",
              "value" : "sentences"
            }, {
              "label" : "Words count",
              "value" : "words"
            }, {
              "label" : "Characters count",
              "value" : "characters"
            }, {
              "label" : "Text contains",
              "value" : "contains"
            }, {
              "label" : "Semantic contains",
              "value" : "semantic_contains"
            }, {
              "label" : "QuickJS",
              "value" : "quickjs"
            }, {
              "label" : "Wasm",
              "value" : "wasm"
            } ]
          }
        },
        "before" : {
          "type" : "boolean",
          "label" : "Before",
          "props" : { }
        },
        "after" : {
          "type" : "boolean",
          "label" : "After",
          "props" : { }
        },
        "config" : {
          "type" : "any",
          "label" : "Config",
          "props" : {
            "height" : "200px"
          }
        }
      },
      "flow" : [ "id", "before", "after", "config" ]
    }
  },
  form_schema: ["provider","name","description","instructions","input","tools","memory","guardrails"],
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

        alreadySeen = alreadySeen.concat([seen]);

        const isSubFlowEmpty = pathNode.kind === 'workflow' && pathNode.steps.length === 0;
        const isOneNodeSubFlow = pathNode.kind === 'workflow' && pathNode.steps.length === 1;

        return {
          ...acc,
          inline_tools: [
            ...acc.inline_tools,
            {
              ...node.data.content.inline_tools[idx],
              node: isSubFlowEmpty ? undefined : isOneNodeSubFlow ? pathNode.steps[0] : pathNode,
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
  },
  buildGraph: ({ workflow, addInformationsToNode, targetId, handleId, buildGraph, current, me }) => {
    let nodes = []
    let edges = []

    let paths = [];

    if (workflow.inline_tools) {
      for (let i = 0; i < workflow.inline_tools.length; i++) {
        const subflow = workflow.inline_tools[i];

        if (subflow) {
          const nestedPath = buildGraph([subflow], addInformationsToNode, targetId, handleId);

          paths.push({
            idx: i,
            nestedPath,
          });
        }
      }

      current.customSourceHandles = [...Array(workflow.inline_tools.length)].map((_, i) => ({
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