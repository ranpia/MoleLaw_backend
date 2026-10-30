"""Reranker-only bounded concurrency probe, sharing the validated phase engine."""
import hashlib
import json
import math
import os
from pathlib import Path
import re
import time
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import capacity as probe

DATA = probe.DATA
FIXTURE = json.loads((DATA/'rerank-fixtures-v1.json').read_text())


def execute(row):
    raw = json.dumps(row['payload'],ensure_ascii=False,sort_keys=True).encode()
    assert hashlib.sha256(raw).hexdigest() == row['payload_sha256']
    try:
        with urlopen(Request(probe.HOST+'/api/rerank',data=raw,headers={'Content-Type':'application/json'}),timeout=120) as reply:
            result = json.load(reply)
        assert result['model'] == FIXTURE['identity']['model'] and result['revision'] == FIXTURE['identity']['revision']
        expected = [d['id'] for d in row['payload']['documents']]
        assert len(result['results']) == len(expected)
        assert [d['id'] for d in result['results']] == expected
        assert len(set(expected)) == len(expected)
        assert all(type(d['score']) in (int,float) and math.isfinite(d['score']) for d in result['results'])
        # Raw logits may be negative or greater than one; they are not probabilities.
        return 200,'success'
    except HTTPError as error:
        try:
            message = json.load(error).get('error','')
        except Exception:
            return error.code,'unknown_http_error'
        reason = {'Embedding queue is full':'queue_full','Inference queue wait expired':'queue_timeout',
                  'Embedding queue wait expired':'queue_timeout'}.get(message,'unexpected_http_error')
        return error.code,reason


def observe():
    return probe.point('rerank')


def stage(profile, cases, concurrency, seconds=60):
    return probe.stage(profile,cases,concurrency,seconds,perform=execute,observation=observe,drain_allowance=135)


def main():
    run_id = os.environ['LT_RERANK_RUN_ID']
    assert re.fullmatch(r'rerank-\d{8}T\d{6}Z',run_id)
    probe.ROOT = DATA/'rerank-runs'/run_id
    probe.ROOT.mkdir(parents=True,exist_ok=False)
    raw = json.dumps(FIXTURE['cases'],ensure_ascii=False,sort_keys=True).encode()
    assert hashlib.sha256(raw).hexdigest() == FIXTURE['cases_sha256']
    version = probe.get('/api/version')
    assert version['device']=='cpu' and version['dtype']=='float32'
    observe()
    probe.write('environment',{'runtime':version,'reranker':FIXTURE['identity'],'fixture_sha256':FIXTURE['cases_sha256'],
        'cpuLimit':4,'memoryLimitGiB':6,'inferenceThreads':4,'rerankerBatchSize':2,
        'sharedInferenceGate':1,'queueSlots':4,'queueWaitSeconds':5,'embeddingRequests':0,
        'note':'CPU reranker only; embedding model loaded but idle. Request-level capacity, candidate count 5 or 20.'})
    all_results, confirmed, baselines = [], {}, []
    for count in (5,20):
        profile = f'candidates{count}'
        inputs = [r for r in FIXTURE['cases'] if r['candidate_count']==count]
        assert len(inputs)==8
        before = time.monotonic()
        assert execute(inputs[0]) == (200,'success')
        baseline = {'profile':profile,'inputId':inputs[0]['id'],'seconds':time.monotonic()-before,
                    'candidates':count,'pairTokens':inputs[0]['pair_tokens']}
        baselines.append(baseline)
        probe.write('single-request-checks',baselines)
        print(f'Single {profile}: {baseline["seconds"]:.3f}s / model and score mapping valid.',flush=True)
        phases = []
        for concurrency in (1,2,4,5):
            result = stage(profile,inputs,concurrency)
            phases.append(result)
            all_results.append(result)
            probe.write('stages',all_results)
            if result['rejections']:
                # Resolve the only skipped integer in a bounded 2 -> 4 search.
                if concurrency==4 and any(r['concurrency']==2 and not r['rejections'] for r in phases):
                    result = stage(profile,inputs,3)
                    phases.append(result)
                    all_results.append(result)
                    probe.write('stages',all_results)
                break
        overloaded = stage(profile,inputs,8)
        all_results.append(overloaded)
        probe.write('stages',all_results)
        stable = [r for r in phases if not r['rejections']]
        assert stable, 'Even one request did not succeed without queue rejection'
        for candidate in sorted(stable,key=lambda r:r['concurrency'],reverse=True):
            result = stage(profile,inputs,candidate['concurrency'],120)
            all_results.append(result)
            probe.write('stages',all_results)
            if not result['rejections']:
                confirmed[profile] = result
                break
        assert profile in confirmed
    recovery = probe.phase([r for r in FIXTURE['cases'] if r['candidate_count']==5],1,10,'recovery',
                          perform=execute,observation=observe,drain_allowance=135)
    assert not recovery['rejections']
    probe.write('result',{'completed':True,'finishedAtUtc':probe.utc(),'stages':all_results,'confirmed':confirmed,
             'singleRequestChecks':baselines,'recovery':recovery,
             'note':'No latency SLO supplied; no rejection in 120s is not a production guarantee. No mixed embedding traffic or answer generation.'})
    print('Reranker standalone capacity probe completed; recovery passed.',flush=True)


if __name__ == '__main__':
    main()
