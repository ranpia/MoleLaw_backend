$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/Paths.ps1')
$review=Join-Path $ReviewRoot 'legal-query-transform-v1';$target=Join-Path $DataRoot 'legal-query-transform-v1'
$manifestPath=Join-Path $target 'manifest.json'
if(Test-Path -LiteralPath $manifestPath){throw 'Already frozen; use a new version'}
$authoredPath=Join-Path $ReviewRoot 'independent-validation-v1/authored-questions.json'
$transformsPath=Join-Path $review 'transforms.json';$auditPath=Join-Path $review 'audit.json'
$baselineDir=Join-Path $DataRoot 'independent-validation-v1';$resultDir=Join-Path $ReportsRoot 'independent-validation-v1/kure-v1'
$questionPath=Join-Path $baselineDir 'questions.json';$baseManifestPath=Join-Path $baselineDir 'manifest.json'
$base=Get-Content -Raw -Encoding UTF8 $baseManifestPath|ConvertFrom-Json
$data=Get-Content -Raw -Encoding UTF8 $questionPath|ConvertFrom-Json
$transforms=Get-Content -Raw -Encoding UTF8 $transformsPath|ConvertFrom-Json
$audit=Get-Content -Raw -Encoding UTF8 $auditPath|ConvertFrom-Json
if(!$audit.completed -or $audit.reviewedCount -ne 100 -or @($audit.unresolvedIds).Count){throw 'Semantic audit incomplete'}
if((Get-FileHash $transformsPath).Hash -ne $audit.transformsSha256 -or (Get-FileHash $authoredPath).Hash -ne $audit.originalSha256){throw 'Audited original/transform hash mismatch'}
if((Get-FileHash $questionPath).Hash -ne $base.questionsSha256){throw 'Baseline questions changed'}
if(@($transforms).Count -ne 100 -or @($transforms.id|Select-Object -Unique).Count -ne 100){throw 'Expected 100 unique transforms'}
$map=@{};foreach($q in $data.questions){$map[$q.id]=$q}
foreach($t in $transforms){if(!$map.ContainsKey($t.id) -or $t.originalText -cne $map[$t.id].text -or [string]::IsNullOrWhiteSpace($t.searchText)){throw 'Transform question/text mismatch'}}
$queriesPath=Join-Path $resultDir 'queries.csv';$top10Path=Join-Path $resultDir 'top10.csv'
$queries=@(Import-Csv $queriesPath);$tops=@(Import-Csv $top10Path)
if($queries.Count -ne 200 -or $tops.Count -ne 2000){throw 'Incomplete baseline results'}
foreach($mode in @('original','rewritten')){foreach($q in $data.questions){$rows=@($queries|Where-Object {$_.queryMode -eq $mode -and $_.queryId -eq $q.id});if($rows.Count -ne 1 -or $rows[0].originalText -cne $q.text -or $rows[0].cohort -ne $q.cohort){throw 'Baseline row identity mismatch'}}}
$report=Get-Content -Raw -Encoding UTF8 (Join-Path $resultDir 'report.json')|ConvertFrom-Json
if($report.freezeSha256 -ne (Get-FileHash $baseManifestPath).Hash -or $report.manifest.questionsSha256 -ne $base.questionsSha256){throw 'Baseline report identity mismatch'}
$finalPath=Join-Path $DataRoot 'final-validation-v1/corpus.jsonl'
if((Get-FileHash $finalPath).Hash -ne $base.corpusSha256){throw 'Corpus changed'}
$manifest=[ordered]@{id='legal-query-transform-v1';frozenAtUtc=[datetime]::UtcNow.ToString('o');transformsSha256=(Get-FileHash $transformsPath).Hash;auditSha256=(Get-FileHash $auditPath).Hash;authoredQuestionsSha256=(Get-FileHash $authoredPath).Hash;originalQuestionsSha256=$base.questionsSha256;baselineManifestSha256=(Get-FileHash $baseManifestPath).Hash;baselineQueriesSha256=(Get-FileHash $queriesPath).Hash;baselineTop10Sha256=(Get-FileHash $top10Path).Hash;corpusSha256=$base.corpusSha256;modelRevision=$base.modelRevision;collection=$base.collection;threshold=[double]$base.thresholds.rewritten;questionCount=100;newQueryMode='legal_transformed';queryModes=@('original','rewritten','legal_transformed');singleVectorPerQuestion=$true;serviceReady=$false;evaluationPolicy='Exploratory strategy comparison on a previously examined and threshold-tuned dataset; not new independent held-out evidence';authorIsolation='Original questions only; no corpus, gold, previous rewrites or search results';actualServiceLlmEvaluated=$false}
$null=New-Item -ItemType Directory -Force $target
[IO.File]::WriteAllText($manifestPath,($manifest|ConvertTo-Json -Depth 12)+"`n",[Text.UTF8Encoding]::new($false))
Write-Host "FROZEN: 100 legal query transforms + semantic audit; $($manifest.transformsSha256)"
