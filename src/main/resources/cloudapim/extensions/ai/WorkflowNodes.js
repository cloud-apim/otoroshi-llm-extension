const workflowNodeSwitch = {
  name: 'extensions.com.cloud-apim.llm-extension.router',
  kind: 'extensions.com.cloud-apim.llm-extension.router',
  description: 'AI agent router',
  display_name: "AI agent router",
  icon: 'fas fa-brain',
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