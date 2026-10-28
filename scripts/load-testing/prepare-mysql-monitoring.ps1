$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repoPath
try {
    $envPath = Join-Path $repoPath 'docker/loadtest/.env'
    $text = [IO.File]::ReadAllText($envPath)
    if ($text -match '(?m)^LT_MYSQL_EXPORTER_PASSWORD=([a-f0-9]{64})\r?$') {
        $password = $Matches[1]
    } else {
        if ($text -match '(?m)^LT_MYSQL_EXPORTER_PASSWORD=.+$') { throw 'Use a 64-character random hex exporter password.' }
        $bytes = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
        $password = -join ($bytes | ForEach-Object { $_.ToString('x2') })
        if ($text -match '(?m)^LT_MYSQL_EXPORTER_PASSWORD=\r?$') {
            $text = [regex]::Replace($text, '(?m)^LT_MYSQL_EXPORTER_PASSWORD=\r?$', 'LT_MYSQL_EXPORTER_PASSWORD=' + $password)
        } else { $text += "`nLT_MYSQL_EXPORTER_PASSWORD=$password`n" }
        [IO.File]::WriteAllText($envPath, $text)
    }
    $container = (docker inspect molelaw-loadtest-mysql-1 | ConvertFrom-Json)[0]
    if ($container.Config.Labels.'com.docker.compose.project' -ne 'molelaw-loadtest') { throw 'Not the isolated MySQL container.' }
    # Send SQL over stdin, never expose the secret in CLI arguments or output.
    $bootstrap = @'
CREATE USER IF NOT EXISTS 'loadtest_exporter'@'%' IDENTIFIED BY '__PASSWORD__' WITH MAX_USER_CONNECTIONS 3;
GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'loadtest_exporter'@'%';
'@
    $bootstrap.Replace('__PASSWORD__', $password) | docker exec -i molelaw-loadtest-mysql-1 sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot --batch'
    if ($LASTEXITCODE -ne 0) { throw 'Exporter account provisioning failed.' }
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile monitoring up -d --no-deps mysql-exporter
    if ($LASTEXITCODE -ne 0) { throw 'Exporter startup failed.' }
    docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml restart prometheus
    if ($LASTEXITCODE -ne 0) { throw 'Prometheus reload failed.' }
    'Isolated MySQL exporter prepared. Credentials remain hidden.'
} finally { Pop-Location }
