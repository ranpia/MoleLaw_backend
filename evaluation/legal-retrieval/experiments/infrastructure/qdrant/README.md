# 실험용 Qdrant 관리

컬렉션 29개를 모델 비교, 검색 검증, 질의 전략으로 분류하고 각 물리 컬렉션에 목적별 별칭을 하나씩 연결했다. [관리 목록](collections.json)에 별칭·물리 이름·정확한 포인트 수·실측 메타데이터·벡터 스키마·관련 결과 위치를 보관한다. 결과 경로는 `evaluation/legal-retrieval/` 기준이다.

| 별칭 접두사 | 자료 |
| --- | --- |
| `lab_models_` | 4개 모델의 청크별 문서, 질문, 기존 평가 목록 |
| `lab_validation_` | 확장 코퍼스 검증과 독립 질문 검증 |
| `lab_queries_` | 법률 질의 변환, 500·700자 문서/질문, 상위 50개 후보 |

별칭은 검색 시 사용할 추가 이름이다. 기존 물리 컬렉션 이름은 유지되며 Qdrant UI에도 남는다. 벡터 삭제·복제·재임베딩은 수행하지 않았다. 300자 법률 질의 실험은 확장 검증의 문서 컬렉션을 공유한다. 하이브리드 후속 단계는 아직 실행 전이므로 별도 컬렉션을 만들지 않았다.

저장소 루트에서 실행한다.

```powershell
# 목적별 관리 목록 조회 (기존 qdrant-lab.ps1 -Action List도 같은 목록 제공)
./evaluation/legal-retrieval/scripts/manage-qdrant-lab.ps1 -Action List
# 저장된 관리 목록 및 별칭·포인트·스키마·샘플 벡터 검증
./evaluation/legal-retrieval/scripts/manage-qdrant-lab.ps1 -Action Verify
# 새 실험 컬렉션에 분류 규칙을 추가한 후 별칭 적용; 기존 같은 매핑은 건너뜀
./evaluation/legal-retrieval/scripts/manage-qdrant-lab.ps1 -Action ApplyAliases
```

소유권이 확인되지 않거나 분류 규칙이 없는 컬렉션은 `unmanaged`로 표시하고 변경하지 않는다. 기존 별칭 충돌은 덮어쓰지 않는다. 적용 전후 물리 컬렉션 집합·포인트 수·스키마·메타데이터를 비교하고, 별칭을 통한 샘플 벡터/페이로드 조회도 대조한다. 적용 전 목록은 Git 제외 `artifacts/organization-v1/`에 남긴다. 실제 컬렉션 삭제나 재색인은 이 관리 명령의 기능이 아니다.

[과거 Qdrant 검색 비교와 실행 안내](reports/README.md)는 원래 결과를 보존한다. 관리 절차는 프로젝트 스킬 [qdrant-experiment-management](../../../../../.agents/skills/qdrant-experiment-management/SKILL.md)를 따른다.
