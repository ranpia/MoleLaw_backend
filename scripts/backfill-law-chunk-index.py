"""Audit/backfill metadata for existing COMPLETE snapshots; never writes vectors or law text.

Run from the repository: python scripts/backfill-law-chunk-index.py [--apply]
Requires the local Docker Compose database and Qdrant. Secrets are never printed.
"""
import argparse
import collections
import hashlib
import json
import pathlib
import re
import subprocess
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parent.parent


def mysql(sql):
    script = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" -D "$MYSQL_DATABASE" -N -B --default-character-set=utf8mb4 <<\'SQL\'\n' + sql + '\nSQL\n'
    result = subprocess.run(['docker', 'compose', '--env-file', 'docker/.env.compose', '-f', 'docker/compose.yml',
                             'exec', '-T', 'mysql', 'sh'], input=script, capture_output=True,
                            text=True, encoding='utf-8', cwd=ROOT, timeout=60)
    if result.returncode:
        raise RuntimeError('Metadata SQL failed; no credentials printed')
    return result.stdout.splitlines()


def run(apply, max_points):
    values = dict(line.split('=', 1) for line in (ROOT / '.env').read_text(encoding='utf-8').splitlines()
                  if '=' in line and not line.startswith('#'))
    collection = values['APP_LAW_VECTOR_COLLECTION_NAME']
    if not re.fullmatch(r'molelaw_service_[a-z0-9_]{1,80}', collection):
        raise ValueError('Invalid service collection')
    rows = mysql("SELECT JSON_OBJECT('generation',i.generation,'reference_id',s.id,'content_hash',i.content_hash,"
                 "'config_fingerprint',i.config_fingerprint,'model_revision',i.model_revision,'external_id',s.external_id,"
                 "'law_id',COALESCE(s.law_id,''),'effective_date',COALESCE(s.effective_date,''),'source_url',s.source_url,"
                 "'chunk_count',i.chunk_count,'dimensions',i.dimensions) FROM legal_source_index i "
                 "JOIN legal_source s ON s.id=i.source_id WHERE i.status='COMPLETE' AND s.source_type='LAW' "
                 "AND i.content_hash=s.content_hash AND i.collection_name='" + collection + "';")
    snapshots = {row['generation']: row for row in map(json.loads, rows)}
    points = collections.defaultdict(list)
    counts = collections.Counter()
    offset = None
    total = 0
    while True:
        body = {'limit': 256, 'with_vector': False, 'with_payload': [
            'generation', 'reference_id', 'content_hash', 'config_fingerprint', 'model_revision',
            'external_id', 'law_id', 'effective_date', 'source_url', 'source_type', 'unit_id', 'title', 'text']}
        if offset is not None:
            body['offset'] = offset
        request = urllib.request.Request(values.get('APP_LAW_VECTOR_QDRANT_BASE_URL', 'http://localhost:6333').rstrip('/')
                                         + '/collections/' + collection + '/points/scroll',
                                         data=json.dumps(body).encode(), headers={
                                             'api-key': values['QDRANT_API_KEY'], 'Content-Type': 'application/json'})
        with urllib.request.urlopen(request, timeout=15) as response:
            result = json.load(response)['result']
        total += len(result['points'])
        if total > max_points:
            raise ValueError('Point safety limit exceeded; no metadata written')
        for point in result['points']:
            payload = point['payload']
            generation = payload.get('generation')
            if generation not in snapshots:
                continue
            snapshot = snapshots[generation]
            if payload.get('source_type') != 'LAW' or any(payload.get(key) != snapshot[key] for key in (
                    'reference_id', 'content_hash', 'config_fingerprint', 'model_revision', 'external_id',
                    'law_id', 'effective_date', 'source_url')):
                raise ValueError('Snapshot/payload identity mismatch')
            if not isinstance(payload.get('title'), str) or not isinstance(payload.get('text'), str):
                raise ValueError('Missing embedding input')
            prefix = snapshot['external_id'] + ':'
            unit = payload['unit_id']
            if not unit.startswith(prefix):
                raise ValueError('Article identity mismatch')
            article = unit[len(prefix):]
            if not re.fullmatch(r'[0-9]{1,8}(-[0-9]{1,8})?', article):
                raise ValueError('Invalid article path')
            point_id = str(uuid.UUID(point['id']))
            input_hash = hashlib.sha256((payload['title'] + '\n' + payload['text']).encode()).hexdigest()
            points[(generation, article)].append((point_id, input_hash))
            counts[generation] += 1
        offset = result.get('next_page_offset')
        if offset is None:
            break
    if any(counts[generation] != snapshot['chunk_count'] for generation, snapshot in snapshots.items()):
        raise ValueError('Stored point count differs from COMPLETE snapshot; no metadata written')
    references = []
    for (generation, article), group in points.items():
        by_id = dict(group)
        for position in range(len(group)):
            # Same RFC-4122 version-3 construction as Java UUID.nameUUIDFromBytes.
            point_id = str(uuid.UUID(bytes=hashlib.md5((generation + ':' + article + ':' + str(position)).encode()).digest(), version=3))
            if point_id not in by_id:
                raise ValueError('Point ID does not match its snapshot/article/chunk position')
            references.append((point_id, generation, article, position, by_id[point_id]))
    claims = {}
    for generation, snapshot in snapshots.items():
        uuid.UUID(generation)
        if not re.fullmatch(r'[a-f0-9]{64}', snapshot['content_hash']) or not re.fullmatch(r'[a-f0-9]{64}', snapshot['config_fingerprint']):
            raise ValueError('Invalid snapshot hashes')
        claims[generation] = hashlib.sha256((snapshot['external_id'] + '\n' + snapshot['content_hash']
                                            + '\n' + snapshot['config_fingerprint']).encode()).hexdigest()
    if len(set(claims.values())) != len(claims):
        raise ValueError('Duplicate COMPLETE content/configuration groups; manual reconciliation required')
    existing = [json.loads(row) for row in mysql("SELECT JSON_OBJECT('point_id',c.point_id,'generation',c.generation,"
               "'article_path',c.article_path,'chunk_index',c.chunk_index,'input_hash',c.input_hash) "
               "FROM law_chunk_index c JOIN legal_source_index i ON i.generation=c.generation WHERE i.collection_name='" + collection + "';")]
    expected = {row[0]: row for row in references}
    for row in existing:
        if row['generation'] in snapshots and expected.get(row['point_id']) != (
                row['point_id'], row['generation'], row['article_path'], row['chunk_index'], row['input_hash']):
            raise ValueError('Existing relational metadata mismatch')
    print(f'Verified COMPLETE snapshots={len(snapshots)} points={len(references)}; existing metadata={len(existing)}')
    if not apply or not references:
        print('Read-only audit; pass --apply to register IDs/hashes and dedup claims.')
        return
    generations = ','.join("'" + generation + "'" for generation in snapshots)
    sql = ['START TRANSACTION;', 'SELECT generation FROM legal_source_index WHERE generation IN (' + generations + ') FOR UPDATE;']
    for start in range(0, len(references), 256):
        values_sql = ','.join("('%s','%s','%s',%d,'%s')" % row for row in references[start:start+256])
        sql.append('INSERT IGNORE INTO law_chunk_index (point_id,generation,article_path,chunk_index,input_hash) VALUES ' + values_sql + ';')
    for generation, key in claims.items():
        sql.append("UPDATE legal_source_index SET dedup_key='" + key + "' WHERE generation='" + generation
                   + "' AND status='COMPLETE' AND dedup_key IS NULL;")
    sql.append('COMMIT;')
    mysql('\n'.join(sql))
    print('Registered metadata and completed-index claims. No law text/vectors were inserted, updated, or deleted.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--max-points', type=int, default=10000)
    arguments = parser.parse_args()
    run(arguments.apply, arguments.max_points)
