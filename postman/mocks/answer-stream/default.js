// Synthetic provider only; never forward traffic or log prompts/credentials.
const http = require('http');
const SOURCE = '00000000-0000-0000-0000-000000000001';
let attempts = 0, cancelled = 0;
const held = new Set();
http.createServer((req, res) => {
  if (req.url.startsWith('/openai/')) req.url=req.url.slice('/openai'.length);
  const json = (code, value) => { res.writeHead(code, {'Content-Type':'application/json'}); res.end(JSON.stringify(value)); };
  if (req.method === 'GET' && req.url === '/control/state') return json(200,{attempts,held:held.size,cancelled});
  if (req.method === 'POST' && req.url === '/control/release') {
    for (const finish of [...held]) finish();
    return json(200,{released:true});
  }
  if (req.method !== 'POST' || req.url !== '/v1/chat/completions') return json(501,{error:{message:'Unmodeled endpoint'}});
  let body = '';
  req.on('data', chunk => { body += chunk; });
  req.on('end', () => {
    let data;
    try { data=JSON.parse(body); } catch { return json(400,{error:{message:'Invalid JSON'}}); }
    attempts++;
    if (req.headers.authorization !== 'Bearer synthetic-key' || data.stream !== true || data.tools?.length ||
        data.model !== 'gpt-5.4-mini' || data.max_completion_tokens !== 4096)
      return json(400,{error:{message:'Streaming contract mismatch'}});
    const text = data.messages.filter(m => m.role === 'user').map(m => m.content).join('\n');
    if (text.includes('fixture-429')) return json(429,{error:{message:'Synthetic quota limit',type:'rate_limit_error'}});
    if (text.includes('fixture-503')) return json(503,{error:{message:'Synthetic provider outage',type:'server_error'}});
    res.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-cache'});
    res.flushHeaders();
    const chunk = (delta, finish_reason=null) => res.write('data: '+JSON.stringify({id:'answer-fixture',object:'chat.completion.chunk',created:0,
      model:data.model,choices:[{index:0,delta,finish_reason}]})+'\n\n');
    const finish = () => {
      held.delete(finish);
      if (res.destroyed) return;
      chunk({content:text.includes('fixture-invalid') ? '잘못된 인용 [[00000000-0000-0000-0000-000000000099]]' : `검증 안내 [[${SOURCE}]]`});
      chunk({},'stop'); res.end('data: [DONE]\n\n');
    };
    res.on('close', () => { if (held.delete(finish)) cancelled++; });
    if (text.includes('fixture-broken')) return res.end('data: {broken-json}\n\n');
    if (text.includes('fixture-empty')) { chunk({},'stop'); return res.end('data: [DONE]\n\n'); }
    chunk({role:'assistant'});
    chunk({content:'임시 안내 "확인"\n'});
    chunk({content:'계속 확인 중입니다. '});
    if (text.includes('fixture-hold') || text.includes('fixture-stall')) { held.add(finish); return; }
    if (text.includes('fixture-truncated')) return res.end();
    setTimeout(finish,100);
  });
}).listen(process.env.PORT || 14041);
