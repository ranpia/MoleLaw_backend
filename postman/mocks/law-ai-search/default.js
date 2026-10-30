// Synthetic documented-field fixture, not captured official output or real legal advice.
const http = require("http");
http.createServer((req, res) => {
  const url = new URL(req.url, "http://localhost");
  const reply = (status, body) => {
    res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
    res.end(JSON.stringify(body));
  };
  if (req.method !== "GET" || url.pathname !== "/lawSearch.do") return reply(501, { error: "Endpoint not modeled" });
  const p = url.searchParams;
  if (p.get("OC") !== "synthetic-key" || p.get("target") !== "aiSearch" || p.get("type") !== "JSON"
      || p.get("search") !== "0" || !p.get("query") || url.searchParams.size !== 7) {
    return reply(400, { error: "Contract mismatch" });
  }
  if (p.get("query") === "denied") return reply(404, { error: "Unavailable upstream endpoint" });
  if (p.get("query") === "broken") return reply(200, { error: "Invalid document" });
  if (p.get("query") === "empty") return reply(200, { AiSearch: { "검색결과개수": 0 } });
  if (p.get("query").startsWith("공항 &") && p.get("query") !== "공항 & 높이+{}") return reply(400, { error: "Query encoding mismatch" });
  const page = Number(p.get("page"));
  return reply(200, { AiSearch: { "검색결과개수": 16, page, "법령조문": [{
    "법령조문ID": page === 1 ? "9001" : "9002", "법령ID": "001234", "법령일련번호": "123456",
    "법령명": "가상 공항검증법", "시행일자": "20260101", "조문번호": page === 1 ? "34" : "35",
    "조문가지번호": "0", "조문제목": "가상 높이 제한", "조문내용": "이 조문은 실제 법령이 아닌 검색 계약 검증용 자료입니다."
  }] } });
}).listen(process.env.PORT || 14038);
