$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
$review=Get-ReviewPath 'independent-validation-v1'
$target=Get-DataPath 'independent-validation-v1'
if(Test-Path -LiteralPath (Join-Path $target 'manifest.json')){throw 'Already frozen; use a new dataset version.'}
function Read-Json($Name){Get-Content -Raw -Encoding UTF8 (Join-Path $review $Name)|ConvertFrom-Json}
$authored=Read-Json 'authored-questions.json'
$rewrites=Read-Json 'rewrites.json'
$gold=Read-Json 'gold-review.json'
$audit=Read-Json 'audit.json'
$authorFreeze=Read-Json 'author-freeze.json'
if((Get-FileHash (Join-Path $review 'authored-questions.json')).Hash -ne $authorFreeze.authoredSha256){throw 'Authored questions changed after independent writing'}
if(!$audit.completed -or @($audit.unresolvedIds).Count -or $audit.rewriteReviewedCount -ne 100 -or $audit.goldReviewedCount -ne 100){throw 'Independent audit incomplete or unresolved'}
foreach($name in @('authored-questions.json','rewrites.json','gold-review.json')){
 if([string]::IsNullOrWhiteSpace($audit.reviewedHashes.$name) -or (Get-FileHash (Join-Path $review $name)).Hash -ne $audit.reviewedHashes.$name){throw "Audited file changed or lacks audit hash: $name"}
}
if(@($authored.text|Select-Object -Unique).Count -ne 100){throw 'Duplicate authored question text'}
foreach($rows in @(@{name='authored';rows=$authored},@{name='rewrites';rows=$rewrites},@{name='gold';rows=$gold})){
 if(@($rows.rows).Count -ne 100 -or @($rows.rows.id|Select-Object -Unique).Count -ne 100){throw "Invalid 100-question set: $($rows.name)"}
}
$originals=@{};$rewriteMap=@{};$goldMap=@{}
foreach($q in $authored){if($q.id -notmatch '^ind-\d{3}$' -or [string]::IsNullOrWhiteSpace($q.text)){throw 'Invalid authored question'};$originals[$q.id]=$q}
foreach($r in $rewrites){if(!$originals.ContainsKey($r.id) -or $r.originalText -cne $originals[$r.id].text -or [string]::IsNullOrWhiteSpace($r.rewrittenText)){throw 'Rewrite source mismatch'};$rewriteMap[$r.id]=$r.rewrittenText}
$units=@{};foreach($line in [IO.File]::ReadLines((Get-DataPath 'final-validation-v1/corpus.jsonl'))){$c=$line|ConvertFrom-Json;$units[$c.unitId]=$true}
foreach($g in $gold){
 if(!$originals.ContainsKey($g.id) -or $g.cohort -notin @('in_corpus','missing_corpus','unsupported','needs_clarification') -or [string]::IsNullOrWhiteSpace($g.reason)){throw 'Invalid gold classification'}
 if($g.cohort -eq 'in_corpus'){
  if(!@($g.goldUnitIds).Count -or !@($g.sourceUrls).Count){throw 'Positive gold needs units and sources'}
  foreach($unit in $g.goldUnitIds){if(!$units.ContainsKey($unit)){throw "Unknown gold unit: $unit"}}
 }elseif(@($g.goldUnitIds).Count){throw 'Only in-corpus positives may have ranked gold units'}
 if($g.cohort -eq 'missing_corpus' -and !@($g.sourceUrls).Count){throw 'Missing-corpus classification needs official source evidence'}
 if(@($g.goldUnitIds|Select-Object -Unique).Count -ne @($g.goldUnitIds).Count){throw 'Duplicate gold unit'}
 $goldMap[$g.id]=$g
}
$old=Get-Content -Raw -Encoding UTF8 (Get-DataPath 'final-validation-v1/questions.json')|ConvertFrom-Json
foreach($q in $authored){if($q.text -in @($old.questions.text)){throw 'Exact legacy question duplicate'}}
$questions=@(foreach($q in $authored){$g=$goldMap[$q.id];[ordered]@{id=$q.id;text=$q.text;rewrittenText=$rewriteMap[$q.id];intentGroup=$q.intentGroup;style=$q.style;cohort=$g.cohort;goldUnitIds=@($g.goldUnitIds);sourceUrls=@($g.sourceUrls);requiredLaws=@($g.requiredLaws);reason=$g.reason}})
$null=New-Item -ItemType Directory -Force $target
function Save-Json($Path,$Value){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 24)+"`n",[Text.UTF8Encoding]::new($false))}
Save-Json (Join-Path $target 'questions.json') ([ordered]@{id='independent-validation-v1';referenceDate='2026-10-19';questionCount=100;questions=$questions;roleSeparated=$true;humanReviewed=$false;serviceLlmRewrites=$false})
$previous=Get-Content -Raw -Encoding UTF8 (Get-DataPath 'final-validation-v1/manifest.json')|ConvertFrom-Json
$hashes=[ordered]@{};foreach($name in @('authored-questions.json','rewrites.json','gold-review.json','audit.json','author-freeze.json')){$hashes[$name]=(Get-FileHash (Join-Path $review $name)).Hash}
$manifest=[ordered]@{id='independent-validation-v1';frozenAtUtc=[datetime]::UtcNow.ToString('o');questionsSha256=(Get-FileHash (Join-Path $target 'questions.json')).Hash;corpusSha256=$previous.corpusSha256;modelRevision=$previous.model.revision;collection=('molelaw_validation_kure_v1_c300_'+$previous.corpusSha256.Substring(0,8).ToLowerInvariant()+'_'+$previous.model.revision.Substring(0,8));thresholds=$previous.thresholds;questionCount=100;reviewHashes=$hashes;cohorts=@($questions|ForEach-Object {[pscustomobject]$_}|Group-Object cohort|ForEach-Object{[ordered]@{cohort=$_.Name;count=$_.Count}});selectionPolicy='Diagnostic independent-role evaluation; no post-hoc pass criterion or retuning';serviceReady=$false}
Save-Json (Join-Path $target 'manifest.json') $manifest
Write-Host "FROZEN: 100 authored questions, rewrites, reviewed gold; $($manifest.questionsSha256)"
$manifest.cohorts|Format-Table
