// Rebuild documentation figures from committed Markdown tables. No API calls.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const out = path.join(root, 'docs/testing/figures');
fs.mkdirSync(out, { recursive: true });
const records = [];
const esc = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
const number = value => Number(String(value).replaceAll(',', '').match(/-?\d+(?:\.\d+)?/)?.[0]);
const ratio = value => {
  const [a, b] = value.split('/').map(number);
  if (!(b > 0) || a < 0 || a > b) throw new Error(`Invalid ratio: ${value}`);
  return a / b * 100;
};
function table(file, header, occurrence = 0) {
  const lines = fs.readFileSync(path.join(root, 'docs/testing', file), 'utf8').split(/\r?\n/);
  const starts = lines.flatMap((line, i) => line.startsWith(header) ? [i] : []);
  const start = starts[occurrence];
  if (start === undefined) throw new Error(`Table missing: ${file}: ${header}`);
  const rows = [];
  for (let i = start + 2; i < lines.length && lines[i].startsWith('|'); i++) {
    rows.push(lines[i].split('|').slice(1, -1).map(s => s.trim()));
  }
  if (!rows.length) throw new Error(`Empty table: ${file}`);
  return rows;
}
const colors = ['#2563eb', '#d97706', '#059669', '#dc2626', '#7c3aed'];
function chart(id, title, subtitle, labels, series, unit, source, note) {
  title = `1차 테스트 · ${title}`;
  if (!labels.length || series.some(s => s.values.length !== labels.length || s.values.some(v => !Number.isFinite(v) || v < 0))) throw new Error(`Invalid figure: ${id}`);
  const W = 1120, H = 240 + labels.length * 48;
  const left = 275, right = 140, top = 145, width = W - left - right;
  const max = Math.max(...series.flatMap(s => s.values), 0.001) * 1.08;
  let svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" role="img" aria-labelledby="title desc"><title id="title">${esc(title)}</title><desc id="desc">${esc(subtitle + '. ' + note)}</desc><style>text{font-family:'Malgun Gothic','Noto Sans KR',sans-serif;fill:#172033} .muted{fill:#526176;font-size:13px}</style><rect width="100%" height="100%" rx="14" fill="#f8fafc"/><text x="28" y="37" font-size="23" font-weight="700">${esc(title)}</text><text x="28" y="65" class="muted">${esc(subtitle)}</text>`;
  series.forEach((s, i) => {
    svg += `<rect x="${28 + i * 255}" y="88" width="12" height="12" rx="3" fill="${colors[i]}"/><text x="${47 + i * 255}" y="99" class="muted">${esc(s.name)}</text>`;
  });
  for (let i = 0; i <= 4; i++) {
    const x = left + width * i / 4;
    svg += `<line x1="${x}" y1="${top - 14}" x2="${x}" y2="${top + labels.length * 48 - 8}" stroke="#dbe3ed"/><text x="${x}" y="${top - 25}" text-anchor="middle" class="muted">${Number((max * i / 4).toPrecision(3))}</text>`;
  }
  labels.forEach((label, j) => {
    const y = top + j * 48;
    svg += `<text x="${left - 14}" y="${y + 17}" text-anchor="end" font-size="14">${esc(label)}</text>`;
    series.forEach((s, i) => {
      const h = Math.min(19, 32 / series.length), sy = y + i * (h + 2), v = s.values[j];
      const bar = v / max * width;
      const display = unit === 'USD' ? v.toFixed(8) : Number(v.toFixed(4));
      svg += `<rect x="${left}" y="${sy}" width="${bar}" height="${h}" rx="2" fill="${colors[i]}"><title>${esc(`${label} / ${s.name}: ${v} ${unit}`)}</title></rect><text x="${left + bar + 7}" y="${sy + h - 2}" font-size="12">${display} ${esc(unit)}</text>`;
    });
  });
  svg += `<text x="28" y="${H - 48}" class="muted">${esc(note)}</text><text x="28" y="${H - 24}" class="muted">출처: ${esc(source)} · 2026-10-28~29 기록 / 2026-10-30 시각화 · 재측정 없음</text></svg>`;
  fs.writeFileSync(path.join(out, `${id}.svg`), svg);
  records.push({ id, title, source, unit, labels, series, note });
}

