// Synthetic regression for one MST with staged enforcement dates. No real statute text.
const http = require("http");
http.createServer((req, res) => {
  const url = new URL(req.url, "http://localhost");
  const reply = (status, body) => {
    res.writeHead(status, { "Content-Type": "application/json" });
    res.end(JSON.stringify(body));
  };
  if (req.method !== "GET" || url.pathname !== "/lawService.do") {
    return reply(501, { error: "Endpoint not modeled" });
  }
  const params = url.searchParams;
  if (params.get("MST") !== "289515" || params.get("type") !== "JSON") {
    return reply(400, { error: "Invalid fixture request" });
  }
  const dated = params.get("target") === "eflaw" && params.get("efYd") === "20270309";
  return reply(200, {
    "법령": {
      "기본정보": { "법령명_한글": "검증법", "법령ID": "1", "시행일자": dated ? "20270309" : "20261209" },
      "조문": { "조문단위": { "조문번호": "1", "조문내용": "실제 법령이 아닌 시행일 일치 검증 자료입니다." } }
    }
  });
}).listen(process.env.PORT || 14037);
