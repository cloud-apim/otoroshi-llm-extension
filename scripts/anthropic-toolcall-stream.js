#!/usr/bin/env node

/**
 * Anthropic Tool Call Example - With Streaming
 *
 * This script demonstrates how to handle Anthropic tool calls with streaming using pure HTTP fetch.
 * It manages the complete flow: initial request -> tool call detection -> tool execution -> final response
 *
 * Anthropic streaming events:
 * - message_start: Contains message metadata
 * - content_block_start: Starts a new content block (text or tool_use)
 * - content_block_delta: Incremental updates to the current block
 * - content_block_stop: Ends the current content block
 * - message_delta: Contains stop_reason and usage updates
 * - message_stop: End of message
 */

const ANTHROPIC_API_KEY = process.env.ANTHROPIC_API_KEY || 'foo';
const ANTHROPIC_BASE_URL = process.env.ANTHROPIC_BASE_URL || 'http://anthropic.oto.tools:9999';
const MODEL = process.env.ANTHROPIC_MODEL || 'gpt-5.2';

if (!ANTHROPIC_API_KEY) {
  console.error('Error: ANTHROPIC_API_KEY environment variable is required');
  process.exit(1);
}

// Define available tools (Anthropic format)
const tools = [
  {
    name: 'get_weather',
    description: 'Get the current weather for a given location',
    input_schema: {
      type: 'object',
      properties: {
        location: {
          type: 'string',
          description: 'The city and country, e.g. "Paris, France"'
        },
        unit: {
          type: 'string',
          enum: ['celsius', 'fahrenheit'],
          description: 'Temperature unit'
        }
      },
      required: ['location']
    }
  },
  {
    name: 'get_current_time',
    description: 'Get the current time for a given timezone',
    input_schema: {
      type: 'object',
      properties: {
        timezone: {
          type: 'string',
          description: 'The timezone, e.g. "Europe/Paris" or "America/New_York"'
        }
      },
      required: ['timezone']
    }
  }
];

// Tool implementations
function executeToolCall(name, args) {
  console.log(`\n[Executing tool: ${name}]`);
  console.log(`[Arguments: ${JSON.stringify(args)}]`);

  switch (name) {
    case 'get_weather': {
      const weatherData = {
        location: args.location,
        temperature: Math.floor(Math.random() * 30) + 5,
        unit: args.unit || 'celsius',
        condition: ['sunny', 'cloudy', 'rainy', 'partly cloudy'][Math.floor(Math.random() * 4)],
        humidity: Math.floor(Math.random() * 50) + 30,
        wind_speed: Math.floor(Math.random() * 30) + 5
      };
      return JSON.stringify(weatherData);
    }
    case 'get_current_time': {
      try {
        const now = new Date();
        const formatter = new Intl.DateTimeFormat('en-US', {
          timeZone: args.timezone,
          dateStyle: 'full',
          timeStyle: 'long'
        });
        return JSON.stringify({
          timezone: args.timezone,
          datetime: formatter.format(now),
          timestamp: now.toISOString()
        });
      } catch (e) {
        return JSON.stringify({ error: `Invalid timezone: ${args.timezone}` });
      }
    }
    default:
      return JSON.stringify({ error: `Unknown tool: ${name}` });
  }
}

/**
 * Parse SSE stream and yield parsed event objects
 */
async function* parseSSEStream(response) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  try {
    while (true) {
      const { done, value } = await reader.read();

      if (done) {
        break;
      }

      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');

      // Keep the last incomplete line in buffer
      buffer = lines.pop() || '';

      let currentEvent = null;

      for (const line of lines) {
        const trimmedLine = line.trim();

        if (trimmedLine === '') {
          continue;
        }

        if (trimmedLine.startsWith('event: ')) {
          currentEvent = trimmedLine.slice(7);
        } else if (trimmedLine.startsWith('data: ')) {
          const jsonStr = trimmedLine.slice(6);
          try {
            const data = JSON.parse(jsonStr);
            yield { event: currentEvent, data };
          } catch (e) {
            console.error('Failed to parse SSE data:', jsonStr);
          }
        }
      }
    }
  } finally {
    reader.releaseLock();
  }
}

/**
 * Call Anthropic with streaming and collect the complete response
 * Returns { content: [...blocks], stopReason }
 */
