$ErrorActionPreference='Stop'
$repoPath=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repoPath
try {
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile ragtools run --rm --no-deps rag-data /data/index.py
    if($LASTEXITCODE -ne 0){throw 'Isolated Qdrant fixture indexing/query verification failed.'}
} finally {Pop-Location}
