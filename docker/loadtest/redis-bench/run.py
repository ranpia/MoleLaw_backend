"""Isolated Redis native benchmark and actual application Lua checks."""
import asyncio
import csv
import io
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time
import traceback
from datetime import datetime, timezone
from urllib.parse import urlencode
from urllib.request import urlopen
import redis
import redis.asyncio as aredis

HOST = 'redis-bench'
PASSWORD = os.environ.get('REDISCLI_AUTH', '')
CREATE = Path('/lua/refresh-create.lua').read_text()
ROTATE = Path('/lua/refresh-rotate.lua').read_text()
ROOT = None
RESULTS = []
STOP = threading.Event()
GUARD = []


def stamp():
    return datetime.now(timezone.utc).isoformat()


def save(name, obj):
    (ROOT / (name + '.json')).write_text(json.dumps(obj, indent=2))


def monitor():
    misses = high = 0
    while not STOP.wait(5):
        try:
            q = 'redis_up{job="molelaw-redis-bench"} or (container_memory_working_set_bytes{container_label_com_docker_compose_service="redis-bench"}/container_spec_memory_limit_bytes{container_label_com_docker_compose_service="redis-bench"})'
            with urlopen('http://prometheus:9090/api/v1/query?' + urlencode({'query': q}), timeout=3) as response:
                vector = json.load(response)['data']['result']
            point = {v['metric'].get('__name__', 'memoryRatio'): float(v['value'][1]) for v in vector}
            if point.get('redis_up') != 1 or 'memoryRatio' not in point:
                raise RuntimeError('Observation missing')
            high = high + 1 if point['memoryRatio'] > .9 else 0
            point['at'] = stamp()
            GUARD.append(point)
            misses = 0
            if high >= 3:
                STOP.set()
        except Exception:
            misses += 1
            if misses >= 3:
                STOP.set()


def check():
    if STOP.is_set():
        raise RuntimeError('Observation or resource guard stopped benchmark')


