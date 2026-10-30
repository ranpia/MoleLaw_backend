"""Fixed-origin AI boundary. Synthetic by default; durable shared paid budget."""
import hashlib
import json
import os
from pathlib import Path
import re
import threading
import time
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request,HTTPRedirectHandler,build_opener
from prometheus_client import Counter,Gauge,Histogram,start_http_server

PRICES={'gpt-5.4-mini':(.75,4.5),'gpt-5.4':(2.5,15)}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs): return None
CALLS=Counter('rag_ai_requests','Model gateway requests',['role','status'])
SECONDS=Histogram('rag_ai_seconds','Model gateway elapsed time',['role'])
SPENT=Gauge('rag_ai_estimated_usd','Uncached usage estimate')
HELD=Gauge('rag_ai_reserved_usd','Unsettled maximum cost')
ATTEMPTS=Gauge('rag_ai_attempts','Durable attempted requests')
MODE=Gauge('rag_ai_mode','Active provider mode',['mode'])


class Ledger:
    def __init__(self,path,limit=8,budget=5):
        self.path=Path(path); self.lock=threading.Lock(); self.limit=limit; self.budget=budget
        self.data=json.loads(self.path.read_text()) if self.path.exists() else dict(attempts=0,spent=0.,pending={},stopped=False)
        if self.data['pending']: self.data['stopped']=True

    def save(self):
        self.path.parent.mkdir(parents=True,exist_ok=True)
        temporary=self.path.with_suffix('.tmp')
        with temporary.open('w') as out:
            json.dump(self.data,out); out.flush(); os.fsync(out.fileno())
        temporary.replace(self.path)
        SPENT.set(self.data['spent']); HELD.set(sum(self.data['pending'].values())); ATTEMPTS.set(self.data['attempts'])

    def reserve(self,cost):
        with self.lock:
            if self.data['stopped'] or self.data['attempts']>=self.limit or self.data['spent']+sum(self.data['pending'].values())+cost>self.budget:
                raise ValueError('budget_or_stopped')
            self.data['attempts']+=1; key=str(self.data['attempts']); self.data['pending'][key]=cost; self.save(); return key

    def settle(self,key,cost):
        with self.lock:
            if not 0<=cost<=self.data['pending'][key]:
                self.data['stopped']=True; self.save(); raise ValueError('usage_exceeds_reservation')
            del self.data['pending'][key]; self.data['spent']+=cost; self.save()

    def fail(self):
        with self.lock: self.data['stopped']=True; self.save()

    def state(self):
        with self.lock: return dict(attempts=self.data['attempts'],estimatedUsd=self.data['spent'],reservedUsd=sum(self.data['pending'].values()),stopped=self.data['stopped'])


def role(body):
    system='\n'.join(str(m.get('content','')) for m in body.get('messages',[]) if m.get('role') in ('system','developer'))
    if body.get('stream'): return 'answer'
    if '법률 질문의 유형' in system: return 'question'
    if '근거 본문 읽기 계획자' in system: return 'read'
    if '근거 수집과 검토' in system: return 'decision'
    raise ValueError('unknown_role')


def synthetic(body,kind):
    user=str([m['content'] for m in body['messages'] if m['role']=='user'][-1])
    if kind=='question':
        return json.dumps(dict(type='LAW_EXPLANATION',responseScope='GENERAL_GUIDANCE',searchQueries=[user[:200]],lawSearches=[],missingFacts=[],
            analysis=dict(title='합성 배선 검증',purpose='고정 색인 배선 확인',facts=[],issues=[dict(id='Q1',request='배선 확인',sourceQuote=user[:1000],
            searchTerms=[user[:200]],mentionedTargets=[],candidateTargets=[],missingFacts=[])],relations=[],ambiguities=[])),ensure_ascii=False)
    if kind=='read':
        payload=json.loads(user)
        available=body['response_format']['json_schema']['schema']['properties']['evidenceIds']['items'].get('enum',[])
        return json.dumps({'evidenceIds':available[:2] if not payload.get('readOutcomes') else []})
    if kind=='decision':
        payload=json.loads(user); ids=[e['id'] for e in payload.get('evidence',[])][:2]
        force='fixture-collect' in payload.get('question','')
        return json.dumps(dict(action='COLLECT_LAW' if force else ('ANSWER_READY' if ids else 'INSUFFICIENT'),reason='합성 배선 검증',
            missingIssues=['추가 수집 차단 확인'] if force or not ids else [],evidenceIds=[] if force else ids,searchQueries=[],
            lawSearches=[dict(query='민법',field='TITLE',exactName=True,ministry=None)] if force else []),ensure_ascii=False)
    ids=re.findall(r'\[\[([0-9a-f-]{36})]]',user)
    if not ids: raise ValueError('missing_citation_fixture')
    return '고정 시험 근거를 사용하는 합성 응답입니다. [['+ids[0]+']]'


