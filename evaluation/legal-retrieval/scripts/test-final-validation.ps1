[CmdletBinding()]
param([string]$DatasetDirectory)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
$dir=Get-DataPath 'final-validation-v1'
if($DatasetDirectory){$dir=[IO.Path]::GetFullPath($DatasetDirectory)}
$manifest=Get-Content -Raw -Encoding UTF8 (Join-Path $dir 'manifest.json')|ConvertFrom-Json
foreach($entry in @(@('questions.json','datasetSha256'),@('corpus.jsonl','corpusSha256'),@('rewrites.json','rewritesSha256'))){if((Get-FileHash (Join-Path $dir $entry[0])).Hash -ne $manifest.($entry[1])){throw "Frozen hash mismatch: $($entry[0])"}}
if((Get-FileHash (Get-ReviewPath 'final-validation-v1/questions.psv')).Hash -ne $manifest.draftSha256){throw 'Question draft changed after freeze'}
$data=Get-Content -Raw -Encoding UTF8 (Join-Path $dir 'questions.json')|ConvertFrom-Json
$rewrites=(Get-Content -Raw -Encoding UTF8 (Join-Path $dir 'rewrites.json')|ConvertFrom-Json).questions
if($data.questions.Count -ne 270 -or $rewrites.Count -ne 270 -or $data.laws.Count -ne 30){throw 'Unexpected evaluation scope'}
$map=@{};$chunks=@{};$unitText=@{}
foreach($line in [IO.File]::ReadLines((Join-Path $dir 'corpus.jsonl'))){
    $c=$line|ConvertFrom-Json
    if($chunks.ContainsKey($c.id) -or !$c.text -or $c.text.Length -gt 300 -or $c.endChar-$c.startChar -ne $c.text.Length -or [LegalDenseIndex]::InputKey($c.text) -ne $c.textSha256){throw 'Invalid chunk/hash/offset'}
    $chunks[$c.id]=$c;$map[$c.unitId]=@($map[$c.unitId]|Where-Object{$null -ne $_})+@($c.id)
    if(!$unitText.ContainsKey($c.unitId)){$unitText[$c.unitId]=[Text.StringBuilder]::new()}
    $builder=$unitText[$c.unitId];$overlap=$builder.Length-$c.startChar
    if($overlap -lt 0 -or $overlap -gt $c.text.Length -or ($overlap -gt 0 -and $builder.ToString($c.startChar,$overlap) -cne $c.text.Substring(0,$overlap))){throw 'Source coverage/overlap mismatch'}
    [void]$builder.Append($c.text.Substring($overlap))
}
if($chunks.Count -ne $manifest.chunkCount -or $map.Count -ne $manifest.sourceUnitCount){throw 'Corpus count mismatch'}
$base=Get-Content -Raw -Encoding UTF8 (Get-DataPath 'variants/char300-overlap64-article-v1/questions.json')|ConvertFrom-Json
$old=@{};foreach($q in $base.questions){$old[$q.id]=$q}
$oldRewrites=@{};foreach($r in (Get-Content -Raw -Encoding UTF8 (Get-ReviewPath 'query-rewrites-v1.json')|ConvertFrom-Json).questions){$oldRewrites[$r.id]=$r.rewrittenText}
$oldGold=@($base.questions|ForEach-Object{$_.relevantUnitIds}|Select-Object -Unique)
$ids=@{};$texts=@{};$rewriteMap=@{};foreach($r in $rewrites){if($rewriteMap.ContainsKey($r.id) -or !$r.rewrittenText){throw 'Duplicate/empty rewrite'};$rewriteMap[$r.id]=$r}
foreach($q in $data.questions){
    if($ids.ContainsKey($q.id) -or $texts.ContainsKey($q.text)){throw 'Duplicate question ID/text'};$ids[$q.id]=$true;$texts[$q.text]=$true
    if($rewriteMap[$q.id].originalText -cne $q.text){throw 'Rewrite original mismatch'}
    if($q.cohort -eq 'expanded_held_out'){
        if($q.split -ne 'held_out' -or @($q.relevantUnitIds|Where-Object{$_ -in $oldGold}).Count){throw 'New holdout leakage'}
    }else{if(!$old.ContainsKey($q.id) -or $q.text -cne $old[$q.id].text -or ($q.relevantUnitIds -join '|') -cne ($old[$q.id].relevantUnitIds -join '|') -or $rewriteMap[$q.id].rewrittenText -cne $oldRewrites[$q.id]){throw 'Regression question/rewrite changed'}}
    if($q.answerability -eq 'in_corpus'){
        if(!$q.relevantUnitIds.Count){throw 'Missing gold'}
        if(@($q.relevantUnitIds|Select-Object -Unique).Count -ne $q.relevantUnitIds.Count -or @($q.relevantChunkGroups).Count -ne $q.relevantUnitIds.Count -or @($q.relevantChunkGroups|ForEach-Object{$_.unitId}|Select-Object -Unique).Count -ne $q.relevantUnitIds.Count -or @($q.relevantChunkGroups|Where-Object{$_.unitId -notin $q.relevantUnitIds}).Count){throw 'Invalid gold unit/group sets'}
        $expected=@();foreach($u in $q.relevantUnitIds){if(!$map.ContainsKey($u)){throw 'Missing gold unit'};$expected+=@($map[$u])}
        if(@($q.relevantChunkIds).Count -ne $expected.Count -or @($expected|Where-Object{$_ -notin $q.relevantChunkIds}).Count){throw 'Incomplete gold chunks'}
        foreach($g in $q.relevantChunkGroups){if($g.chunkIds.Count -ne $map[$g.unitId].Count -or @($g.chunkIds|Where-Object{$_ -notin $map[$g.unitId]}).Count){throw 'Invalid gold group'}}
    }elseif($q.answerability -ne 'out_of_corpus' -or $q.relevantUnitIds.Count -or $q.relevantChunkIds.Count){throw 'Invalid negative'}
}
$new=@($data.questions|Where-Object cohort -eq 'expanded_held_out')
if(@($new|Where-Object answerability -eq 'in_corpus').Count -ne 120 -or @($new|Where-Object answerability -eq 'out_of_corpus').Count -ne 40 -or @($new|Where-Object style -eq 'multi_evidence').Count -ne 20){throw 'Wrong final cohorts'}
if($manifest.model.revision -ne '8b418a58414668e75532ed045c22d9ca018ae2b2' -or $manifest.model.dimensions -ne 1024 -or $data.corpus.maxChars -ne 300 -or $data.corpus.overlapChars -ne 64){throw 'Selected conditions changed'}
$raw=@((Get-Content -Raw -Encoding UTF8 (Get-ReportPath 'kure-v1/char300-overlap64-article-v1/summary.json')|ConvertFrom-Json).summary|Where-Object split -eq 'development')[0].threshold
$refined=@((Get-Content -Raw -Encoding UTF8 (Get-ReportPath 'query-rewrite-v1/char300/kure-v1/report.json')|ConvertFrom-Json).summary|Where-Object split -eq 'development')[0].threshold
if([math]::Abs($manifest.thresholds.original-$raw) -gt 1e-12 -or [math]::Abs($manifest.thresholds.rewritten-$refined) -gt 1e-12){throw 'Frozen thresholds were recalibrated'}
Write-Host "PASS: 30 main laws, 270 questions, 120 new positives / 40 negatives, 20 multi-evidence, $($chunks.Count) chunks; frozen hashes, source coverage, gold and regression identity verified."
