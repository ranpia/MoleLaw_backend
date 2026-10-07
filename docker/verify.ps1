param()

$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
$probeCreated = $false
try {
    $configJson = docker compose --env-file .env.compose config --format json
    if ($LASTEXITCODE -ne 0) { throw 'Compose configuration failed.' }
    $config = ($configJson -join "`n") | ConvertFrom-Json
    $headers = @{ 'api-key' = $config.services.qdrant.environment.QDRANT__SERVICE__API_KEY }
    $httpPort = $config.services.qdrant.ports | Where-Object { $_.target -eq 6333 }
    $dbHost = $httpPort.host_ip
    if ($dbHost -eq '0.0.0.0') { $dbHost = '127.0.0.1' }
    $baseUri = "http://${dbHost}:$($httpPort.published)"

    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        $mysqlId = docker compose --env-file .env.compose ps -q mysql
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect MySQL container.' }
        $mysqlHealthy = $false
        $redisId = docker compose --env-file .env.compose ps -q redis
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect Redis container.' }
        $redisHealthy = $false
        if ($redisId) {
            $redisHealth = docker inspect --format '{{.State.Health.Status}}' $redisId
            $redisHealthy = $LASTEXITCODE -eq 0 -and $redisHealth -eq 'healthy'
        }
        if ($mysqlId) {
            $health = docker inspect --format '{{.State.Health.Status}}' $mysqlId
            $mysqlHealthy = $LASTEXITCODE -eq 0 -and $health -eq 'healthy'
        }
        try {
            $null = Invoke-RestMethod "$baseUri/collections" -Headers $headers -TimeoutSec 2
            if ($mysqlHealthy -and $redisHealthy) { $ready = $true; break }
        } catch { }
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw 'DB readiness check exceeded its bounded attempts.' }

    $mysqlCheck = 'MYSQL_PWD="$MYSQL_PASSWORD" mysql --protocol=TCP -h 127.0.0.1 -u "$MYSQL_USER" -D "$MYSQL_DATABASE" -N -e "SELECT 1; SELECT VERSION();"'
    $mysqlResult = $mysqlCheck | docker compose --env-file .env.compose exec -T mysql sh
    if ($LASTEXITCODE -ne 0 -or $mysqlResult[0] -ne '1') { throw 'MySQL query failed.' }
    Write-Output "MySQL authenticated SELECT 1 passed; version $($mysqlResult[1])"

    $redisUnauthenticated = docker compose --env-file .env.compose exec -T redis redis-cli ping
    if (($redisUnauthenticated -join ' ') -notmatch 'NOAUTH') { throw 'Redis did not reject missing password.' }
    $redisProbe = @'
set -eu
export REDISCLI_AUTH="$REDIS_PASSWORD"
probe="molelaw_probe_$(cat /proc/sys/kernel/random/uuid)"
trap 'redis-cli DEL "$probe" >/dev/null' EXIT
test "$(redis-cli PING)" = PONG
test "$(redis-cli SET "$probe" probe EX 30 NX)" = OK
test "$(redis-cli GET "$probe")" = probe
ttl=$(redis-cli TTL "$probe")
test "$ttl" -gt 0
test "$ttl" -le 30
redis-cli DEL "$probe" >/dev/null
test "$(redis-cli EXISTS "$probe")" = 0
'@
    $redisProbeEncoded = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes($redisProbe.Replace("`r`n", "`n")))
    docker compose --env-file .env.compose exec -T redis sh -c "echo $redisProbeEncoded | base64 -d | sh"
    if ($LASTEXITCODE -ne 0) { throw 'Redis authentication or TTL probe failed.' }
    Write-Output 'Redis missing-password rejection, authenticated write, TTL and delete passed.'

    $unauthenticatedStatus = 0
    try {
        $null = Invoke-RestMethod "$baseUri/collections" -TimeoutSec 5
    } catch {
        if ($_.Exception.Response) { $unauthenticatedStatus = [int]$_.Exception.Response.StatusCode }
    }
    if ($unauthenticatedStatus -notin @(401, 403)) { throw 'Qdrant did not reject missing API key.' }

    $probeName = 'molelaw_probe_' + [Guid]::NewGuid().ToString('N')
    $collectionUri = "$baseUri/collections/$probeName"
    $null = Invoke-RestMethod $collectionUri -Method Put -Headers $headers -ContentType 'application/json' -Body '{"vectors":{"size":3,"distance":"Cosine"}}' -TimeoutSec 10
    $probeCreated = $true
    $null = Invoke-RestMethod "$collectionUri/points?wait=true" -Method Put -Headers $headers -ContentType 'application/json' -Body '{"points":[{"id":1,"vector":[1,0,0],"payload":{"probe":true}}]}' -TimeoutSec 10
    $queryResult = Invoke-RestMethod "$collectionUri/points/query" -Method Post -Headers $headers -ContentType 'application/json' -Body '{"query":[1,0,0],"limit":1,"with_payload":true}' -TimeoutSec 10
    if ($queryResult.result.points[0].id -ne 1 -or $queryResult.result.points[0].score -lt 0.99) { throw 'Qdrant vector query failed.' }
    Write-Output 'Qdrant API key rejection, authenticated write and vector query passed.'
    docker compose --env-file .env.compose ps
    if ($LASTEXITCODE -ne 0) { throw 'Compose status check failed.' }
} finally {
    if ($probeCreated) {
        $null = Invoke-RestMethod $collectionUri -Method Delete -Headers $headers -TimeoutSec 10
        Write-Output 'Temporary probe collection removed.'
    }
    Pop-Location
}
