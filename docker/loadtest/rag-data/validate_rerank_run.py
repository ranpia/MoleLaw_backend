"""Audit completed reranker request windows and freeze monitoring evidence."""
from collections import Counter
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import time
from urllib.parse import urlencode
from urllib.request import urlopen
import capacity


def main():
    root = capacity.DATA/'rerank-runs'/os.environ['LT_RERANK_RUN_ID']
    result = json.loads((root/'result.json').read_text())
    assert result['completed']
    fixture = json.loads((capacity.DATA/'rerank-fixtures-v1.json').read_text())
    cases = {row['id']:row for row in fixture['cases']}
    audits = []
    resources = []
    phases = []
    for path in sorted(root.glob('*.json')):
        summary = json.loads(path.read_text())
        if not isinstance(summary,dict) or 'measurementSeconds' not in summary:
            continue
        records_path = path.with_name(path.stem+'-requests.jsonl')
        records = [json.loads(line) for line in records_path.read_text().splitlines()]
        seconds = summary['measurementSeconds']
        assert len(records) == summary['attemptsStarted']
        assert not summary['errors']
        assert all(0 <= row['startSeconds'] < seconds and row['finishSeconds'] >= row['startSeconds'] for row in records)
        assert all(row['inputId'] in cases for row in records)
        responses = Counter(str(row['status'])+':'+row['reason'] for row in records)
        assert dict(responses) == summary['responses']
        assert set(responses) <= {'200:success','503:queue_full','503:queue_timeout'}
        successes = [row for row in records if row['status']==200]
        within = sum(row['finishSeconds'] <= seconds for row in successes)
        assert within == summary['successesWithinWindow']
        assert len(successes) == summary['successesIncludingDrain']
        assert summary['successRps'] == within/seconds
        assert summary['rejections'] == len(records)-len(successes)
        resources.extend(summary['resources'])
        phases.append(summary)
        audits.append({'label':summary['label'],'records':len(records),
            'successfulDistinctQueries':len({cases[row['inputId']]['query_id'] for row in successes}),
            'successfulInputs':dict(Counter(row['inputId'] for row in successes)),
            'requestsSha256':hashlib.sha256(records_path.read_bytes()).hexdigest()})
    assert phases
    for name,summary in result['confirmed'].items():
        assert summary['measurementSeconds']==120 and summary['rejections']==0
    assert result['recovery']['rejections']==0
    # Allow one scrape interval after the final request drains.
    for _ in range(11):
        final = capacity.point('rerank')
        gates = [v for key,v in final.items() if key.startswith(('kure_inference_active:', 'kure_queue_waiting:'))]
        if len(gates)==4 and all(v==0 for v in gates):
            break
        time.sleep(3)
    assert len(gates)==4 and all(v==0 for v in gates), 'Inference gate did not drain'
    # Scraped counters provide independent evidence that embeddings were idle.
    start = min(datetime.fromisoformat(p['startedAtUtc']).timestamp() for p in phases)
    end = datetime.fromisoformat(result['finishedAtUtc']).timestamp()
    query = 'kure_requests_total or on(__name__,job,instance,operation) kure_inference_active or on(__name__,job,instance,operation) kure_queue_waiting'
    params = urlencode({'query':query,'start':start,'end':end,'step':5})
    with urlopen('http://prometheus:9090/api/v1/query_range?'+params,timeout=15) as reply:
        monitoring = json.load(reply)
    assert monitoring['status']=='success'
    embed_deltas = []
    for series in monitoring['data']['result']:
        labels = series['metric']
        if labels.get('__name__')=='kure_requests_total' and labels.get('operation')=='embed':
            values = [float(pair[1]) for pair in series['values']]
            assert values and min(values)==max(values), 'Embedding traffic or counter reset during reranker run'
            embed_deltas.append({'labels':labels,'delta':max(values)-min(values)})
    assert embed_deltas, 'Embedding counter series missing'
    (root/'server-evidence.json').write_text(json.dumps(monitoring,indent=2))
    report = {'phaseCount':len(phases),'phaseAudits':audits,'embeddingCounterDeltas':embed_deltas,
        'generatorMaxCores':max(r['generatorCores'] for r in resources),
        'generatorMaxMemoryBytes':max(r['generatorMemoryBytes'] for r in resources),
        'targetMaxMemoryRatio':max(r['memoryRatio'] for r in resources),
        'finalEmbeddingObservation':capacity.point(), 'finalRerankerObservation':final,
        'pairTokens':{str(count):{'min':min(n for c in cases.values() if c['candidate_count']==count for n in c['pair_tokens']),
            'max':max(n for c in cases.values() if c['candidate_count']==count for n in c['pair_tokens'])} for count in (5,20)}}
    assert all(v==0 for key,v in report['finalRerankerObservation'].items() if key.startswith(('kure_inference_active:', 'kure_queue_waiting:')))
    (root/'validation.json').write_text(json.dumps(report,indent=2))
    print(f"Validated {len(phases)} phases; embedding counter delta=0; inference gate drained; evidence saved.")


if __name__=='__main__':
    main()
