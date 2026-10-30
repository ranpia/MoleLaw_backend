"""Small application-compatible index; actual title-newline-text CPU embeddings."""
import hashlib
import json
import os
from pathlib import Path
import re
import uuid
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from validate_vectors import verify

ROOT=Path('/results/legal-perf-v1')
OUT=ROOT/'app-rag-v2'
COLLECTION='molelaw_service_loadtest_rag_app_v1'
MODEL='kure-v1:official'
REVISION='8b418a58414668e75532ed045c22d9ca018ae2b2'
IDENTITY=dict(model=MODEL,revision=REVISION,dimensions=1024,chunkSize=512,chunkOverlap=64,documentFormat='title-newline-text-v1')
LAW_IDS={'civil':'001706','housing':'001248','labor':'001872','copyright':'000798'}


def digest(value):
    return hashlib.sha256(value).hexdigest()


def stable_id(text):
    return str(uuid.uuid5(uuid.NAMESPACE_URL,'molelaw:app-rag-v1:'+text))


def http(host,method,path,body=None,key=False):
    headers={'Content-Type':'application/json'}
    if key: headers['api-key']=os.environ['LT_QDRANT_API_KEY']
    raw=json.dumps(body,ensure_ascii=False).encode() if body is not None else None
    with urlopen(Request(host+path,data=raw,method=method,headers=headers),timeout=120) as reply:
        return json.load(reply)


def q(method,suffix='',body=None):
    return http('http://qdrant:6333',method,'/collections/'+COLLECTION+suffix,body,True)['result']


