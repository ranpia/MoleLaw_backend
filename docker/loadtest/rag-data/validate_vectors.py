"""Validate stored real vectors offline, without model/API/database access."""
import hashlib
import json
import math
from pathlib import Path


def verify():
    root = Path('/results/legal-perf-v1')
    output = root/'embeddings-v1'
    manifest = json.loads((root/'manifest.json').read_text())
    result = json.loads((output/'result.json').read_text())
    identity = json.loads((output/'identity.json').read_text())
    assert result['completed'] and result['identity'] == identity
    assert identity['revision'] == manifest['revision'] and identity['source_files'] == manifest['files']
    for kind, filename in (('queries','queries.jsonl'),('corpus','corpus-1000.jsonl')):
        original = (root/filename).read_bytes()
        assert hashlib.sha256(original).hexdigest() == manifest['files'][filename]['sha256']
        rows = [json.loads(line) for line in original.decode().splitlines()]
        raw = (output/(kind+'.jsonl')).read_bytes()
        assert hashlib.sha256(raw).hexdigest() == result['files'][kind]['sha256']
        records = [json.loads(line) for line in raw.decode().splitlines()]
        assert len(records) == len(rows) == result['files'][kind]['rows']
        assert len({r['id'] for r in records}) == len(rows)
        for record, source in zip(records,rows):
            assert record['id'] == source['id'] and record['text_sha256'] == source['sha256']
            assert record['tokens'] == source['tokens'] and record['dimensions'] == 1024
            vector = record['vector']
            assert len(vector)==1024 and all(type(v) in (float,int) and math.isfinite(v) for v in vector)
            norm = math.sqrt(sum(v*v for v in vector))
            assert abs(norm-1) <= .01 and abs(norm-record['norm']) < 1e-9
        print(f'Verified {kind}: {len(records)} vectors / source hashes / finite 1024D / unit norm.',flush=True)


if __name__ == '__main__':
    verify()
