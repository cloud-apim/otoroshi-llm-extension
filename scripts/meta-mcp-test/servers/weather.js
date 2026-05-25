import { z } from "zod";
import { startMcpHttpServer } from "../lib/mcp-http-server.js";

const CITIES = {
  paris:    { tempC: 14, condition: "cloudy",  humidity: 78 },
  london:   { tempC: 11, condition: "rainy",   humidity: 85 },
  tokyo:    { tempC: 19, condition: "sunny",   humidity: 60 },
  newyork:  { tempC: 9,  condition: "windy",   humidity: 55 },
  sydney:   { tempC: 24, condition: "sunny",   humidity: 50 },
};

const norm = (s) => String(s ?? "").toLowerCase().replace(/[^a-z]/g, "");
const text = (value) => ({
  content: [{ type: "text", text: typeof value === "string" ? value : JSON.stringify(value) }],
});

await startMcpHttpServer({
  name: "weather-server",
  port: Number(process.env.WEATHER_PORT ?? 7002),
  register: (server) => {
    server.tool(
      "list_cities",
      "List the cities for which weather is available.",
      {},
      async () => text(Object.keys(CITIES)),
    );

    server.tool(
      "get_current_weather",
      "Get the current (fake) weather for a city.",
      { city: z.string().describe("City name (e.g. Paris, London, Tokyo, New York, Sydney)") },
      async ({ city }) => {
        const c = CITIES[norm(city)];
        if (!c) return { isError: true, content: [{ type: "text", text: `unknown city '${city}'` }] };
        return text({ city, ...c });
      },
    );

    server.tool(
      "get_forecast",
      "Get a multi-day fake forecast for a city.",
      {
        city: z.string(),
        days: z.number().int().min(1).max(7).default(3),
      },
      async ({ city, days }) => {
        const base = CITIES[norm(city)];
        if (!base) return { isError: true, content: [{ type: "text", text: `unknown city '${city}'` }] };
        const forecast = Array.from({ length: days }, (_, i) => ({
          dayOffset: i,
          tempC: base.tempC + ((i % 3) - 1),
          condition: base.condition,
        }));
        return text({ city, forecast });
      },
    );
  },
});
