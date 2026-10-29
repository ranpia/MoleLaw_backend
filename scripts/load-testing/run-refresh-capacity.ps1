param([string]$ResumeFrom='')
$ErrorActionPreference='Stop'
$repoPath=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repoPath
try {
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile redisbench --profile refreshbench up -d fault-proxy
    if($LASTEXITCODE -ne 0){throw 'Fault proxy failed.'}
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile refreshbench run --rm --no-deps --entrypoint python -e LT_REFRESH_MODE=setup refresh-load /scenario/refresh_suite.py
    if($LASTEXITCODE -ne 0){throw 'Fault routes preparation failed.'}
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile redisbench --profile refreshbench up -d refresh-backend
    if($LASTEXITCODE -ne 0){throw 'Refresh backend startup failed.'}
    $healthy=$false
    for($attempt=0;$attempt -lt 60;$attempt++) {
        $status=docker inspect --format '{{.State.Health.Status}}' molelaw-loadtest-refresh-backend-1
        if($status -eq 'healthy'){$healthy=$true;break}
        Start-Sleep -Seconds 2
    }
    if(-not $healthy){throw 'Refresh backend not healthy.'}
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml restart prometheus
    if($LASTEXITCODE -ne 0){throw 'Prometheus reload failed.'}
    Start-Sleep -Seconds 10
    $runId='refresh-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    "Refresh run ID: $runId"
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile refreshbench run --rm --no-deps --entrypoint python -e "LT_REFRESH_RUN_ID=$runId" -e "LT_REFRESH_RESUME_FROM=$ResumeFrom" refresh-load /scenario/refresh_suite.py
    if($LASTEXITCODE -ne 0){throw 'Refresh suite stopped; inspect private artifacts.'}
    "Refresh suite complete: build/refresh-loadtest-runs/$runId"
} finally {Pop-Location}
