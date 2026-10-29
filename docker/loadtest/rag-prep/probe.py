"""Authenticated NDJSON dry-run, retaining events/times rather than answer text."""
import http.cookiejar
import json
import os
from pathlib import Path
import time
from urllib.request import build_opener,HTTPCookieProcessor,Request,urlopen

HOST='http://rag-backend:8080'


def main():
    import argparse
    parser=argparse.ArgumentParser(); parser.add_argument('--live',action='store_true'); options=parser.parse_args()
    with urlopen('http://rag-model:8080/state',timeout=5) as reply: before=json.load(reply)
    assert before['mode']==('live' if options.live else 'synthetic'),'Model mode mismatch'
    assert not before['stopped'] and before['reservedUsd']==0
    cookie=http.cookiejar.CookieJar(); client=build_opener(HTTPCookieProcessor(cookie))
    def request(method,path,body=None,csrf=None):
        headers={'Content-Type':'application/json'}
        if csrf: headers['X-XSRF-TOKEN']=csrf
        payload=json.dumps(body).encode() if body is not None else None
        with client.open(Request(HOST+path,data=payload,headers=headers,method=method),timeout=180) as reply: return json.load(reply)
    account=json.loads(Path('/fixtures/manifest.json').read_text())['users'][0]
    csrf=request('GET','/api/auth/csrf')['token']
    assert request('POST','/api/auth/login',dict(email=account['email'],password=os.environ['LT_DATA_PASSWORD']),csrf)['accessToken']
    csrf=request('GET','/api/auth/csrf')['token']
    records=[]
    cases=[('빌려준 돈을 돌려받지 못하면 어떻게 하나요?','completed')]
    if not options.live: cases.append(('fixture-collect 추가 법령 수집이 필요한가요?','error'))
    for query,expected in cases:
        started=time.monotonic(); events=[]; first=None; previous=0; stage=None; stage_times={}; terminal=None
        req=Request(HOST+'/api/legal-answers/ndjson',data=json.dumps({'query':query}).encode(),
                    headers={'Content-Type':'application/json','Accept':'application/x-ndjson','X-XSRF-TOKEN':csrf})
        with client.open(req,timeout=180) as reply:
            assert reply.status==200
            for line in reply:
                elapsed=time.monotonic()-started
                assert elapsed<180,'Overall probe deadline'
                event=json.loads(line); kind=event['type']; events.append(dict(type=kind,stage=event.get('stage'),code=event.get('code'),atSeconds=elapsed))
                if kind=='progress':
                    if stage: stage_times[stage]=stage_times.get(stage,0)+elapsed-previous
                    previous=elapsed; stage=event['stage']
                if kind=='answer_delta' and event.get('delta') and first is None: first=elapsed
                if kind in ('completed','error'):
                    assert terminal is None; terminal=kind
        elapsed=time.monotonic()-started
        if stage: stage_times[stage]=stage_times.get(stage,0)+elapsed-previous
        assert terminal==expected,(expected,events)
        if expected=='completed':
            assert first is not None and {'QUESTION_PLANNING','INITIAL_SEARCH','DECIDING','ANSWER_GENERATION','ANSWER_VALIDATION'}<={e['stage'] for e in events if e['stage']}
        else: assert events[-1]['code']=='EVIDENCE_INSUFFICIENT'
        record=dict(expected=expected,terminal=terminal,firstTextSeconds=first,completedSeconds=elapsed,stageSeconds=stage_times,events=events)
        records.append(record)
        if expected=='completed': Path('/results/latest.json').write_text(json.dumps(dict(record,mode=before['mode'])))
        print(f'Dry-run expected={expected} terminal={terminal} elapsed={elapsed:.3f}s',flush=True)
    with urlopen('http://rag-model:8080/state',timeout=5) as reply: after=json.load(reply)
    assert after['estimatedUsd']<=5 and after['reservedUsd']==0 and not after['stopped']
    if not options.live: assert after['estimatedUsd']==0
    Path('/results/'+('paid-smoke.json' if options.live else 'preflight.json')).write_text(json.dumps(dict(completed=True,mode=before['mode'],records=records,modelBefore=before,modelAfter=after),indent=2))


if __name__=='__main__': main()
