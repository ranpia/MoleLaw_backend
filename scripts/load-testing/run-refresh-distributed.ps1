$ErrorActionPreference='Stop'
$repoPath=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$containerIds=@()
Push-Location $repoPath
try {
    $runId='refresh-'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    $root=Join-Path $repoPath "build/refresh-loadtest-runs/$runId"
    New-Item -ItemType Directory -Path $root | Out-Null
    "Four-process refresh confirmation: $runId / 25 users each / 120 seconds"
    foreach($worker in 0..3) {
        $containerId=docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile refreshbench run -d --no-deps --entrypoint python -e LT_REFRESH_MODE=worker -e "LT_REFRESH_RUN_ID=$runId" -e "LT_REFRESH_WORKER_ID=$worker" refresh-load /scenario/refresh_suite.py
        if($LASTEXITCODE -ne 0 -or $containerId -notmatch '^[a-f0-9]{64}$'){throw 'Worker startup failed.'}
        $containerIds+=$containerId
    }
    $ready=$false
    for($attempt=0;$attempt -lt 150;$attempt++) {
        if(@(0..3 | Where-Object {Test-Path "$root/worker-$_/ready.json"}).Count -eq 4){$ready=$true;break}
        foreach($containerId in $containerIds) {
            if((docker inspect --format '{{.State.Running}}' $containerId) -ne 'true'){throw 'Worker exited before barrier.'}
        }
        Start-Sleep -Seconds 1
    }
    if(-not $ready){throw 'Workers did not reach warmup barrier.'}
    $startEpoch=[DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()/1000.0+2
    [IO.File]::WriteAllText("$root/start.json",(@{startEpoch=$startEpoch}|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    'Four workers ready; synchronized measurement started.'
    for($attempt=0;$attempt -lt 180;$attempt++) {
        $running=@($containerIds | ForEach-Object {docker inspect --format '{{.State.Running}}' $_})
        if($running -notcontains 'true'){break}
        Start-Sleep -Seconds 1
    }
    $loads=@()
    foreach($worker in 0..3) {
        $containerId=$containerIds[$worker]
        $ErrorActionPreference='Continue'
        docker logs $containerId 2>&1 | Out-File -Encoding UTF8 "$root/worker-$worker/output.txt"
        $ErrorActionPreference='Stop'
        $exitCode=docker inspect --format '{{.State.ExitCode}}' $containerId
        if($exitCode -ne '0'){throw 'Worker failed; inspect private artifacts.'}
        $r=Get-Content -Raw -Encoding UTF8 "$root/worker-$worker/result.json" | ConvertFrom-Json
        if(-not $r.completed -or $r.load.failures -ne 0){throw 'Worker results invalid.'}
        $loads+=$r.load
    }
    $hist=@{}
    foreach($load in $loads){foreach($property in $load.responseTimeHistogramMs.psobject.Properties){
        $bucket=[int]$property.Name
        if(-not $hist.ContainsKey($bucket)){$hist[$bucket]=0}
        $hist[$bucket]+=[long]$property.Value
    }}
    $count=($loads | Measure-Object completedIncludingDrain -Sum).Sum
    function Percentile($fraction) {
        $seen=0
        foreach($bucket in ($hist.Keys | Sort-Object)){$seen+=$hist[$bucket];if($seen -ge [math]::Ceiling($count*$fraction)){return $bucket}}
    }
    $duration=($loads | Measure-Object measurementSeconds -Maximum).Maximum
    $result=@{completed=$true;users=100;workers=4;measurementSeconds=$duration;completedIncludingDrain=$count;
        rpsIncludingDrainApprox=$count/$duration;p95Ms=(Percentile .95);p99Ms=(Percentile .99);failures=0;
        generatorCpuWarning=($loads.generatorCpuWarning -contains $true);workerResults=$loads}
    [IO.File]::WriteAllText("$root/result.json",($result|ConvertTo-Json -Depth 20),[Text.UTF8Encoding]::new($false))
    [pscustomobject]$result | Select-Object users,workers,rpsIncludingDrainApprox,p95Ms,p99Ms,failures,generatorCpuWarning | Format-List
} finally {
    foreach($containerId in $containerIds){
        docker stop --timeout 2 $containerId | Out-Null
        docker rm $containerId | Out-Null
    }
    Pop-Location
}
