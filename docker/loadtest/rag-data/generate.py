"""Sequential, checkpointed real embedding generation. No Qdrant or LLM calls."""
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import time
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path('/results/legal-perf-v1')
MANIFEST = json.loads((ROOT/'manifest.json').read_text())
OUTPUT = ROOT/'embeddings-v1'
HOST = 'http://kure:11434'


def now():
    return datetime.now(timezone.utc).isoformat()


def get(path):
    with urlopen(HOST+path,timeout=10) as response:
        return json.load(response)


def observe():
    q = 'up{job="molelaw-kure"} or kure_model_ready{operation="embed"} or max(container_memory_working_set_bytes{container_label_com_docker_compose_project="molelaw-loadtest",container_label_com_docker_compose_service="kure"}/container_spec_memory_limit_bytes{container_label_com_docker_compose_project="molelaw-loadtest",container_label_com_docker_compose_service="kure"})'
    with urlopen('http://prometheus:9090/api/v1/query?'+urlencode({'query':q}),timeout=5) as response:
        values = json.load(response)['data']['result']
    point = {v['metric'].get('__name__','memoryRatio'):float(v['value'][1]) for v in values}
    if point.get('up') != 1 or point.get('kure_model_ready') != 1 or not 0 <= point.get('memoryRatio',1) < .85:
        raise RuntimeError('Model observation or memory guard failed')
    point['atUtc'] = now()
    return point


def read_rows(name):
    raw = (ROOT/name).read_bytes()
    assert hashlib.sha256(raw).hexdigest() == MANIFEST['files'][name]['sha256']
    return [json.loads(line) for line in raw.decode().splitlines()]


def encode(rows, threads):
    assert 1 <= len(rows) <= 8 and all(0 < r['tokens'] <= 512 for r in rows)
    payload = {'model':'kure-v1:official','input':[r['text'] for r in rows],'truncate':False,
               'options':{'num_thread':threads,'num_gpu':0}}
    started = time.monotonic()
    with urlopen(Request(HOST+'/api/embed',data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'}),timeout=120) as response:
        result = json.load(response)
    elapsed = time.monotonic()-started
    assert result['model'] == 'kure-v1:official' and len(result['embeddings']) == len(rows)
    prepared = []
    for row, vector in zip(rows,result['embeddings']):
        assert len(vector) == 1024 and all(type(v) in (float,int) and math.isfinite(v) for v in vector)
        norm = math.sqrt(sum(v*v for v in vector))
        assert abs(norm-1) <= .01
        prepared.append({'id':row['id'],'text_sha256':row['sha256'],'tokens':row['tokens'],
                         'dimensions':1024,'norm':norm,'vector':vector})
    return prepared, elapsed


def main():
    assert MANIFEST['dataset'] == 'legal-perf-v1'
    OUTPUT.mkdir(exist_ok=True)
    if (OUTPUT/'result.json').exists():
        from validate_vectors import verify
        verify()
        print('Completed embedding artifacts verified and reused; no inference repeated.',flush=True)
        return
    version = get('/api/version')
    tags = get('/api/tags')
    assert any(t['digest'] == MANIFEST['revision'] and t['name'] == 'kure-v1:official' for t in tags['models'])
    assert version['device'] == 'cpu' and version['dtype'] == 'float32'
    threads = int(os.environ.get('LT_KURE_THREADS','4'))
    identity = {'dataset':MANIFEST['dataset'],'model':MANIFEST['model'],'revision':MANIFEST['revision'],
                'source_files':MANIFEST['files'],'runtime':version,'threads':threads,'generator_batch_size':8}
    identity_file = OUTPUT/'identity.json'
    if identity_file.exists():
        assert json.loads(identity_file.read_text()) == identity, 'Existing vectors use a different configuration'
    else:
        identity_file.write_text(json.dumps(identity,indent=2))
    queries = read_rows('queries.jsonl')
    corpus = read_rows('corpus-1000.jsonl')
    assert len(corpus) == 1000
    clock = time.monotonic()
    resources, batches, smoke = [], [], []
    # One question, then a real chunk from each law, before any larger request.
    selected = [queries[0]]
    for source in ('civil','housing','labor','copyright'):
        selected.append(next(r for r in corpus if r['source_id']==source))
    for row in selected:
        resources.append(observe())
        vectors, elapsed = encode([row],threads)
        smoke.append({'id':row['id'],'tokens':row['tokens'],'seconds':elapsed,'norm':vectors[0]['norm']})
        print(f"Smoke {row['id']}: {row['tokens']} tokens / {elapsed:.3f}s / norm {vectors[0]['norm']:.6f}",flush=True)
    (OUTPUT/'smoke.json').write_text(json.dumps(smoke,indent=2))
    for kind, rows in (('queries',queries),('corpus',corpus)):
        path = OUTPUT/(kind+'.jsonl')
        existing = [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
        assert len(existing) <= len(rows)
        for record, row in zip(existing,rows):
            assert record['id']==row['id'] and record['text_sha256']==row['sha256'] and len(record['vector'])==1024
            assert all(math.isfinite(v) for v in record['vector']) and abs(math.sqrt(sum(v*v for v in record['vector']))-1)<=.01
        with path.open('a') as output:
            for offset in range(len(existing),len(rows),8):
                assert time.monotonic()-clock < 1200, 'Overall generation budget reached; resume from checkpoint'
                point = observe()
                resources.append(point)
                batch = rows[offset:offset+8]
                vectors, elapsed = encode(batch,threads)
                # Only validated batches are appended; a partial write is rejected on resume.
                for vector in vectors:
                    output.write(json.dumps(vector,separators=(',',':'))+'\n')
                output.flush()
                os.fsync(output.fileno())
                batches.append({'kind':kind,'offset':offset,'items':len(batch),'tokens':sum(r['tokens'] for r in batch),'seconds':elapsed,'finishedAtUtc':now()})
                (OUTPUT/'progress.json').write_text(json.dumps({'kind':kind,'completed':offset+len(batch),'total':len(rows),'lastBatchSeconds':elapsed,'atUtc':now()},indent=2))
                with (OUTPUT/'batches.jsonl').open('a') as log:
                    log.write(json.dumps(batches[-1])+'\n')
                if kind=='queries' or (offset//8)%10==0 or offset+len(batch)==len(rows):
                    print(f"{kind}: {offset+len(batch)}/{len(rows)} / batch {elapsed:.3f}s",flush=True)
    files = {}
    for kind, count in (('queries',32),('corpus',1000)):
        path = OUTPUT/(kind+'.jsonl')
        records = [json.loads(line) for line in path.read_text().splitlines()]
        assert len(records)==count==len({r['id'] for r in records})
        files[kind] = {'rows':count,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'bytes':path.stat().st_size}
    elapsed = time.monotonic()-clock
    result = {'completed':True,'finishedAtUtc':now(),'elapsedSecondsThisRun':elapsed,'identity':identity,
              'files':files,'smoke':smoke,'batchesThisRun':len(batches),'resources':resources,
              'note':'Sequential artifact generation including observation and I/O, not a concurrent capacity benchmark; no Qdrant writes or answer generation.'}
    (OUTPUT/'result.json').write_text(json.dumps(result,indent=2))
    print(f'Embedding generation complete: 32 queries + 1000 corpus vectors / {elapsed:.1f}s this run.',flush=True)


if __name__ == '__main__':
    main()
