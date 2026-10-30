/** POST SSE/NDJSON client. Events share one payload contract. No automatic retry. */
export async function streamInitialAnswer({
  url, body, csrfToken, onEvent, signal, format = "sse",
}) {
  if (format !== "sse" && format !== "ndjson") throw new Error("지원하지 않는 스트림 형식입니다.");
  const ndjson = format === "ndjson";
  url ??= ndjson ? "/api/chat-rooms/ndjson" : "/api/chat-rooms/stream";
  const mediaType = ndjson ? "application/x-ndjson" : "text/event-stream";
  const response = await fetch(url, {
    method: "POST", credentials: "include", signal,
    headers: { "Content-Type": "application/json", Accept: mediaType, "X-XSRF-TOKEN": csrfToken },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`상담 요청 실패: HTTP ${response.status}`);
  if (!response.headers.get("content-type")?.includes(mediaType) || !response.body) {
    throw new Error("요청한 스트림 형식의 응답을 받지 못했습니다.");
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let terminal = null;
  try {
    while (!terminal) {
      const { value, done } = await reader.read();
      buffer += done ? decoder.decode() : decoder.decode(value, { stream: true });
      let boundary;
      while ((boundary = (ndjson ? /\r?\n/ : /\r?\n\r?\n/).exec(buffer)) !== null) {
        const frame = buffer.slice(0, boundary.index);
        buffer = buffer.slice(boundary.index + boundary[0].length);
        let event = "message";
        const data = [];
        if (ndjson) {
          if (!frame.trim()) continue;
          data.push(frame);
        } else {
          for (const line of frame.split(/\r?\n/)) {
            if (line.startsWith("event:")) event = line.slice(6).replace(/^ /, "");
            if (line.startsWith("data:")) data.push(line.slice(5).replace(/^ /, ""));
          }
        }
        if (!data.length) continue;
        const payload = JSON.parse(data.join("\n"));
        if (ndjson) event = payload.type;
        if (payload.type !== event) throw new Error("잘못된 SSE 이벤트입니다.");
        if (event !== "heartbeat") await onEvent(payload);
        if (event === "completed" || event === "error") { terminal = payload; break; }
      }
      if (done) break;
    }
    if (!terminal) throw new Error("완료 전에 연결이 끊겼습니다. 임시 답변을 확정하지 마세요.");
    return terminal;
  } finally {
    await reader.cancel().catch(() => {});
    reader.releaseLock();
  }
}

// Example UI wiring:
// let savedEvidence = []; // load existing room evidence before starting the request
// await streamInitialAnswer({ format: "ndjson", body: { content: question }, csrfToken, signal, onEvent: e => {
//   if (e.type === "progress") setStatus(e.message);
//   if (e.type === "info") setInfo(e.info); // replace the entire snapshot
//   if (e.type === "evidence") {
//     if (e.evidence.state === "SAVED") savedEvidence = e.evidence.items;
//     setEvidenceCards(e.evidence.items); // full replacement; use item.id as the card key
//   }
//   if (e.type === "answer_delta") appendProvisionalAnswer(e.delta);
//   if (e.type === "completed") {
//     setAnswer(e.response.answer);
//     if (e.response.info) setInfo(e.response.info);
//     if (e.room) setRoom(e.room);
//     if (e.messageResult) appendSavedMessage(e.messageResult);
//   }
//   if (e.type === "error") { clearProvisionalAnswer(); setEvidenceCards(savedEvidence); setError(e.message); }
// }});
// Also restore savedEvidence if fetch throws or ends before completed.
