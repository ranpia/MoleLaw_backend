param([Alias('Profile')][ValidateSet('all','questions','document512')][string]$Workload='all', [int]$Concurrency=0)
$ErrorActionPreference='Stop'
$repoPath=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repoPath
try {
    $runId='embed-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    if($Workload -ne 'all' -and $Concurrency -notin @(1,2,3,4,5,8)){throw 'Choose a bounded supported concurrency.'}
    if($Workload -eq 'all' -and $Concurrency -ne 0){throw 'Choose a specific workload for targeted concurrency.'}
    $selectedWorkload=if($Workload -eq 'all'){''}else{$Workload}
    "Embedding concurrency run: $runId"
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile ragtools run --rm --no-deps `
        -e "LT_EMBED_RUN_ID=$runId" -e "LT_EMBED_PROFILE=$selectedWorkload" -e "LT_EMBED_CONCURRENCY=$Concurrency" rag-data /data/capacity.py
    if($LASTEXITCODE -ne 0){throw 'Embedding capacity probe stopped; inspect stage logs.'}
    "Results: build/rag-dataset/legal-perf-v1/concurrency-runs/$runId"
} finally {Pop-Location}
