# Intentionally fixed at 1 user / 60 seconds. Do not increase without a new test scope.
$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repoPath
try {
    & (Join-Path $PSScriptRoot 'check-minimal.ps1')
    $runId = 'one-user-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ')
    $outputPath = Join-Path $repoPath "build/loadtest-runs/$runId"
    New-Item -ItemType Directory -Path $outputPath | Out-Null
    "Run ID: $runId"
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile tools run --rm --no-deps -e "LT_RUN_ID=$runId" locust -f /scenario/one_user.py --headless --host http://gateway:8080 --users 1 --spawn-rate 1 --run-time 1m --stop-timeout 15 --only-summary --exit-code-on-error 1 --csv "/results/$runId/requests" --html "/results/$runId/report.html"
    if ($LASTEXITCODE -ne 0) { throw "Locust exercise failed. Inspect local reports: $outputPath" }
    "Reports saved: $outputPath"
} finally {
    Pop-Location
}
