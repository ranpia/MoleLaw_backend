$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/RankingMetrics.ps1')
function Assert-Near($Actual,$Expected) { if ([math]::Abs($Actual-$Expected) -gt 1e-10) { throw "Expected $Expected, got $Actual" } }
$hits = @(1..10 | ForEach-Object { [pscustomobject]@{rank=$_;unitId="u$_"} })
$perfect = Get-RankingMetrics $hits @('u1','u2')
Assert-Near $perfect.ndcgAt5 1
Assert-Near $perfect.precisionAt5 0.4
Assert-Near $perfect.completeAt5 1
$partial = Get-RankingMetrics $hits @('u2','u8')
Assert-Near $partial.recallAt5 0.5
Assert-Near $partial.hitAt5 1
Assert-Near $partial.completeAt5 0
Assert-Near $partial.ndcgAt5 ((1/[math]::Log(3,2))/(1+1/[math]::Log(3,2)))
Assert-Near $partial.completeAt10 1
$miss = Get-RankingMetrics $hits @('absent')
Assert-Near $miss.ndcgAt10 0
Assert-Near $miss.hitAt5 0
# IDCG must include gold units outside the saved top ten.
$many = Get-RankingMetrics $hits @(1..12 | ForEach-Object { "u$_" })
Assert-Near $many.ndcgAt10 1
Assert-Near $many.recallAt10 (10.0/12)
Assert-Near $many.completeAt10 0
Write-Host 'PASS: perfect, partial, missing, and gold beyond top ten ranking metrics.'
