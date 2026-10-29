"""Programmatic Locust refresh load, then isolated real API fault checks."""
from gevent import monkey
monkey.patch_all()
import copy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import time
import traceback
import gevent
import requests
from locust import HttpUser, task, constant
from locust.env import Environment
from locust.event import Events

HOST = 'http://refresh-backend:8080'
PROXY = 'http://fault-proxy:8474'
ROOT = None
FIXTURE = json.loads(Path('/fixtures/manifest.json').read_text())
OUTCOMES = []
REDIS_CONNECTION = None
REDIS_STREAM = None


def stamp():
    return datetime.now(timezone.utc).isoformat()


def save(name, value):
    (ROOT / (name+'.json')).write_text(json.dumps(value, indent=2))


def control(method, path, payload=None):
    response = requests.request(method, PROXY+path, json=payload, timeout=5)
    assert response.status_code in (200, 201, 204), 'Fault proxy control failed'
    return response.json() if response.content else None


def setup_proxies():
    for name, listen, upstream in [('redis','0.0.0.0:16379','redis-bench:6379'),
                                   ('mysql','0.0.0.0:13306','mysql:3306'),
                                   ('http','0.0.0.0:18081','refresh-backend:8080')]:
        response = requests.get(PROXY+'/proxies/'+name, timeout=5)
        if response.status_code == 404:
            control('POST', '/proxies', {'name': name, 'listen': listen, 'upstream': upstream, 'enabled': True})
        else:
            response.raise_for_status()
            control('POST', '/proxies/'+name, {'enabled': True})
            for toxic in control('GET', '/proxies/'+name)['toxics']:
                control('DELETE', '/proxies/'+name+'/toxics/'+toxic['name'])
    print('Redis/MySQL/HTTP fault routes ready; no host ports.', flush=True)


def login(index=0):
    session = requests.Session()
    csrf = session.get(HOST+'/api/auth/csrf', timeout=10)
    assert csrf.status_code == 200
    token = csrf.json()['token']
    response = session.post(HOST+'/api/auth/login', json={
        'email': FIXTURE['users'][index]['email'], 'password': os.environ['LT_DATA_PASSWORD']},
        headers={'X-XSRF-TOKEN': token}, timeout=15)
    assert response.status_code == 200 and response.json()['accessToken']
    assert refresh_cookie(session)
    return session, token


def refresh_cookie(session):
    return next((c.value for c in session.cookies if c.name == 'refreshToken' and c.path == '/api/auth'), None)


def clone(session):
    result = requests.Session()
    result.cookies = copy.deepcopy(session.cookies)
    return result


def redis_command(*args):
    """Tiny RESP client for private state assertions; no credential in argv or logs."""
    global REDIS_CONNECTION, REDIS_STREAM
    def encoded(values):
        result = b'*'+str(len(values)).encode()+b'\r\n'
        for value in values:
            raw = str(value).encode()
            result += b'$'+str(len(raw)).encode()+b'\r\n'+raw+b'\r\n'
        return result
    def read(stream):
        line = stream.readline()
        assert line, 'Redis connection lost'
        kind, value = line[:1], line[1:-2]
        if kind == b'-':
            raise RuntimeError('redis_oom' if value.startswith(b'OOM') else 'redis_error')
        if kind == b':':
            return int(value)
        if kind == b'+':
            return value.decode()
        if kind == b'$':
            length = int(value)
            if length == -1:
                return None
            result = stream.read(length)
            assert stream.read(2) == b'\r\n'
            return result.decode()
        if kind == b'*':
            return [read(stream) for _ in range(int(value))]
        raise RuntimeError('Unexpected RESP type')
    # Keep the pressure connection open: closing every SET connection releases
    # its buffers and can leave room for small app writes after the probe's OOM.
    if REDIS_CONNECTION is None:
        REDIS_CONNECTION = socket.create_connection(('redis-bench',6379),timeout=5)
        REDIS_CONNECTION.settimeout(5)
        REDIS_STREAM = REDIS_CONNECTION.makefile('rb')
        REDIS_CONNECTION.sendall(encoded(('AUTH',os.environ['REDISCLI_AUTH'])))
        assert read(REDIS_STREAM) == 'OK'
    REDIS_CONNECTION.sendall(encoded(args))
    return read(REDIS_STREAM)


