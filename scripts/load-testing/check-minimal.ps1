# Small read-only smoke check. No login, test users, AI calls or load generation.
$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$settings = @{}
foreach ($settingLine in [IO.File]::ReadAllLines((Join-Path $repoPath 'docker/loadtest/.env'))) {
    if ($settingLine -match '^([A-Z0-9_]+)=(.*)$') { $settings[$Matches[1]] = $Matches[2].Trim() }
}
$appUrl = 'http://localhost:' + $settings.LT_BACKEND_PORT
$promUrl = 'http://localhost:' + $settings.LT_PROMETHEUS_PORT
$grafanaUrl = 'http://localhost:' + $settings.LT_GRAFANA_PORT

function Assert-HttpStatus($Uri, $Expected, $Method = 'GET') {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $Uri -Method $Method -TimeoutSec 10
        $actual = [int]$response.StatusCode
    } catch {
        if (-not $_.Exception.Response) { throw "Service unreachable: $Uri" }
        $actual = [int]$_.Exception.Response.StatusCode
    }
    if ($actual -ne $Expected) { throw "Unexpected HTTP status at ${Uri}: $actual (expected $Expected)" }
    "OK: $Method $Uri -> $actual"
}

Assert-HttpStatus "$appUrl/api/auth/csrf" 200
Assert-HttpStatus "$appUrl/api/chat-rooms" 401
Assert-HttpStatus "$appUrl/actuator/prometheus" 401
Assert-HttpStatus "$appUrl/v3/api-docs" 401
Assert-HttpStatus "$appUrl/api/auth/login" 403 'POST'
Assert-HttpStatus "$grafanaUrl/api/health" 200
Assert-HttpStatus "$promUrl/-/ready" 200

$target = Invoke-RestMethod -Uri ($promUrl + '/api/v1/query?query=' + [uri]::EscapeDataString('up{job="molelaw-backend"}')) -TimeoutSec 10
if ($target.status -ne 'success' -or $target.data.result.Count -ne 1 -or $target.data.result[0].value[1] -ne '1') {
    throw 'Prometheus backend target is not UP yet.'
}
'OK: Prometheus backend scrape UP'

# Keep credentials inside this process; never print headers, cookies, tokens or env values.
$encodedAuth = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('loadtest:' + $settings.LT_GRAFANA_PASSWORD))
$authHeaders = @{ Authorization = 'Basic ' + $encodedAuth }
$dashboard = Invoke-RestMethod -Uri "$grafanaUrl/api/dashboards/uid/molelaw-loadtest-backend" -Headers $authHeaders -TimeoutSec 10
if ($dashboard.dashboard.panels.Count -ne 8) { throw 'Provisioned backend dashboard is incomplete.' }
$source = Invoke-RestMethod -Uri "$grafanaUrl/api/datasources/uid/loadtest-prometheus/health" -Headers $authHeaders -TimeoutSec 10
if ($source.status -ne 'OK') { throw 'Grafana cannot query its Prometheus data source.' }
'OK: Grafana provisioned dashboard and Prometheus data source'
'Smoke check complete. This does not establish API load capacity or RAG readiness.'
