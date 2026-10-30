$ErrorActionPreference='Stop'
$repoPath=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repoPath
$composeArgs=@('--env-file','docker/loadtest/.env','-f','docker/loadtest/compose.yml','-f','docker/loadtest/rag.compose.yml','--profile','ragprep','--profile','ragtools')
try {
    docker compose @composeArgs config --quiet
    if($LASTEXITCODE -ne 0){throw 'Compose configuration invalid.'}
    if(-not (Test-Path build/loadtest-data/manifest.json)){throw 'Prepare existing loadtest account fixtures first.'}
    $runningModel=docker compose @composeArgs ps -q --status running rag-model
    if($runningModel){
        $mode=docker compose @composeArgs exec -T rag-model python -c "import json,urllib.request; print(json.load(urllib.request.urlopen('http://127.0.0.1:8080/state'))['mode'])"
        if($LASTEXITCODE -ne 0 -or $mode.Trim() -ne 'synthetic'){throw 'An existing non-synthetic gateway must finish before preparation can replace it.'}
    }
    docker compose @composeArgs up -d --no-recreate mysql redis qdrant kure
    if($LASTEXITCODE -ne 0){throw 'Dependencies failed to start.'}
    docker compose @composeArgs run --rm --no-deps rag-data /data/prepare_app_index.py
    if($LASTEXITCODE -ne 0){throw 'Application index preparation failed.'}
    Get-Content build/rag-dataset/legal-perf-v1/app-rag-v2/seed.sql -Raw -Encoding UTF8 | docker compose @composeArgs exec -T mysql sh -c 'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql --user=molelaw_loadtest --database=molelaw_loadtest'
    if($LASTEXITCODE -ne 0){throw 'Isolated source/index seed failed.'}
    $fixture=Get-Content build/rag-dataset/legal-perf-v1/app-rag-v2/manifest.json -Raw -Encoding UTF8 | ConvertFrom-Json
    if($fixture.fingerprint -notmatch '^[0-9a-f]{64}$'){throw 'Invalid index fingerprint.'}
    $sql="SELECT COUNT(*),COALESCE(SUM(i.chunk_count),0) FROM legal_source_index i JOIN legal_source s ON s.id=i.source_id WHERE i.config_fingerprint='$($fixture.fingerprint)' AND i.status='COMPLETE' AND SHA2(s.raw_json,256)=s.content_hash AND s.law_id IS NOT NULL;"
    $verified=$sql | docker compose @composeArgs exec -T mysql sh -c 'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql --batch --skip-column-names --user=molelaw_loadtest --database=molelaw_loadtest'
    if($LASTEXITCODE -ne 0 -or $verified.Trim() -ne "4`t29"){throw 'Source/index row, hash or chunk count mismatch.'}
    .\gradlew.bat bootJar ragPreparationContractTest
    if($LASTEXITCODE -ne 0){throw 'Application fixture contract/build failed.'}
    docker compose @composeArgs run --rm --no-deps --entrypoint python rag-model -m unittest discover -s /tools -p test_gateway.py
    if($LASTEXITCODE -ne 0){throw 'Gateway guard tests failed.'}
    docker compose @composeArgs build rag-backend
    if($LASTEXITCODE -ne 0){throw 'Isolated backend build failed.'}
    docker compose @composeArgs up -d --no-deps --force-recreate --wait --wait-timeout 180 rag-model rag-backend
    if($LASTEXITCODE -ne 0){throw 'RAG preparation services failed readiness.'}
    '[{"targets":["rag-backend:9091"],"labels":{"component":"backend","__metrics_path__":"/actuator/prometheus"}},{"targets":["rag-model:9093"],"labels":{"component":"ai-gateway"}}]' | Set-Content build/loadtest-secrets/rag-targets.json -Encoding Ascii
    $promId=docker compose @composeArgs ps -q --status running prometheus
    if($promId){docker kill --signal HUP $promId | Out-Null}
    docker compose @composeArgs run --rm --no-deps rag-probe
    if($LASTEXITCODE -ne 0){throw 'Synthetic full RAG preflight failed.'}
    'Ready: isolated RAG backend on 127.0.0.1:18083; synthetic AI, no paid calls. Results: build/rag-prep/probe/preflight.json'
} finally {Pop-Location}
