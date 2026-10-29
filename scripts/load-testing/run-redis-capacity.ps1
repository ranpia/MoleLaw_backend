param([string]$ResumeFrom='')
$ErrorActionPreference='Stop'
$repoPath=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repoPath
try {
    $envPath=Join-Path $repoPath 'docker/loadtest/.env'
    $text=[IO.File]::ReadAllText($envPath)
    if($text -notmatch '(?m)^LT_REDIS_BENCH_PASSWORD=([a-f0-9]{64})\r?$') {
        if($text -match '(?m)^LT_REDIS_BENCH_PASSWORD=.+$'){throw 'Benchmark password must be 64 random hex characters.'}
        $bytes=New-Object byte[] 32
        $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
        try {$rng.GetBytes($bytes)} finally {$rng.Dispose()}
        $password=-join ($bytes | ForEach-Object {$_.ToString('x2')})
        if($text -match '(?m)^LT_REDIS_BENCH_PASSWORD=\r?$') {
            $text=[regex]::Replace($text,'(?m)^LT_REDIS_BENCH_PASSWORD=\r?$','LT_REDIS_BENCH_PASSWORD='+$password)
        } else {$text+="`nLT_REDIS_BENCH_PASSWORD=$password`n"}
        [IO.File]::WriteAllText($envPath,$text)
    }
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile redisbench build redis-load
    if($LASTEXITCODE -ne 0){throw 'Redis runner build failed.'}
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile monitoring --profile redisbench up -d redis-bench redis-bench-exporter prometheus cadvisor mysql-exporter redis-exporter grafana gateway
    if($LASTEXITCODE -ne 0){throw 'Redis benchmark services failed.'}
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml restart prometheus
    if($LASTEXITCODE -ne 0){throw 'Prometheus reload failed.'}
    $ready=$false
    for($attempt=0;$attempt -lt 15;$attempt++) {
        try {
            $query=[uri]::EscapeDataString('redis_up{job="molelaw-redis-bench"}')
            $reply=Invoke-RestMethod ("http://localhost:19090/api/v1/query?query=$query")
            if($reply.data.result.Count -eq 1 -and $reply.data.result[0].value[1] -eq '1'){$ready=$true;break}
        } catch {}
        Start-Sleep -Seconds 2
    }
    if(-not $ready){throw 'Benchmark Redis observation is not ready.'}
    $runId='redis-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    "Redis run ID: $runId"
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile redisbench run --rm --no-deps -e "LT_REDIS_RUN_ID=$runId" -e "LT_REDIS_RESUME_FROM=$ResumeFrom" redis-load run
    if($LASTEXITCODE -ne 0){throw 'Redis suite stopped; inspect private artifacts.'}
    "Redis benchmark complete: build/redis-loadtest-runs/$runId"
} finally {Pop-Location}
