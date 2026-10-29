$ErrorActionPreference='Stop'
$repoPath=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repoPath
try {
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile ragtools run --rm --no-deps rag-data /data/validate.py
    if($LASTEXITCODE -ne 0){throw 'Dataset integrity check failed.'}
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile ragtools run --rm --no-deps rag-data /data/generate.py
    if($LASTEXITCODE -ne 0){throw 'Embedding generation stopped; validated batches are preserved for resume.'}
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile ragtools run --rm --no-deps rag-data /data/validate_vectors.py
    if($LASTEXITCODE -ne 0){throw 'Stored vector validation failed.'}
} finally {Pop-Location}
