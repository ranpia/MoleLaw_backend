"""Bounded read capacity exploration; shared accounts, independent cookie sessions."""
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import time
from types import SimpleNamespace

import gevent
import requests
from locust import events
from locust.stats import RequestStats
from read_client import ReadOnlyUser


def utc():
    return datetime.now(timezone.utc).isoformat()


def abort(env, reason):
    if not env.stage.abort:
        env.stage.abort = reason
        env.process_exit_code = 1
        gevent.spawn(env.runner.quit)


def monitor(env):
    last_cpu = last_clock = None
    busy = 0
    unavailable = 0
    while not env.stage.stopping:
        try:
            query = 'up{job="molelaw-backend"} or mysql_up or redis_up or sum(jvm_memory_used_bytes{job="molelaw-backend"}) or hikaricp_connections_pending{job="molelaw-backend"} or mysql_global_status_threads_running or redis_memory_used_bytes'
            vector = requests.get('http://prometheus:9090/api/v1/query', params={'query': query}, timeout=3).json()['data']['result']
            point = {item['metric'].get('__name__', 'jvm_total_used'): float(item['value'][1]) for item in vector}
            if any(point.get(name) != 1 for name in ('up', 'mysql_up', 'redis_up')):
                raise ValueError('Required monitoring target is unavailable')
            if 'jvm_total_used' not in point:
                raise ValueError('Required JVM memory metric is unavailable')
            unavailable = 0
            cpu = dict(line.split() for line in Path('/sys/fs/cgroup/cpu.stat').read_text().splitlines())
            now = time.monotonic()
            used = int(cpu['usage_usec']) / 1e6
            point['generatorCores'] = (used - last_cpu) / (now - last_clock) if last_clock else 0
            point['generatorMemory'] = int(Path('/sys/fs/cgroup/memory.current').read_text())
            point['at'] = utc()
            env.stage.resources.append(point)
            last_cpu, last_clock = used, now
            if point['jvm_total_used'] > 1.6 * 1024**3:
                abort(env, 'backend_memory_guard')
            busy = busy + 1 if env.stage.measuring and point['generatorCores'] > 2.55 else 0
            if busy >= 3 or point['generatorMemory'] > 900 * 1024**2:
                abort(env, 'generator_resource_limit')
        except Exception:
            unavailable += 1
            if unavailable >= 3:
                abort(env, 'monitoring_unavailable')
        gevent.sleep(5)


@events.test_start.add_listener
def start(environment, **kwargs):
    users = int(os.environ['LT_STAGE_USERS'])
    warmup = int(os.environ['LT_WARMUP_SECONDS'])
    measurement = int(os.environ['LT_MEASURE_SECONDS'])
    options = environment.parsed_options
    if not (2 <= users <= 4096 and 10 <= warmup and measurement >= 30 and warmup + measurement <= 285
            and options.num_users == users and options.run_time == warmup + measurement
            and environment.host == 'http://gateway:8080' and not options.master and not options.worker):
        raise SystemExit('Unsupported stress scope or target')
    environment.stage = SimpleNamespace(users=users, warmup=warmup, measurement=measurement,
        started=utc(), clock=time.monotonic(), measure_start=None, measure_epoch=None,
        measure_clock=None, end=None, measuring=False, stopping=False, assigned=0,
        failures=0, abort=None, stats=RequestStats(), completions=RequestStats(), warmup_reads=0, covered=set(), resources=[], errors=[])
    state = environment.stage
    state.preauth = os.environ.get('LT_PREAUTH_MODE') == 'pool'
    state.tokens = {}
    state.fixture = json.loads(Path('/fixtures/manifest.json').read_text())
    if state.preauth:
        if state.fixture['dataset'] != 'lt-read-v1' or len(state.fixture['users']) != 20:
            raise SystemExit('Unexpected account pool')
        before = time.monotonic()
        for account in state.fixture['users'][:min(users, 20)]:
            with requests.Session() as session:
                csrf_response = session.get('http://gateway:8080/api/auth/csrf', timeout=10)
                csrf_response.raise_for_status()
                csrf = csrf_response.json()['token']
                login = session.post('http://gateway:8080/api/auth/login',
                    json={'email': account['email'], 'password': os.environ['LT_DATA_PASSWORD']},
                    headers={'X-XSRF-TOKEN': csrf}, timeout=10)
                login.raise_for_status()
                state.tokens[account['email']] = login.json()['accessToken']
        state.preAuthSeconds = time.monotonic() - before
        print(f'PREAUTH: {len(state.tokens)} account tokens prepared sequentially; no tokens are logged', flush=True)

    def begin_measurement():
        if not environment.stage.stopping:
            state = environment.stage
            if state.assigned != users:
                abort(environment, 'users_not_ready')
                return
            state.measure_start, state.measure_epoch, state.measure_clock = utc(), time.time(), time.monotonic()
            state.measuring = True
            environment.stats.reset_all()
            print(f'MEASUREMENT START: users={users}, seconds={measurement}', flush=True)
    gevent.spawn_later(max(0, warmup - (time.monotonic() - state.clock)), begin_measurement)
    gevent.spawn(monitor, environment)
    print(f'WARMUP START: users={users}, seconds={warmup}, independent sessions over 20 fixture accounts', flush=True)


