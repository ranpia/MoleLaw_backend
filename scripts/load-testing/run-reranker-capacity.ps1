$ErrorActionPreference='Stop'
$repoPath=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repoPath
try {
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile ragtools run --rm --no-deps rerank-data
    if($LASTEXITCODE -ne 0){throw 'Reranker candidate preparation failed.'}
    $runId='rerank-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    "Reranker standalone run: $runId"
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile ragtools run --rm --no-deps `
        -e "LT_RERANK_RUN_ID=$runId" rag-data /data/rerank_capacity.py
    if($LASTEXITCODE -ne 0){throw 'Reranker probe stopped; inspect stage artifacts.'}
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile ragtools run --rm --no-deps `
        -e "LT_RERANK_RUN_ID=$runId" rag-data /data/validate_rerank_run.py
    if($LASTEXITCODE -ne 0){throw 'Reranker artifact or monitoring validation failed.'}
    "Results: build/rag-dataset/legal-perf-v1/rerank-runs/$runId"
} finally {Pop-Location}
