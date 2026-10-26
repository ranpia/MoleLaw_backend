# 브랜치와 로컬 파일 관리

기준일: 2026-10-23. 서비스 개발은 `dev`, 검증된 배포 반영은 `main`, 임베딩 평가 자료는 `experiment/legal-embedding-evaluation`에서 관리한다. `main` 통합과 배포는 별도 요청 범위다.

## 이번 동기화

- 법령 PR #8은 `dev`에 병합됐다.
- 후속 상담 PR #9는 `feat/law-retrieval-pipeline`에 병합됐으므로 해당 브랜치의 병합 결과를 `dev`에도 통합했다.
- 로컬의 미추적 서비스·문서 파일 85개는 병합된 브랜치와 Git blob 해시가 모두 같았다. 별도 로컬 백업 후 정식 추적 파일로 갱신했다. assume-unchanged·skip-worktree 플래그는 발견되지 않았다.
- 평가용 로컬 산출물 4,524개·약 324MiB는 실험 워크트리로 보존했다. 벡터 캐시·로그·원문 다운로드는 계속 Git에서 제외한다. 공유 가능한 결과 114개 중 100개는 실험 브랜치에 이미 있었고, 추가 14개는 해시 매니페스트와 함께 실험 아카이브로 보존했다. 새 실험을 실행하거나 점수를 재산정한 결과가 아니다.

## 작업 위치

서비스 폴더는 `MoleLaw_backend`의 `dev`를 사용한다. 실험 폴더는 같은 상위 디렉터리의 `MoleLaw_backend-experiments` 워크트리이며 `experiment/legal-embedding-evaluation`이 열려 있다.

```powershell
git worktree list
git status --short
git pull --ff-only origin dev

git -C ../MoleLaw_backend-experiments status --short
git -C ../MoleLaw_backend-experiments pull --ff-only origin experiment/legal-embedding-evaluation
```

`dev`의 `.gitignore`는 `/evaluation/`을 제외한다. 실험 브랜치에서는 재현용 입력·스크립트·선별 보고서를 추적하고 `evaluation/legal-retrieval/artifacts/`의 캐시·로그는 제외한다. 제외 파일을 무조건 `git add -f`로 올리지 않는다. 서비스용 KURE Docker 구성과 서비스 회귀 검증용 Postman 파일은 `dev`에서 추적한다.

## 갱신이 막힐 때

미추적 파일이 incoming 커밋과 같은 경로를 차지하면 먼저 내용을 비교하고 변경본을 백업한다. 같은 파일이라고 추정해 삭제하거나 `git clean -fd`를 실행하지 않는다. 변경 파일은 목적에 맞는 브랜치에 커밋한 뒤 갱신한다.

```powershell
git status --short
git diff --name-status HEAD origin/dev
git ls-files -v | Where-Object { $_ -cmatch '^[a-zS]' }
```

기존 `AGENTS.md`, `.env`, Compose 비밀값, IDE 설정과 로컬 빌드 결과는 이번 정리에 포함하지 않았다.