def family_key(session):
    token = refresh_cookie(session)
    parts = token.split('.')
    return f'molelaw:auth:refresh:v1:{{{parts[1]}}}:{parts[2]}'


def family_state(key):
    values = redis_command('HGETALL',key)
    # Redis hash iteration order is not a state invariant (rehash can change it).
    return dict(zip(values[::2],values[1::2]))


def refresh(session, csrf, expected=200, code=None, host=HOST):
    before = refresh_cookie(session)
    response = session.post(host+'/api/auth/refresh', headers={'X-XSRF-TOKEN': csrf} if csrf else {}, timeout=12)
    if response.status_code != expected:
        save('unexpected-response', {'expectedStatus':expected,'actualStatus':response.status_code,'atUtc':stamp()})
    assert response.status_code == expected, 'Unexpected refresh HTTP status: '+str(response.status_code)
    if code:
        assert response.json()['error']['code'] == code
    if expected == 200:
        after = refresh_cookie(session)
        assert response.json()['success'] and response.json()['data']['accessToken']
        assert after and after != before and after.split('.')[2] == before.split('.')[2]
    else:
        assert not any(c.name in ('accessToken','refreshToken') and c.value for c in response.cookies)
    return response


def record(name, **values):
    OUTCOMES.append({'scenario': name, 'passed': True, **values})
    save('functional', OUTCOMES)
    print('API passed: '+name, flush=True)


