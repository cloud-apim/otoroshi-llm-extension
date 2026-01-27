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

// ANSI color codes for terminal output
const colors = {
  reset: '\x1b[0m',
  bright: '\x1b[1m',
  dim: '\x1b[2m',
  red: '\x1b[31m',
  green: '\x1b[32m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  magenta: '\x1b[35m',
  cyan: '\x1b[36m',
  white: '\x1b[37m',
  bgRed: '\x1b[41m',
  bgGreen: '\x1b[42m',
  bgYellow: '\x1b[43m',
  bgBlue: '\x1b[44m',
};

// Helper functions for styled output
const log = {
  success: (msg) => console.log(`${colors.green}${colors.bright}✓ SUCCESS${colors.reset} ${colors.green}${msg}${colors.reset}`),
  error: (msg) => console.log(`${colors.red}${colors.bright}✗ ERROR${colors.reset} ${colors.red}${msg}${colors.reset}`),
  warning: (msg) => console.log(`${colors.yellow}${colors.bright}⚠ WARNING${colors.reset} ${colors.yellow}${msg}${colors.reset}`),
  info: (msg) => console.log(`${colors.cyan}ℹ ${msg}${colors.reset}`),
  tool: (msg) => console.log(`${colors.magenta}🔧 ${msg}${colors.reset}`),
  api: (msg) => console.log(`${colors.blue}📡 ${msg}${colors.reset}`),
  stream: (msg) => process.stdout.write(`${colors.white}${msg}${colors.reset}`),
};

function banner(title, type = 'info') {
  const width = 70;
  const line = '═'.repeat(width);
  let color = colors.blue;
  let icon = 'ℹ';

  if (type === 'success') { color = colors.green; icon = '✓'; }
  if (type === 'error') { color = colors.red; icon = '✗'; }
  if (type === 'user') { color = colors.cyan; icon = '👤'; }
  if (type === 'assistant') { color = colors.magenta; icon = '🤖'; }

  console.log(`\n${color}╔${line}╗${colors.reset}`);
  console.log(`${color}║ ${icon} ${title.padEnd(width - 3)}║${colors.reset}`);
  console.log(`${color}╚${line}╝${colors.reset}`);
}

if (!ANTHROPIC_API_KEY) {
  log.error('ANTHROPIC_API_KEY environment variable is required');
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
  log.tool(`Executing tool: ${colors.bright}${name}${colors.reset}`);
  log.info(`Arguments: ${JSON.stringify(args, null, 2)}`);

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
      log.success(`Tool returned weather data`);
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
        const result = {
          timezone: args.timezone,
          datetime: formatter.format(now),
          timestamp: now.toISOString()
        };
        log.success(`Tool returned time data`);
        return JSON.stringify(result);
      } catch (e) {
        log.error(`Invalid timezone: ${args.timezone}`);
        return JSON.stringify({ error: `Invalid timezone: ${args.timezone}` });
      }
    }
    default:
      log.error(`Unknown tool: ${name}`);
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
  let chunkCount = 0;

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
            chunkCount++;
            yield { event: currentEvent, data, chunkCount };
          } catch (e) {
            log.error(`Failed to parse SSE data: ${jsonStr.substring(0, 100)}...`);
          }
        }
      }
    }
    log.info(`Stream completed after ${chunkCount} chunks`);
  } finally {
    reader.releaseLock();
  }
}

/**
 * Call Anthropic with streaming and collect the complete response
 * Returns { content: [...blocks], stopReason }
 */
