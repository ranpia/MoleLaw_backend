const http = require('http');
http.createServer((req, res) => {
  const reply = (status, body) => { res.writeHead(status, {'Content-Type':'application/json'}); res.end(JSON.stringify(body)); };
  if (req.method !== 'POST' || req.url !== '/v1/chat/completions') return reply(501, {error:{message:'Unmodeled request'}});
  let body='';
  req.on('data', chunk => { body += chunk; });
  req.on('end', () => {
  let data;
  try { data=JSON.parse(body); } catch { return reply(400,{error:{message:'Invalid request JSON'}}); }
  if (req.headers.authorization !== 'Bearer synthetic-key' || data.response_format?.type !== 'json_schema'
      || data.response_format.json_schema?.strict !== true || data.tools?.length || data.stream === true
      || data.messages?.length !== 2 || data.model !== 'gpt-5.4-mini' || data.reasoning_effort !== 'low' || data.max_completion_tokens !== 2048)
    return reply(400,{error:{message:'Suggestion contract mismatch'}});
  const content=data.messages.find(message => message.role === 'user')?.content;
  let input; try { input=JSON.parse(content); } catch { return reply(400,{error:{message:'Missing structured draft'}}); }
  if (input.query === 'fixture-unavailable') return reply(503,{error:{message:'Synthetic dependency outage',type:'server_error'}});
  const suggestions=input.query === 'fixture-invalid' ? [] : [{label:'판단 기준',focus:'위 상황에서 검토할 기준을 설명해 주세요.'}];
  reply(200,{id:'suggestion-fixture',object:'chat.completion',created:0,model:data.model,
    choices:[{index:0,message:{role:'assistant',content:JSON.stringify({suggestions})},finish_reason:'stop'}],
    usage:{prompt_tokens:100,completion_tokens:40,total_tokens:140}});
  });
}).listen(process.env.PORT || 14040);