class Gateway(BaseHTTPRequestHandler):
    ledger=None; mode='synthetic'; records=None
    def log_message(self,*args): pass
    def send_response(self,*args,**kwargs):
        self.responded=True
        return super().send_response(*args,**kwargs)
    def reply(self,status,body):
        raw=json.dumps(body).encode(); self.send_response(status); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(raw))); self.end_headers(); self.wfile.write(raw)
    def do_GET(self):
        if self.path=='/health': return self.reply(200,{'mode':self.mode})
        if self.path=='/state': return self.reply(200,dict(self.ledger.state(),mode=self.mode))
        self.reply(404,{})
    def do_POST(self):
        self.responded=False
        if self.path!='/v1/chat/completions': return self.reply(403,{'error':{'message':'endpoint_denied'}})
        if self.headers.get('Authorization')!='Bearer rag-internal-dummy': return self.reply(401,{'error':{'message':'unauthorized'}})
        key=None; kind='unknown'; status='failed'; started=time.monotonic(); usage=None; applied_ceiling=False
        try:
            if self.headers.get('Transfer-Encoding','').lower()=='chunked':
                if self.headers.get('Content-Length'): raise ValueError('body_limit')
                raw=bytearray()
                while True:
                    size=int(self.rfile.readline(128).split(b';',1)[0].strip(),16)
                    if size==0:
                        if self.rfile.readline(8192) not in (b'\r\n',b'\n'): raise ValueError('body_limit')
                        break
                    if size<0 or len(raw)+size>256*1024: raise ValueError('body_limit')
                    raw.extend(self.rfile.read(size))
                    if self.rfile.read(2)!=b'\r\n': raise ValueError('body_limit')
            else:
                length=int(self.headers.get('Content-Length','0'))
                if not 1<=length<=256*1024: raise ValueError('body_limit')
                raw=self.rfile.read(length)
            body=json.loads(raw); kind=role(body); model=body['model']
            if model not in PRICES or body.get('tools') or body.get('n') not in (None,1): raise ValueError('model_or_tools_denied')
            maximum=body.get('max_completion_tokens')
            if maximum is None:
                # Schema-only per-request Spring AI options can omit the role default.
                # The test boundary supplies an explicit ceiling instead of forwarding unbounded work.
                maximum=4096; body['max_completion_tokens']=maximum
                applied_ceiling=True
                print(f'Gateway applied missing completion ceiling role={kind}',flush=True)
            if type(maximum)!=int or not 1<=maximum<=16384: raise ValueError('completion_limit')
            input_bound=sum(len(str(m.get('content','')).encode())+1024 for m in body['messages'])+1024
            if input_bound>100000: raise ValueError('input_limit')
            rates=PRICES[model]; reservation=(input_bound*rates[0]+maximum*rates[1])/1e6
            key=self.ledger.reserve(reservation if self.mode=='live' else 0.)
            if self.mode=='synthetic':
                content=synthetic(body,kind); usage={'prompt_tokens':100,'completion_tokens':50,'total_tokens':150}
                if body.get('stream'):
                    self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.end_headers()
                    def chunk(delta,finish=None,final_usage=None):
                        data=dict(id='rag-synthetic',object='chat.completion.chunk',created=0,model=model,
                            choices=[] if final_usage else [dict(index=0,delta=delta,finish_reason=finish)])
                        if final_usage: data['usage']=final_usage
                        self.wfile.write(('data: '+json.dumps(data,ensure_ascii=False)+'\n\n').encode()); self.wfile.flush()
                    chunk({'role':'assistant'}); chunk({'content':content[:12]}); chunk({'content':content[12:]}); chunk({},'stop'); chunk({},final_usage=usage)
                    self.wfile.write(b'data: [DONE]\n\n'); self.wfile.flush()
                else: self.reply(200,dict(id='rag-synthetic',object='chat.completion',created=0,model=model,
                    choices=[dict(index=0,message=dict(role='assistant',content=content),finish_reason='stop')],usage=usage))
                self.ledger.settle(key,0.)
            else:
                body['store']=False
                if body.get('stream'): body['stream_options']={'include_usage':True}
                secret=Path('/run/secrets/openai-key').read_text().strip()
                if len(secret)<20: raise ValueError('missing_provider_key')
                request=Request('https://api.openai.com/v1/chat/completions',data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+secret,'Content-Type':'application/json'})
                with build_opener(NoRedirect()).open(request,timeout=30) as upstream:
                    self.send_response(upstream.status); self.send_header('Content-Type',upstream.headers.get('Content-Type','application/json')); self.end_headers()
                    if body.get('stream'):
                        for line in upstream:
                            if time.monotonic()-started>180: raise TimeoutError()
                            if line.startswith(b'data: ') and line.strip()!=b'data: [DONE]':
                                event=json.loads(line[6:]); usage=event.get('usage') or usage
                            self.wfile.write(line); self.wfile.flush()
                    else:
                        raw=upstream.read(4*1024*1024); usage=json.loads(raw).get('usage'); self.wfile.write(raw)
                if not usage: raise ValueError('missing_usage')
                if type(usage.get('prompt_tokens'))!=int or type(usage.get('completion_tokens'))!=int or not 0<usage['prompt_tokens']<=input_bound or not 0<=usage['completion_tokens']<=maximum: raise ValueError('invalid_usage')
                self.ledger.settle(key,(usage['prompt_tokens']*rates[0]+usage['completion_tokens']*rates[1])/1e6)
            status='ok'
        except Exception as failure:
            known={'budget_or_stopped','body_limit','unknown_role','model_or_tools_denied','completion_limit','input_limit','missing_citation_fixture','missing_provider_key','missing_usage','invalid_usage','usage_exceeds_reservation'}
            reason=str(failure) if str(failure) in known else type(failure).__name__
            print(f'Gateway rejected role={kind} reason={reason}',flush=True)
            if key is not None: self.ledger.fail()
            try:
                if not self.responded: self.reply(429 if isinstance(failure,ValueError) and str(failure)=='budget_or_stopped' else 502,{'error':{'message':'rag_gateway_rejected'}})
                else: self.close_connection=True
            except (BrokenPipeError,ConnectionResetError): pass
        finally:
            CALLS.labels(kind,status).inc(); SECONDS.labels(kind).observe(time.monotonic()-started)
            if self.records and key is not None:
                with self.ledger.lock:
                    with self.records.open('a') as out: out.write(json.dumps(dict(attempt=key,role=kind,model=model,
                        reasoningEffort=body.get('reasoning_effort'),maxCompletionTokens=maximum,appliedCompletionCeiling=applied_ceiling,
                        inputTokenUpperBound=input_bound,status=status,elapsedSeconds=time.monotonic()-started,usage=usage,mode=self.mode))+'\n')


