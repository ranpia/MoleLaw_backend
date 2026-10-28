"""Offline boundary check: no HTTP traffic or fixture credentials."""
from types import SimpleNamespace
from locust.stats import RequestStats
from stress_read import StressRead, sample

state = SimpleNamespace(measuring=True, stopping=False, measure_epoch=100.0,
    failures=0, stats=RequestStats(), completions=RequestStats(), covered=set(), warmup_reads=0)
StressRead.active_environment = SimpleNamespace(stage=state)

def emit(start):
    sample('read: test', 'GET', 10, 100, None, {'vu': 1}, start)

emit(99.9)  # Started in warmup, completed in measurement: throughput only.
assert state.completions.total.num_requests == 1 and state.stats.total.num_requests == 0
emit(100.1)  # Entirely in measurement: both.
assert state.completions.total.num_requests == 2 and state.stats.total.num_requests == 1
state.stopping, state.end_epoch = True, 101.0
emit(100.9)  # Drained after deadline: latency only.
assert state.completions.total.num_requests == 2 and state.stats.total.num_requests == 2
emit(101.1)  # Outside the request-start window: neither.
assert state.completions.total.num_requests == 2 and state.stats.total.num_requests == 2
print('Throughput and drained-latency window checks passed (offline).')
