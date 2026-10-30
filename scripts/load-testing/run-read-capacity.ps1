param([ValidateSet(2,4,8,16,32,64,128,256,512,1024,2048,4096)][int]$StartUsers=2,[switch]$Preauthenticate)
$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repoPath
try {
    & (Join-Path $PSScriptRoot 'check-minimal.ps1')
    $seriesId = 'capacity-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    $seriesPath = Join-Path $repoPath "build/loadtest-runs/$seriesId"
    New-Item -ItemType Directory -Path $seriesPath | Out-Null
    $stages = @()
    $lastGood = if ($StartUsers -gt 2) { $StartUsers / 2 } else { $null }
    $flat = 0
    $previousRps = 0
    $stopReason = 'ceiling_reached_not_capacity_limit'
    foreach ($userCount in @(2,4,8,16,32,64,128,256,512,1024,2048,4096)) {
        if ($userCount -lt $StartUsers) { continue }
        $warmup = [math]::Max(15, [math]::Ceiling($userCount / 20) + 10)
        $measure = 45
        $duration = $warmup + $measure
        $runId = "$seriesId/explore-$userCount"
        New-Item -ItemType Directory -Path (Join-Path $repoPath "build/loadtest-runs/$runId") | Out-Null
        "Starting exploratory stage: $userCount users, warmup $warmup sec, measurement $measure sec."
        $authMode = if ($Preauthenticate) { 'pool' } else { 'per-user' }
        docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile tools run --rm --no-deps -e "LT_RUN_ID=$runId" -e "LT_PREAUTH_MODE=$authMode" -e "LT_STAGE_USERS=$userCount" -e "LT_WARMUP_SECONDS=$warmup" -e "LT_MEASURE_SECONDS=$measure" locust -f /scenario/stress_read.py --headless --host http://gateway:8080 --users $userCount --spawn-rate 20 --run-time "${duration}s" --stop-timeout 10 --only-summary --exit-code-on-error 1 --csv "/results/$runId/requests" --html "/results/$runId/report.html"
        $exitStatus = $LASTEXITCODE
        $summaryPath = Join-Path $repoPath "build/loadtest-runs/$runId/summary.json"
        if (-not (Test-Path $summaryPath)) { throw "Stage produced no summary: $runId" }
        $summary = Get-Content -Raw -Encoding UTF8 $summaryPath | ConvertFrom-Json
        $stages += @{ runId=$runId; result=$summary }
        $stages | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 (Join-Path $seriesPath 'stages.json')
        if ($exitStatus -ne 0 -or -not $summary.passed) { $stopReason=$summary.abortReason; break }
        if ($summary.slow) { $stopReason='p95_over_1000ms_provisional_guard'; break }
        if ($previousRps -gt 0 -and $summary.readRps -lt $previousRps * 1.2) { $flat++ } else { $flat=0 }
        $lastGood = $userCount
        $previousRps = $summary.readRps
        if ($flat -ge 2) { $stopReason='throughput_plateau'; break }
    }
    if ($lastGood) {
        $warmup=[math]::Max(30,[math]::Ceiling($lastGood/20)+10)
        $measure=[math]::Min(180,280-$warmup)
        $duration=$warmup+$measure
        $runId="$seriesId/confirm-$lastGood"
        New-Item -ItemType Directory -Path (Join-Path $repoPath "build/loadtest-runs/$runId") | Out-Null
        "Confirming $lastGood users for $measure measured seconds after $warmup seconds warmup."
        docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile tools run --rm --no-deps -e "LT_RUN_ID=$runId" -e "LT_PREAUTH_MODE=$authMode" -e "LT_STAGE_USERS=$lastGood" -e "LT_WARMUP_SECONDS=$warmup" -e "LT_MEASURE_SECONDS=$measure" locust -f /scenario/stress_read.py --headless --host http://gateway:8080 --users $lastGood --spawn-rate 20 --run-time "${duration}s" --stop-timeout 10 --only-summary --exit-code-on-error 1 --csv "/results/$runId/requests" --html "/results/$runId/report.html"
        $confirmationExit=$LASTEXITCODE
        $confirmation=Get-Content -Raw -Encoding UTF8 (Join-Path $repoPath "build/loadtest-runs/$runId/summary.json") | ConvertFrom-Json
    }
    $confirmedStable = $confirmation -and $confirmationExit -eq 0 -and $confirmation.passed -and -not $confirmation.slow
    @{ series=$seriesId; stopReason=$stopReason; lastExploratoryGood=$lastGood; confirmedStableUsers=$(if($confirmedStable){$lastGood}else{$null}); confirmationExit=$confirmationExit; confirmation=$confirmation } | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 (Join-Path $seriesPath 'result.json')
    "Capacity exploration complete: $seriesPath (stop: $stopReason)"
} finally { Pop-Location }
