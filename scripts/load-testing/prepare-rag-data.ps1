$ErrorActionPreference='Stop'
$repoPath=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$source=Join-Path $repoPath 'docker/loadtest/rag-data'
$output=Join-Path $repoPath 'build/rag-dataset'
if(-not(Test-Path (Join-Path $source 'snapshot/preparation.lock.json'))){throw 'Versioned dataset snapshot/lock is missing.'}
New-Item -ItemType Directory -Force $output | Out-Null
# Offline tokenizer only. The existing isolated public model cache is read-only.
docker run --rm --network none --entrypoint python -e HF_HOME=/models -e HF_HUB_OFFLINE=1 `
    -v molelaw-loadtest_model_cache:/models:ro -v "${source}:/data:ro" -v "${output}:/results" `
    molelaw/kure-service:loadtest-cpu /data/prepare.py prepare
if($LASTEXITCODE -ne 0){throw 'Dataset preparation failed; check the pinned tokenizer cache/image.'}
docker run --rm --network none --entrypoint python -v "${source}:/data:ro" -v "${output}:/results:ro" `
    molelaw/kure-service:loadtest-cpu /data/validate.py
if($LASTEXITCODE -ne 0){throw 'Dataset integrity validation failed.'}
'Dataset ready at build/rag-dataset/legal-perf-v1; no inference or database writes performed.'
