$ErrorActionPreference='Stop'
$repoPath=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repoPath
try {
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile ragtools run --rm --no-deps rerank-data
    if($LASTEXITCODE -ne 0){throw 'Frozen input verification failed.'}
    $runId='mixed-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    "Mixed run: $runId"
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile ragtools run --rm --no-deps `
        -e "LT_MIXED_RUN_ID=$runId" rag-data /data/mixed_capacity.py
    if($LASTEXITCODE -ne 0){throw 'Mixed probe stopped; inspect stage artifacts.'}
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile ragtools run --rm --no-deps `
        -e "LT_MIXED_RUN_ID=$runId" rag-data /data/validate_mixed_run.py
    if($LASTEXITCODE -ne 0){throw 'Mixed result validation failed.'}
    "Results: build/rag-dataset/legal-perf-v1/mixed-runs/$runId"
} finally {Pop-Location}
