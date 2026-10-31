[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ReportDirectory,[switch]$AllowPartial)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
. (Join-Path $PSScriptRoot 'lib/RerankerComparison.ps1')
. (Join-Path $PSScriptRoot 'lib/RankingMetrics.ps1')
$run=Read-RerankerJson (Join-Path $ReportDirectory 'run.json')
if(!$run.completed -and !$AllowPartial){throw 'Run is incomplete.'}
$inputPath=Get-DataPath 'reranker-v1/c500-legal_transformed/candidates.jsonl'
if($run.questionsSha256 -and (Get-FileHash $inputPath).Hash -ne $run.questionsSha256){throw 'Frozen question file hash changed.'}
if($run.completed -and $run.collection){
    $before=Read-RerankerJson (Join-Path $ReportDirectory 'qdrant-before.json')
    $after=Read-RerankerJson (Join-Path $ReportDirectory 'qdrant-after.json')
    if($before.count -ne $after.count -or ($before.config | ConvertTo-Json -Depth 30 -Compress) -cne ($after.config | ConvertTo-Json -Depth 30 -Compress)){throw 'Qdrant collection changed.'}
}
$inputs=@{}; foreach($line in [IO.File]::ReadAllLines($inputPath)){$q=$line | ConvertFrom-Json;$inputs[$q.queryId]=$q}
$files=@(Get-ChildItem -LiteralPath $ReportDirectory -Filter 'ind*.json' -File)
if(!$AllowPartial -and $files.Count -ne $run.questionCount){throw 'Missing question results.'}
if(!$files.Count){throw 'No question results.'}
function Measure-Pool($Pool,$Gold){
    $pool=@($Pool); $hits=@(for($i=0;$i -lt $pool.Count;$i++){[pscustomobject]@{rank=$i+1;unitId=$pool[$i].sourceId}})
    $m=Get-RankingMetrics $hits $Gold
    $found=@($pool | Where-Object {$_.sourceId -in $Gold}).Count
    [pscustomobject]@{hitAt5=$m.hitAt5;completeAt5=$m.completeAt5;recallAt5=$m.recallAt5;ndcgAt5=$m.ndcgAt5;
        hitAt15=[double]($found -gt 0);completeAt15=[double]($found -eq @($Gold).Count);recallAt15=$found/[double]@($Gold).Count}
}
$rows=@(); $rankingRows=@()
foreach($file in $files | Sort-Object Name){
    $q=Read-RerankerJson $file.FullName; $input=$inputs[$q.queryId]
    if(!$input -or $q.question -cne $input.originalText -or
        (@($q.goldUnitIds | Sort-Object) -join '|') -cne (@($input.goldUnitIds | Sort-Object) -join '|')){throw 'Question/gold changed.'}
    foreach($pool in @('initialPool','lastPool','selectedEvidence')){
        $items=@($q.$pool | Where-Object {$null -ne $_})
        if($items.Count -gt 15 -or @($items.sourceId | Select-Object -Unique).Count -ne $items.Count){throw 'Invalid candidate pool.'}
        for($i=0;$i -lt $items.Count;$i++){$rankingRows+=[pscustomobject]@{queryId=$q.queryId;phase=$pool;rank=$i+1;unitId=$items[$i].sourceId;isGold=($items[$i].sourceId -in $q.goldUnitIds)}}
    }
    $rounds=@($q.rounds)
    if($rounds.Count){
        if(($q.initialPool | ConvertTo-Json -Depth 20 -Compress) -cne ($rounds[0].pool | ConvertTo-Json -Depth 20 -Compress) -or
            ($q.lastPool | ConvertTo-Json -Depth 20 -Compress) -cne ($rounds[-1].pool | ConvertTo-Json -Depth 20 -Compress)){throw 'Shared prefix/terminal pool mismatch.'}
        for($i=0;$i -lt $rounds.Count;$i++){
            if($rounds[$i].remainingActions -ne 4-$i){throw 'Invalid adaptive action count.'}
            $round=$rounds[$i]
            foreach($id in @($round.decision.evidenceIds)) {if($id -and $id -cnotin @($round.pool.id)){throw 'Decision selected an unknown candidate.'}}
        }
    }
    foreach($search in @($q.searches)){
        $dense=@($search.denseCandidates).Count
        if((!$search.exactArticleLookup -and $dense -ne 50) -or $dense -gt 50 -or
            @($search.rerankedTop15).Count -ne [math]::Min(15,$dense) -or
            @($search.denseCandidates.unitId | Select-Object -Unique).Count -ne $dense){throw 'Invalid dense/reranked count.'}
        foreach($hit in $search.rerankedTop15){
            $original=@($search.denseCandidates | Where-Object {$_.unitId -ceq $hit.unitId})
            if($original.Count -ne 1 -or $original[0].score -ne $hit.score -or $original[0].articleText -cne $hit.articleText){throw 'Reranking changed source identity/body/cosine.'}
            if($search.exactArticleLookup -and $hit.canonicalUnitId -cnotin @($search.canonicalExactUnits)){throw 'Named-article restriction was lost.'}
        }
    }
    if($q.status -eq 'SUCCESS'){
        if(!@($q.selectedEvidence).Count){throw 'Success without selected evidence.'}
        foreach($item in $q.selectedEvidence){if($item.id -cnotin @($q.lastPool.id)){throw 'Selected evidence missing from terminal pool.'}}
    } elseif(@($q.selectedEvidence | Where-Object {$null -ne $_}).Count){throw 'Failure must not publish evidence.'}
    $initial=Measure-Pool @($q.initialPool | Where-Object {$null -ne $_}) $q.goldUnitIds
    $terminal=Measure-Pool @($q.lastPool | Where-Object {$null -ne $_}) $q.goldUnitIds
    $selected=Measure-Pool @($q.selectedEvidence | Where-Object {$null -ne $_}) $q.goldUnitIds
    $everGold=@($rounds | ForEach-Object {$_.pool} | Where-Object {$_.sourceId -in $q.goldUnitIds} | Select-Object -ExpandProperty sourceId -Unique)
    $terminalGold=@($q.lastPool | Where-Object {$_.sourceId -in $q.goldUnitIds} | Select-Object -ExpandProperty sourceId -Unique)
    $rawGold=@($q.searches | ForEach-Object {$_.rerankedTop15} | Where-Object {$_.unitId -in $q.goldUnitIds} | Select-Object -ExpandProperty unitId -Unique)
    $initialQueryCount=@($q.initialQueries | Where-Object {$null -ne $_}).Count
    $initialDenseGold=@($q.searches | Select-Object -First $initialQueryCount | ForEach-Object {$_.denseCandidates} | Where-Object {$_.unitId -in $q.goldUnitIds} | Select-Object -ExpandProperty unitId -Unique)
    $row=[ordered]@{queryId=$q.queryId;status=$q.status;errorCode=$q.errorCode;rounds=$rounds.Count;searchCount=@($q.searches).Count;
        initialQueryCount=@($q.initialQueries | Where-Object {$null -ne $_}).Count;
        initialElapsedMs=$q.initialElapsedMs;totalElapsedMs=$q.elapsedMs;
        initialCount=@($q.initialPool | Where-Object {$null -ne $_}).Count;terminalCount=@($q.lastPool | Where-Object {$null -ne $_}).Count;
        everPoolRecall=$everGold.Count/[double]@($q.goldUnitIds).Count;everRetrievedRecall=$rawGold.Count/[double]@($q.goldUnitIds).Count;
        goldLostAfterSeen=@($everGold | Where-Object {$_ -notin $terminalGold}).Count;
        terminalGoldOutsideInitialDense=@($terminalGold | Where-Object {$_ -notin $initialDenseGold}).Count;
        lastAction=$(if($rounds.Count){if($rounds[-1].reconsideredDecision){$rounds[-1].reconsideredDecision.action}else{$rounds[-1].decision.action}}else{'NONE'})}
    foreach($metric in @('hitAt5','completeAt5','recallAt5','ndcgAt5','hitAt15','completeAt15','recallAt15')){
        $row["initial_$metric"]=$initial.$metric; $row["terminal_$metric"]=$terminal.$metric;$row["delta_$metric"]=$terminal.$metric-$initial.$metric
        $row["selected_$metric"]=$selected.$metric
    }
    $rows+=[pscustomobject]$row
}
$rows | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $ReportDirectory 'queries.csv')
$rankingRows | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $ReportDirectory 'rankings.csv')
$summary=@(foreach($cohort in @('all_questions','initial_pool_available')){
    $subset=@($rows | Where-Object {$cohort -eq 'all_questions' -or $_.initialCount -gt 0})
    if(!$subset.Count){continue}
    foreach($phase in @('initial','terminal','selected')){
        $row=[ordered]@{cohort=$cohort;phase=$phase;questionCount=$subset.Count}
        foreach($metric in @('hitAt5','completeAt5','recallAt5','ndcgAt5','hitAt15','completeAt15','recallAt15')){$row[$metric]=($subset | Measure-Object -Property "${phase}_$metric" -Average).Average}
        [pscustomobject]$row
    }
})
$summary | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $ReportDirectory 'summary.csv')
$paired=[ordered]@{questionCount=$rows.Count;successful=@($rows | Where-Object status -eq 'SUCCESS').Count;
    hitAt15Gains=@($rows | Where-Object delta_hitAt15 -gt 0).Count;hitAt15Losses=@($rows | Where-Object delta_hitAt15 -lt 0).Count;
    completeAt15Gains=@($rows | Where-Object delta_completeAt15 -gt 0).Count;completeAt15Losses=@($rows | Where-Object delta_completeAt15 -lt 0).Count;
    meanSearchCount=($rows | Measure-Object searchCount -Average).Average;
    initialPoolAvailable=@($rows | Where-Object initialCount -gt 0).Count;
    repeatedQuestions=@($rows | Where-Object {$_.searchCount -gt $_.initialQueryCount -and $_.initialQueryCount -gt 0}).Count;
    questionsLosingPreviouslySeenGold=@($rows | Where-Object goldLostAfterSeen -gt 0).Count;
    questionsFindingGoldOutsideInitialDense=@($rows | Where-Object terminalGoldOutsideInitialDense -gt 0).Count;
    meanInitialElapsedMs=($rows | Measure-Object initialElapsedMs -Average).Average;
    meanTotalElapsedMs=($rows | Measure-Object totalElapsedMs -Average).Average;
    meanInitialAvailableElapsedMs=($rows | Where-Object initialCount -gt 0 | Measure-Object initialElapsedMs -Average).Average;
    meanTotalAvailableElapsedMs=($rows | Where-Object initialCount -gt 0 | Measure-Object totalElapsedMs -Average).Average;
    lastActions=@($rows | Group-Object lastAction | ForEach-Object {[pscustomobject]@{action=$_.Name;count=$_.Count}});
    interpretation='Candidate availability is not answer accuracy. Terminal pools from failed executions remain diagnostic only; failed executions publish no selected evidence.'}
Write-RerankerJson (Join-Path $ReportDirectory 'paired.json') $paired
Write-Host "VALIDATED: $($rows.Count) paired question traces; saved queries.csv, rankings.csv, summary.csv, paired.json"
$summary | Format-Table -AutoSize
Write-Output ([pscustomobject]$paired)
