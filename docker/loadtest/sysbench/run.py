"""Fixed-schema sysbench runner. Passwords live only in private temporary config files."""
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime, timezone
from urllib.parse import urlencode
from urllib.request import urlopen

SCHEMA = 'molelaw_sysbench'
DATASET = 'sysbench-v1-4x500k'
TABLES, ROWS = 4, 500000


def utc():
    return datetime.now(timezone.utc).isoformat()


def query_scalar(sql, cnf):
    args = ['mariadb', '--defaults-extra-file=' + cnf, '--database=' + SCHEMA,
            '--batch', '--skip-column-names', '-e', sql]
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE).strip()


def run_process(args, folder, limit):
    started, clock = utc(), time.monotonic()
    process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    guard = {'reason': None, 'samples': []}
    done = threading.Event()

    def stop(reason):
        guard['reason'] = reason
        process.terminate()

    def watch():
        misses = 0
        high_memory = 0
        while not done.wait(5):
            if time.monotonic() - clock > limit:
                stop('runtime_limit')
                return
            try:
                q = 'mysql_up or (container_memory_working_set_bytes{container_label_com_docker_compose_service="mysql"}/container_spec_memory_limit_bytes{container_label_com_docker_compose_service="mysql"})'
                with urlopen('http://prometheus:9090/api/v1/query?' + urlencode({'query': q}), timeout=3) as reply:
                    vector = json.load(reply)['data']['result']
                point = {r['metric'].get('__name__', 'working_set_ratio'): float(r['value'][1]) for r in vector}
                if point.get('mysql_up') != 1 or 'working_set_ratio' not in point:
                    raise ValueError('Missing DB observation')
                point['at'] = utc()
                guard['samples'].append(point)
                high_memory = high_memory + 1 if point['working_set_ratio'] > .92 else 0
                if high_memory >= 3:
                    stop('mysql_memory_guard')
                    return
                misses = 0
            except Exception:
                misses += 1
                if misses >= 3:
                    stop('monitoring_unavailable')
                    return
    thread = threading.Thread(target=watch, daemon=True)
    thread.start()
    lines = []
    folder.mkdir(parents=True, exist_ok=True)
    with (folder / 'output.txt').open('w') as output:
        for line in process.stdout:
            safe = line.replace(os.environ['LT_SYSBENCH_PASSWORD'], '<redacted>')
            lines.append(safe)
            output.write(safe)
            output.flush()
            print(safe, end='', flush=True)
    status = process.wait()
    done.set()
    thread.join(timeout=4)
    raw = ''.join(lines)
    def number(pattern):
        match = re.search(pattern, raw)
        return float(match.group(1)) if match else None
    result = {'startedAtUtc': started, 'finishedAtUtc': utc(), 'elapsedSeconds': time.monotonic()-clock,
              'exitCode': status, 'guardReason': guard['reason'], 'resources': guard['samples'],
              'transactions': number(r'transactions:\s+(\d+)'),
              'tps': number(r'transactions:\s+\d+\s+\(([\d.]+) per sec'),
              'qps': number(r'queries:\s+\d+\s+\(([\d.]+) per sec'),
              'ignoredErrors': number(r'ignored errors:\s+(\d+)'),
              'reconnects': number(r'reconnects:\s+(\d+)'),
              'averageMs': number(r'avg:\s+([\d.]+)'),
              'p95Ms': number(r'95th percentile:\s+([\d.]+)'),
              'maxMs': number(r'max:\s+([\d.]+)')}
    (folder / 'summary.json').write_text(json.dumps(result, indent=2))
    if status or guard['reason']:
        raise RuntimeError('Benchmark aborted; inspect isolated run output')
    return result


