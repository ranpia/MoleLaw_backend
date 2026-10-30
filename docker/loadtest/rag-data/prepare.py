"""Acquire pinned official statutes; prepare tokenizer-only performance fixtures.

No encoder, database writes, answer generation or private credentials are used.
"""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import time
import unicodedata
from urllib.request import Request, urlopen

BASE = Path(__file__).parent
SPEC = json.loads((BASE/'sources.json').read_text())
SNAPSHOT = BASE/'snapshot'
OUTPUT = Path('/results')/SPEC['dataset']


def digest(value):
    return hashlib.sha256(value).hexdigest()


def normalized(text):
    return re.sub(r'\s+', ' ', unicodedata.normalize('NFC',text)).strip()


def write_json(path, value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')


def write_lines(path, rows):
    path.write_text(''.join(json.dumps(row,ensure_ascii=False,sort_keys=True)+'\n' for row in rows))


class Articles(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.level = 0
        self.start_level = None
        self.skip_span = 0
        self.text = []
        self.code = None
        self.rows = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == 'div':
            self.level += 1
            if self.start_level is None and 'lawcon' in attrs.get('class','').split():
                self.start_level = self.level
                self.text, self.code = [], None
        if self.start_level is not None:
            if tag == 'input' and attrs.get('name') == 'joNoList':
                self.code = attrs.get('id')
            if tag == 'span' and (self.skip_span or 'sfon' in attrs.get('class','').split()):
                self.skip_span += 1
            if tag in ('p','br'):
                self.text.append(' ')

    def handle_endtag(self, tag):
        if tag == 'span' and self.skip_span:
            self.skip_span -= 1
        if tag == 'div':
            if self.start_level == self.level:
                text = normalized(''.join(self.text))
                if self.code and re.fullmatch(r'Y\d{6}',self.code) and re.match(r'^제\d+조',text):
                    self.rows.append({'article_code':self.code[1:],'text':text})
                self.start_level, self.skip_span = None, 0
            self.level -= 1

    def handle_data(self, data):
        if self.start_level is not None and not self.skip_span:
            self.text.append(data)


def acquire():
    if SNAPSHOT.exists():
        raise RuntimeError('Snapshot already exists; do not overwrite a versioned dataset')
    raw_root = OUTPUT/'raw'
    raw_root.mkdir(parents=True,exist_ok=True)
    articles, provenance = [], []
    for source in SPEC['sources']:
        seq = source['lsiSeq']
        url = f'https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq={seq}&efYd={source["effective_date"]}&chrClsCd=010202'
        with urlopen(Request(url,headers={'User-Agent':'MoleLaw-Performance-Fixture/1.0'}),timeout=45) as reply:
            raw = reply.read(8_000_001)
            if len(raw) > 8_000_000:
                raise RuntimeError('Source exceeds acquisition bound')
            text = raw.decode(reply.headers.get_content_charset() or 'utf-8')
        (raw_root/(source['id']+'.html')).write_bytes(raw)
        name = re.search(r'<h2[^>]*>\s*([^<]+)',text)
        version = re.search(r'\[시행\s+[^\]]+\]\s*\[법률[^\]]+\]',text)
        if not name or normalized(name.group(1)) != source['name'] or not version:
            raise RuntimeError('Source identity/version cannot be verified')
        parser = Articles()
        parser.feed(text)
        unique = {}
        deleted = 0
        for row in parser.rows:
            if re.match(r'^제\d+조(?:의\d+)?\s+삭제',row['text']):
                deleted += 1
                continue
            key = source['id']+':'+row['article_code']
            if key in unique:
                raise RuntimeError('Duplicate main article code; parser needs review')
            unique[key] = {'id':key, 'source_id':source['id'], 'law_name':source['name'],
                           'lsiSeq':seq, 'version':normalized(version.group()),
                           'source_url':f'https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq={seq}',
                           **row, 'text_sha256':digest(row['text'].encode())}
        if len(unique) < 20:
            raise RuntimeError('Too few main articles; parser/source may have changed')
        articles.extend(unique.values())
        provenance.append({**source,'download_url':url,'raw_sha256':digest(raw),
                           'downloaded_at_utc':datetime.now(timezone.utc).isoformat(),
                           'version':normalized(version.group()),'articles':len(unique),'deleted_omitted':deleted})
        print(f"Acquired {source['name']} / {seq}: {len(unique)} articles",flush=True)
        time.sleep(1)
    SNAPSHOT.mkdir()
    write_lines(SNAPSHOT/'articles.jsonl',articles)
    write_json(SNAPSHOT/'provenance.json',{'dataset':SPEC['dataset'],'sources':provenance,
               'articles_sha256':digest((SNAPSHOT/'articles.jsonl').read_bytes()),
               'extraction':'Main joNoList lawcon blocks; HTML/UI, sfon history notes, deleted articles omitted; NFC/whitespace normalized'})


def prepare():
    from transformers import AutoTokenizer
    from huggingface_hub import snapshot_download
    from importlib.metadata import version
    articles = [json.loads(line) for line in (SNAPSHOT/'articles.jsonl').read_text().splitlines()]
    provenance = json.loads((SNAPSHOT/'provenance.json').read_text())
    assert digest((SNAPSHOT/'articles.jsonl').read_bytes()) == provenance['articles_sha256']
    tokenizer_path = Path(snapshot_download(SPEC['model'],revision=SPEC['revision'],local_files_only=True))
    assert tokenizer_path.name == SPEC['revision']
    tokenizer = AutoTokenizer.from_pretrained(tokenizer_path,local_files_only=True,trust_remote_code=False)
    tokenizer.model_max_length = 1000000  # Tokenizer-only: explicit limits below, no model execution.
    special = tokenizer.num_special_tokens_to_add(pair=False)
    def token_count(text):
        return len(tokenizer(text,add_special_tokens=True,truncation=False)['input_ids'])
    chunks = []
    for article in articles:
        content = article['law_name']+' '+article['text']
        encoded = tokenizer(content,add_special_tokens=False,return_offsets_mapping=True,truncation=False)
        offsets = encoded['offset_mapping']
        start = 0
        while start < len(offsets):
            end = min(len(offsets),start+512-special)
            piece = content[offsets[start][0]:offsets[end-1][1]]
            length = token_count(piece)
            while length > 512 and end > start+1:
                end -= 1
                piece = content[offsets[start][0]:offsets[end-1][1]]
                length = token_count(piece)
            assert 2 < length <= 512
            chunks.append({'id':article['id']+f':chunk-{start}', 'article_id':article['id'],
                           'source_id':article['source_id'],'source_url':article['source_url'],
                           'lsiSeq':article['lsiSeq'],'version':article['version'],
                           'text':piece,'tokens':length,'sha256':digest(piece.encode()),
                           'transform':'contiguous article character slice at tokenizer offsets',
                           'character_start':offsets[start][0],'character_end':offsets[end-1][1]})
            if end == len(offsets):
                break
            start = max(start+1,end-64)
    by_hash = {}
    for chunk in chunks:
        by_hash.setdefault(chunk['sha256'],chunk)
    chunks = list(by_hash.values())
    if len(chunks) < 1000:
        raise RuntimeError('Fewer than 1,000 unique real chunks; acquire more source laws instead of duplicating')
    buckets = defaultdict(list)
    questions = json.loads((BASE/'questions.json').read_text())
    referenced = {key for question in questions for key in question['candidate_articles']}
    corpus = [chunk for chunk in chunks if chunk['article_id'] in referenced]
    assert len(corpus) < 1000
    included = {chunk['id'] for chunk in corpus}
    for chunk in chunks:
        if chunk['id'] not in included:
            buckets[chunk['source_id']].append(chunk)
    while len(corpus) < 1000:
        for source in SPEC['sources']:
            if buckets[source['id']] and len(corpus) < 1000:
                corpus.append(buckets[source['id']].pop(0))
    length_inputs = []
    grouped_articles = {s['id']:[a for a in articles if a['source_id']==s['id']] for s in SPEC['sources']}
    length_pool = []
    for index in range(max(len(rows) for rows in grouped_articles.values())):
        for source in SPEC['sources']:
            rows = grouped_articles[source['id']]
            if index < len(rows):
                length_pool.append(rows[index])
    # Distinct contiguous article concatenations; not pretend single legal documents.
    for target in (128,512,2048,4096):
        for variant in range(12):
            parts, ids = [], []
            cursor = variant*17
            while True:
                article = length_pool[cursor % len(length_pool)]
                parts.append(article['law_name']+' '+article['text'])
                ids.append(article['id'])
                cursor += 1
                combined = '\n'.join(parts)
                encoded = tokenizer(combined,add_special_tokens=False,return_offsets_mapping=True,truncation=False)
                if len(encoded['input_ids']) >= target-special:
                    break
            offsets = encoded['offset_mapping']
            end = target-special
            piece = combined[:offsets[end-1][1]]
            while token_count(piece) > target:
                end -= 1
                piece = combined[:offsets[end-1][1]]
            count = token_count(piece)
            assert target-4 <= count <= target
            length_inputs.append({'id':f'length-{target}-{variant:02d}','kind':'concatenated_statute_length_fixture',
                 'target_tokens':target,'tokens':count,'text':piece,'source_articles':ids,
                 'sha256':digest(piece.encode()),'not_single_article':True})
    boundary_inputs = []
    for target in (8191,8192,8193):
        text = ('test '*(target-special)).strip()
        count = token_count(text)
        assert count == target
        boundary_inputs.append({'id':f'boundary-{target}','kind':'synthetic_context_boundary',
             'text':text,'tokens':count,'sha256':digest(text.encode()),
             'expected_input_validation':'reject' if count > 8192 else 'accept',
             'not_statute_text':True})
    available = {a['id'] for a in articles}
    assert referenced <= {c['article_id'] for c in corpus}
    for question in questions:
        assert set(question['candidate_articles']) <= available, question['id']
        question['kind'] = 'project_authored_synthetic_query'
        question['labels_status'] = 'candidate_articles_not_expert_validated'
        question['tokens'] = token_count(question['text'])
        question['sha256'] = digest(question['text'].encode())
    OUTPUT.mkdir(parents=True,exist_ok=True)
    files = {'chunks.jsonl':chunks,'corpus-1000.jsonl':corpus,'length-inputs.jsonl':length_inputs,'queries.jsonl':questions,'boundary-inputs.jsonl':boundary_inputs}
    for name, rows in files.items():
        path = OUTPUT/name
        if path.exists() and path.read_text() != ''.join(json.dumps(row,ensure_ascii=False,sort_keys=True)+'\n' for row in rows):
            raise RuntimeError('Existing generated data differs; do not overwrite a versioned fixture')
        write_lines(path,rows)
    summary = {'dataset':SPEC['dataset'],'model':SPEC['model'],'revision':SPEC['revision'],
       'tokenizer_special_tokens':special,'tokenizer_files':{p.name:digest(p.read_bytes()) for p in tokenizer_path.iterdir() if p.is_file() and (p.name.startswith('tokenizer') or p.name in ('sentencepiece.bpe.model','special_tokens_map.json'))},
       'libraries':{name:version(name) for name in ('transformers','tokenizers')},
       'articles':len(articles),'unique_chunks':len(chunks),'initial_corpus':len(corpus),
       'article_sources':dict(Counter(a['source_id'] for a in articles)),
       'corpus_sources':dict(Counter(c['source_id'] for c in corpus)),
       'queries':len(questions),'length_inputs':len(length_inputs),'boundary_inputs':len(boundary_inputs),
       'length_groups':{str(target):[r['tokens'] for r in length_inputs if r['target_tokens']==target] for target in (128,512,2048,4096)},
       'corpus_tokens':{'min':min(c['tokens'] for c in corpus),'max':max(c['tokens'] for c in corpus)},
       'source_snapshot_sha256':provenance['articles_sha256'],
       'files':{name:{'rows':len(rows),'sha256':digest((OUTPUT/name).read_bytes())} for name,rows in files.items()},
       'limitations':['Historical fixed law snapshots; not current legal advice','Candidate query mappings are not relevance gold labels',
                       'No embedding, Qdrant writes or AI answer generation performed','10,000 unique real vectors require additional source corpus']}
    lock = SNAPSHOT/'preparation.lock.json'
    if lock.exists() and json.loads(lock.read_text()) != summary:
        raise RuntimeError('Preparation differs from the checked-in dataset lock; review before changing dataset version')
    write_json(OUTPUT/'manifest.json',summary)
    print(json.dumps({k:summary[k] for k in ('dataset','articles','unique_chunks','initial_corpus','queries','length_inputs','article_sources','corpus_sources')},ensure_ascii=False),flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('command',choices=('acquire','prepare'))
    args = parser.parse_args()
    if args.command == 'acquire':
        acquire()
    else:
        prepare()
