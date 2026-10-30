"""Offline integrity checks for the acquired, generated data (no inference)."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path('/results/legal-perf-v1')
SOURCE = Path(__file__).parent/'snapshot'
manifest = json.loads((ROOT/'manifest.json').read_text())
sha = lambda data: hashlib.sha256(data).hexdigest()
articles = [json.loads(line) for line in (SOURCE/'articles.jsonl').read_text().splitlines()]
assert sha((SOURCE/'articles.jsonl').read_bytes()) == manifest['source_snapshot_sha256']
assert len(articles) == manifest['articles'] == len({a['id'] for a in articles})
lookup = {a['id']:a for a in articles}
for article in articles:
    assert re.match(r'^제\d+조',article['text'])
    assert sha(article['text'].encode()) == article['text_sha256']
    assert '<script' not in article['text'] and 'javascript:' not in article['text']
data = {}
for name, expected in manifest['files'].items():
    raw = (ROOT/name).read_bytes()
    assert sha(raw) == expected['sha256'], name
    rows = [json.loads(line) for line in raw.decode().splitlines()]
    assert len(rows) == expected['rows'] == len({row['id'] for row in rows}), name
    assert len(rows) == len({row['sha256'] for row in rows}), name
    assert all(sha(row['text'].encode()) == row['sha256'] for row in rows), name
    data[name] = rows
chunks = {c['id']:c for c in data['chunks.jsonl']}
for chunk in chunks.values():
    article = lookup[chunk['article_id']]
    original = article['law_name']+' '+article['text']
    assert original[chunk['character_start']:chunk['character_end']] == chunk['text']
    assert 2 < chunk['tokens'] <= 512
assert len(data['corpus-1000.jsonl']) == 1000
assert all(c == chunks[c['id']] for c in data['corpus-1000.jsonl'])
for row in data['length-inputs.jsonl']:
    assert row['target_tokens']-4 <= row['tokens'] <= row['target_tokens']
    assert set(row['source_articles']) <= lookup.keys()
    original = '\n'.join(lookup[key]['law_name']+' '+lookup[key]['text'] for key in row['source_articles'])
    assert original.startswith(row['text'])
for row in data['queries.jsonl']:
    assert set(row['candidate_articles']) <= lookup.keys()
    assert set(row['candidate_articles']) <= {c['article_id'] for c in data['corpus-1000.jsonl']}
    assert row['labels_status'] == 'candidate_articles_not_expert_validated'
assert [r['tokens'] for r in data['boundary-inputs.jsonl']] == [8191,8192,8193]
assert [r['expected_input_validation'] for r in data['boundary-inputs.jsonl']] == ['accept','accept','reject']
lock = json.loads((SOURCE/'preparation.lock.json').read_text())
assert manifest == lock
print('Dataset integrity passed: hashes, unique IDs/text, source slices, 1,000 corpus rows, query links, token groups and boundaries.')