let rows = table('read-capacity.md', '| 구간 |');
// Keep the short exploration and sustained confirmation separate.
chart('read-throughput', '조회 API: 처리량 증가와 포화', '사전 인증 조회 · 사용자당 2~5초 휴식 · 같은 호스트 · 약식 완료 RPS 포함', rows.map(r => r[0]), [{name:'완료 처리량', values:rows.map(r => number(r[2]))}], 'RPS', 'read-capacity.md', '1,024 VU 지속 시험은 HTTP 500으로 중단. VU는 동시 쿼리 수가 아니다.');
// The sustained 1,024 VU p95 is a range, so do not fabricate a point estimate.
const readLatency = rows.filter(r => !r[0].includes('지속'));
chart('read-latency', '조회 API: 부하별 p95 지연', '1,024 VU 지속 p95 1.5~1.7초는 범위값이므로 막대에서 제외', readLatency.map(r => r[0]), [{name:'응답 p95', values:readLatency.map(r => number(r[3]) * (r[3].includes('ms') ? 1 : 1000))}], 'ms', 'read-capacity.md', '64/256/512는 약 180초, 1,024/2,048 탐색은 약 45초. 측정 길이가 다르다.');

rows = table('mysql-capacity.md', '| 시나리오 | 연결 | 측정 |');
const connections = [1,4,8,16,32];
const mysql = ['읽기','혼합'].map(name => rows.filter(r => r[0] === name));
chart('mysql-throughput', 'MySQL: 연결 수별 읽기·혼합 처리량', 'sysbench · CPU 1개 · 200만 행 · 탐색 각 30초', connections.map(n => `${n} 연결`), mysql.map((rs,i) => ({name:['읽기','읽기·쓰기 혼합'][i], values:rs.map(r=>number(r[3]))})), 'TPS', 'mysql-capacity.md', 'TPS는 HTTP RPS와 다름. 읽기 확인 515.10 TPS, 혼합 확인 268.94 TPS는 별도 120초 기록.');
chart('mysql-latency', 'MySQL: 연결 수별 p95 지연', '읽기·혼합 탐색 각 30초 · 동일 자원 설정', connections.map(n => `${n} 연결`), mysql.map((rs,i) => ({name:['읽기','읽기·쓰기 혼합'][i], values:rs.map(r=>number(r[4]))})), 'ms', 'mysql-capacity.md', '연결 수 증가가 처리량 증가를 보장하지 않는다. 단일 행 경합은 원문 별도 표 참조.');

rows = table('redis-performance.md', '| 세션 수 |');
chart('refresh-throughput', 'Refresh API: 단일 발생기 탐색', '생각 시간 0.05초 · 50/100세션에서 발생기 CPU 경고 · 각 단계 실패 0', rows.map((r,i)=>`${r[0]} 세션${i===5?' / 같은 계정':''}`), [{name:'약식 완료 처리량',values:rows.map(r=>number(r[2]))}], 'RPS', 'redis-performance.md', '다중 발생기 최종 확인은 100세션·120초·883.1 RPS·p95 160ms. 탐색과 조건이 다르다.');
rows = table('redis-performance.md', '| 명령 | 연결 |');
chart('redis-commands', 'Redis 직접 명령: 지속 측정 처리량', '연결 50개 · pipeline 1 · CPU 0.5개 · 각 60초 이상', rows.slice(0,3).map(r=>`${r[0]} / ${r[2]}`), [{name:'직접 명령 처리량',values:rows.slice(0,3).map(r=>number(r[4]))}], 'OPS', 'redis-performance.md', 'PING·GET·SETEX의 명령 비용은 다르다. Lua 회전·Refresh API·짧은 pipeline 탐색과 분리.');

