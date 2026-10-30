# Explicit future paid-mode setup only; never called by prepare-full-rag.ps1.
$ErrorActionPreference='Stop'
$repoPath=(Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$providerKey=$env:OPENAI_API_KEY
if(-not $providerKey){
    foreach($line in Get-Content (Join-Path $repoPath '.env') -Encoding UTF8){
        if($line -match '^\s*(?:export\s+)?OPENAI_API_KEY\s*=\s*(.+)$'){$providerKey=$Matches[1].Trim().Trim('"').Trim("'");break}
    }
}
if(-not $providerKey -or $providerKey.Length -lt 20 -or $providerKey -match 'dummy|example'){throw 'A real OPENAI_API_KEY is required for explicit paid mode.'}
$secretDirectory=Join-Path $repoPath 'build/loadtest-secrets'
New-Item -ItemType Directory -Path $secretDirectory -Force | Out-Null
[System.IO.File]::WriteAllText((Join-Path $secretDirectory 'openai-key'),$providerKey,[System.Text.UTF8Encoding]::new($false))
$providerKey=$null
'Prepared Git-excluded gateway key file. No API request was made.'