def main():
    args = sys.argv[1:]
    if args == ['version']:
        subprocess.run(['sysbench', '--version'], check=True)
        return
    if not re.fullmatch('[a-f0-9]{64}', os.environ.get('LT_SYSBENCH_PASSWORD', '')):
        raise RuntimeError('A private benchmark password is required')
    run_id = os.environ['LT_DB_RUN_ID']
    if not re.fullmatch(r'db-\d{8}T\d{6}Z', run_id):
        raise RuntimeError('Invalid benchmark run ID')
    root = Path('/results') / run_id
    with tempfile.TemporaryDirectory(prefix='sysbench-') as private:
        client = str(Path(private) / 'client.cnf')
        bench = str(Path(private) / 'sysbench.cnf')
        credentials = 'host=mysql\nport=3306\nuser=molelaw_sysbench\npassword=' + os.environ['LT_SYSBENCH_PASSWORD'] + '\n'
        Path(client).write_text('[client]\n' + credentials)
        Path(bench).write_text('mysql-host=mysql\nmysql-port=3306\nmysql-user=molelaw_sysbench\nmysql-db=' + SCHEMA + '\nmysql-password=' + os.environ['LT_SYSBENCH_PASSWORD'] + '\n')
        os.chmod(client, 0o600)
        os.chmod(bench, 0o600)
        if query_scalar('SELECT DATABASE()', client) != SCHEMA:
            raise RuntimeError('Wrong benchmark schema')
        common = ['sysbench', '--config-file=' + bench, '--tables=4', '--table-size=500000',
                  '--db-driver=mysql', '--mysql-ignore-errors=1213', '--rand-type=uniform', '--rand-seed=42']
        if args == ['prepare']:
            count = int(query_scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'sbtest%'", client))
            if count == 0:
                run_process(common + ['--threads=4', '/usr/share/sysbench/oltp_read_write.lua', 'prepare'], root/'prepare', 900)
            elif count != TABLES:
                raise RuntimeError('Partial dataset exists; no tables are deleted automatically')
            for i in range(1, TABLES+1):
                if int(query_scalar(f'SELECT COUNT(*) FROM sbtest{i}', client)) != ROWS:
                    raise RuntimeError('Existing benchmark row count differs')
            query_scalar('CREATE TABLE IF NOT EXISTS bench_hotspot (id INT PRIMARY KEY,counter BIGINT NOT NULL) ENGINE=InnoDB', client)
            query_scalar('INSERT IGNORE INTO bench_hotspot VALUES (1,0)', client)
            info = query_scalar("SELECT VERSION(),@@innodb_buffer_pool_size,@@max_connections,@@innodb_flush_log_at_trx_commit,@@sync_binlog,@@transaction_isolation,@@log_bin,@@innodb_redo_log_capacity", client)
            size = query_scalar('SELECT SUM(data_length+index_length) FROM information_schema.tables WHERE table_schema=DATABASE()', client)
            root.mkdir(parents=True, exist_ok=True)
            (root/'dataset.json').write_text(json.dumps({'schema':SCHEMA, 'dataset':DATASET,'tables':TABLES,'rowsPerTable':ROWS,'estimatedBytes':size,'mysqlSettings':info}, indent=2))
            print('Dedicated benchmark dataset verified: 4 tables, 2,000,000 rows; app schema untouched.')
        elif args == ['verify']:
            for i in range(1, TABLES+1):
                if int(query_scalar(f'SELECT COUNT(*) FROM sbtest{i}', client)) != ROWS:
                    raise RuntimeError('Final benchmark row count differs')
            print('Final benchmark row counts verified.')
        elif len(args) == 5 and args[0] == 'run':
            _, profile, threads, warmup, duration = args
            threads, warmup, duration = int(threads), int(warmup), int(duration)
            if profile not in ('read','mixed','hot') or threads not in (1,4,8,16,32) or not (0<=warmup<=60 and 20<=duration<=180):
                raise RuntimeError('Unsupported benchmark scope')
            script = {'read':'/usr/share/sysbench/oltp_read_only.lua', 'mixed':'/usr/share/sysbench/oltp_read_write.lua','hot':'/bench/hotrow.lua'}[profile]
            opts = common if profile != 'hot' else ['sysbench','--config-file='+bench,'--db-driver=mysql','--mysql-ignore-errors=1213']
            folder = root/f'{profile}-t{threads}-s{duration}'
            if (folder/'summary.json').exists():
                raise RuntimeError('Run output exists; choose a new run ID')
            if warmup:
                run_process(opts + [f'--threads={threads}', f'--time={warmup}', '--events=0', script, 'run'], folder/'warmup', warmup+90)
            result = run_process(opts + [f'--threads={threads}',f'--time={duration}', '--events=0','--report-interval=5','--percentile=95',script,'run'], folder, duration+90)
            result.update({'profile':profile,'threads':threads,'warmupSeconds':warmup,'measurementSeconds':duration,'dataset':DATASET})
            (folder/'summary.json').write_text(json.dumps(result, indent=2))
        else:
            raise RuntimeError('Use version, prepare, verify, or bounded run profile/threads/warmup/duration')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('DB benchmark stopped (' + type(error).__name__ + '); inspect local logs. Credentials are not printed.', file=sys.stderr)
        sys.exit(1)
