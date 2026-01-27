#!/usr/bin/env node

/**
 * OpenAI Tool Call Example - No Streaming
 *
 * This script demonstrates how to handle OpenAI tool calls using pure HTTP fetch.
 * It manages the complete flow: initial request -> tool call detection -> tool execution -> final response
 */

const OPENAI_API_KEY = process.env.OPENAI_API_KEY || "foo";
const OPENAI_BASE_URL = process.env.OPENAI_BASE_URL || 'http://openai.oto.tools:9999/v1';
const MODEL = process.env.OPENAI_MODEL || 'gpt-5.2';

if (!OPENAI_API_KEY) {
  console.error('Error: OPENAI_API_KEY environment variable is required');
  process.exit(1);
}

// Define available tools
const tools = [
  {
    type: 'function',
    function: {
      name: 'get_weather',
      description: 'Get the current weather for a given location',
      parameters: {
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
    }
  },
  {
    type: 'function',
    function: {
      name: 'get_current_time',
      description: 'Get the current time for a given timezone',
      parameters: {
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
  }
];

// Tool implementations
function executeToolCall(name, args) {
  console.log(`\n[Executing tool: ${name}]`);
  console.log(`[Arguments: ${JSON.stringify(args)}]`);

  switch (name) {
    case 'get_weather': {
      // Simulated weather data
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

async function callOpenAI(messages) {
  const response = await fetch(`${OPENAI_BASE_URL}/chat/completions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${OPENAI_API_KEY}`
    },
    body: JSON.stringify({
      model: MODEL,
      messages: messages,
      tools: tools,
      stream: false
    })
  });

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`OpenAI API error: ${response.status} - ${error}`);
  }

  return response.json();
}

async function chat(userMessage) {
  console.log(`\n${'='.repeat(60)}`);
  console.log(`User: ${userMessage}`);
  console.log('='.repeat(60));

  const messages = [
    {
      role: 'system',
      content: 'You are a helpful assistant. Use the available tools when needed to provide accurate information.'
    },
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

    const response = await callOpenAI(messages);
    const choice = response.choices[0];
    const assistantMessage = choice.message;

    // Add assistant message to conversation history
    messages.push(assistantMessage);

    // Check if the model wants to call tools
    if (choice.finish_reason === 'tool_calls' && assistantMessage.tool_calls) {
      console.log(`[Model requested ${assistantMessage.tool_calls.length} tool call(s)]`);

      // Execute each tool call and add results to messages
      for (const toolCall of assistantMessage.tool_calls) {
        const functionName = toolCall.function.name;
        const functionArgs = JSON.parse(toolCall.function.arguments);

        const result = executeToolCall(functionName, functionArgs);
        console.log(`[Tool result: ${result}]`);

        // Add tool result to messages
        messages.push({
          role: 'tool',
          tool_call_id: toolCall.id,
          content: result
        });
      }
    } else {
      // No more tool calls, we have the final response
      console.log(`\n${'='.repeat(60)}`);
      console.log(`Assistant: ${assistantMessage.content}`);
      console.log('='.repeat(60));
      console.log(`\n[Completed in ${iteration} API call(s)]`);
      return assistantMessage.content;
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
