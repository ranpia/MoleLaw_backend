"""Fixed embedding/reranker worker lanes against the shared inference gate."""
from collections import Counter
import hashlib
import json
import math
import os
import re
import statistics
import time
import capacity as probe
import rerank_capacity as rerank


def observe():
    point = probe.point('embed')
    point.update(probe.point('rerank'))
    return point


def execute(row):
    return probe.request(row) if row['operation']=='embed' else rerank.execute(row)


def summarize(records, seconds):
    out = {}
    for operation in ('embed','rerank'):
        rows = [r for r in records if r['operation']==operation]
        success = [r for r in rows if r['status']==200]
        durations = sorted(r['elapsedSeconds'] for r in success)
        percentile = lambda q: durations[min(len(durations)-1,math.ceil(len(durations)*q)-1)] if durations else None
        completed = sum(r['finishSeconds']<=seconds for r in success)
        out[operation] = {'attempts':len(rows),'successesIncludingDrain':len(success),
            'successesWithinWindow':completed,'successRps':completed/seconds,
            'rejections':sum(r['status']==503 for r in rows),
            'responses':dict(Counter(str(r['status'])+':'+r['reason'] for r in rows)),
            'successMeanSeconds':statistics.mean(durations) if durations else None,
            'successP95Seconds':percentile(.95),'successP99Seconds':percentile(.99),
            'successfulDistinctInputs':len({r['inputId'] for r in success})}
    return out


def phase(embeds, ranks, e, r, seconds, label):
    lanes = [embeds]*e+[ranks]*r
    summary = probe.phase([],e+r,seconds,label,execute,observe,135,worker_inputs=lanes)
    records = [json.loads(line) for line in (probe.ROOT/(label+'-requests.jsonl')).read_text().splitlines()]
    summary.update(embeddingWorkers=e,rerankerWorkers=r,candidateCount=ranks[0]['candidate_count'],
                   operations=summarize(records,seconds))
    probe.write(label,summary)
    print(label+': '+json.dumps(summary['operations']),flush=True)
    return summary


def main():
    run_id = os.environ['LT_MIXED_RUN_ID']
    assert re.fullmatch(r'mixed-\d{8}T\d{6}Z',run_id)
    probe.ROOT = probe.DATA/'mixed-runs'/run_id
    probe.ROOT.mkdir(parents=True,exist_ok=False)
    fixture = rerank.FIXTURE
    assert hashlib.sha256(json.dumps(fixture['cases'],ensure_ascii=False,sort_keys=True).encode()).hexdigest()==fixture['cases_sha256']
    embeds = [dict(row,operation='embed') for row in probe.rows('queries.jsonl') if not row.get('negative_control')]
    version = probe.get('/api/version')
    assert version['device']=='cpu' and version['dtype']=='float32' and version['revision']==probe.MANIFEST['revision']
    observe()
    probe.write('environment',{'runtime':version,'embeddingManifest':probe.MANIFEST,
        'reranker':fixture['identity'],'fixtureSha256':fixture['cases_sha256'],
        'cpuLimit':4,'memoryGiB':6,'threads':4,'rerankerBatchSize':2,
        'sharedGate':1,'queueSlots':4,'waitSeconds':5,
        'note':'Independent fixed worker lanes, closed loop, no success think time, 503 backoff .25s; not a sequential RAG/user scenario.'})
    stages = []
    for count in (5,20):
        ranks = [dict(row,operation='rerank') for row in fixture['cases'] if row['candidate_count']==count]
        for e,r,seconds in ((1,1,120),(4,1,60),(4,4,60)):
            label = f'candidates{count}-e{e}-r{r}-s{seconds}'
            phase(embeds,ranks,e,r,10,label+'-warmup')
            result = phase(embeds,ranks,e,r,seconds,label)
            stages.append(result)
            probe.write('stages',stages)
    # Remove contention and verify each operation independently recovers.
    recovery = []
    for operation, row in (('embed',embeds[0]),('rerank',dict(fixture['cases'][0],operation='rerank'))):
        before = time.monotonic()
        assert execute(row)==(200,'success')
        recovery.append({'operation':operation,'status':200,'seconds':time.monotonic()-before})
    probe.write('result',{'completed':True,'finishedAtUtc':probe.utc(),'stages':stages,'recovery':recovery,
        'note':'Direct component contention only. No Qdrant traffic during phases, no backend RAG or answer generation. No latency SLO supplied.'})
    print('Mixed probe complete; embedding and reranker recovery passed.',flush=True)


if __name__=='__main__':
    main()
