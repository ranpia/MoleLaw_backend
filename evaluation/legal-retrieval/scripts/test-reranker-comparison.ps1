$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/RankingMetrics.ps1')
. (Join-Path $PSScriptRoot 'lib/RerankerComparison.ps1')
function Assert-True($Value,[string]$Message){if(!$Value){throw $Message}}
function Assert-Rejected([scriptblock]$Action){$failed=$false;try{& $Action}catch{$failed=$true};if(!$failed){throw 'Expected contract rejection.'}}
$c=@([pscustomobject]@{rank=1;unitId='wrong';similarity=0.9},[pscustomobject]@{rank=2;unitId='gold';similarity=0.7})
$response=@{model='m';revision='r';results=@(@{id='gold';score=5.0},@{id='wrong';score=-2.0})}
$ordered=@(Get-RerankerOrder $c $response 'm' 'r')
Assert-True ($ordered[0].unitId -eq 'gold' -and $ordered[0].similarity -eq 0.7) 'Ordering must preserve dense scores.'
$before=Get-RerankerMetrics $c @('gold');$after=Get-RerankerMetrics $ordered @('gold')
Assert-True ($before.recallAt50 -eq $after.recallAt50 -and $after.mrrAt10 -eq 1 -and $before.mrrAt10 -eq 0.5) 'Metrics/permutation mismatch.'
$tie=@{model='m';revision='r';results=@(@{id='gold';score=1.0},@{id='wrong';score=1.0})}
Assert-True (@(Get-RerankerOrder $c $tie 'm' 'r')[0].unitId -eq 'wrong') 'Ties must retain dense order.'
Assert-Rejected {Get-RerankerOrder $c $response 'other' 'r'}
Assert-Rejected {Get-RerankerOrder $c $response 'm' 'other'}
Assert-Rejected {Get-RerankerOrder $c @{model='m';revision='r';results=@(@{id='gold';score=1})} 'm' 'r'}
Assert-Rejected {Get-RerankerOrder $c @{model='m';revision='r';results=@(@{id='gold';score=1},@{id='gold';score=2})} 'm' 'r'}
Assert-Rejected {Get-RerankerOrder $c @{model='m';revision='r';results=@(@{id='unknown';score=1},@{id='wrong';score=2})} 'm' 'r'}
Assert-Rejected {Get-RerankerOrder $c @{model='m';revision='r';results=@(@{id='gold';score=[double]::NaN},@{id='wrong';score=2})} 'm' 'r'}
Assert-Rejected {Get-RerankerOrder $c @{model='m';revision='r';results=@(@{id='gold';score='1'},@{id='wrong';score=2})} 'm' 'r'}
Assert-Rejected {Get-RerankerOrder $c @{model='m';revision='r';results=@(@{id='gold';score=$true},@{id='wrong';score=2})} 'm' 'r'}
Assert-Rejected {Get-RerankerOrder $c @{model='m';revision='r';results=@(@{id='gold';score=[double]::PositiveInfinity},@{id='wrong';score=2})} 'm' 'r'}
Write-Host 'PASS: reranker identity, exact candidate membership, finite scores, stable ties, cosine preservation and paired metrics.'
