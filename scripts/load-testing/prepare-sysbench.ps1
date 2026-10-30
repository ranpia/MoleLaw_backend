$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Push-Location $repoPath
try {
    $envPath=Join-Path $repoPath 'docker/loadtest/.env'
    $text=[IO.File]::ReadAllText($envPath)
    if($text -match '(?m)^LT_SYSBENCH_PASSWORD=([a-f0-9]{64})\r?$') {$password=$Matches[1]}
    else {
        if($text -match '(?m)^LT_SYSBENCH_PASSWORD=.+$') {throw 'Benchmark password must be 64 random hex characters.'}
        $bytes=New-Object byte[] 32
        $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
        try {$rng.GetBytes($bytes)} finally {$rng.Dispose()}
        $password=-join ($bytes | ForEach-Object {$_.ToString('x2')})
        if($text -match '(?m)^LT_SYSBENCH_PASSWORD=\r?$') {$text=[regex]::Replace($text,'(?m)^LT_SYSBENCH_PASSWORD=\r?$','LT_SYSBENCH_PASSWORD='+$password)}
        else {$text+="`nLT_SYSBENCH_PASSWORD=$password`n"}
        [IO.File]::WriteAllText($envPath,$text)
    }
    $container=(docker inspect molelaw-loadtest-mysql-1 | ConvertFrom-Json)[0]
    if($container.Config.Labels.'com.docker.compose.project' -ne 'molelaw-loadtest') {throw 'Not the isolated MySQL container.'}
    $sql=@'
CREATE DATABASE IF NOT EXISTS molelaw_sysbench CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'molelaw_sysbench'@'%' IDENTIFIED BY '__PASSWORD__' WITH MAX_USER_CONNECTIONS 40;
GRANT ALL PRIVILEGES ON molelaw_sysbench.* TO 'molelaw_sysbench'@'%';
'@
    $sql.Replace('__PASSWORD__',$password) | docker exec -i molelaw-loadtest-mysql-1 sh -c 'MYSQL_PWD=$MYSQL_ROOT_PASSWORD mysql -uroot --batch'
    if($LASTEXITCODE -ne 0){throw 'Benchmark schema provisioning failed.'}
    'Isolated sysbench schema/account prepared; no credentials printed.'
} finally {Pop-Location}
