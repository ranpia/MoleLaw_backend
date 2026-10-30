$ErrorActionPreference='Stop'
$repoPath=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$values=@{}
foreach($line in [IO.File]::ReadAllLines((Join-Path $repoPath 'docker/loadtest/.env'))){
    if($line -match '^([A-Z0-9_]+)=(.*)$'){$values[$Matches[1]]=$Matches[2]}
}
$key=$values.LT_QDRANT_API_KEY
if($key -notmatch '^[a-f0-9]{64}$'){throw 'Expected private loadtest Qdrant key.'}
$secretPath=Join-Path $repoPath 'build/loadtest-secrets'
New-Item -ItemType Directory -Force $secretPath | Out-Null
[IO.File]::WriteAllText((Join-Path $secretPath 'qdrant-api-key'),$key,[Text.UTF8Encoding]::new($false))
'Qdrant scrape credential prepared in Git-ignored file; value not printed.'