rows = table('embedding-capacity.md', '| 동시 요청 |', 0).filter(r=>r[1]==='60초');
let docs = table('embedding-capacity.md', '| 동시 요청 |', 1).filter(r=>r[1]==='60초');
const common = [1,2,4,5,8];
const embed = [rows,docs].map(rs=>common.map(n=>rs.find(r=>number(r[0])===n)));
chart('embedding-throughput', '임베딩: 입력 길이와 성공 처리량', 'KURE · CPU 4개 · 단일 추론·대기 4 · 탐색 각 60초', common.map(n=>`동시 요청 ${n}개`), embed.map((rs,i)=>({name:['짧은 질문','511~512토큰 문서'][i],values:rs.map(r=>number(r[2]))})), '성공/초', 'embedding-capacity.md', '120초 무거부 확인은 질문 5개·문서 2개. 백엔드 전체 RAG 동시 사용자 수가 아니다.');
chart('embedding-rejections', '임베딩: 503 거부율', '거부 수 / 시작 창 전체 요청 수 · 문서 동시 3개는 별도 세분화 결과', common.map(n=>`동시 요청 ${n}개`), embed.map((rs,i)=>({name:['짧은 질문','511~512토큰 문서'][i],values:rs.map(r=>ratio(r[4]))})), '%', 'embedding-capacity.md', '빠른 503은 성공 지연에 포함하지 않는다. 동시 8개에서는 큐 포화와 대기 만료를 함께 확인.');

rows = table('reranker-capacity.md', '| 후보 수 | 동시 요청 |').filter(r=>r[2]==='60초');
chart('reranker-rejections', '리랭커: 동시 요청별 503 거부율', 'BGE · CPU 4개 · 단일 추론·대기 4 · 각 60초 · 성공 p95는 원문 참조', [1,2,8].map(n=>`동시 요청 ${n}개`), [5,20].map(n=>({name:`후보 ${n}개`,values:rows.filter(r=>number(r[0])===n).map(r=>ratio(r[6]))})), '%', 'reranker-capacity.md', '120초 동시 1개는 후보 5개 24건·20개 12건 성공. 작은 표본으로 운영 백분위를 보장하지 않는다.');

rows = table('mixed-rag-capacity.md', '| 후보 | E+R |');
chart('mixed-rejections', '혼합 부하: 연산별 503 거부율', 'E=임베딩 worker / R=리랭커 worker · 공유 추론 잠금 1개', rows.map(r=>`후보 ${r[0]} / E+R ${r[1]}`), [0,1].map(i=>({name:['임베딩','리랭킹'][i],values:rows.map(r=>ratio(r[5].split(' / ')[i]))})), '%', 'mixed-rag-capacity.md', '1+1은 120초, 나머지는 60초. 후보 20개·4+4의 임베딩 성공은 4건뿐이다.');

rows = table('answer-generation-results.md', '| 단계 | 성공/시도 |');
chart('writer-latency', '최종 writer 재실행: 첫 텍스트와 완료', '고정 근거·실제 모델 · 생성 단독 · 전체 RAG 지연과 다름', rows.map(r=>`${r[0]} (${r[1]})`), [{name:'첫 모델 텍스트 평균',values:rows.map(r=>number(r[2]))},{name:'생성 완료 평균',values:rows.map(r=>number(r[3]))}], '초', 'answer-generation-results.md', '초기 6호출 중 인용 실패 2건은 별도 보존. 재실행 성공이 간헐적 형식 오류 해결을 뜻하지 않는다.');

rows = table('full-rag-first-live.md', '| 항목 |');
chart('rag-stage-gaps', '전체 RAG 1상담: 관측된 단계 간격', '첫 answer_delta 39.002초 / completed 42.365초 · 무저장·고정 색인', rows.slice(2).map(r=>r[0]), [{name:'클라이언트 progress 간격',values:rows.slice(2).map(r=>number(r[1]))}], '초', 'full-rag-first-live.md', '내부 trace가 아님. 검색에는 임베딩·Qdrant·리랭킹 포함. 간격 합과 전체 경과 시간은 다르다.');
rows = table('full-rag-first-live.md', '| 역할 | 모델 / 실제 effort |');
chart('rag-cost', '전체 RAG 1상담: 역할별 추정 비용', '공급자 5호출 · 캐시 할인 제외 · 당시 기록 가격 · 실제 청구와 다름', rows.map(r=>r[0]), [{name:'추정 비용',values:rows.map(r=>number(r[4]))}], 'USD', 'full-rag-first-live.md', '합계 USD 0.04329075. reasoning은 출력 토큰에 포함하며 비용에 중복 가산하지 않는다.');

fs.writeFileSync(path.join(out,'data.json'), JSON.stringify({round:'1차 테스트', basis:'Committed Markdown tables; historical runs, not rerun', figures:records},null,2)+'\n');
console.log(`Rendered ${records.length} SVG figures and data.json from documentation tables.`);
