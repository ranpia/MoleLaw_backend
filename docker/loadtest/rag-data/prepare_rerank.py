"""Freeze real top-20 candidate sets; tokenize pairs offline, no inference."""
import hashlib
import json
from pathlib import Path
from index import request as qdrant, rows, ROOT
from validate_vectors import verify

MODEL = 'BAAI/bge-reranker-v2-m3'
REVISION = '953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e'
QUERY_IDS = ('q01','q02','q11','q12','q17','q18','q26','q27')
FIXTURE = ROOT/'rerank-fixtures-v1.json'


def main():
    verify()
    manifest = json.loads((ROOT/'manifest.json').read_text())
    vectors = json.loads((ROOT/'embeddings-v1/result.json').read_text())
    identity = {'dataset':'legal-perf-v1','model':MODEL,'revision':REVISION,
                'source_files':manifest['files'],'query_vectors_sha256':vectors['files']['queries']['sha256'],
                'corpus_vectors_sha256':vectors['files']['corpus']['sha256'],'query_ids':list(QUERY_IDS),
                'candidate_counts':[5,20],'pair_token_limit':1024}
    if FIXTURE.exists():
        saved = json.loads(FIXTURE.read_text())
        assert saved['identity'] == identity
        raw = json.dumps(saved['cases'],ensure_ascii=False,sort_keys=True).encode()
        assert hashlib.sha256(raw).hexdigest() == saved['cases_sha256']
        print('Frozen reranker fixtures verified and reused; no search/inference repeated.',flush=True)
        return
    from transformers import AutoTokenizer
    from huggingface_hub import snapshot_download
    from importlib.metadata import version
    path = Path(snapshot_download(MODEL,revision=REVISION,local_files_only=True))
    assert path.name == REVISION
    tokenizer = AutoTokenizer.from_pretrained(path,local_files_only=True,trust_remote_code=False)
    collection = qdrant('GET','')
    metadata = collection['config']['metadata']
    assert metadata['dataset']=='legal-perf-v1' and metadata['vectors_sha256']==vectors['files']['corpus']['sha256']
    assert collection['points_count'] == 1000
    queries = {r['id']:r for r in rows(ROOT/'queries.jsonl')}
    encoded = {r['id']:r for r in rows(ROOT/'embeddings-v1/queries.jsonl')}
    corpus = {r['id']:r for r in rows(ROOT/'corpus-1000.jsonl')}
    cases = []
    for query_id in QUERY_IDS:
        query = queries[query_id]
        found = qdrant('POST','/points/query',{'query':encoded[query_id]['vector'],'limit':20,'with_payload':True,'with_vector':False})['points']
        assert len(found)==20 and len({r['id'] for r in found})==20
        docs = []
        for hit in found:
            payload = hit['payload']
            source = corpus[payload['chunk_id']]
            assert payload['text_sha256'] == source['sha256'] and payload['text']==source['text']
            docs.append({'id':source['id'],'text':source['text']})
        lengths = tokenizer([query['text']]*20,[d['text'] for d in docs],truncation=False,return_length=True)['length']
        assert all(0 < length <= 1024 for length in lengths)
        for count in (5,20):
            payload = {'model':MODEL,'revision':REVISION,'query':query['text'],'documents':docs[:count]}
            cases.append({'id':f'{query_id}-candidates{count}','query_id':query_id,'candidate_count':count,
                          'payload':payload,'tokens':sum(lengths[:count]),'pair_tokens':lengths[:count],
                          'payload_sha256':hashlib.sha256(json.dumps(payload,ensure_ascii=False,sort_keys=True).encode()).hexdigest()})
    saved = {'identity':identity,'cases':cases,'cases_sha256':hashlib.sha256(json.dumps(cases,ensure_ascii=False,sort_keys=True).encode()).hexdigest(),
             'tokenizer_files':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in path.iterdir() if p.is_file() and (p.name.startswith('tokenizer') or p.name in ('sentencepiece.bpe.model','special_tokens_map.json'))},
             'libraries':{name:version(name) for name in ('transformers','tokenizers')},
             'note':'Fixed Qdrant top-20, prefixes of 5/20; no relevance gold labels or encoder calls.'}
    FIXTURE.write_text(json.dumps(saved,ensure_ascii=False,indent=2))
    print(f'Reranker fixtures: {len(cases)} cases / 8 queries / 5 or 20 candidates / maximum pair tokens {max(max(c["pair_tokens"]) for c in cases)}.',flush=True)


if __name__ == '__main__':
    main()
