[CmdletBinding()]
param([ValidateSet(300,500,700)][int]$Size=700)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
$repo=Split-Path (Split-Path $EvaluationRoot -Parent) -Parent
Set-Location $repo
$flag=Join-Path $ArtifactsRoot 'control/stop-qwen3-0.6b.flag'
$logRoot=Join-Path $ArtifactsRoot 'logs'
$null=New-Item -ItemType Directory -Force $logRoot
Start-Transcript -Path (Join-Path $logRoot ("qwen$Size-terminal-"+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.log'))
try {
    Write-Host "Qwen3: running char$Size only; reusing existing vectors. Keep this terminal open." -ForegroundColor Cyan
    docker start molelaw-qwen3-lab
    if($LASTEXITCODE -ne 0){throw 'Qwen container start failed'}
    $deadline=[datetime]::UtcNow.AddMinutes(15)
    while($true){
        if(Test-Path -LiteralPath $flag){throw 'Stopped by user request'}
        try{$null=Invoke-RestMethod http://127.0.0.1:11437/api/version -TimeoutSec 3;break}catch{
            if([datetime]::UtcNow -gt $deadline){throw 'Model startup timeout'}
            Start-Sleep -Seconds 5
        }
    }
    & (Join-Path $PSScriptRoot 'run-model.ps1') -Model qwen3-0.6b -Sizes @($Size) -Port 11437
    & (Join-Path $PSScriptRoot 'run-chunk-experiments.ps1') -Model qwen3-0.6b -Sizes @($Size)
    $profilePath=Join-Path $ReviewRoot 'models.json'
    $profiles=Get-Content -Raw -Encoding UTF8 $profilePath | ConvertFrom-Json
    $sizes=@(@($profiles.'qwen3-0.6b'.experimentSizes)+@($Size) | Sort-Object -Unique)
    $profiles.'qwen3-0.6b'.experimentSizes=$sizes
    [IO.File]::WriteAllText($profilePath,($profiles | ConvertTo-Json -Depth 10)+"`n",[Text.UTF8Encoding]::new($false))
    & (Join-Path $PSScriptRoot 'store-qdrant-evaluation.ps1') -Model qwen3-0.6b -Sizes $sizes
    & (Join-Path $PSScriptRoot 'store-qdrant-catalog.ps1')
    & (Join-Path $PSScriptRoot 'compare-results.ps1')
    & (Join-Path $PSScriptRoot 'compare-qdrant-benchmark.ps1') -Size $Size
    & (Join-Path $PSScriptRoot 'compare-chunk-experiments.ps1')
    $rows=@(Import-Csv (Join-Path $ReportsRoot 'chunk-experiments/comparison.csv') | Where-Object { $_.model -eq 'qwen3-0.6b' -and [int]$_.maxChars -eq $Size -and $_.queryMode -eq 'rewritten' })
    if($rows.Count -ne 2){throw 'Incomplete final summaries'}
    Write-Host "Qwen3 char$Size complete. Other sizes were not executed." -ForegroundColor Green
    $rows | Select-Object maxChars,split,recallAt5,mrr | Format-Table
    $readme=Join-Path $repo 'README.md'
    $text=[IO.File]::ReadAllText($readme)
    $text=[regex]::Replace($text,'(?m)^Qwen3-Embedding-0\.6B(?:는|의).*평가.*\r?\n?','')
    $text=[regex]::Replace($text,'(?m)^\| Qwen3-Embedding-0\.6B \|[^\r\n]*',('| Qwen3-Embedding-0.6B | 1,024 | '+($sizes -join '·')+'문자 평가 완료 |'))
    $text+="`r`nQwen3-Embedding-0.6B의 "+($sizes -join '·')+"문자 평가가 완료됐습니다. 결과·그래프는 통합 비교 문서를 참고하세요.`r`n"
    [IO.File]::WriteAllText($readme,$text,[Text.UTF8Encoding]::new($false))
    Write-Host 'Reports and Qdrant updated. Git commit/push remains for review.'
} finally {
    [IO.File]::WriteAllText($flag,"Completed requested char$Size run; further model evaluation requires explicit resume.")
    docker stop molelaw-qwen3-lab
    Stop-Transcript
}
