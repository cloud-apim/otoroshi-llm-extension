#!/usr/bin/env node

/**
 * Anthropic Tool Call Example - No Streaming
 *
 * This script demonstrates how to handle Anthropic tool calls using pure HTTP fetch.
 * It manages the complete flow: initial request -> tool call detection -> tool execution -> final response
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
  test: (pass, msg) => console.log(`${pass ? colors.green + '✓ PASS' : colors.red + '✗ FAIL'}${colors.reset} ${msg}`),
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

// Tool implementations - returns both the result string and extracted values for validation
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
      return {
        result: JSON.stringify(weatherData),
        valuesToCheck: [
          String(weatherData.temperature),
          weatherData.condition,
          String(weatherData.humidity)
        ]
      };
    }
    case 'get_current_time': {
      try {
        const now = new Date();
        const formatter = new Intl.DateTimeFormat('en-US', {
          timeZone: args.timezone,
          dateStyle: 'full',
          timeStyle: 'long'
        });
        const timeData = {
          timezone: args.timezone,
          datetime: formatter.format(now),
          timestamp: now.toISOString()
        };
        log.success(`Tool returned time data`);
        const timeParts = timeData.datetime.split(' ');
        return {
          result: JSON.stringify(timeData),
          valuesToCheck: [
            timeParts[0], // Day name
            args.timezone.split('/')[1] || args.timezone // City from timezone
          ]
        };
      } catch (e) {
        log.error(`Invalid timezone: ${args.timezone}`);
        return {
          result: JSON.stringify({ error: `Invalid timezone: ${args.timezone}` }),
          valuesToCheck: []
        };
      }
    }
    default:
      log.error(`Unknown tool: ${name}`);
      return {
        result: JSON.stringify({ error: `Unknown tool: ${name}` }),
        valuesToCheck: []
      };
  }
}

async function callAnthropic(messages, system) {
  const startTime = Date.now();
  log.api(`Calling ${ANTHROPIC_BASE_URL}/v1/messages`);
  log.info(`Model: ${MODEL}`);

  let response;
  try {
    log.info(JSON.stringify(messages, null, 2));

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
        tools: tools
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

  const data = await response.json();
  return { data, responseTime };
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

  // Collect all tool results for validation
  const allToolValues = [];

  while (iteration < maxIterations) {
    iteration++;
    console.log(`\n${colors.bgBlue}${colors.white}${colors.bright} API CALL #${iteration} ${colors.reset}\n`);

    // Debug: show current message history
    log.info(`Messages in history: ${messages.length}`);
    messages.forEach((m, i) => {
      const contentPreview = typeof m.content === 'string'
        ? m.content.substring(0, 50)
        : Array.isArray(m.content)
          ? `[${m.content.length} blocks: ${m.content.map(b => b.type).join(', ')}]`
          : JSON.stringify(m.content).substring(0, 50);
      log.info(`  [${i}] ${m.role}: ${contentPreview}...`);
    });

    let result;
    try {
      result = await callAnthropic(messages, system);
    } catch (err) {
      banner(`FAILED: ${err.message}`, 'error');
      throw err;
    }

    const response = result.data;

    // Debug: show raw response structure
    log.info(`Raw response keys: ${Object.keys(response).join(', ')}`);
    log.info(`Received stop_reason: "${response.stop_reason}"`);

    // Ensure content is an array
    const content = Array.isArray(response.content) ? response.content : [];
    log.info(`Content is array: ${Array.isArray(response.content)}, length: ${content.length}`);

    if (content.length > 0) {
      log.info(`Content blocks types: ${content.map(b => b.type).join(', ')}`);
    }

    const toolUseBlocks = content.filter(block => block.type === 'tool_use');
    const textBlocks = content.filter(block => block.type === 'text');
    log.info(`Received tool_use blocks: ${toolUseBlocks.length}`);
    log.info(`Received text blocks: ${textBlocks.length}`);

    // Add assistant response to conversation history
    messages.push({
      role: 'assistant',
      content: content
    });

    // Check if the model wants to use tools
    const hasToolCalls = toolUseBlocks.length > 0;

    if (hasToolCalls) {
      console.log(`\n${colors.bgYellow}${colors.bright} TOOL CALLS REQUESTED: ${toolUseBlocks.length} (stop_reason: ${response.stop_reason}) ${colors.reset}\n`);

      // Execute each tool and collect results
      const toolResults = [];
      for (const toolUse of toolUseBlocks) {
        const { result: toolResult, valuesToCheck } = executeToolCall(toolUse.name, toolUse.input);
        allToolValues.push(...valuesToCheck);
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

    return {
      response: finalResponse,
      toolValues: allToolValues
    };
  }

  banner(`Max iterations (${maxIterations}) reached without final response`, 'error');
  throw new Error('Max iterations reached without getting a final response');
}

/**
 * Validate that the response contains the expected tool values
 */
function validateResponse(response, toolValues, testName) {
  console.log(`\n${colors.bgBlue}${colors.white}${colors.bright} VALIDATION: ${testName} ${colors.reset}\n`);

  if (!response) {
    log.test(false, `Response is empty or null`);
    return false;
  }

  if (toolValues.length === 0) {
    log.warning(`No tool values to validate`);
    return true;
  }

  const responseLower = response.toLowerCase();
  let allPassed = true;
  let passCount = 0;

  for (const value of toolValues) {
    const valueLower = String(value).toLowerCase();
    const found = responseLower.includes(valueLower);
    log.test(found, `Response contains "${value}"`);
    if (found) {
      passCount++;
    } else {
      allPassed = false;
    }
  }

  console.log(`\n${allPassed ? colors.green : colors.red}Validation: ${passCount}/${toolValues.length} values found in response${colors.reset}`);

  return allPassed;
}

// Main execution
async function main() {
  console.log(`\n${colors.bgBlue}${colors.white}${colors.bright} ANTHROPIC TOOL CALL DEMO (NO STREAMING) ${colors.reset}`);
  console.log(`${colors.dim}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${colors.reset}`);
  log.info(`API URL: ${ANTHROPIC_BASE_URL}`);
  log.info(`Model: ${MODEL}`);
  log.info(`API Key: ${ANTHROPIC_API_KEY.substring(0, 8)}...${ANTHROPIC_API_KEY.substring(ANTHROPIC_API_KEY.length - 4)}`);
  console.log(`${colors.dim}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${colors.reset}\n`);

  let successCount = 0;
  let failCount = 0;

  // Test 1: Weather and time query
  try {
    const { response, toolValues } = await chat("What's the weather like in Paris, France and what time is it there?");
    const valid = validateResponse(response, toolValues, "Test 1: Weather + Time");
    if (valid) {
      successCount++;
      log.success(`Test 1 PASSED`);
    } else {
      failCount++;
      log.error(`Test 1 FAILED: Response missing tool values`);
    }
  } catch (error) {
    failCount++;
    log.error(`Test 1 FAILED: ${error.message}`);
  }

  console.log('\n');

  // Test 2: Single tool call
  try {
    const { response, toolValues } = await chat("What's the current time in Tokyo?");
    const valid = validateResponse(response, toolValues, "Test 2: Time only");
    if (valid) {
      successCount++;
      log.success(`Test 2 PASSED`);
    } else {
      failCount++;
      log.error(`Test 2 FAILED: Response missing tool values`);
    }
  } catch (error) {
    failCount++;
    log.error(`Test 2 FAILED: ${error.message}`);
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
