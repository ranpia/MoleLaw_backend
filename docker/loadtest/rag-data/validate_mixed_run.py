"""Audit lane isolation, raw request windows, and shared-server evidence."""
from collections import Counter
from datetime import datetime
import hashlib
import json
import os
import time
from urllib.parse import urlencode
from urllib.request import urlopen
import capacity as probe


def main():
    root = probe.DATA/'mixed-runs'/os.environ['LT_MIXED_RUN_ID']
    result = json.loads((root/'result.json').read_text())
    assert result['completed'] and len(result['stages'])==6
    assert {r['operation'] for r in result['recovery'] if r['status']==200}=={'embed','rerank'}
    fixture = json.loads((probe.DATA/'rerank-fixtures-v1.json').read_text())
    source = {('rerank',r['id']):r for r in fixture['cases']}
    source.update({('embed',r['id']):r for r in probe.rows('queries.jsonl') if not r.get('negative_control')})
    audits, phases, resources = [], [], []
    for path in sorted(root.glob('*.json')):
        summary = json.loads(path.read_text())
        if not isinstance(summary,dict) or 'measurementSeconds' not in summary:
            continue
        records_path = path.with_name(path.stem+'-requests.jsonl')
        records = [json.loads(line) for line in records_path.read_text().splitlines()]
        seconds = summary['measurementSeconds']
        assert not summary['errors'] and len(records)==summary['attemptsStarted']
        assert summary['concurrency']==summary['embeddingWorkers']+summary['rerankerWorkers']
        for row in records:
            assert 0 <= row['startSeconds'] < seconds and row['finishSeconds']>=row['startSeconds']
            assert row['tokens']==source[(row['operation'],row['inputId'])]['tokens']
            assert 0<=row['worker']<summary['concurrency']
            assert row['operation']==('embed' if row['worker']<summary['embeddingWorkers'] else 'rerank')
            if row['operation']=='rerank':
                assert source[('rerank',row['inputId'])]['candidate_count']==summary['candidateCount']
        responses = Counter(str(r['status'])+':'+r['reason'] for r in records)
        assert dict(responses)==summary['responses']
        assert set(responses)<={'200:success','503:queue_full','503:queue_timeout'}
        for operation, expected in summary['operations'].items():
            group = [r for r in records if r['operation']==operation]
            successes = [r for r in group if r['status']==200]
            within = sum(r['finishSeconds']<=seconds for r in successes)
            assert group and len(group)==expected['attempts']
            assert len(successes)==expected['successesIncludingDrain']
            assert within==expected['successesWithinWindow'] and within/seconds==expected['successRps']
            assert len(group)-len(successes)==expected['rejections']
            assert dict(Counter(str(r['status'])+':'+r['reason'] for r in group))==expected['responses']
        e = [r for r in records if r['operation']=='embed']
        r = [r for r in records if r['operation']=='rerank']
        overlap = any(a['startSeconds']<b['finishSeconds'] and b['startSeconds']<a['finishSeconds'] for a in e for b in r)
        assert overlap, 'Operations never overlapped'
        audits.append({'label':summary['label'],'requests':len(records),'overlap':True,
            'sha256':hashlib.sha256(records_path.read_bytes()).hexdigest()})
        phases.append(summary)
        resources.extend(summary['resources'])
    assert len(phases)==12
    for _ in range(11):
        final = probe.point('embed')
        final.update(probe.point('rerank'))
        gates = [v for k,v in final.items() if k.startswith(('kure_inference_active:','kure_queue_waiting:'))]
        if len(gates)==4 and all(v==0 for v in gates):
            break
        time.sleep(3)
    assert len(gates)==4 and all(v==0 for v in gates)
    start = min(datetime.fromisoformat(p['startedAtUtc']).timestamp() for p in phases)
    end = datetime.fromisoformat(result['finishedAtUtc']).timestamp()
    query = 'kure_requests_total or on(__name__,job,instance,operation) kure_inference_active or on(__name__,job,instance,operation) kure_queue_waiting or on(__name__,job,instance,operation) kure_inference_duration_seconds_sum or on(__name__,job,instance,operation) kure_queue_wait_seconds_sum'
    params = urlencode({'query':query,'start':start,'end':end,'step':5})
    with urlopen('http://prometheus:9090/api/v1/query_range?'+params,timeout=15) as reply:
        evidence = json.load(reply)
    assert evidence['status']=='success'
    deltas = {}
    for series in evidence['data']['result']:
        labels = series['metric']
        if labels.get('__name__')=='kure_requests_total' and labels.get('status')=='200':
            values = [float(p[1]) for p in series['values']]
            assert all(a<=b for a,b in zip(values,values[1:])), 'Counter reset'
            deltas[labels['operation']]=max(values)-min(values)
    assert all(deltas.get(op,0)>0 for op in ('embed','rerank'))
    report = {'phaseCount':len(phases),'audits':audits,'successfulServerCounterDeltas':deltas,
        'maxSharedActive':max(sum(v for k,v in r.items() if k.startswith('kure_inference_active:')) for r in resources),
        'maxSharedWaiting':max(sum(v for k,v in r.items() if k.startswith('kure_queue_waiting:')) for r in resources),
        'generatorMaxCores':max(r['generatorCores'] for r in resources),
        'generatorMaxMemoryBytes':max(r['generatorMemoryBytes'] for r in resources),
        'targetMaxMemoryRatio':max(r['memoryRatio'] for r in resources),'finalObservation':final}
    (root/'server-evidence.json').write_text(json.dumps(evidence,indent=2))
    (root/'validation.json').write_text(json.dumps(report,indent=2))
    print(f'Validated {len(phases)} phases: lanes, overlap, request windows/counts/hashes, both operation counters, drained gate.')


if __name__=='__main__':
    main()
