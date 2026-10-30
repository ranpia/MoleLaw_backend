// Synthetic precedent fixture. No real legal conclusions or captured personal information.
const http = require("http");
http.createServer((req, res) => {
  const url = new URL(req.url, "http://localhost");
  const p = url.searchParams;
  const reply = (status, body) => {
    res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
    res.end(JSON.stringify(body));
  };
  if (req.method !== "GET" || p.get("OC") !== "synthetic-key" || p.get("target") !== "prec" || p.get("type") !== "JSON")
    return reply(400, { error: "Contract mismatch" });
  if (url.pathname === "/lawSearch.do") {
    if ((p.has("JO") && p.get("JO") !== "검증법") || !["1", "2"].includes(p.get("search")) || p.get("display") !== "100")
      return reply(400, { error: "Selected search scope contract mismatch" });
    if (p.get("query") === "unavailable") return reply(503, { error: "Synthetic dependency outage" });
    const id = p.get("query") === "invalid-answer" ? "902" : "901";
    if (p.get("query") === "empty") return reply(200, { PrecSearch: { totalCnt: "0", page: p.get("page") } });
    return reply(200, { PrecSearch: { totalCnt: "1", page: p.get("page"), prec: {
      "판례일련번호": id, "사건명": "가상 검증 사건", "사건번호": "2026다0001", "선고일자": "20260101", "법원명": "검증법원"
    } } });
  }
  if (url.pathname === "/lawService.do" && ["901", "902"].includes(p.get("ID")))
    return reply(200, { PrecService: { "판례정보일련번호": p.get("ID"), "사건명": "가상 검증 사건",
      "판시사항": "<p>검증 쟁점</p>", "판결요지": "<b>표시용 요지</b><br>검증&nbsp;내용",
      "판례내용": "<p>이는 실제 판결이 아닌 후속 상담 도구와 저장 검증용 본문입니다.</p><p>다음 문단입니다.</p>", "참조조문": "검증법<br>제1조" } });
  return reply(501, { error: "Unmodeled request" });
}).listen(process.env.PORT || 14039);