def functional():
    session, csrf = login()
    previous = clone(session)
    expiry = redis_command('HGET', family_key(session), 'expires')
    response = refresh(session, csrf)
    assert redis_command('HGET', family_key(session), 'expires') == expiry
    cookies = [c for c in response.cookies if c.name == 'refreshToken' and c.path == '/api/auth']
    assert cookies and cookies[0].has_nonstandard_attr('HttpOnly')
    refresh(previous, csrf, 401, 'REFRESH_TOKEN_REUSED')
    refresh(session, csrf, 401, 'INVALID_REFRESH_TOKEN')
    record('normal_rotation_then_reuse_revokes_new_token')

    first, first_csrf = login()
    independent, independent_csrf = login()
    attempts = [clone(first) for _ in range(16)]
    gate = gevent.event.Event()
    def race(client):
        gate.wait()
        reply = client.post(HOST+'/api/auth/refresh', headers={'X-XSRF-TOKEN': first_csrf}, timeout=15)
        return reply.status_code, reply.json().get('error', {}).get('code'), client
    workers = [gevent.spawn(race, client) for client in attempts]
    gate.set()
    gevent.joinall(workers, timeout=25, raise_error=True)
    assert all(w.ready() for w in workers)
    results = [w.value for w in workers]
    assert sum(r[0] == 200 for r in results) == 1
    assert sum(r[1] == 'REFRESH_TOKEN_REUSED' for r in results) == 1
    assert sum(r[1] == 'INVALID_REFRESH_TOKEN' for r in results) == 14
    winner = next(r[2] for r in results if r[0] == 200)
    refresh(winner, first_csrf, 401, 'INVALID_REFRESH_TOKEN')
    refresh(independent, independent_csrf)
    record('16_way_same_token_race', success=1, reuse=1, revoked=14, otherFamilySurvives=True)

    session, csrf = login()
    refresh(session, None, 403)
    refresh(session, 'invalid-csrf', 403)
    refresh(session, csrf)
    record('csrf_rejections_do_not_consume_refresh')
    missing = clone(session)
    for cookie in list(missing.cookies):
        if cookie.name == 'refreshToken':
            missing.cookies.clear(cookie.domain, cookie.path, cookie.name)
    refresh(missing, csrf, 401, 'INVALID_REFRESH_TOKEN')
    tampered = clone(session)
    for cookie in tampered.cookies:
        if cookie.name == 'refreshToken' and cookie.path == '/api/auth':
            cookie.value = 'invalid'
    refresh(tampered, csrf, 401, 'INVALID_REFRESH_TOKEN')
    logged_out = clone(session)
    response = session.post(HOST+'/api/auth/logout', headers={'X-XSRF-TOKEN':csrf}, timeout=10)
    assert response.status_code == 200
    refresh(session, csrf, 401, 'INVALID_REFRESH_TOKEN')
    refresh(logged_out, csrf, 401, 'INVALID_REFRESH_TOKEN')
    record('missing_tampered_logout_rejected')

    expired, csrf = login()
    key = family_key(expired)
    clock = redis_command('TIME')
    redis_command('HSET',key,'expires',int(clock[0])*1000+int(clock[1])//1000+250)
    redis_command('PEXPIRE',key,250)
    gevent.sleep(.4)
    refresh(expired,csrf,401,'INVALID_REFRESH_TOKEN')
    record('expired_family_api_rejected')


class RefreshUser(HttpUser):
    wait_time = constant(.05)
    def on_start(self):
        prepared, self.csrf = self.environment.prepared.pop()
        self.client.cookies = copy.deepcopy(prepared.cookies)

    @task
    def rotate(self):
        if self.environment.draining:
            gevent.sleep(.05)
            return
        before = refresh_cookie(self.client)
        self.environment.inflight += 1
        try:
            with self.client.post('/api/auth/refresh', headers={'X-XSRF-TOKEN': self.csrf},
                 name='refresh: rotate', timeout=(3,10), catch_response=True) as response:
                try:
                    after = refresh_cookie(self.client)
                    valid = (response.status_code == 200 and response.json()['data']['accessToken']
                             and after and before != after and before.split('.')[2] == after.split('.')[2])
                except (ValueError, KeyError, TypeError):
                    valid = False
                if not valid:
                    response.failure('Refresh response/cookie contract failed; no token values logged')
                    self.environment.violation = True
        finally:
            self.environment.inflight -= 1


def load(users, same_account=False, seconds=60):
    assert users in (1, 10, 20, 25, 50, 100) and seconds in (60,120)
    filename = f'load-u{users}-same{int(same_account)}-s{seconds}'
    source = os.environ.get('LT_REFRESH_RESUME_FROM')
    if source:
        previous = Path('/results')/source/(filename+'.json')
        if previous.exists():
            result = json.loads(previous.read_text())
            assert result['failures'] == 0 and result['measurementSeconds'] >= seconds-.1
            result.setdefault('sourceRunId',source)
            save(filename,result)
            print('Reusing completed API load: '+filename,flush=True)
            return result
    offset = int(os.environ.get('LT_REFRESH_WORKER_ID','0'))*users if os.environ.get('LT_REFRESH_MODE') == 'worker' else 0
    prepared = [login(0 if same_account else (index+offset) % 20) for index in range(users)]
    env = Environment(user_classes=[RefreshUser], host=HOST, events=Events())
    env.prepared, env.draining, env.inflight, env.violation = prepared, False, 0, False
    runner = env.create_local_runner()
    resources = []
    try:
        runner.start(users, spawn_rate=20)
        limit = time.monotonic()+30
        while runner.user_count != users:
            assert time.monotonic() < limit
            gevent.sleep(.1)
        gevent.sleep(10)
        env.draining = True
        while env.inflight:
            gevent.sleep(.05)
        assert not env.violation, 'Warmup failed'
        if os.environ.get('LT_REFRESH_MODE') == 'worker':
            save('ready',{'ready':True})
            barrier = ROOT.parent/'start.json'
            deadline = time.monotonic()+150
            while not barrier.exists():
                assert time.monotonic() < deadline, 'Worker start barrier timeout'
                gevent.sleep(.1)
            epoch = json.loads(barrier.read_text())['startEpoch']
            gevent.sleep(max(0,epoch-time.time()))
        env.stats.reset_all()
        start = stamp()
        clock = time.monotonic()
        env.draining = False
        while time.monotonic()-clock < seconds:
            assert not env.violation, 'Unexpected API load failure'
            query = 'up{job="molelaw-refresh-backend"} or redis_up{job="molelaw-redis-bench"} or hikaricp_connections_pending{job="molelaw-refresh-backend"}'
            reply = requests.get('http://prometheus:9090/api/v1/query', params={'query':query}, timeout=3)
            vector = reply.json()['data']['result']
            assert all(float(v['value'][1]) == 1 for v in vector if v['metric'].get('__name__') in ('up','redis_up'))
            assert len(vector) >= 3, 'Monitoring lost'
            resources.append({'at':stamp(), 'metrics':[{'metric':v['metric'], 'value':v['value'][1]} for v in vector]})
            gevent.sleep(min(5, max(0, seconds-(time.monotonic()-clock))))
        env.draining = True
        elapsed = time.monotonic()-clock
        deadline = time.monotonic()+15
        while env.inflight:
            assert time.monotonic() < deadline
            gevent.sleep(.05)
        total = env.stats.total
        assert not env.violation and total.num_failures == 0 and total.num_requests > 0
        result = {'users':users, 'accountMode':'same_account' if same_account else 'independent_up_to_20_accounts',
                  'startedAtUtc':start, 'finishedAtUtc':stamp(), 'measurementSeconds':elapsed,
                  'completedIncludingDrain':total.num_requests, 'failures':total.num_failures,
                  'rpsIncludingDrainApprox':total.num_requests/elapsed, 'p95Ms':total.get_response_time_percentile(.95),
                  'p99Ms':total.get_response_time_percentile(.99), 'resources':resources,
                  'thinkTimeSeconds':.05, 'warmupSeconds':10,
                  'generatorCpuWarning':runner.cpu_warning_emitted,
                  'responseTimeHistogramMs':dict(total.response_times)}
        save(filename, result)
        print(f"API load {users} users/same={same_account}: {result['rpsIncludingDrainApprox']:.1f} RPS, p95 {result['p95Ms']}ms, 0 failures", flush=True)
        return result
    finally:
        runner.quit()


def faults():
    session, csrf = login()
    key = family_key(session)
    state = family_state(key)
    filled = 0
    fine_filled = 0
    try:
        for index in range(160):
            try:
                redis_command('SET','bench:api-pressure:'+str(index),'m'*(1024*1024),'EX',15)
                filled += 1
            except RuntimeError as error:
                assert str(error) == 'redis_oom'
                break
        else:
            raise AssertionError('Expected bounded Redis command OOM')
        for index in range(20000):
            try:
                redis_command('SET','bench:api-fine:'+str(index),'s'*256,'EX',15)
                fine_filled += 1
            except RuntimeError as error:
                assert str(error) == 'redis_oom'
                break
        else:
            raise AssertionError('Expected small-write Redis OOM')
        refresh(session,csrf,503,'AUTH_STORE_UNAVAILABLE')
        after = family_state(key)
        if after != state:
            save('oom-state-difference', {'currentChanged':after.get('current') != state.get('current'),
                 'revokedChanged':after.get('revoked') != state.get('revoked'),
                 'fieldCountBefore':len(state),'fieldCountAfter':len(after)})
        assert after == state
        gevent.sleep(18)
        refresh(session,csrf)
        record('redis_oom_503_unchanged_family_then_ttl_recovery',writtenMiBValues=filled,fineValues=fine_filled)
    finally:
        for index in range(filled):
            redis_command('UNLINK','bench:api-pressure:'+str(index))
        for index in range(fine_filled):
            redis_command('UNLINK','bench:api-fine:'+str(index))

    # Disconnect: disable route, then restore in finally; only the test backend uses it.
    session, csrf = login()
    control('POST','/proxies/redis',{'enabled':False})
    try:
        refresh(session, csrf, 503, 'AUTH_STORE_UNAVAILABLE')
    finally:
        control('POST','/proxies/redis',{'enabled':True})
    gevent.sleep(3)
    refresh(session, csrf)
    record('redis_disconnect_503_then_recovery')

    # Upstream delay lets client timeout happen before command execution; it is an ambiguous outcome.
    session, csrf = login()
    old = clone(session)
    control('POST','/proxies/redis/toxics', {'name':'delay','type':'latency','stream':'upstream','toxicity':1,
                                           'attributes':{'latency':3000,'jitter':0}})
    try:
        refresh(session, csrf, 503, 'AUTH_STORE_UNAVAILABLE')
    finally:
        control('DELETE','/proxies/redis/toxics/delay')
    gevent.sleep(4)
    response = old.post(HOST+'/api/auth/refresh', headers={'X-XSRF-TOKEN':csrf}, timeout=12)
    assert response.status_code in (200,401), 'Post-timeout state must remain secure'
    if response.status_code == 401:
        assert response.json()['error']['code'] in ('REFRESH_TOKEN_REUSED','INVALID_REFRESH_TOKEN')
    record('redis_latency_503_and_post_timeout_state', retryStatus=response.status_code,
           retryCode=response.json().get('error',{}).get('code'))

    # Request reaches backend; downstream HTTP response is delayed past the caller timeout.
    session, csrf = login()
    old = clone(session)
    control('POST','/proxies/http/toxics', {'name':'lost-response','type':'latency','stream':'downstream','toxicity':1,
                                          'attributes':{'latency':4000,'jitter':0}})
    # Cookies are scoped to refresh-backend. Preserve the real Cookie header when
    # routing this one request through a different hostname for response loss.
    cookie_header = session.prepare_request(requests.Request('POST',HOST+'/api/auth/refresh')).headers['Cookie']
    try:
        try:
            session.post('http://fault-proxy:18081/api/auth/refresh',
                         headers={'X-XSRF-TOKEN':csrf,'Cookie':cookie_header}, timeout=(3,1))
        except requests.exceptions.ReadTimeout:
            pass
        else:
            raise AssertionError('Expected simulated response loss')
    finally:
        control('DELETE','/proxies/http/toxics/lost-response')
    gevent.sleep(1)
    refresh(old, csrf, 401, 'REFRESH_TOKEN_REUSED')
    record('processed_refresh_response_loss_then_reuse_revokes_family')

    session, csrf = login()
    key = family_key(session)
    before = family_state(key)
    control('POST','/proxies/mysql',{'enabled':False})
    try:
        refresh(session,csrf,500,'INTERNAL_SERVER_ERROR')
        assert family_state(key) == before
    finally:
        control('POST','/proxies/mysql',{'enabled':True})
    gevent.sleep(3)
    refresh(session,csrf)
    record('db_disconnect_500_before_rotation_then_recovery')


def main():
    global ROOT
    if os.environ.get('LT_REFRESH_MODE') == 'setup':
        setup_proxies()
        cursor = '0'
        deleted = 0
        while True:
            cursor, keys = redis_command('SCAN',cursor,'MATCH','bench:*','COUNT',1000)
            if keys:
                deleted += redis_command('UNLINK',*keys)
            if cursor == '0':
                break
        print(f'Only synthetic bench:* keys removed before API stage: {deleted}', flush=True)
        return
    run_id = os.environ['LT_REFRESH_RUN_ID']
    assert re.fullmatch(r'refresh-\d{8}T\d{6}Z', run_id)
    if os.environ.get('LT_REFRESH_MODE') == 'worker':
        worker = os.environ['LT_REFRESH_WORKER_ID']
        assert worker in ('0','1','2','3')
        ROOT = Path('/results')/run_id/('worker-'+worker)
        ROOT.mkdir(parents=True,exist_ok=False)
        result = load(25,seconds=120)
        save('result',{'completed':True,'load':result})
        return
    source = os.environ.get('LT_REFRESH_RESUME_FROM')
    if source:
        assert re.fullmatch(r'refresh-\d{8}T\d{6}Z', source)
    ROOT = Path('/results')/run_id
    ROOT.mkdir(parents=True, exist_ok=False)
    assert FIXTURE['dataset'] == 'lt-read-v1' and len(FIXTURE['users']) == 20
    setup_proxies()
    functional()
    results = []
    for users in (1,10,20,50,100):
        results.append(load(users))
    results.append(load(20, same_account=True))
    best = max(results[:5], key=lambda r:r['rpsIncludingDrainApprox'])
    results.append(load(best['users'], seconds=120))
    faults()
    save('result', {'completed':True, 'finishedAtUtc':stamp(), 'loads':results, 'functional':OUTCOMES})
    print('Refresh API suite complete; Redis maxmemory/TTL and DB faults have separate checks.', flush=True)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Refresh suite stopped: '+type(error).__name__+'; no credentials printed.', flush=True)
        print('Failure locations: '+' -> '.join(f'{Path(f.filename).name}:{f.lineno}:{f.name}' for f in traceback.extract_tb(error.__traceback__)), flush=True)
        raise SystemExit(1)
