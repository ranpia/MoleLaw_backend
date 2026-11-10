param([switch]$AuthorizeRetryWithUnresolvedCosts)
$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repoPath
$composeArgs = @('--env-file', 'docker/loadtest/.env', '-f', 'docker/loadtest/compose.yml', '-f', 'docker/loadtest/rag.compose.yml', '--profile', 'ragprep')
$runPath = Join-Path 'build/rag-prep/runs' ('tool-comparison-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))
$liveStarted = $false
$probeStarted = $false
try {
    New-Item -ItemType Directory -Path $runPath | Out-Null
    $ledger = Get-Content 'build/rag-prep/model/live-ledger.json' -Raw | ConvertFrom-Json
    Copy-Item 'build/rag-prep/model/live-ledger.json' (Join-Path $runPath 'ledger-before.json')
    if ($ledger.stopped -or $ledger.pending.PSObject.Properties.Count -gt 0) {
        if (-not $AuthorizeRetryWithUnresolvedCosts) { throw 'Existing paid ledger requires explicit retry authorization; unknown usage must stay reserved.' }
        docker compose @composeArgs run --rm --no-deps --entrypoint python rag-model -c "import fcntl,sys; sys.path.insert(0,'/tools'); from pathlib import Path; from model_gateway import Ledger; lock=Path('/results/live-ledger.lock').open('a'); fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB); ledger=Ledger('/results/live-ledger.json'); ledger.authorize_retry('Explicit operator retry; all unknown usage stays reserved against the original budget')"
        if ($LASTEXITCODE -ne 0) { throw 'Cannot authorize retry while preserving the budget.' }
        $ledger = Get-Content 'build/rag-prep/model/live-ledger.json' -Raw | ConvertFrom-Json
    }
    Copy-Item 'build/rag-prep/probe/preflight.json' (Join-Path $runPath 'preflight.json')
    $cap = [int]$ledger.attempts + 14 # One question call, up to twelve tool rounds, one final answer.
    $override = Join-Path $runPath 'live.compose.yml'
    @"
services:
  rag-model:
    environment:
      RAG_MODEL_MODE: live
      RAG_MODEL_MAX_CALLS: "$cap"
    networks: [default, model_download]
    volumes:
      - ../../build/loadtest-secrets/openai-key:/run/secrets/openai-key:ro
"@ | Set-Content $override -Encoding Ascii
    @{
        commit = (git rev-parse HEAD)
        branch = (git branch --show-current)
        baseline = 'rag-live-20261029T083706Z'
        maxNewCalls = 14
        cumulativeGatewayBudgetUsd = 5
        retainedUnresolvedCosts = $ledger.unresolved
        jarSha256 = (Get-FileHash 'build/libs/MoleLaw_backend-0.0.1-SNAPSHOT.jar' -Algorithm SHA256).Hash
        fixtureSha256 = (Get-FileHash 'build/rag-dataset/legal-perf-v1/app-rag-v2/manifest.json' -Algorithm SHA256).Hash
        dirtyFiles = @(git diff --name-only HEAD)
    } | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $runPath 'identity.json') -Encoding UTF8
    & ./scripts/load-testing/prepare-rag-live-key.ps1
    $liveArgs = $composeArgs + @('-f', $override)
    $liveStarted = $true
    docker compose @liveArgs up -d --no-deps --force-recreate rag-model
    if ($LASTEXITCODE -ne 0) { throw 'Paid gateway startup failed.' }
    $probeStarted = $true
    docker compose @liveArgs run --rm --no-deps rag-probe --live
    if ($LASTEXITCODE -ne 0) { throw 'Paid comparison did not complete; inspect retained evidence. No automatic retry.' }
} finally {
    if ($probeStarted -and (Test-Path 'build/rag-prep/probe/paid-smoke.json')) {
        Copy-Item 'build/rag-prep/probe/paid-smoke.json' (Join-Path $runPath 'probe.json')
    }
    Copy-Item 'build/rag-prep/model/live-ledger.json' (Join-Path $runPath 'ledger.json')
    Copy-Item 'build/rag-prep/model/live-requests.jsonl' (Join-Path $runPath 'requests-all.jsonl')
    docker compose @composeArgs logs --no-color rag-backend | Set-Content (Join-Path $runPath 'backend.log') -Encoding UTF8
    docker compose @composeArgs logs --no-color rag-model | Set-Content (Join-Path $runPath 'gateway.log') -Encoding UTF8
    if ($liveStarted) {
        docker compose @composeArgs up -d --no-deps --force-recreate rag-model
        if ($LASTEXITCODE -ne 0) { Write-Error 'Could not restore synthetic gateway.' }
    }
    Write-Output "Retained comparison: $runPath"
    Pop-Location
}
