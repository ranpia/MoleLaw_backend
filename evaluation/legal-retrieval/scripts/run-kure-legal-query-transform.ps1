param([switch]$ValidateOnly)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
. (Join-Path $PSScriptRoot 'lib/RankingMetrics.ps1')
$repo=Split-Path (Split-Path $EvaluationRoot -Parent) -Parent
Set-Location $repo
$dir=Join-Path $DataRoot 'legal-query-transform-v1'
$review=Join-Path $ReviewRoot 'legal-query-transform-v1'
$baselineDir=Join-Path $DataRoot 'independent-validation-v1'
$baselineReports=Join-Path $ReportsRoot 'independent-validation-v1/kure-v1'
$baseDir=Join-Path $DataRoot 'final-validation-v1'
$work=Join-Path $ArtifactsRoot 'legal-query-transform-v1'
$target=Join-Path $ReportsRoot 'legal-query-transform-v1/kure-v1'
$logs=Join-Path $ArtifactsRoot 'logs'
$stop=Join-Path $ArtifactsRoot 'control/stop-kure-legal-query-transform-v1.flag'
$started=$false
function Check-Stop {if(Test-Path -LiteralPath $stop){throw 'User stop flag found; caches retained'}}
function Save-Json($Path,$Value){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 32)+"`n",[Text.UTF8Encoding]::new($false))}
function Convert-BaselineRow($Row){
    $Row.accepted=[bool]::Parse($Row.accepted);$Row.top1Relevant=[bool]::Parse($Row.top1Relevant)
    foreach($key in @('top1Similarity','threshold','mrrAt10','recallAt5','recallAt10','hitAt5','completeAt5','precisionAt5','ndcgAt5','ndcgAt10')){
        if([string]::IsNullOrWhiteSpace([string]$Row.$key)){
            if($key -in @('top1Similarity','threshold') -or $Row.cohort -eq 'in_corpus'){throw "Missing baseline numeric field: $key"}
            $Row.$key=$null
        }else{
            $value=[double]::Parse([string]$Row.$key,[Globalization.CultureInfo]::InvariantCulture)
            if([double]::IsNaN($value) -or [double]::IsInfinity($value)){throw "Invalid baseline numeric field: $key"}
            $Row.$key=$value
        }
    }
    if($Row.accepted -ne ($Row.top1Similarity -ge $Row.threshold)){throw 'Baseline acceptance inconsistent with frozen threshold'}
}
function Get-ModeSummary($Results,$Modes,$Cohorts){
    foreach($mode in $Modes){foreach($cohort in $Cohorts){
        $selected=@($Results|Where-Object{$_.queryMode -eq $mode -and $_.cohort -eq $cohort});if(!$selected.Count){continue}
        $row=[ordered]@{queryMode=$mode;cohort=$cohort;count=$selected.Count;scoreAcceptanceRate=@($selected|Where-Object accepted).Count/[double]$selected.Count}
        foreach($key in @('recallAt5','recallAt10','hitAt5','completeAt5','precisionAt5','ndcgAt5','ndcgAt10','mrrAt10')){$row[$key]=$(if($cohort -eq 'in_corpus'){($selected|Measure-Object $key -Average).Average}else{$null})}
        [pscustomobject]$row
    }}
}
function Assert-Frozen {
    if((Get-FileHash -LiteralPath $manifestPath).Hash -ne $freezeHash){throw 'Experiment manifest changed'}
    foreach($key in $files.Keys){if(!(Test-Path -LiteralPath $files[$key]) -or (Get-FileHash -LiteralPath $files[$key]).Hash -ne $manifest.$key){throw "Frozen input mismatch: $key"}}
    if((Get-FileHash -LiteralPath (Join-Path $baseDir 'manifest.json')).Hash -ne $baseFreeze){throw 'Corpus provenance manifest changed'}
}
function Assert-Owned($Name,$Expected){
    $info=(Invoke-QdrantLab GET "collections/$Name").result
    foreach($key in $Expected.Keys){if($info.config.metadata.$key -ne $Expected[$key]){throw "Collection identity mismatch: $key"}}
    if($info.config.params.vectors.size -ne 1024 -or $info.config.params.vectors.distance -ne 'Cosine'){throw 'Vector schema mismatch'}
}
$manifestPath=Join-Path $dir 'manifest.json'
$manifest=Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8|ConvertFrom-Json
$freezeHash=(Get-FileHash -LiteralPath $manifestPath).Hash
$baseFreeze=(Get-FileHash -LiteralPath (Join-Path $baseDir 'manifest.json')).Hash
$files=@{
    transformsSha256=(Join-Path $review 'transforms.json');auditSha256=(Join-Path $review 'audit.json')
    originalQuestionsSha256=(Join-Path $baselineDir 'questions.json');baselineManifestSha256=(Join-Path $baselineDir 'manifest.json')
    baselineQueriesSha256=(Join-Path $baselineReports 'queries.csv');baselineTop10Sha256=(Join-Path $baselineReports 'top10.csv')
    corpusSha256=(Join-Path $baseDir 'corpus.jsonl')
    authoredQuestionsSha256=(Join-Path $ReviewRoot 'independent-validation-v1/authored-questions.json')
}
Assert-Frozen
$data=Get-Content -LiteralPath $files.originalQuestionsSha256 -Raw -Encoding UTF8|ConvertFrom-Json
$transforms=Get-Content -LiteralPath $files.transformsSha256 -Raw -Encoding UTF8|ConvertFrom-Json
$audit=Get-Content -LiteralPath $files.auditSha256 -Raw -Encoding UTF8|ConvertFrom-Json
$baseline=Get-Content -LiteralPath $files.baselineManifestSha256 -Raw -Encoding UTF8|ConvertFrom-Json
$baselineReport=Get-Content -LiteralPath (Join-Path $baselineReports 'report.json') -Raw -Encoding UTF8|ConvertFrom-Json
if($baselineReport.freezeSha256 -ne $manifest.baselineManifestSha256 -or $baselineReport.documentCollection -ne $manifest.collection){throw 'Baseline report provenance mismatch'}
$base=Get-Content -LiteralPath (Join-Path $baseDir 'manifest.json') -Raw -Encoding UTF8|ConvertFrom-Json
$context=Get-QdrantLabContext 'kure-v1' 300
if($manifest.originalQuestionsSha256 -ne $baseline.questionsSha256 -or $manifest.corpusSha256 -ne $base.corpusSha256 -or $manifest.corpusSha256 -ne $baseline.corpusSha256){throw 'Baseline provenance mismatch'}
if($manifest.modelRevision -ne $base.model.revision -or $manifest.modelRevision -ne $baseline.modelRevision -or $context.profile.revision -ne $base.model.revision){throw 'Model revision mismatch'}
foreach($field in @('tag','dimensions','inputMode','documentFormat','queryFormat')){if($context.profile.$field -ne $base.model.$field){throw "Model profile mismatch: $field"}}
if($base.model.queryFormat -ne '{text}' -or $base.model.dimensions -ne 1024){throw 'KURE raw query / dimensions required'}
if($null -eq $manifest.threshold -or [math]::Abs([double]$manifest.threshold-0.59829341466087427) -gt 1e-12 -or [math]::Abs([double]$manifest.threshold-[double]$baseline.thresholds.rewritten) -gt 1e-12){throw 'Frozen diagnostic threshold mismatch'}
$collection='molelaw_validation_kure_v1_c300_'+$base.corpusSha256.Substring(0,8).ToLowerInvariant()+'_'+$base.model.revision.Substring(0,8)
if($manifest.collection -ne $collection -or $baseline.collection -ne $collection){throw 'Unexpected document collection'}
$chunks=@(foreach($line in [IO.File]::ReadLines($files.corpusSha256)){$line|ConvertFrom-Json})
if($chunks.Count -ne 10999){throw 'Expected frozen 10999 document chunks'}
$units=@{};foreach($chunk in $chunks){$units[$chunk.unitId]=$true}
if(@($data.questions).Count -ne 100 -or @($data.questions.id|Select-Object -Unique).Count -ne 100 -or @($transforms).Count -ne 100 -or @($transforms.id|Select-Object -Unique).Count -ne 100){throw 'Expected 100 unique baseline/transform IDs'}
if($audit.completed -isnot [bool] -or !$audit.completed -or $audit.reviewedCount -ne 100 -or @($audit.unresolvedIds).Count -ne 0 -or $audit.transformsSha256 -ne $manifest.transformsSha256 -or $audit.originalSha256 -ne $manifest.authoredQuestionsSha256){throw 'Transform audit incomplete or mismatched'}
$cohorts=@('in_corpus','missing_corpus','unsupported','needs_clarification')
$questionMap=@{};foreach($q in $data.questions){
    if([string]::IsNullOrWhiteSpace($q.id) -or [string]::IsNullOrWhiteSpace($q.text) -or $q.cohort -notin $cohorts){throw 'Invalid baseline question'}
    $questionMap[$q.id]=$q
    if($q.cohort -eq 'in_corpus'){
        if(@($q.goldUnitIds).Count -eq 0){throw 'Positive gold required'}
        foreach($id in $q.goldUnitIds){if(!$units.ContainsKey($id)){throw 'Unknown frozen gold unit'}}
    }elseif(@($q.goldUnitIds).Count -ne 0){throw 'Only in-corpus questions may have gold'}
}
foreach($t in $transforms){if(!$questionMap.ContainsKey($t.id) -or $t.originalText -cne $questionMap[$t.id].text -or [string]::IsNullOrWhiteSpace($t.searchText) -or $null -eq $t.concepts){throw 'Invalid transform or original text mismatch'}}
$baselineResults=@(Import-Csv -LiteralPath $files.baselineQueriesSha256)
$baselineTop=@(Import-Csv -LiteralPath $files.baselineTop10Sha256)
if($baselineResults.Count -ne 200 -or $baselineTop.Count -ne 2000){throw 'Baseline output counts mismatch'}
foreach($mode in @('original','rewritten')){
    $rows=@($baselineResults|Where-Object queryMode -eq $mode)
    if($rows.Count -ne 100 -or @($rows.queryId|Select-Object -Unique).Count -ne 100){throw 'Invalid baseline query identities'}
    foreach($r in $rows){
        if(!$questionMap.ContainsKey($r.queryId) -or $r.cohort -ne $questionMap[$r.queryId].cohort -or $r.originalText -cne $questionMap[$r.queryId].text){throw 'Baseline query provenance mismatch'}
        $expectedText=$(if($mode -eq 'original'){$questionMap[$r.queryId].text}else{$questionMap[$r.queryId].rewrittenText})
        if($r.retrievalText -cne $expectedText -or [math]::Abs([double]$r.threshold-[double]$baseline.thresholds.$mode) -gt 1e-12){throw 'Baseline text/threshold mismatch'}
        $tops=@($baselineTop|Where-Object{$_.queryMode -eq $mode -and $_.queryId -eq $r.queryId})
        if($tops.Count -ne 10 -or @($tops|ForEach-Object{[int]$_.rank}|Sort-Object -Unique).Count -ne 10 -or @($tops|Where-Object{[int]$_.rank -lt 1 -or [int]$_.rank -gt 10}).Count){throw 'Baseline rank coverage mismatch'}
        Convert-BaselineRow $r
    }
}
$recomputed=@(Get-ModeSummary $baselineResults @('original','rewritten') $cohorts)
if($recomputed.Count -ne 8 -or @($baselineReport.summary).Count -ne 8){throw 'Baseline summary coverage mismatch'}
foreach($row in $recomputed){
    $previous=@($baselineReport.summary|Where-Object{$_.queryMode -eq $row.queryMode -and $_.cohort -eq $row.cohort})
    if($previous.Count -ne 1 -or $previous[0].count -ne $row.count){throw 'Baseline summary identity mismatch'}
    foreach($key in @('scoreAcceptanceRate','recallAt5','recallAt10','hitAt5','completeAt5','precisionAt5','ndcgAt5','ndcgAt10','mrrAt10')){
        if(($null -eq $row.$key) -ne ($null -eq $previous[0].$key) -or [math]::Abs([double]$row.$key-[double]$previous[0].$key) -gt 1e-12){throw "Baseline summary drift: $key"}
    }
}
if($ValidateOnly){Write-Host 'PASS: 100 transforms, completed audit, frozen baselines/corpus/model/threshold; no inference';return}
foreach($path in @($work,$target,$logs)){$null=New-Item -ItemType Directory -Force $path}
Start-Transcript -Path (Join-Path $logs ('kure-legal-query-transform-v1-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.log'))
try {
    Check-Stop
    Initialize-QdrantLab 6333
    $docMetadata=@{owner=$script:LabOwner;kind='final-validation-v1';model_digest=$base.model.revision;dataset_sha256=$base.datasetSha256;corpus_sha256=$base.corpusSha256;freeze_sha256=$baseFreeze}
    Assert-Owned $collection $docMetadata
    if((Invoke-QdrantLab POST "collections/$collection/points/count" @{exact=$true}).result.count -ne 10999){throw 'Document collection count mismatch'}
    $running=@(docker ps --format '{{.Names}}')
    if($LASTEXITCODE -ne 0){throw 'Docker unavailable'}
    if('molelaw-kure-lab-kure-1' -in $running){throw 'KURE already running; avoid stopping another session'}
    if('molelaw-qwen3-lab' -in $running){throw 'Qwen running; finish other inference first'}
    if('molelaw-embedding-lab-ollama-1' -in $running){$loaded=Invoke-RestMethod http://127.0.0.1:11435/api/ps -TimeoutSec 5;if(@($loaded.models).Count){throw 'Ollama has loaded models; unload first'}}
    $started=$true
    docker compose -f docker/kure/compose.yml up -d --no-build
    if($LASTEXITCODE -ne 0){throw 'KURE startup failed'}
    $deadline=[datetime]::UtcNow.AddMinutes(15)
    while($true){Check-Stop;try{$runtime=Invoke-RestMethod http://127.0.0.1:11436/api/version -TimeoutSec 3;break}catch{if([datetime]::UtcNow -gt $deadline){throw 'KURE startup timeout'};Start-Sleep -Seconds 5}}
    Assert-QdrantLabRuntime $context $runtime
    foreach($field in @('version','dtype')){if($runtime.$field -ne $baselineReport.runtime.$field){throw "Baseline runtime mismatch: $field"}}
    $tags=Invoke-RestMethod http://127.0.0.1:11436/api/tags
    if(@($tags.models|Where-Object{$_.name -eq 'kure-v1:official' -and $_.digest -eq $base.model.revision}).Count -ne 1){throw 'Checkpoint mismatch'}
    $queryCollection='molelaw_legal_query_kure_v1_'+$freezeHash.Substring(0,8).ToLowerInvariant()
    $metadata=@{owner=$script:LabOwner;kind='legal-query-transform-v1';model_digest=$base.model.revision;transforms_sha256=$manifest.transformsSha256;corpus_sha256=$manifest.corpusSha256;freeze_sha256=$freezeHash}
    if(@((Invoke-QdrantLab GET 'collections').result.collections|Where-Object name -eq $queryCollection).Count -eq 0){$null=Invoke-QdrantLab PUT "collections/$queryCollection" @{vectors=@{size=1024;distance='Cosine'};metadata=$metadata}}
    Assert-Owned $queryCollection $metadata
    $cacheDir=Join-Path $work ('query-cache/'+$freezeHash.Substring(0,16));$null=New-Item -ItemType Directory -Force $cacheDir
    $cache=[LegalDenseIndex]::new(1024)
    $cacheMeta=@{modelRevision=$base.model.revision;runtime=$runtime.version;dtype=$runtime.dtype;inputMode=$base.model.inputMode;queryFormat=$base.model.queryFormat;transformsSha256=$manifest.transformsSha256;freezeSha256=$freezeHash}
    $metaPath=Join-Path $cacheDir 'metadata.json'
    if(!(Test-Path -LiteralPath $metaPath) -and @(Get-ChildItem -LiteralPath $cacheDir -Filter '*.bin').Count){throw 'Cache vectors without identity metadata'}
    if(Test-Path -LiteralPath $metaPath){$previous=Get-Content -LiteralPath $metaPath -Raw -Encoding UTF8|ConvertFrom-Json;foreach($key in $cacheMeta.Keys){if($previous.$key -ne $cacheMeta[$key]){throw 'Query cache identity mismatch'}}}
    Save-Json $metaPath $cacheMeta
    foreach($file in Get-ChildItem -LiteralPath $cacheDir -Filter '*.bin'){$cache.ReadBatch($file.FullName)}
    $queries=@(foreach($t in $transforms){$text=$t.searchText;$inputText=$base.model.queryFormat.Replace('{text}',$text);[pscustomobject]@{mode='legal_transformed';question=$questionMap[$t.id];text=$text;inputText=$inputText;key=[LegalDenseIndex]::InputKey($inputText)}})
    $pending=@{};foreach($query in $queries){if(!$cache.Contains($query.key)){$pending[$query.key]=$query.inputText}}
    $keys=@($pending.Keys|Sort-Object)
    for($offset=0;$offset -lt $keys.Count;$offset+=16){
        Check-Stop;Assert-Frozen;$end=[math]::Min($offset+16,$keys.Count);$batch=[string[]]@($keys[$offset..($end-1)])
        $body=@{model='kure-v1:official';input=@($batch|ForEach-Object{$pending[$_]});truncate=$false;keep_alive='5m';options=@{num_thread=6;num_gpu=0}}|ConvertTo-Json -Depth 5 -Compress
        $response=Invoke-RestMethod -Method Post http://127.0.0.1:11436/api/embed -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 300
        if(@($response.embeddings).Count -ne $batch.Count){throw 'Embedding count mismatch'}
        $cache.WriteBatch((Join-Path $cacheDir ([guid]::NewGuid().ToString('N')+'.bin')),$batch,[single[][]]@($response.embeddings|ForEach-Object{,[single[]]$_}))
        Write-Host "Cached new query vectors $end/$($keys.Count)"
    }
    $results=@($baselineResults);$topRows=@($baselineTop);$stored=@();$context.collection=$collection;$index=0
    foreach($query in $queries){
        Check-Stop;$q=$query.question;$vector=$cache.GetVector($query.key)
        $groups=@(Invoke-QdrantLabGroups $context $vector 10 $true)
        if($groups.Count -ne 10 -or @($groups.id|Select-Object -Unique).Count -ne 10){throw 'Invalid grouped results'}
        $hits=@();for($i=0;$i -lt 10;$i++){
            $hit=[pscustomobject]@{rank=$i+1;unitId=$groups[$i].id;similarity=$groups[$i].hits[0].score;relevant=($groups[$i].id -in @($q.goldUnitIds))};$hits+=$hit
            $topRows+=[pscustomobject]@{queryId=$q.id;queryMode=$query.mode;cohort=$q.cohort;rank=$hit.rank;unitId=$hit.unitId;similarity=$hit.similarity;relevant=$hit.relevant;title=$groups[$i].hits[0].payload.title}
        }
        $metrics=$null;$rr=$null
        if($q.cohort -eq 'in_corpus'){$metrics=Get-RankingMetrics $hits $q.goldUnitIds;$first=@($hits|Where-Object relevant|Select-Object -First 1);$rr=$(if($first.Count){1.0/$first[0].rank}else{0.0})}
        $threshold=[double]$manifest.threshold
        $row=[ordered]@{queryId=$q.id;queryMode=$query.mode;cohort=$q.cohort;originalText=$q.text;retrievalText=$query.text;top1Similarity=$hits[0].similarity;top1Relevant=$hits[0].relevant;accepted=($hits[0].similarity -ge $threshold);threshold=$threshold;mrrAt10=$rr}
        foreach($key in @('recallAt5','recallAt10','hitAt5','completeAt5','precisionAt5','ndcgAt5','ndcgAt10')){$row[$key]=$metrics.$key}
        $results+=[pscustomobject]$row
        $stored+=@{id=(Get-QdrantLabPointId "$queryCollection/$($q.id)/legal_transformed");vector=@($vector);payload=@{lab_owner=$script:LabOwner;query_id=$q.id;query_mode=$query.mode;cohort=$q.cohort;question=$q;original_text=$q.text;transform_text=$query.text;retrieval_text=$query.text;gold_unit_ids=@($q.goldUnitIds);metrics=$row;top10=$hits;freeze_sha256=$freezeHash;document_collection=$collection;model_revision=$base.model.revision;transforms_sha256=$manifest.transformsSha256}}
        if($stored.Count -eq 16){$null=Invoke-QdrantLab PUT "collections/$queryCollection/points?wait=true" @{points=$stored};$stored=@()}
        $index++;if($index%20 -eq 0){Write-Host "Evaluated $index/100 new query variants"}
    }
    if($stored.Count){$null=Invoke-QdrantLab PUT "collections/$queryCollection/points?wait=true" @{points=$stored}}
    if($results.Count -ne 300 -or $topRows.Count -ne 3000 -or (Invoke-QdrantLab POST "collections/$queryCollection/points/count" @{exact=$true}).result.count -ne 100){throw 'Incomplete results'}
    $summary=@(Get-ModeSummary $results @('original','rewritten','legal_transformed') $cohorts)
    Assert-Frozen
    $probe=@((Invoke-QdrantLab POST "collections/$queryCollection/points/scroll" @{limit=1;with_payload=$true;with_vector=$true}).result.points)
    if($probe.Count -ne 1 -or $probe[0].payload.freeze_sha256 -ne $freezeHash -or @($probe[0].vector).Count -ne 1024){throw 'Query archive roundtrip failed'}
    $report=@{completedAtUtc=[datetime]::UtcNow.ToString('o');freezeSha256=$freezeHash;manifest=$manifest;runtime=$runtime;documentCollection=$collection;queryCollection=$queryCollection;summary=$summary;serviceReady=$false;scope='Exploratory tuning using existing known questions/results. No new holdout questions or actual service LLM prompt quality evaluated. No threshold optimization; transformed mode uses frozen rewritten threshold diagnostically.'}
    $results|Export-Csv (Join-Path $target 'queries.csv') -NoTypeInformation -Encoding UTF8
    $topRows|Export-Csv (Join-Path $target 'top10.csv') -NoTypeInformation -Encoding UTF8
    $summary|Export-Csv (Join-Path $target 'summary.csv') -NoTypeInformation -Encoding UTF8
    Save-Json (Join-Path $target 'report.json') $report
    $lines=@('# KURE-v1 법률 쟁점 중심 질의 변환 비교','','KURE 1024차원·300자·중복 64자와 기존 10,999개 문서 청크를 고정했다. 기존 100문항의 원본·의미 보존 정제·법률 쟁점 중심 변환을 비교한다. 원본·정제 200건은 고정 결과를 읽기만 하며 신규 변환 100건만 추론한다. 문서 재색인은 하지 않는다.','','| 질문 | 분류 | 문항 | Recall@5 | Complete@5 | MRR@10 | 점수 수용률 |','| --- | --- | ---: | ---: | ---: | ---: | ---: |')
    foreach($r in $summary){
        $recall='-';$complete='-';$mrr='-'
        if($r.cohort -eq 'in_corpus'){$recall=('{0:F2}%' -f (100*$r.recallAt5));$complete=('{0:F2}%' -f (100*$r.completeAt5));$mrr=('{0:F4}' -f $r.mrrAt10)}
        $lines+=('| {0} | {1} | {2} | {3} | {4} | {5} | {6:F2}% |' -f $r.queryMode,$r.cohort,$r.count,$recall,$complete,$mrr,(100*$r.scoreAcceptanceRate))
    }
    $lines+=@('','Recall은 정답 근거 중 찾은 비율, Complete는 근거를 모두 찾은 문항 비율이다. MRR@10은 첫 정답 순위의 역수이며 10위 밖은 0이다. in_corpus 외 세 분류에는 정답 순위 지표를 계산하지 않는다.','','점수 수용률은 상위 점수가 고정 임계값을 넘은 비율이다. 원본과 기존 정제는 기존 임계값을 유지하고 legal_transformed는 기존 정제 임계값 0.598293414660874를 진단용으로 사용한다. 임계값 최적화는 수행하지 않았다. 서비스 LLM의 근거 충분성 판단, 답변 생성, 자동 수집 정확도가 아니다.','','기존 결과를 알고 설계한 탐색·튜닝 평가이며 새 질문을 사용하지 않았다. 에이전트 질의 작성·검수 역할을 나누었지만 독립 검증셋이 아니며 서비스 성능 확정 근거가 아니다. 실제 서비스 LLM 프롬프트의 품질도 평가하지 않았다.','','[고정 입력](../../../data/legal-query-transform-v1/manifest.json) · [기존 독립 검증](../../independent-validation-v1/kure-v1/README.md) · [선정 근거](../../../review/model-selection.md)')
    [IO.File]::WriteAllText((Join-Path $target 'README.md'),($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
    $summary|Format-Table queryMode,cohort,count,recallAt5,completeAt5,mrrAt10,scoreAcceptanceRate
    Write-Host 'LEGAL QUERY TRANSFORM COMPLETE. See reports/legal-query-transform-v1/kure-v1/README.md' -ForegroundColor Green
} finally {
    if($started){
        try{$body=@{model='kure-v1:official';input=@();keep_alive=0}|ConvertTo-Json;$null=Invoke-RestMethod -Method Post http://127.0.0.1:11436/api/embed -ContentType application/json -Body $body -TimeoutSec 10}catch{}
        docker compose -f docker/kure/compose.yml stop kure
    }
    Stop-Transcript
}
