# 36문항 반복 검색 비교 — 완료

2026-10-31, 현재 작업 트리의 법령 근거 수집 코드와 실행 중 모델 설정을 고정한 비교다. 자세한 해석은 [결과 보고서](../../review/interpretation.md), 조건과 한계는 [비교 계약](../../review/protocol.md)을 본다.

| 자료 | 내용 |
| --- | --- |
| [run.json](run.json) | 완료 상태, 입력 식별자, 서비스 모델 설정, 로컬 소스 복사본 경로 |
| [summary.csv](summary.csv) | 전체 36문항 및 초기 후보 확보 31문항의 최초/종료/선택 지표 |
| [queries.csv](queries.csv) | 문항별 지표·차이·실패·검색 수·지연·최초 후보 밖 회수 진단 |
| [rankings.csv](rankings.csv) | 최초/종료 후보와 최종 선택의 원문 ID·순서·정답 여부 |
| [paired.json](paired.json) | 개선/악화 수, 검색 수, 종료 행동, 지연 요약 |
| `ind-*.json` 36개 | 원본 질문·정답, 계획, 실제 질의, dense 후보, BGE 결과, 전체 판단 라운드·최종 선택 |
| [independent-recalculation.json](independent-recalculation.json) | 정답 집합을 이용한 독립 지표 재계산 |
| [source-snapshot.json](source-snapshot.json) | 사용한 소스·프롬프트 236개 파일의 해시와 원본 HEAD |
| [runtime-build-hashes.json](runtime-build-hashes.json) | 빌드·Wrapper·Java 실행기 6개 파일 해시 |
| [input-hashes.json](input-hashes.json) | 질문·코퍼스·수집 법령명 입력 해시 |
| [qdrant-before.json](qdrant-before.json), [qdrant-after.json](qdrant-after.json) | 정확한 포인트 수와 컬렉션 설정 보존 |
| [model-runtime-observed.json](model-runtime-observed.json), [model-runtime-after.json](model-runtime-after.json) | 모델 컨테이너·이미지·런타임 관측과 유지 검증 |
| [validation.json](validation.json) | 독립 계산 및 원본 보존 검증 결과 |

원문 ID의 `sourceId`/`unitId`는 고정 정답 ID이며 `canonicalSourceId`/`canonicalUnitId`에 서비스 형식 ID를 함께 보존했다. 정답은 집계에만 사용하고 모델에게 제공하지 않았다. 최종 답변 생성은 실행하지 않았으며 실패 시 마지막 후보를 선택된 근거로 간주하지 않는다.

원본 코드·프롬프트·빌드 복사본은 `run.json.snapshotDirectory`의 Git 제외 로컬 자료다. 소스 HEAD가 같아도 당시 작업 트리에는 미커밋 변경이 있으므로 재실행에는 이 복사본 또는 동일 해시의 소스가 필요하다. Git에 있는 실험 브랜치 HEAD만으로 실행 코드를 재현했다고 간주하지 않는다.