def main():
    mode=os.environ.get('RAG_MODEL_MODE','synthetic')
    assert mode in ('synthetic','live')
    Gateway.mode=mode
    MODE.labels(mode).set(1)
    directory=Path('/results'); directory.mkdir(exist_ok=True)
    # Budget ownership is single-process even if a second container is started accidentally.
    import fcntl
    lock_file=(directory/(mode+'-ledger.lock')).open('a')
    fcntl.flock(lock_file,fcntl.LOCK_EX|fcntl.LOCK_NB)
    Gateway.ledger=Ledger(directory/(mode+'-ledger.json'),int(os.environ.get('RAG_MODEL_MAX_CALLS','8')) if mode=='live' else 100,5)
    Gateway.records=directory/(mode+'-requests.jsonl'); Gateway.ledger.save()
    def latest():
        try: return json.loads(Path('/probe/latest.json').read_text())
        except (OSError,ValueError): return {}
    Gauge('rag_probe_first_text_seconds','Last probe first answer delta').set_function(lambda:latest().get('firstTextSeconds',0))
    Gauge('rag_probe_completed_seconds','Last probe validated completion').set_function(lambda:latest().get('completedSeconds',0))
    stages=Gauge('rag_probe_stage_seconds','Last observed client progress interval',['stage'])
    for stage in ('QUESTION_PLANNING','INITIAL_SEARCH','DECIDING','ANSWER_GENERATION','ANSWER_VALIDATION'):
        stages.labels(stage).set_function(lambda stage=stage:latest().get('stageSeconds',{}).get(stage,0))
    start_http_server(9093)
    ThreadingHTTPServer(('0.0.0.0',8080),Gateway).serve_forever()


if __name__=='__main__': main()
