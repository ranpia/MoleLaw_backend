"""Bounded closed-loop embedding concurrency probe, one text per HTTP request."""
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import statistics
import threading
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

DATA = Path('/results/legal-perf-v1')
HOST = 'http://kure:11434'
MANIFEST = json.loads((DATA/'manifest.json').read_text())
THREADS = int(os.environ.get('LT_KURE_THREADS','4'))
ROOT = None


def utc():
    return datetime.now(timezone.utc).isoformat()


def write(name, value):
    (ROOT/(name+'.json')).write_text(json.dumps(value,indent=2))


def get(path):
    with urlopen(HOST+path,timeout=5) as reply:
        return json.load(reply)


def rows(filename):
    raw = (DATA/filename).read_bytes()
    assert hashlib.sha256(raw).hexdigest() == MANIFEST['files'][filename]['sha256']
    return [json.loads(line) for line in raw.decode().splitlines()]


def point(operation='embed'):
    assert operation in ('embed','rerank')
    q = 'up{job="molelaw-kure"} or on(__name__,job,instance,operation) kure_model_ready{operation="embed"} or on(__name__,job,instance,operation) kure_inference_active or on(__name__,job,instance,operation) kure_queue_waiting or on(__name__,job,instance,operation) max(container_memory_working_set_bytes{container_label_com_docker_compose_project="molelaw-loadtest",container_label_com_docker_compose_service="kure"}/container_spec_memory_limit_bytes{container_label_com_docker_compose_project="molelaw-loadtest",container_label_com_docker_compose_service="kure"})'
    q = q.replace('kure_model_ready{operation="embed"}', 'kure_model_ready{operation="'+operation+'"}')
    with urlopen('http://prometheus:9090/api/v1/query?'+urlencode({'query':q}),timeout=4) as reply:
        vector = json.load(reply)['data']['result']
    out = {'atUtc':utc()}
    for item in vector:
        name = item['metric'].get('__name__','memoryRatio')
        if item['metric'].get('operation'):
            name += ':'+item['metric']['operation']
        out[name] = float(item['value'][1])
    assert out.get('up') == 1 and out.get('kure_model_ready:'+operation) == 1
    assert 0 <= out.get('memoryRatio',1) < .85
    return out


