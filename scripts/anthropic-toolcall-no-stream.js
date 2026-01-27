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

async function callAnthropic(messages, system) {
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
      tools: tools
    })
  });

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`Anthropic API error: ${response.status} - ${error}`);
  }

  return response.json();
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
    console.log(`\n[API Call #${iteration}]`);

    const response = await callAnthropic(messages, system);

    // Add assistant response to conversation history
    messages.push({
      role: 'assistant',
      content: response.content
    });

    // Check if the model wants to use tools (stop_reason === 'tool_use')
    if (response.stop_reason === 'tool_use') {
      // Find all tool_use blocks in the response
      const toolUseBlocks = response.content.filter(block => block.type === 'tool_use');
      console.log(`[Model requested ${toolUseBlocks.length} tool call(s)]`);

      // Execute each tool and collect results
      const toolResults = [];
      for (const toolUse of toolUseBlocks) {
        const result = executeToolCall(toolUse.name, toolUse.input);
        console.log(`[Tool result: ${result}]`);

        toolResults.push({
          type: 'tool_result',
          tool_use_id: toolUse.id,
          content: result
        });
      }

      // Add tool results as a user message
      messages.push({
        role: 'user',
        content: toolResults
      });
    } else {
      // No more tool calls, extract the final text response
      const textBlocks = response.content.filter(block => block.type === 'text');
      const finalResponse = textBlocks.map(block => block.text).join('\n');

      console.log(`\n${'='.repeat(60)}`);
      console.log(`Assistant: ${finalResponse}`);
      console.log('='.repeat(60));
      console.log(`\n[Completed in ${iteration} API call(s)]`);
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
