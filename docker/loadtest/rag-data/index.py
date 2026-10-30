"""Idempotent insertion into one isolated fixture collection, then query smoke."""
import json
import math
import os
from pathlib import Path
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid
from validate_vectors import verify

ROOT = Path('/results/legal-perf-v1')
HOST = 'http://qdrant:6333'
COLLECTION = 'molelaw_perf_legal_v1_kure_1024'


def request(method, suffix, payload=None):
    body = json.dumps(payload).encode() if payload is not None else None
    req = Request(HOST+'/collections/'+COLLECTION+suffix,data=body,method=method,
                  headers={'api-key':os.environ['LT_QDRANT_API_KEY'],'Content-Type':'application/json'})
    with urlopen(req,timeout=60) as response:
        result = json.load(response)
    assert result['status'] == 'ok'
    return result['result']


def point_id(record):
    return str(uuid.uuid5(uuid.NAMESPACE_URL,'molelaw:legal-perf-v1:'+record['id']))


def rows(path):
    return [json.loads(line) for line in path.read_text().splitlines()]


def main():
    verify()
    manifest = json.loads((ROOT/'manifest.json').read_text())
    generated = json.loads((ROOT/'embeddings-v1/result.json').read_text())
    metadata = {'purpose':'isolated_loadtest_fixture','dataset':manifest['dataset'],
                'model':manifest['model'],'revision':manifest['revision'],
                'corpus_sha256':manifest['files']['corpus-1000.jsonl']['sha256'],
                'vectors_sha256':generated['files']['corpus']['sha256']}
    try:
        info = request('GET','')
    except HTTPError as error:
        if error.code != 404:
            raise
        request('PUT','',{'vectors':{'size':1024,'distance':'Cosine'},'metadata':metadata})
        info = request('GET','')
    assert info['config']['metadata'] == metadata, 'Existing collection is not the same fixture; refuse overwrite'
    assert info['config']['params']['vectors']['size'] == 1024
    assert info['config']['params']['vectors']['distance'] == 'Cosine'
    corpus = rows(ROOT/'corpus-1000.jsonl')
    vectors = rows(ROOT/'embeddings-v1/corpus.jsonl')
    assert len(corpus) == len(vectors) == 1000
    lookup = {}
    clock = time.monotonic()
    for offset in range(0,1000,64):
        points = []
        for item, encoded in zip(corpus[offset:offset+64],vectors[offset:offset+64]):
            assert item['id'] == encoded['id']
            key = point_id(item)
            lookup[key] = item
            points.append({'id':key,'vector':encoded['vector'],'payload':{
                'dataset':'legal-perf-v1','chunk_id':item['id'],'article_id':item['article_id'],
                'source_id':item['source_id'],'lsiSeq':item['lsiSeq'],'version':item['version'],
                'text':item['text'],'text_sha256':item['sha256'],'tokens':item['tokens'],'source_url':item['source_url']}})
        ack = request('PUT','/points?wait=true',{'points':points})
        assert ack['status'] == 'completed'
        print(f'Qdrant fixture indexed: {min(offset+64,1000)}/1000',flush=True)
    info = request('GET','')
    assert info['points_count'] == 1000
    # Point identity and cosine preservation, without assuming tie order in top-k.
    for item, encoded in zip(corpus[:3],vectors[:3]):
        result = request('POST','/points/query',{'query':encoded['vector'],
                         'filter':{'must':[{'has_id':[point_id(item)]}]},'limit':1})
        assert len(result['points']) == 1 and result['points'][0]['id'] == point_id(item)
        assert result['points'][0]['score'] > .999
    queries = rows(ROOT/'queries.jsonl')
    query_vectors = rows(ROOT/'embeddings-v1/queries.jsonl')
    diagnostics = []
    for query, vector in zip(queries,query_vectors):
        started = time.monotonic()
        result = request('POST','/points/query',{'query':vector['vector'],'limit':5,'with_payload':True,'with_vector':False})
        elapsed = time.monotonic()-started
        assert len(result['points']) == 5
        hits = []
        for hit in result['points']:
            assert hit['id'] in lookup and math.isfinite(hit['score'])
            expected = lookup[hit['id']]
            assert hit['payload']['text_sha256'] == expected['sha256']
            hits.append({'id':hit['id'],'chunk_id':expected['id'],'article_id':expected['article_id'],'score':hit['score']})
        diagnostics.append({'query_id':query['id'],'seconds':elapsed,'top5':hits,
             'candidate_article_in_top5':bool(set(query['candidate_articles']) & {h['article_id'] for h in hits}),
             'negative_control':query.get('negative_control',False),
             'note':'Diagnostic only; candidates are not relevance gold labels.'})
    output = {'completed':True,'collection':COLLECTION,'metadata':metadata,'points':info['points_count'],
              'indexedVectors':info.get('indexed_vectors_count'),'elapsedSeconds':time.monotonic()-clock,
              'queries':diagnostics,'selfChecks':3,
              'note':'Sequential insert/query smoke on 1000 points, not ANN capacity or quality benchmark; no answer generation.'}
    (ROOT/'qdrant-result.json').write_text(json.dumps(output,indent=2))
    print('Qdrant fixture ready: 1000 points; 3 cosine/identity checks; 32 queries with valid top-5.',flush=True)


if __name__ == '__main__':
    main()
