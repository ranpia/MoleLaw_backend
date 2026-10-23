# 로컬 실험 산출물 보존 기록

2026-10-23 서비스 dev 동기화 과정에서 로컬에 남은 실험 자료를 기존 실험 브랜치로 분리했다. 새 모델 추론·검색 평가·점수 재산정은 수행하지 않았다.

## 보존한 자료

원본 `artifacts/` 4,524개·약 324MiB를 별도 실험 워크트리에 그대로 보존했다. 캐시·로그·다운로드 원문·실행 제어 파일은 계속 Git 제외다. 공유할 JSON·CSV·JSONL 등 114개를 SHA-256으로 비교한 결과 100개는 이미 추적 중이어서 복제하지 않고, 추가 14개만 목적별 폴더에 저장했다.

| 목적 | 위치 | 추가 파일 |
| --- | --- | ---: |
| 독립 검증 코퍼스 원문 단위 인벤토리 | [independent-v1/data](../../retrieval-validation/independent-v1/data/) | 1 |
| 과거 파일 배치·Qdrant 목록 관측 기록 | [qdrant/review/archive/organization-v1](../qdrant/review/archive/organization-v1/) | 5 |
| 초기 EmbeddingGemma 실행 결과 | [model-comparison/review/archive/local-runs](../../model-comparison/review/archive/local-runs/) | 8 |

초기 실행은 보고서가 일부만 남은 경우도 있다. 완료된 모델 비교나 새 합격 결과로 취급하지 않는다. Qdrant JSON은 당시 목록을 보관한 자료이며 현재 서비스 컬렉션 상태나 정리 실행 지시가 아니다.

## 경로와 검증

[manifest.json](manifest.json)의 `Artifact`는 기존 `artifacts/` 기준 경로다. `ExistingTracked`와 `ArchivePath`는 저장소 루트 기준 경로이며 둘 중 하나가 실제 공유 위치를 가리킨다. `SHA256`·`Bytes`는 원본 바이트를 기록한다. 기존 매니페스트·결과 JSON 내부의 경로는 재직렬화하지 않았다.

실험 루트 `.gitattributes`의 고정 바이트 정책을 유지한다. 추가 14개의 원본·작업 파일·Git 인덱스 SHA-256 일치와 JSON/JSONL 문법(6,527행)을 확인했다. 기존 공통 경로 해석기와 실행 스크립트는 변경하지 않았다. 벡터 캐시와 기존 Qdrant 데이터도 삭제·재생성하지 않았다.

서비스 개발은 `MoleLaw_backend`의 `dev`, 실험은 인접한 `MoleLaw_backend-experiments`의 `experiment/legal-embedding-evaluation`에서 계속한다.

검증 명령은 다음과 같으며 모두 통과했다. 첫 검사는 고정 데이터·변형 데이터의 동일성, 잘못된 정답·분할·보고서 해시 거절을 확인한다. 두 번째는 현재 경로에서 독립 질문·코퍼스·검수·모델·정답 계약을 읽기 전용으로 확인하며 Qdrant 호출·모델 추론 전에 종료한다.

```powershell
.\evaluation\legal-retrieval\scripts\test-evaluation-contract.ps1
.\evaluation\legal-retrieval\scripts\run-kure-independent-validation.ps1 -ValidateOnly
```