async function callAnthropicStreaming(messages, system, onTextChunk) {
  const response = await fetch(`${ANTHROPIC_BASE_URL}/v1/messages`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'x-api-key': ANTHROPIC_API_KEY,
    },
    body: JSON.stringify({
      model: MODEL,
      max_tokens: 4096,
      system: system,
      messages: messages,
      tools: tools,
      stream: true
    })
  });

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Anthropic API error: ${response.status} - ${error}`);
  }

  // Accumulate content blocks
  const contentBlocks = [];
  let currentBlockIndex = -1;
  let currentBlock = null;
  let stopReason = null;

  for await (const { event, data } of parseSSEStream(response)) {
    switch (event) {
      case 'message_start':
        // Message metadata - we could extract usage info here
        break;

      case 'content_block_start':
        currentBlockIndex = data.index;
        if (data.content_block.type === 'text') {
          currentBlock = {
            type: 'text',
            text: ''
          };
        } else if (data.content_block.type === 'tool_use') {
          currentBlock = {
            type: 'tool_use',
            id: data.content_block.id,
            name: data.content_block.name,
            input: ''  // Will accumulate JSON string, then parse
          };
        }
        contentBlocks[currentBlockIndex] = currentBlock;
        break;

      case 'content_block_delta':
        if (data.delta.type === 'text_delta') {
          contentBlocks[data.index].text += data.delta.text;
          if (onTextChunk) {
            onTextChunk(data.delta.text);
          }
        } else if (data.delta.type === 'input_json_delta') {
          // Tool input comes as partial JSON string
          contentBlocks[data.index].input += data.delta.partial_json;
        }
        break;

      case 'content_block_stop':
        // Finalize the block
        const block = contentBlocks[data.index];
        if (block && block.type === 'tool_use' && typeof block.input === 'string') {
          // Parse the accumulated JSON input
          try {
            block.input = JSON.parse(block.input || '{}');
          } catch (e) {
            console.error('Failed to parse tool input:', block.input);
            block.input = {};
          }
        }
        break;

      case 'message_delta':
        if (data.delta?.stop_reason) {
          stopReason = data.delta.stop_reason;
        }
        break;

      case 'message_stop':
        // End of message
        break;
    }
  }

  return {
    content: contentBlocks.filter(Boolean),
    stopReason
  };
}

async function chat(userMessage) {
  console.log(`\n${'='.repeat(60)}`);
  console.log(`User: ${userMessage}`);
  console.log('='.repeat(60));

  const system = 'You are a helpful assistant. Use the available tools when needed to provide accurate information.';

  const messages = [
    {
      role: 'user',
      content: userMessage
    }
  ];

  let iteration = 0;
  const maxIterations = 10;

  while (iteration < maxIterations) {
    iteration++;
    console.log(`\n[API Call #${iteration} - Streaming]`);

    // Stream text callback - print content as it arrives
    const onTextChunk = (chunk) => {
      process.stdout.write(chunk);
    };

    const result = await callAnthropicStreaming(messages, system, onTextChunk);

    // Add assistant response to conversation history
    messages.push({
      role: 'assistant',
      content: result.content
    });

    // Check if the model wants to use tools
    if (result.stopReason === 'tool_use') {
      const toolUseBlocks = result.content.filter(block => block.type === 'tool_use');
      console.log(`\n[Model requested ${toolUseBlocks.length} tool call(s)]`);

      // Execute each tool and collect results
      const toolResults = [];
      for (const toolUse of toolUseBlocks) {
        const toolResult = executeToolCall(toolUse.name, toolUse.input);
        console.log(`[Tool result: ${toolResult}]`);

        toolResults.push({
          type: 'tool_result',
          tool_use_id: toolUse.id,
          content: toolResult
        });
      }

      // Add tool results as a user message
      messages.push({
        role: 'user',
        content: toolResults
      });
    } else {
      // No more tool calls, extract the final text response
      const textBlocks = result.content.filter(block => block.type === 'text');
      const finalResponse = textBlocks.map(block => block.text).join('\n');

      console.log(`\n${'='.repeat(60)}`);
      console.log(`[Completed in ${iteration} API call(s)]`);
      return finalResponse;
    }
  }

  throw new Error('Max iterations reached without getting a final response');
}

// Main execution
async function main() {
  try {
    // Test with a message that requires tool calls
    await chat("What's the weather like in Paris, France and what time is it there?");

    console.log('\n\n');

    // Another example with a single tool call
    await chat("What's the current time in Tokyo?");

  } catch (error) {
    console.error('Error:', error.message);
    process.exit(1);
  }
}

main();