def native(command, clients, payload=1024, pipeline=1, sustained=True):
    """Calibrate request count, then retain full native percentiles of each sample."""
    if any(s.get('command') == command and s.get('clients') == clients and s.get('payloadBytes') == payload
           and s.get('pipeline') == pipeline and s.get('kind') == ('sustained' if sustained else 'short_exploration') for s in RESULTS):
        print(f'Reusing completed {command}/c{clients}/b{payload}/p{pipeline}.', flush=True)
        return
    label = f'{command}-c{clients}-b{payload}-p{pipeline}'
    base = ['redis-benchmark', '-h', HOST, '-c', str(clients), '-P', str(pipeline),
            '-d', str(payload), '--csv', '--seed', '42', '-r', '10000', '--user', 'loadbench', '-a', '']
    if command == 'setex':
        tail = ['SETEX', 'bench:ttl:__rand_int__', '120', 'x' * payload]
    elif command == 'ping':
        tail = ['PING']
    elif command == 'get':
        tail = ['GET', 'bench:value:__rand_int__']
    else:
        tail = ['SET', 'bench:value:__rand_int__', 'x' * payload]

    def sample(count, suffix):
        check()
        started = stamp()
        clock = time.monotonic()
        proc = subprocess.Popen(base + ['-n', str(count)] + tail, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            while proc.poll() is None:
                if STOP.wait(.5) or time.monotonic() - clock > 210:
                    proc.kill()
                    raise RuntimeError('Native benchmark guard stopped process')
            out, err = proc.communicate(timeout=5)
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.wait()
        elapsed = time.monotonic() - clock
        safe = out.replace(PASSWORD, '<redacted>')
        (ROOT / (label + '-' + suffix + '.csv')).write_text(safe)
        (ROOT / (label + '-' + suffix + '-stderr.txt')).write_text(err.replace(PASSWORD, '<redacted>'))
        allowed_warning = "Warning: Using a password with '-a' or '-u' option on the command line interface may not be safe."
        unexpected_stderr = '\n'.join(line for line in err.splitlines() if line.strip() and line.strip() != allowed_warning)
        if proc.returncode or unexpected_stderr or 'error' in out.lower():
            raise RuntimeError('Native benchmark returned an error; no secret output is printed')
        rows = list(csv.reader(io.StringIO(out)))
        row = next((r for r in reversed(rows) if len(r) == 8 and r[1].replace('.', '', 1).isdigit()), None)
        if row is None:
            raise RuntimeError('Unexpected native CSV schema')
        return {'startedAtUtc': started, 'finishedAtUtc': stamp(), 'elapsedSeconds': elapsed,
                'requests': count, 'command': command, 'clients': clients, 'payloadBytes': payload,
                'pipeline': pipeline, 'ops': float(row[1]), 'averageMs': float(row[2]),
                'p50Ms': float(row[4]), 'p95Ms': float(row[5]), 'p99Ms': float(row[6]), 'maxMs': float(row[7])}

    calibration = sample(10000, 'calibration')
    count = min(10000000, max(10000, math.ceil(calibration['ops'] * (67 if sustained else 8))))
    attempts = []
    for attempt in range(3):
        result = sample(count, 'sample' + str(attempt))
        attempts.append(result)
        if not sustained or result['elapsedSeconds'] >= 60:
            break
        count = math.ceil(count * 67 / max(result['elapsedSeconds'], .1))
    if sustained and result['elapsedSeconds'] < 60:
        raise RuntimeError('Sustained sample did not reach 60 seconds')
    result['attempts'] = [dict(a) for a in attempts]
    result['kind'] = 'sustained' if sustained else 'short_exploration'
    RESULTS.append(result)
    save('stages', RESULTS)
    print(f"{label}: {result['ops']:.0f} OPS, p95 {result['p95Ms']:.3f} ms, {result['elapsedSeconds']:.1f}s", flush=True)


def family(r, user, name, ttl=120000):
    prefix = f'molelaw:auth:refresh:v1:{{{user}}}:'
    keys = [prefix + name, prefix + 'generation', prefix + 'families']
    now = r.time()
    expiry = now[0] * 1000 + now[1] // 1000 + ttl
    r.set(keys[1], '1234567', px=ttl)
    assert r.eval(CREATE, 3, *keys, 'old', name, expiry, '1234567') == 1
    return keys, expiry


def semantics(r):
    keys, expiry = family(r, 900000001, 'normal')
    assert r.eval(ROTATE, 2, *keys[:2], 'old', 'next') == expiry
    assert r.hget(keys[0], 'current') == 'next'
    assert 0 < r.pttl(keys[0]) <= 120000
    assert r.eval(ROTATE, 2, *keys[:2], 'old', 'other') == -2
    assert r.eval(ROTATE, 2, *keys[:2], 'next', 'later') == -1
    keys, _ = family(r, 900000002, 'expired', 150)
    time.sleep(.25)
    assert r.eval(ROTATE, 2, *keys[:2], 'old', 'next') == -1
    keys, _ = family(r, 900000003, 'lost-generation')
    r.delete(keys[1])
    assert r.eval(ROTATE, 2, *keys[:2], 'old', 'next') == -1
    save('semantics', {'normalRotation': True, 'absoluteExpiryPreserved': True,
         'reuseRevokesFamily': True, 'expiredRejected': True, 'lostGenerationRejected': True})
    print('Lua normal/reuse/expiry/generation invariants passed.', flush=True)


async def lua_load(clients, duration=60):
    if any(s.get('kind') == 'actual_lua' and s.get('clients') == clients for s in RESULTS):
        print(f'Reusing completed 64-character Lua / c{clients}.', flush=True)
        return
    r = aredis.Redis(host=HOST, password=PASSWORD, decode_responses=True, max_connections=clients+5)
    sha = await r.script_load(ROTATE)
    hist = [0] * 100001  # 0.1ms bins; final bucket includes >=10 seconds
    counts = [0] * clients
    setup_counts = [0] * clients
    deadline = time.monotonic() + duration
    started = stamp()

    async def worker(index):
        prefix = f'molelaw:auth:refresh:v1:{{{910000000+index}}}:'
        generation, families = prefix+'generation', prefix+'families'
        current = None
        key = None
        expiry = None
        serial = 0
        while time.monotonic() < deadline:
            check()
            if serial % 100 == 0:
                if key:
                    await r.unlink(key)
                    await r.zrem(families, key[len(prefix):])
                name = 'load-' + str(serial)
                key = prefix + name
                clock = await r.time()
                expiry = clock[0]*1000+clock[1]//1000+120000
                await r.set(generation, '1234567', px=120000)
                current = 'f' * 64
                assert await r.eval(CREATE, 3, key, generation, families, current, name, expiry, '1234567') == 1
                setup_counts[index] += 1
            following = f'{serial:064x}'
            before = time.perf_counter()
            answer = await r.evalsha(sha, 2, key, generation, current, following)
            elapsed = time.perf_counter() - before
            assert answer == expiry
            hist[min(len(hist)-1, int(elapsed*10000))] += 1
            counts[index] += 1
            current = following
            serial += 1
        assert await r.hget(key, 'current') == current
        assert 0 < await r.pttl(key) <= 120000

    before = time.monotonic()
    try:
        await asyncio.gather(*(worker(i) for i in range(clients)))
    finally:
        await r.aclose()
    elapsed = time.monotonic() - before
    count = sum(counts)
    def percentile(p):
        target = math.ceil(count*p)
        seen = 0
        for bucket, number in enumerate(hist):
            seen += number
            if seen >= target:
                return (bucket+1)/10
    result = {'kind': 'actual_lua', 'clients': clients, 'startedAtUtc': started, 'finishedAtUtc': stamp(),
              'elapsedSeconds': elapsed, 'rotations': count, 'ops': count/elapsed,
              'p95MsUpperBound': percentile(.95), 'p99MsUpperBound': percentile(.99),
              'histogramBinMs': .1, 'familyPreparations': sum(setup_counts), 'errors': 0,
              'note': 'Throughput includes periodic family preparation; latency covers EVALSHA round trip.'}
    RESULTS.append(result)
    save('stages', RESULTS)
    print(f"Lua c{clients}: {count/elapsed:.0f} rotations/s, p95 <= {result['p95MsUpperBound']}ms", flush=True)


async def collision():
    r = aredis.Redis(host=HOST, password=PASSWORD, decode_responses=True, max_connections=40)
    sync = redis.Redis(host=HOST, password=PASSWORD, decode_responses=True)
    keys, expiry = family(sync, 920000001, 'collision')
    try:
        answers = await asyncio.gather(*(r.eval(ROTATE, 2, *keys[:2], 'old', 'next-'+str(i)) for i in range(32)))
        assert answers.count(expiry) == 1 and answers.count(-2) == 1 and answers.count(-1) == 30
        winner = answers.index(expiry)
        assert await r.eval(ROTATE, 2, *keys[:2], 'next-'+str(winner), 'later') == -1
        save('collision', {'requests': 32, 'success': 1, 'reuseDetected': 1, 'revokedRejected': 30,
                           'winningRefreshAlsoRejected': True})
    finally:
        sync.close()
        await r.aclose()
    print('32-way same-token race: exactly one rotation; family revoked, including winning token.', flush=True)


def memory_pressure(r):
    keys, _ = family(r, 930000001, 'oom')
    before = r.hgetall(keys[0])
    written = 0
    fine_written = 0
    try:
        for i in range(160):
            check()
            try:
                r.set('bench:pressure:'+str(i), 'm' * (1024*1024), ex=15)
                written += 1
            except redis.exceptions.OutOfMemoryError:
                break
        else:
            raise RuntimeError('Expected bounded maxmemory OOM was not reached')
        # A large request can be rejected while small writes still fit. Fill the
        # remaining gap with small values before asserting a tiny Lua write fails.
        for i in range(20000):
            check()
            try:
                r.set('bench:pressure:fine:'+str(i), 's'*256, ex=15)
                fine_written += 1
            except redis.exceptions.OutOfMemoryError:
                break
        else:
            raise RuntimeError('Small-write memory pressure not reached within bound')
        try:
            r.eval(ROTATE, 2, *keys[:2], 'old', 'next')
        except redis.exceptions.OutOfMemoryError:
            rejected = True
        else:
            rejected = False
        assert rejected and r.hgetall(keys[0]) == before
        info = r.info()
        save('memory-pressure', {'writtenMiBValues': written, 'fineValues': fine_written, 'redisCommandOomObserved': True,
             'rotationOomRejected': rejected, 'familyUnchanged': True, 'usedMemory': info['used_memory'],
             'maxmemory': info['maxmemory'], 'evictedKeys': info['evicted_keys']})
        print(f'Memory pressure: OOM after {written} MiB values; rotation rejected and family unchanged. Waiting for TTL recovery.', flush=True)
        for _ in range(20):
            check()
            time.sleep(1)
        assert r.eval(ROTATE, 2, *keys[:2], 'old', 'recovered') > 0
        assert r.info()['evicted_keys'] == 0
        save('recovery', {'rotationSucceeded': True, 'pressureKeysExpired': not list(r.scan_iter('bench:pressure:*')),
             'usedMemory': r.info()['used_memory'], 'evictedKeys': r.info()['evicted_keys']})
        print('TTL recovery succeeded; original valid token rotates again, no eviction.', flush=True)
    finally:
        for key in r.scan_iter('bench:pressure:*'):
            r.unlink(key)


def main():
    global ROOT
    if sys.argv[1:] == ['version']:
        subprocess.run(['redis-benchmark', '--version'], check=True)
        print('redis-py', redis.__version__)
        return
    resume = os.environ.get('LT_REDIS_RESUME_FROM', '')
    if sys.argv[1:] != ['run'] or not re.fullmatch('[a-f0-9]{64}', PASSWORD):
        raise RuntimeError('Explicit bounded run and private credential required')
    run_id = os.environ['LT_REDIS_RUN_ID']
    if not re.fullmatch(r'redis-\d{8}T\d{6}Z', run_id):
        raise RuntimeError('Invalid run ID')
    ROOT = Path('/results') / run_id
    ROOT.mkdir(parents=True, exist_ok=False)
    if resume:
        if not re.fullmatch(r'redis-\d{8}T\d{6}Z', resume):
            raise RuntimeError('Invalid source run ID')
        previous = json.loads((Path('/results')/resume/'stages.json').read_text())
        expected = {1, 10, 50, 100, 200}
        imported = [s for s in previous if s.get('command') == 'set' and s.get('pipeline') == 1
                    and s.get('payloadBytes') == 1024 and s.get('elapsedSeconds', 0) >= 60]
        if len(imported) != 5 or {s['clients'] for s in imported} != expected:
            raise RuntimeError('Source run lacks the five completed baseline SET stages')
        for result in previous:
            result.setdefault('sourceRunId', resume)
        RESULTS.extend(previous)
        save('stages', RESULTS)
        save('resume', {'sourceRunId': resume, 'importedSetStages': 5, 'importedTotalStages': len(previous)})
    r = redis.Redis(host=HOST, password=PASSWORD, decode_responses=True, socket_timeout=5)
    info = r.info()
    assert info['redis_version'] == '7.4.11' and info['maxmemory'] == 128*1024*1024 and info['maxmemory_policy'] == 'noeviction'
    assert r.dbsize() == 0, 'Requires fresh isolated benchmark Redis; existing keys are not deleted'
    save('environment', {'startedAtUtc': stamp(), 'redisVersion': info['redis_version'], 'redisPy': redis.__version__,
         'maxmemory': info['maxmemory'], 'policy': info['maxmemory_policy'], 'host': HOST,
         'redisCpuLimit': .5, 'redisMemoryLimitMiB': 256, 'generatorCpuLimit': 3})
    thread = threading.Thread(target=monitor, daemon=True)
    thread.start()
    try:
        # Native redis-benchmark lacks REDISCLI_AUTH. Use a restricted named ACL user
        # without a password only on this synthetic instance with no published port.
        # The default user still requires its private password. CONFIG GET is used by the tool.
        r.execute_command('ACL', 'SETUSER', 'loadbench', 'on', 'nopass', '~bench:*',
                          '+ping', '+get', '+set', '+setex', '+select', '+config|get', '+client|setname', '+client|setinfo')
        # All GET samples hit prepared values; misses are not used as the read baseline.
        with r.pipeline(transaction=False) as pipe:
            for i in range(10000):
                pipe.set(f'bench:value:{i:012d}', 'x'*1024)
            pipe.execute()
        semantics(r)
        asyncio.run(collision())
        if not resume:
            for clients in (1, 10, 50, 100, 200):
                native('set', clients)
        for command in ('ping', 'get', 'setex'):
            native(command, 50)
        for payload in (64, 4096):
            native('set', 50, payload=payload, sustained=False)
        for pipeline in (10, 100):
            native('set', 50, pipeline=pipeline, sustained=False)
        r.execute_command('ACL', 'SETUSER', 'loadbench', 'off')
        for clients in (1, 10, 50):
            asyncio.run(lua_load(clients))
        memory_pressure(r)
        save('result', {'completed': True, 'finishedAtUtc': stamp(), 'results': RESULTS,
             'info': {k: r.info()[k] for k in ('total_error_replies', 'evicted_keys', 'expired_keys', 'used_memory')}})
        print('Redis suite complete. API load/fault testing is a separate next stage.', flush=True)
    finally:
        STOP.set()
        thread.join(timeout=5)
        save('observations', GUARD)
        r.close()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Redis suite stopped: ' + type(error).__name__ + '. Inspect private artifacts; secret values are not printed.', file=sys.stderr)
        print('Failure locations: ' + ' -> '.join(f'{Path(f.filename).name}:{f.lineno}:{f.name}' for f in traceback.extract_tb(error.__traceback__)), file=sys.stderr)
        sys.exit(1)
