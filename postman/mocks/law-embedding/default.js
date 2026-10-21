// Two-dimensional deterministic fixture only; never use for semantic search.
// Simulation routeKey "api" restores production /api/tags and /api/embed paths.
const http = require("http");
const MODEL = "kure-v1:official";
const REVISION = "8b418a58414668e75532ed045c22d9ca018ae2b2";
http.createServer((req, res) => {
  const reply = (status, body) => {
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(JSON.stringify(body));
  };
  if (req.method === "GET" && req.url === "/tags") {
    return reply(200, { models: [{ name: MODEL, digest: REVISION }] });
  }
  if (req.method === "POST" && req.url === "/embed") {
    const body = req.body;
    if (!body || body.model !== MODEL || body.truncate !== false ||
        !Array.isArray(body.input) || body.input.length < 1 || body.input.length > 32 ||
        body.input.some(value => typeof value !== "string" || !value.trim()) ||
        !body.options || body.options.num_gpu !== 0 || body.options.num_thread !== 6) {
      return reply(400, { error: "Invalid embedding contract" });
    }
    return reply(200, { model: MODEL, embeddings: body.input.map(() => [0.6, 0.8]) });
  }
  return reply(404, { error: "Unknown embedding endpoint" });
}).listen(process.env.PORT || 14036);
