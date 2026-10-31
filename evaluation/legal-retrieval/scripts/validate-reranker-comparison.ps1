[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ReportDirectory,[string]$DatasetDirectory,[switch]$AllowPartial)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
. (Join-Path $PSScriptRoot 'lib/RankingMetrics.ps1')
. (Join-Path $PSScriptRoot 'lib/RerankerComparison.ps1')
if(!$DatasetDirectory){$DatasetDirectory=Get-DataPath 'reranker-v1/c500-legal_transformed'}
function Assert-Near($A,$B,[string]$Name){if([math]::Abs([double]$A-[double]$B) -gt 1e-10){throw "Metric mismatch: $Name"}}
$run=Read-RerankerJson (Join-Path $ReportDirectory 'run.json')
$data=Read-RerankerJson (Join-Path $DatasetDirectory 'manifest.json')
if(!$AllowPartial -and (!$run.completed -or $run.completedQueries -ne 36)){throw 'Not a completed 36-question comparison.'}
if((Get-FileHash (Join-Path $DatasetDirectory 'candidates.jsonl')).Hash -ne $run.candidatesSha256 -or
    $run.candidatesSha256 -ne $data.candidatesSha256){throw 'Candidate input hash mismatch.'}
if((Get-FileHash (Get-DataPath 'independent-validation-v1/questions.json')).Hash -ne $data.goldQuestionsSha256 -or
    (Get-FileHash (Get-ReportPath 'recall50-v1/kure-v1/top50.csv')).Hash -ne $data.baselineTop50Sha256 -or
    (Get-FileHash (Get-ReportPath 'legal-query-chunks-v1/kure-v1/queries.csv')).Hash -ne $data.baselineQuerySha256 -or
    (Get-FileHash (Get-ReportPath 'recall50-v1/kure-v1/manifest.json')).Hash -ne $data.baselineManifestSha256){throw 'Frozen original questions/results changed.'}
$frozen=@{};foreach($line in [IO.File]::ReadLines((Join-Path $DatasetDirectory 'candidates.jsonl'))){$q=$line | ConvertFrom-Json;$frozen[$q.queryId]=$q}
$queries=@(Import-Csv (Join-Path $ReportDirectory 'queries.csv'))
$rankings=@(Import-Csv (Join-Path $ReportDirectory 'rankings.csv'))
$summary=@(Import-Csv (Join-Path $ReportDirectory 'summary.csv'))
if($queries.Count -ne $run.completedQueries -or @($queries.queryId | Select-Object -Unique).Count -ne $queries.Count -or
    $rankings.Count -ne 100*$queries.Count -or $summary.Count -ne 2){throw 'Incomplete or duplicate report rows.'}
$allBefore=@();$allAfter=@()
foreach($row in $queries) {
    $q=$frozen[$row.queryId]
    if(!$q -or $row.retrievalText -cne $q.retrievalText -or $row.originalText -cne $q.originalText){throw 'Question text mismatch.'}
    $metrics=@{}
    foreach($condition in @('baseline','reranked')) {
        $hits=@($rankings | Where-Object {$_.queryId -eq $row.queryId -and $_.condition -eq $condition} | Sort-Object {[int]$_.rank})
        if($hits.Count -ne 50 -or (@($hits | ForEach-Object {[int]$_.rank}) -join ',') -ne ((1..50) -join ',') -or
            (@($hits.unitId | Sort-Object) -join '|') -cne (@($q.candidates.unitId | Sort-Object) -join '|')){throw 'Candidate membership/rank mismatch.'}
        foreach($hit in $hits) {
            $original=@($q.candidates | Where-Object unitId -CEQ $hit.unitId)[0]
            Assert-Near $hit.denseSimilarity $original.similarity 'dense score preservation'
            if([bool]::Parse($hit.relevant) -ne ($hit.unitId -in $q.goldUnitIds)){throw 'Gold label mismatch.'}
            if($condition -eq 'reranked') {
                $score=[double]::Parse($hit.rerankScore,[Globalization.CultureInfo]::InvariantCulture)
                if([double]::IsNaN($score) -or [double]::IsInfinity($score)){throw 'Non-finite saved reranker score.'}
            } elseif([int]$hit.rank -ne [int]$original.rank){throw 'Baseline order changed.'}
        }
        $metrics[$condition]=Get-RerankerMetrics $hits $q.goldUnitIds
    }
    $allBefore+=$metrics.baseline;$allAfter+=$metrics.reranked
    foreach($name in @('hitAt5','completeAt5','recallAt5','ndcgAt5','mrrAt10','recallAt10','ndcgAt10','recallAt50')) {
        Assert-Near $row.$("baseline_$name") $metrics.baseline.$name "$($q.queryId)/baseline/$name"
        Assert-Near $row.$("reranked_$name") $metrics.reranked.$name "$($q.queryId)/reranked/$name"
        Assert-Near $row.$("delta_$name") ($metrics.reranked.$name-$metrics.baseline.$name) "$($q.queryId)/delta/$name"
    }
}
foreach($pair in @(@{name='baseline';rows=$allBefore},@{name='reranked';rows=$allAfter})) {
    $s=@($summary | Where-Object condition -eq $pair.name)
    if($s.Count -ne 1 -or [int]$s[0].count -ne $queries.Count){throw 'Summary count mismatch.'}
    foreach($name in @('hitAt5','completeAt5','recallAt5','ndcgAt5','mrrAt10','recallAt10','ndcgAt10','recallAt50','hitAt50','completeAt50')) {
        Assert-Near $s[0].$name ($pair.rows | Measure-Object $name -Average).Average "summary/$($pair.name)/$name"
    }
}
Write-Host "PASS: $($queries.Count) queries, $($rankings.Count) ranking rows, frozen hashes, gold, candidate identity, preserved cosine scores and recomputed paired metrics. Full completion=$($run.completed)"