@events.request.add_listener
def sample(name, request_type, response_time, response_length, exception, context, start_time, **kwargs):
    env = StressRead.active_environment
    if env is None or not hasattr(env, 'stage'):
        return
    state = env.stage
    if exception is not None:
        state.failures += 1
        if len(state.errors) < 20:
            state.errors.append({'request': name, 'error': str(exception), 'at': utc()})
        abort(env, 'request_failure')
    if name.startswith('read:'):
        if state.measuring and not state.stopping:
            state.completions.log_request(request_type, name, response_time, response_length)
            if exception:
                state.completions.log_error(request_type, name, Exception('Response validation failed'))
        if (state.measuring and start_time >= state.measure_epoch
                and (not state.stopping or start_time <= state.end_epoch)):
            state.stats.log_request(request_type, name, response_time, response_length)
            if exception:
                state.stats.log_error(request_type, name, Exception('Response validation failed'))
            state.covered.add(context.get('vu'))
        elif not state.measuring and not state.stopping:
            state.warmup_reads += 1


@events.test_stopping.add_listener
def stopping(environment, **kwargs):
    if hasattr(environment, 'stage'):
        environment.stage.end = utc()
        environment.stage.end_epoch = time.time()
        environment.stage.end_clock = time.monotonic()
        environment.stage.stopping = True


@events.quitting.add_listener
def save(environment, **kwargs):
    if not hasattr(environment, 'stage'):
        return
    state = environment.stage
    elapsed = max(0, getattr(state, 'end_clock', time.monotonic()) - (state.measure_clock or time.monotonic()))
    rows = [{'name': item.name, 'requests': item.num_requests, 'failures': item.num_failures,
             'averageMs': item.avg_response_time, 'p95Ms': item.get_response_time_percentile(.95),
             'p99Ms': item.get_response_time_percentile(.99), 'maxMs': item.max_response_time}
            for item in state.stats.entries.values()]
    enough = elapsed >= state.measurement - 3 and len(state.covered) == state.users
    passed = not state.abort and not state.failures and enough
    p95 = state.stats.total.get_response_time_percentile(.95) or 0
    data = {'users': state.users, 'startedAtUtc': state.started, 'measurementStartUtc': state.measure_start,
        'measurementEndUtc': state.end, 'warmupSeconds': state.warmup, 'measurementSeconds': elapsed,
        'warmupReads': state.warmup_reads, 'readRequests': state.completions.total.num_requests,
        'latencySamplesStartedInWindow': state.stats.total.num_requests,
        'readRps': state.completions.total.num_requests / elapsed if elapsed > 0 else 0,
        'failuresAcrossWarmupAndMeasurement': state.failures, 'abortReason': state.abort,
        'coveredUsers': len(state.covered), 'spawnedUsers': state.assigned, 'passed': passed, 'slow': p95 > 1000,
        'preauthenticatedPool': state.preauth, 'preAuthSeconds': getattr(state, 'preAuthSeconds', 0),
        'p95Ms': p95, 'requests': rows, 'resources': state.resources, 'errors': state.errors,
        'notes': 'Read-only over 20 accounts. In pool mode, access tokens are reused in independent cookie sessions; token preparation is sequential and outside read measurements. No logout burst: credentials discarded and TTLs apply. No capacity SLO established.'}
    folder = Path('/results') / os.environ['LT_RUN_ID']
    folder.mkdir(parents=True, exist_ok=True)
    (folder / 'summary.json').write_text(json.dumps(data, indent=2))
    if not passed:
        environment.process_exit_code = 1
    print(f'STAGE RESULT: users={state.users}, reads={data["readRequests"]}, rps={data["readRps"]:.2f}, p95={p95}ms, abort={state.abort}', flush=True)


class StressRead(ReadOnlyUser):
    abstract = False

    def on_start(self):
        if not self.environment.stage.preauth:
            return super().on_start()
        StressRead.active_environment = self.environment
        self.logged_in = False
        self.show_rooms = True
        self.fixture = self.environment.stage.fixture
        self.account = self.select_account(self.fixture)
        self.client.cookies.set('accessToken', self.environment.stage.tokens[self.account['email']])

    def select_account(self, fixture):
        self.vu = self.environment.stage.assigned
        self.environment.stage.assigned += 1
        return fixture['users'][self.vu % len(fixture['users'])]

    def context(self):
        return {'vu': getattr(self, 'vu', None)}

    def on_stop(self):
        # Avoid making thousands of logout calls look like read-service overload.
        pass