async function callAnthropicStreaming(messages, system, onTextChunk) {
  const startTime = Date.now();
  log.api(`Calling ${ANTHROPIC_BASE_URL}/v1/messages`);
  log.info(`Model: ${MODEL}`);

  let response;
  try {
    response = await fetch(`${ANTHROPIC_BASE_URL}/v1/messages`, {
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
  } catch (err) {
    log.error(`Network error: ${err.message}`);
    throw err;
  }

  const responseTime = Date.now() - startTime;

  if (!response.ok) {
    const error = await response.text();
    log.error(`API returned HTTP ${response.status}`);
    log.error(`Response: ${error.substring(0, 500)}`);
    throw new Error(`Anthropic API error: ${response.status} - ${error}`);
  }

  log.success(`API responded in ${responseTime}ms (HTTP ${response.status})`);
  log.info(`Starting to read stream...`);

  // Accumulate content blocks
  const contentBlocks = [];
  let currentBlockIndex = -1;
  let currentBlock = null;
  let stopReason = null;

  console.log(`\n${colors.dim}--- Stream output ---${colors.reset}`);

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
            log.error(`Failed to parse tool input: ${block.input}`);
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

  console.log(`\n${colors.dim}--- End stream ---${colors.reset}\n`);

  const totalTime = Date.now() - startTime;
  log.success(`Total API call duration: ${totalTime}ms`);
  log.info(`Stop reason: ${colors.bright}${stopReason}${colors.reset}`);

  const toolUseBlocks = contentBlocks.filter(b => b && b.type === 'tool_use');
  const textBlocks = contentBlocks.filter(b => b && b.type === 'text');

  if (toolUseBlocks.length > 0) {
    log.info(`Tool use blocks received: ${toolUseBlocks.length}`);
    toolUseBlocks.forEach((tc, i) => {
      log.info(`  [${i + 1}] ${tc.name}(${JSON.stringify(tc.input)})`);
    });
  }

  if (textBlocks.length > 0) {
    const totalTextLength = textBlocks.reduce((sum, b) => sum + b.text.length, 0);
    log.info(`Text blocks: ${textBlocks.length} (${totalTextLength} chars total)`);
  }

  return {
    content: contentBlocks.filter(Boolean),
    stopReason
  };
}

async function chat(userMessage) {
  banner(`USER: ${userMessage.substring(0, 50)}${userMessage.length > 50 ? '...' : ''}`, 'user');

  const system = 'You are a helpful assistant. Use the available tools when needed to provide accurate information.';

  const messages = [
    {
      role: 'user',
      content: userMessage
    }
  ];

  let iteration = 0;
  const maxIterations = 10;
  const startTime = Date.now();

  while (iteration < maxIterations) {
    iteration++;
    console.log(`\n${colors.bgBlue}${colors.white}${colors.bright} API CALL #${iteration} (Streaming) ${colors.reset}\n`);

    // Stream text callback - print content as it arrives
    const onTextChunk = (chunk) => {
      log.stream(chunk);
    };

    let result;
    try {
      result = await callAnthropicStreaming(messages, system, onTextChunk);
    } catch (err) {
      banner(`FAILED: ${err.message}`, 'error');
      throw err;
    }

    // Add assistant response to conversation history
    messages.push({
      role: 'assistant',
      content: result.content
    });

    // Debug: show what we received
    log.info(`Received stop_reason: "${result.stopReason}"`);
    const toolUseBlocks = result.content.filter(block => block.type === 'tool_use');
    const textBlocks = result.content.filter(block => block.type === 'text');
    log.info(`Received tool_use blocks: ${toolUseBlocks.length}`);
    log.info(`Received text blocks: ${textBlocks.length}`);

    // Check if the model wants to use tools
    const hasToolCalls = toolUseBlocks.length > 0;

    if (hasToolCalls) {
      console.log(`\n${colors.bgYellow}${colors.bright} TOOL CALLS REQUESTED: ${toolUseBlocks.length} (stop_reason: ${result.stopReason}) ${colors.reset}\n`);

      // Execute each tool and collect results
      const toolResults = [];
      for (const toolUse of toolUseBlocks) {
        const toolResult = executeToolCall(toolUse.name, toolUse.input);
        log.info(`Tool result: ${toolResult.substring(0, 200)}${toolResult.length > 200 ? '...' : ''}`);

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

      // Continue the loop to make the next API call with tool results
      log.info(`Tool results added to messages. Making next API call...`);
      continue;
    }

    // No tool calls, we have the final response
    const totalTime = Date.now() - startTime;
    const finalResponse = textBlocks.map(block => block.text).join('\n');

    banner(`ASSISTANT RESPONSE`, 'assistant');
    console.log(`\n${finalResponse}\n`);

    console.log(`\n${colors.bgGreen}${colors.white}${colors.bright} ✓ CONVERSATION COMPLETED SUCCESSFULLY ${colors.reset}`);
    console.log(`${colors.green}├─ Total API calls: ${iteration}${colors.reset}`);
    console.log(`${colors.green}├─ Total duration: ${totalTime}ms${colors.reset}`);
    console.log(`${colors.green}└─ Final response length: ${finalResponse.length} chars${colors.reset}\n`);

    return finalResponse;
  }

  banner(`Max iterations (${maxIterations}) reached without final response`, 'error');
  throw new Error('Max iterations reached without getting a final response');
}

// Main execution
async function main() {
  console.log(`\n${colors.bgBlue}${colors.white}${colors.bright} ANTHROPIC TOOL CALL DEMO (STREAMING) ${colors.reset}`);
  console.log(`${colors.dim}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${colors.reset}`);
  log.info(`API URL: ${ANTHROPIC_BASE_URL}`);
  log.info(`Model: ${MODEL}`);
  log.info(`API Key: ${ANTHROPIC_API_KEY.substring(0, 8)}...${ANTHROPIC_API_KEY.substring(ANTHROPIC_API_KEY.length - 4)}`);
  console.log(`${colors.dim}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${colors.reset}\n`);

  let successCount = 0;
  let failCount = 0;

  try {
    // Test with a message that requires tool calls
    await chat("What's the weather like in Paris, France and what time is it there?");
    successCount++;
  } catch (error) {
    failCount++;
    log.error(`Test 1 failed: ${error.message}`);
  }

  console.log('\n');

  try {
    // Another example with a single tool call
    await chat("What's the current time in Tokyo?");
    successCount++;
  } catch (error) {
    failCount++;
    log.error(`Test 2 failed: ${error.message}`);
  }

  // Final summary
  console.log(`\n${colors.dim}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${colors.reset}`);
  if (failCount === 0) {
    console.log(`${colors.bgGreen}${colors.white}${colors.bright} ✓ ALL TESTS PASSED (${successCount}/${successCount + failCount}) ${colors.reset}\n`);
    process.exit(0);
  } else {
    console.log(`${colors.bgRed}${colors.white}${colors.bright} ✗ SOME TESTS FAILED (${successCount} passed, ${failCount} failed) ${colors.reset}\n`);
    process.exit(1);
  }
}

main();
