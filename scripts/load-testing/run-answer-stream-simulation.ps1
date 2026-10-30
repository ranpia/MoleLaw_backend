param([int]$SimulationPort=14242,[int]$DirectPort=14241)
$ErrorActionPreference='Stop'
$repoPath=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$processes=@()
$oldSimulationUrl=$env:ANSWER_STREAM_SIMULATION_URL
$oldDirectUrl=$env:ANSWER_STREAM_DIRECT_URL
Push-Location $repoPath
try {
    if($SimulationPort -eq $DirectPort){throw 'Use different fixture ports.'}
    foreach($port in @($SimulationPort,$DirectPort)){
        if($port -lt 1024 -or $port -gt 65535 -or (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)){
            throw "Fixture port unavailable: $port"
        }
    }
    $runPath=Join-Path $repoPath ('build/answer-stream/'+[DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))
    New-Item -ItemType Directory -Path $runPath -Force | Out-Null
    $mockPath=Join-Path $repoPath 'postman/mocks/answer-stream/default.js'
    $simPath=Join-Path $repoPath 'postman/simulations/answer-stream.sim.yaml'
    $oldPort=$env:PORT
    try {
        $env:PORT="$DirectPort"
        $processes+=Start-Process -FilePath (Get-Command node).Source -ArgumentList @(('"'+$mockPath+'"')) `
            -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runPath/direct.log" -RedirectStandardError "$runPath/direct.err"
    } finally {$env:PORT=$oldPort}
    $processes+=Start-Process -FilePath (Get-Command postman).Source `
        -ArgumentList @('simulation','run',('"'+$simPath+'"'),'--port',"$SimulationPort",'--no-history','--output','ndjson') `
        -WindowStyle Hidden -PassThru -RedirectStandardOutput "$runPath/simulation.ndjson" -RedirectStandardError "$runPath/simulation.err"
    foreach($port in @($SimulationPort,$DirectPort)){
        $ready=$false
        $deadline=[DateTime]::UtcNow.AddSeconds(30)
        while([DateTime]::UtcNow -lt $deadline){
            try {Invoke-RestMethod "http://127.0.0.1:$port/openai/control/state" -TimeoutSec 2 | Out-Null; $ready=$true; break}
            catch {Start-Sleep -Milliseconds 250}
        }
        if(-not $ready){throw "Fixture did not become ready: $port"}
    }
    $env:ANSWER_STREAM_SIMULATION_URL="http://127.0.0.1:$SimulationPort"
    $env:ANSWER_STREAM_DIRECT_URL="http://127.0.0.1:$DirectPort"
    .\gradlew.bat answerStreamSimulationTest
    if($LASTEXITCODE -ne 0){throw 'Answer stream simulation failed; inspect Gradle report.'}
    Copy-Item -LiteralPath 'build/test-results/answerStreamSimulationTest/TEST-com.MoleLaw_backend.chat.controller.AnswerStreamSimulationTest.xml' -Destination "$runPath/results.xml"
    "Simulation results: $runPath"
} finally {
    foreach($process in $processes){if(-not $process.HasExited){taskkill /PID $process.Id /T /F | Out-Null}}
    $env:ANSWER_STREAM_SIMULATION_URL=$oldSimulationUrl
    $env:ANSWER_STREAM_DIRECT_URL=$oldDirectUrl
    Pop-Location
}