def request(row):
    payload = {'model':'kure-v1:official','input':[row['text']],'truncate':False,
               'options':{'num_thread':THREADS,'num_gpu':0}}
    try:
        with urlopen(Request(HOST+'/api/embed',data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'}),timeout=30) as reply:
            result = json.load(reply)
        assert result['model'] == 'kure-v1:official' and len(result['embeddings']) == 1
        vector = result['embeddings'][0]
        assert len(vector) == 1024 and all(type(v) in (int,float) and math.isfinite(v) for v in vector)
        assert abs(math.sqrt(sum(v*v for v in vector))-1) <= .01
        return 200,'success'
    except HTTPError as error:
        try:
            message = json.load(error).get('error','')
        except Exception:
            return error.code,'unknown_http_error'
        reason = {'Embedding queue is full':'queue_full',
                  'Embedding queue wait expired':'queue_timeout',
                  'Inference queue wait expired':'queue_timeout'}.get(message,'unexpected_http_error')
        return error.code,reason


def phase(inputs, concurrency, seconds, label, perform=None, observation=None, drain_allowance=45):
    assert concurrency in (1,2,3,4,5,8) and seconds in (10,60,120)
    assert drain_allowance in (45,135)
    perform = perform or request
    observation = observation or point
    stop = threading.Event()
    finished = threading.Event()
    lock = threading.Lock()
    records, resources = [], []
    error = []
    counter = 0
    start = time.monotonic()
    end = start+seconds
    started_utc = utc()

    def fail(reason):
        with lock:
            if not error:
                error.append(reason)
        stop.set()

    def monitor():
        misses = high = 0
        old_cpu, old_time = None, None
        while not finished.is_set():
            try:
                sample = observation()
                cpu = dict(line.split() for line in Path('/sys/fs/cgroup/cpu.stat').read_text().splitlines())
                used = int(cpu['usage_usec'])/1e6
                clock = time.monotonic()
                sample['generatorCores'] = (used-old_cpu)/(clock-old_time) if old_cpu is not None else 0
                sample['generatorMemoryBytes'] = int(Path('/sys/fs/cgroup/memory.current').read_text())
                old_cpu, old_time = used, clock
                resources.append(sample)
                high = high+1 if sample['generatorCores'] > .85 else 0
                if high >= 3 or sample['generatorMemoryBytes'] > 400*1024**2:
                    fail('generator_resource_limit')
                misses = 0
            except Exception:
                misses += 1
                if misses >= 3:
                    fail('monitoring_or_model_resource_guard')
            finished.wait(3)

    def worker():
        nonlocal counter
        while not stop.is_set() and time.monotonic() < end:
            with lock:
                row = inputs[counter % len(inputs)]
                counter += 1
            before = time.monotonic()
            try:
                status, reason = perform(row)
            except Exception as exc:
                status, reason = 0,type(exc).__name__
            after = time.monotonic()
            record = {'inputId':row['id'],'tokens':row['tokens'],'startSeconds':before-start,
                      'finishSeconds':after-start,'elapsedSeconds':after-before,'status':status,'reason':reason}
            with lock:
                records.append(record)
            if status != 200 and not (status == 503 and reason in ('queue_full','queue_timeout')):
                fail('unexpected_response_or_invalid_output')
            if status == 503:
                # Bound overload traffic instead of spinning on immediate rejection.
                stop.wait(.25)

    monitor_thread = threading.Thread(target=monitor,daemon=True)
    monitor_thread.start()
    try:
        with ThreadPoolExecutor(max_workers=concurrency) as pool:
            jobs = [pool.submit(worker) for _ in range(concurrency)]
            for job in jobs:
                job.result(timeout=seconds+drain_allowance)
    finally:
        finished.set()
        monitor_thread.join(timeout=6)
    successes = [r for r in records if r['status']==200]
    completed = [r for r in successes if r['finishSeconds'] <= seconds]
    rejected = [r for r in records if r['status']==503]
    durations = sorted(r['elapsedSeconds'] for r in successes)
    percentile = lambda q: durations[min(len(durations)-1,math.ceil(len(durations)*q)-1)] if durations else None
    summary = {'label':label,'concurrency':concurrency,'startedAtUtc':started_utc,'finishedAtUtc':utc(),
       'measurementSeconds':seconds,'elapsedIncludingDrainSeconds':time.monotonic()-start,
       'attemptsStarted':len(records),'successesIncludingDrain':len(successes),
       'successesWithinWindow':len(completed),'successRps':len(completed)/seconds,
       'drainSuccesses':len(successes)-len(completed),'rejections':len(rejected),
       'rejectionFraction':len(rejected)/len(records) if records else 0,
       'responses':dict(Counter(str(r['status'])+':'+r['reason'] for r in records)),
       'successMeanSeconds':statistics.mean(durations) if durations else None,
       'successP95Seconds':percentile(.95),'successP99Seconds':percentile(.99),
       'meanSuccessfulInputTokens':statistics.mean(r['tokens'] for r in successes) if successes else None,
       'errors':error,'resources':resources,'requestBatchSize':1,'retryOn503':False,'backoffOn503Seconds':.25}
    write(label,summary)
    (ROOT/(label+'-requests.jsonl')).write_text(''.join(json.dumps(r)+'\n' for r in records))
    if error:
        raise RuntimeError('Probe stopped: '+error[0])
    assert successes, 'No successful embedding requests'
    return summary


def stage(profile, inputs, concurrency, seconds=60, perform=None, observation=None, drain_allowance=45):
    label = f'{profile}-c{concurrency}-s{seconds}'
    # Drain every phase completely before starting the next one.
    phase(inputs,concurrency,10,label+'-warmup',perform,observation,drain_allowance)
    result = phase(inputs,concurrency,seconds,label,perform,observation,drain_allowance)
    print(f"{label}: success {result['successRps']:.3f}/s, p95 {result['successP95Seconds']:.3f}s, rejected {result['rejections']}/{result['attemptsStarted']}",flush=True)
    return result


def main():
    global ROOT
    run_id = os.environ['LT_EMBED_RUN_ID']
    assert re.fullmatch(r'embed-\d{8}T\d{6}Z',run_id)
    ROOT = DATA/'concurrency-runs'/run_id
    ROOT.mkdir(parents=True,exist_ok=False)
    version = get('/api/version')
    assert version['device']=='cpu' and version['dtype']=='float32'
    assert version['revision']==MANIFEST['revision']
    assert get('/api/tags')['models'][0]['digest']==MANIFEST['revision']
    point()
    write('environment',{'runtime':version,'threads':THREADS,'cpuLimit':4,'memoryLimitGiB':6,
          'dataset':MANIFEST['dataset'],'revision':MANIFEST['revision'],'sourceFiles':MANIFEST['files'],
          'modelConcurrency':1,'queueCapacity':4,'queueWaitSeconds':5,
          'note':'Embedding only; reranker loaded but idle. Closed-loop fixed worker counts, no success think time.'})
    profiles = {'questions':[r for r in rows('queries.jsonl') if not r.get('negative_control')],
                'document512':[r for r in rows('length-inputs.jsonl') if r['target_tokens']==512]}
    selected = os.environ.get('LT_EMBED_PROFILE','')
    if selected:
        assert selected in profiles
        concurrency = int(os.environ['LT_EMBED_CONCURRENCY'])
        assert concurrency in (1,2,3,4,5,8)
        result = stage(selected,profiles[selected],concurrency)
        confirmation = stage(selected,profiles[selected],concurrency,120) if not result['rejections'] else None
        recovery = phase(profiles['questions'],1,10,'recovery')
        assert not recovery['rejections']
        write('result',{'completed':True,'mode':'targeted_refinement','stage':result,'confirmation':confirmation,
                         'recovery':recovery,'finishedAtUtc':utc()})
        return
    all_results = []
    confirmations = {}
    for profile, inputs in profiles.items():
        phases = []
        for concurrency in (1,2,4,5,8):
            result = stage(profile,inputs,concurrency)
            phases.append(result)
            all_results.append(result)
            write('stages',all_results)
        stable = [r for r in phases if not r['rejections'] and not r['errors']]
        assert stable
        # A hard admission count is not a latency SLO. Confirm the highest tested
        # no-rejection count, falling back only if the longer observation rejects.
        for candidate in sorted(stable,key=lambda r:r['concurrency'],reverse=True):
            result = stage(profile,inputs,candidate['concurrency'],120)
            all_results.append(result)
            write('stages',all_results)
            if not result['rejections']:
                confirmations[profile] = result
                break
        assert profile in confirmations
    # Verify that pressure is gone and a fresh request still succeeds.
    recovery = phase(profiles['questions'],1,10,'recovery')
    assert recovery['rejections']==0
    result = {'completed':True,'finishedAtUtc':utc(),'stages':all_results,'confirmed':confirmations,
              'recovery':recovery,'limits':{'hardAdmission':5,'sharedGate':1,'waitSeconds':5},
              'note':'No latency SLO supplied; confirmed means no rejection observed in 120s, not production capacity. Qdrant/answer generation not exercised.'}
    write('result',result)
    print('Embedding concurrency probe complete; recovery passed.',flush=True)


if __name__ == '__main__':
    main()