def main():
    verify()
    OUT.mkdir(exist_ok=True)
    config=json.loads(Path('/data/sources.json').read_text())
    articles={r['id']:r for r in (json.loads(line) for line in Path('/data/snapshot/articles.jsonl').read_text().splitlines())}
    fixtures=json.loads((ROOT/'rerank-fixtures-v1.json').read_text())
    fingerprint=digest(json.dumps(dict(IDENTITY,collection=COLLECTION),sort_keys=True,separators=(',',':')).encode())
    sources,points,sql=[],[],[]
    for law in config['sources']:
        selected=[]
        for case in fixtures['cases']:
            if case['query_id'] not in ('q01','q11','q17','q26'): continue
            for doc in case['payload']['documents']:
                article_id=doc['id'].split(':chunk-')[0]
                if article_id.startswith(law['id']+':') and article_id not in selected: selected.append(article_id)
        for article_id in sorted(articles):
            if article_id.startswith(law['id']+':') and article_id not in selected: selected.append(article_id)
        units=[]
        for article_id in selected[:5]:
            article=articles[article_id]
            assert digest(article['text'].encode())==article['text_sha256']
            text=re.sub(r'\s+',' ',article['text']).strip()
            match=re.match(r'제(\d+)조(?:의(\d+))?(?:\(([^)]*)\))?',text)
            assert match,article_id
            number,branch,subtitle=match.groups()
            units.append({'조문번호':str(int(number)),'조문가지번호':str(int(branch or '0')),
                          '조문제목':subtitle or '','조문내용':text})
        raw=json.dumps({'기본정보':{'법령명_한글':law['name']},'조문':{'조문단위':units}},ensure_ascii=False,separators=(',',':'))
        content_hash=digest(raw.encode())
        reference=stable_id(law['id']+':'+content_hash)
        generation=stable_id(reference+':'+fingerprint)
        url='https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq='+law['lsiSeq']
        count=0
        for unit in units:
            branch=unit['조문가지번호']
            path=unit['조문번호']+('-'+branch if int(branch) else '')
            title=law['name']+' 제'+unit['조문번호']+'조'+('의'+branch if int(branch) else '')
            if unit['조문제목']: title+='('+unit['조문제목']+')'
            text=unit['조문내용']
            for i,start in enumerate(range(0,len(text),448)):
                chunk=text[start:start+512]
                point_id=stable_id(generation+':'+path+':'+str(i))
                points.append({'id':point_id,'input':title+'\n'+chunk,'payload':{
                    'source_type':'LAW','unit_id':law['lsiSeq']+':'+path,'reference_id':reference,
                    'external_id':law['lsiSeq'],'law_id':LAW_IDS[law['id']],'effective_date':law['effective_date'],
                    'title':title,'text':chunk,'article_text':text,'source_url':url,'content_hash':content_hash,
                    'generation':generation,'config_fingerprint':fingerprint,'model_revision':REVISION}})
                count+=1
                if start+512>=len(text): break
        source=dict(id=reference,externalId=law['lsiSeq'],lawId=LAW_IDS[law['id']],effectiveDate=law['effective_date'],sourceUrl=url,
                    contentHash=content_hash,rawJson=raw,generation=generation,chunkCount=count)
        sources.append(source)
        def lit(value): return "CONVERT(0x"+value.encode().hex()+" USING utf8mb4)"
        sql.append("INSERT INTO legal_source(id,source_type,external_id,law_id,effective_date,source_url,collected_at,content_hash,raw_json) VALUES ("+
                   ','.join([lit(reference),"'LAW'",lit(law['lsiSeq']),lit(LAW_IDS[law['id']]),lit(law['effective_date']),lit(url),'UTC_TIMESTAMP(6)',lit(content_hash),lit(raw)])+") ON DUPLICATE KEY UPDATE id=id;")
        sql.append("INSERT INTO legal_source_index(generation,source_id,content_hash,config_fingerprint,collection_name,model_name,model_revision,dimensions,status,started_at,completed_at,chunk_count,dedup_key) VALUES ("+
                   ','.join([lit(generation),lit(reference),lit(content_hash),lit(fingerprint),lit(COLLECTION),lit(MODEL),lit(REVISION),'1024',"'COMPLETE'",'UTC_TIMESTAMP(6)','UTC_TIMESTAMP(6)',str(count),'NULL'])+") ON DUPLICATE KEY UPDATE generation=generation;")
    marker=digest(json.dumps(points,ensure_ascii=False,sort_keys=True).encode())
    saved=OUT/'manifest.json'
    old=json.loads(saved.read_text()) if saved.exists() else None
    repair=old is not None and old['inputSha256']!=marker and all('lawId' not in s for s in old['sources'])
    if old and not repair: assert old['inputSha256']==marker,'Fixture changed; refuse replacement'
    try: info=q('GET')
    except HTTPError as failure:
        if failure.code!=404: raise
        q('PUT',body={'vectors':{'size':1024,'distance':'Cosine'},'metadata':IDENTITY})
        info=q('GET')
    assert info['config']['metadata']==IDENTITY
    assert info['points_count']==0 or old is not None,'Unowned nonempty collection'
    cache={}
    previous=ROOT/'app-rag-v1/manifest.json'
    if not old and previous.exists():
        previous_manifest=json.loads(previous.read_text())
        assert previous_manifest['identity']==IDENTITY
        prior=http('http://qdrant:6333','POST','/collections/'+previous_manifest['collection']+'/points/scroll',
                   {'limit':100,'with_payload':True,'with_vector':True},True)['result']['points']
        assert len(prior)==previous_manifest['pointCount']
        for item in prior:
            assert item['payload']['config_fingerprint']==previous_manifest['fingerprint']
            cache[item['payload']['title']+'\n'+item['payload']['text']]=item['vector']
    if repair:
        found=q('POST','/points',{'ids':[p['id'] for p in points],'with_payload':True,'with_vector':True})
        lookup={str(p['id']):p for p in found}
        assert len(lookup)==len(points)
        for point in points:
            stored=lookup[point['id']]
            assert stored['payload']['law_id']=='' and dict(stored['payload'],law_id=point['payload']['law_id'])==point['payload']
            point['vector']=stored['vector']
        q('PUT','/points?wait=true',{'points':[{k:v for k,v in p.items() if k!='input'} for p in points]})
    if not old:
        for offset in range(0,len(points),4):
            batch=points[offset:offset+4]
            missing=[p['input'] for p in batch if p['input'] not in cache]
            if missing:
                reply=http('http://kure:11434','POST','/api/embed',{'model':MODEL,'input':missing,
                           'truncate':False,'options':{'num_thread':int(os.environ.get('LT_KURE_THREADS','4')),'num_gpu':0}})
                assert reply['model']==MODEL and len(reply['embeddings'])==len(missing)
                cache.update(zip(missing,reply['embeddings']))
            import math
            for point in batch:
                vector=cache[point['input']]
                assert len(vector)==1024 and all(type(v) in (int,float) and math.isfinite(v) for v in vector)
                assert abs(math.sqrt(sum(v*v for v in vector))-1)<.01
                point['vector']=vector
            q('PUT','/points?wait=true',{'points':[{k:v for k,v in p.items() if k!='input'} for p in batch]})
            print(f'App-compatible index: {min(offset+4,len(points))}/{len(points)}',flush=True)
    if not old or repair:
        saved.write_text(json.dumps(dict(collection=COLLECTION,identity=IDENTITY,fingerprint=fingerprint,
            inputSha256=marker,sources=sources,pointCount=len(points),articleCount=20,
            note='20 pinned public articles, bootstrap only; verified original LIDs; actual title-newline-text embeddings.'),ensure_ascii=False,indent=2))
    assert q('GET')['points_count']==len(points)
    for offset in range(0,len(points),64):
        batch=points[offset:offset+64]
        found=q('POST','/points',{'ids':[p['id'] for p in batch],'with_payload':True,'with_vector':False})
        lookup={str(p['id']):p for p in found}
        assert len(lookup)==len(batch)
        for point in batch: assert lookup[point['id']]['payload']==point['payload']
    (OUT/'seed.sql').write_text('\n'.join(sql)+'\n')
    (OUT/'contract.json').write_text(json.dumps([dict(id=p['id'],payload=p['payload'],embeddingInputSha256=digest(p['input'].encode())) for p in points],ensure_ascii=False))
    print(f'Prepared {len(points)} app-compatible points / 20 articles / 4 pinned laws; original collection unchanged.')


if __name__=='__main__': main()
