$ErrorActionPreference='Stop'
$repoPath=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repoPath
try {
    $runId='db-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    $root=Join-Path $repoPath "build/db-loadtest-runs/$runId"
    New-Item -ItemType Directory -Path $root | Out-Null
    "DB run ID: $runId"
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile dbbench run --rm --no-deps -e "LT_DB_RUN_ID=$runId" sysbench prepare
    if($LASTEXITCODE -ne 0){throw 'Data preparation failed.'}
    $all=@()
    foreach($profile in @('read','mixed','hot')) {
        $threadsToRun=if($profile -eq 'hot') {@(1,8,32)} else {@(1,4,8,16,32)}
        $phase=@()
        foreach($threads in $threadsToRun) {
            "DB phase $profile / threads $threads / warmup 10s / measurement 30s"
            docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile dbbench run --rm --no-deps -e "LT_DB_RUN_ID=$runId" sysbench run $profile $threads 10 30
            if($LASTEXITCODE -ne 0){throw "DB stage failed: $profile / $threads"}
            $s=Get-Content -Raw -Encoding UTF8 (Join-Path $root "$profile-t$threads-s30/summary.json") | ConvertFrom-Json
            if(-not $s.tps -or -not $s.qps){throw 'No valid transaction metrics.'}
            $phase+=$s
            $all+=$s
            $all | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $root 'stages.json')
        }
        if($profile -ne 'hot') {
            $peak=($phase | Measure-Object -Property tps -Maximum).Maximum
            $chosen=($phase | Where-Object {$_.tps -ge .95*$peak} | Sort-Object threads | Select-Object -First 1).threads
            "DB confirmation $profile / threads $chosen / warmup 15s / measurement 120s"
            docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile dbbench run --rm --no-deps -e "LT_DB_RUN_ID=$runId" sysbench run $profile $chosen 15 120
            if($LASTEXITCODE -ne 0){throw "DB confirmation failed: $profile"}
            $all+=Get-Content -Raw -Encoding UTF8 (Join-Path $root "$profile-t$chosen-s120/summary.json") | ConvertFrom-Json
        }
    }
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile dbbench run --rm --no-deps -e "LT_DB_RUN_ID=$runId" sysbench verify
    if($LASTEXITCODE -ne 0){throw 'Final benchmark data verification failed.'}
    @{runId=$runId;completed=$true;results=$all} | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $root 'result.json')
    "DB benchmark complete: $root"
} finally {Pop-Location}
