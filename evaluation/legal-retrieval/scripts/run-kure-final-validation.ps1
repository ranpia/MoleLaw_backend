$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'lib/QdrantLab.ps1')
. (Join-Path $PSScriptRoot 'lib/RankingMetrics.ps1')
$repo=Split-Path (Split-Path $EvaluationRoot -Parent) -Parent
Set-Location $repo
$dir=Get-DataPath 'final-validation-v1'
$work=Join-Path $ArtifactsRoot 'final-validation-v1'
$target=Get-ReportPath 'final-validation-v1/kure-v1'
$logs=Join-Path $ArtifactsRoot 'logs'
foreach($path in @($work,$target,$logs)){$null=New-Item -ItemType Directory -Force $path}
$stop=Join-Path $ArtifactsRoot 'control/stop-kure-final-validation-v1.flag'
Start-Transcript -Path (Join-Path $logs ('kure-final-validation-v1-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.log'))
function Check-Stop {if(Test-Path -LiteralPath $stop){throw 'Final validation stopped by user; caches retained'}}
function Save-Json($Path,$Object){[IO.File]::WriteAllText($Path,($Object|ConvertTo-Json -Depth 32)+"`n",[Text.UTF8Encoding]::new($false))}
function Embed([string[]]$Inputs){
    Check-Stop
    $body=@{model='kure-v1:official';input=@($Inputs);truncate=$false;keep_alive='5m';options=@{num_thread=6;num_gpu=0}}|ConvertTo-Json -Depth 5 -Compress
    $r=Invoke-RestMethod -Method Post -Uri http://127.0.0.1:11436/api/embed -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 300
    if(@($r.embeddings).Count -ne $Inputs.Count){throw 'Embedding batch count mismatch'}
    return $r
}
function Assert-Owned($Name,$Metadata,[int]$Dimensions){
    $info=(Invoke-QdrantLab GET "collections/$Name").result
    foreach($key in $Metadata.Keys){if($info.config.metadata.$key -ne $Metadata[$key]){throw "Collection identity mismatch: $key"}}
    if($info.config.params.vectors.size -ne $Dimensions -or $info.config.params.vectors.distance -ne 'Cosine'){throw 'Vector schema mismatch'}
    return $info
}
try {
    Check-Stop
    & (Join-Path $PSScriptRoot 'test-final-validation.ps1')
    $manifest=Get-Content -Raw -Encoding UTF8 (Join-Path $dir 'manifest.json')|ConvertFrom-Json
    $freezeHash=(Get-FileHash (Join-Path $dir 'manifest.json')).Hash
    $data=Get-Content -Raw -Encoding UTF8 (Join-Path $dir 'questions.json')|ConvertFrom-Json
    $rewriteDoc=Get-Content -Raw -Encoding UTF8 (Join-Path $dir 'rewrites.json')|ConvertFrom-Json
    $rewriteMap=@{};foreach($r in $rewriteDoc.questions){$rewriteMap[$r.id]=$r.rewrittenText}
    $context=Get-QdrantLabContext 'kure-v1' 300
    foreach($field in @('tag','dimensions','inputMode','documentFormat','queryFormat','revision')){if($manifest.model.$field -ne $context.profile.$field){throw "Selected model profile changed: $field"}}
    $chunks=@(foreach($line in [IO.File]::ReadLines((Join-Path $dir 'corpus.jsonl'))){$line|ConvertFrom-Json})
    $docs=Read-QdrantLabCache $context
    $cacheDir=Join-Path $ResultsRoot ('shared-cache/kure-v1-'+$manifest.model.revision.Substring(0,16)+'-'+$manifest.model.inputMode)
    $texts=@{};$chunkKeys=@{}
    foreach($c in $chunks){$text=$context.profile.documentFormat.Replace('{title}',$c.title).Replace('{text}',$c.text);$key=[LegalDenseIndex]::InputKey($text);$texts[$key]=$text;$chunkKeys[$c.id]=$key}
    $missing=@($texts.Keys|Where-Object{!$docs.Contains($_)}|Sort-Object @{Expression={$texts[$_].Length}},@{Expression={$_}})
    $qwenRunning=docker inspect --format '{{.State.Running}}' molelaw-qwen3-lab
    if($LASTEXITCODE -eq 0 -and $qwenRunning -eq 'true'){throw 'Qwen is running; finish that inference before KURE'}
    docker compose -f docker/kure/compose.yml up -d --no-build
    if($LASTEXITCODE -ne 0){throw 'KURE startup failed'}
    $deadline=[datetime]::UtcNow.AddMinutes(15)
    while($true){Check-Stop;try{$runtime=Invoke-RestMethod http://127.0.0.1:11436/api/version -TimeoutSec 3;break}catch{if([datetime]::UtcNow -gt $deadline){throw 'KURE startup timeout'};Start-Sleep -Seconds 5}}
    Assert-QdrantLabRuntime $context $runtime
    $tags=Invoke-RestMethod http://127.0.0.1:11436/api/tags
    if(@($tags.models|Where-Object{ $_.name -eq 'kure-v1:official' -and $_.digest -eq $manifest.model.revision }).Count -ne 1){throw 'Checkpoint mismatch'}
    Write-Host "New document vectors: $($missing.Count); total chunks: $($chunks.Count). CPU 6 / memory 8GB."
    for($offset=0;$offset -lt $missing.Count;$offset+=16){
        $end=[math]::Min($offset+16,$missing.Count);$keys=[string[]]@($missing[$offset..($end-1)])
        $response=Embed ([string[]]@($keys|ForEach-Object{$texts[$_]}))
        $vectors=[single[][]]@($response.embeddings|ForEach-Object{,[single[]]$_})
        $docs.WriteBatch((Join-Path $cacheDir ('final-'+[guid]::NewGuid().ToString('N')+'.bin')),$keys,$vectors)
        Write-Host "Saved document vectors $end/$($missing.Count)"
    }
    Initialize-QdrantLab 6333
    $collection='molelaw_validation_kure_v1_c300_'+$manifest.corpusSha256.Substring(0,8).ToLowerInvariant()+'_'+$manifest.model.revision.Substring(0,8)
    $queryCollection=$collection+'_queries'
    $metadata=@{owner=$script:LabOwner;kind='final-validation-v1';model_digest=$manifest.model.revision;dataset_sha256=$manifest.datasetSha256;corpus_sha256=$manifest.corpusSha256;freeze_sha256=$freezeHash}
    foreach($name in @($collection,$queryCollection)){
        if(@((Invoke-QdrantLab GET 'collections').result.collections|Where-Object name -eq $name).Count -eq 0){$null=Invoke-QdrantLab PUT "collections/$name" @{vectors=@{size=1024;distance='Cosine'};metadata=$metadata}}
        $null=Assert-Owned $name $metadata 1024
    }
    foreach($field in @('unit_id','law_id')){$null=Invoke-QdrantLab PUT "collections/$collection/index?wait=true" @{field_name=$field;field_schema='keyword'}}
    for($offset=0;$offset -lt $chunks.Count;$offset+=64){
        Check-Stop;$end=[math]::Min($offset+64,$chunks.Count)
        $points=@(foreach($c in $chunks[$offset..($end-1)]){@{id=(Get-QdrantLabPointId $c.id);vector=@($docs.GetVector($chunkKeys[$c.id]));payload=@{lab_owner=$script:LabOwner;chunk_id=$c.id;unit_id=$c.unitId;law_id=$c.lawId;title=$c.title;text=$c.text;start_char=$c.startChar;end_char=$c.endChar;source_url=$c.sourceUrl;source_sha256=$c.sourceSha256;corpus_sha256=$manifest.corpusSha256}}})
        $null=Invoke-QdrantLab PUT "collections/$collection/points?wait=true" @{points=$points}
        if($offset%640 -eq 0 -or $end -eq $chunks.Count){Write-Host "Qdrant documents $end/$($chunks.Count)"}
    }
    if((Invoke-QdrantLab POST "collections/$collection/points/count" @{exact=$true}).result.count -ne $chunks.Count){throw 'Indexed document count mismatch'}
    $queryCacheDir=Join-Path $work 'query-cache';$null=New-Item -ItemType Directory -Force $queryCacheDir
    $queryCache=[LegalDenseIndex]::new(1024)
    $cacheMetadata=@{revision=$manifest.model.revision;runtime=$runtime.version;dtype=$runtime.dtype;inputMode=$manifest.model.inputMode}
    $metaPath=Join-Path $queryCacheDir 'metadata.json'
    if(Test-Path -LiteralPath $metaPath){$oldMeta=Get-Content -Raw -Encoding UTF8 $metaPath|ConvertFrom-Json;foreach($k in $cacheMetadata.Keys){if($oldMeta.$k -ne $cacheMetadata[$k]){throw 'Query cache runtime mismatch'}}}
    Save-Json $metaPath $cacheMetadata
    $oldQueryCache=Join-Path $ArtifactsRoot ('qdrant-query-cache/kure-v1-'+$manifest.model.revision.Substring(0,16)+'-'+$manifest.model.inputMode)
    $oldMeta=Get-Content -Raw -Encoding UTF8 (Join-Path $oldQueryCache 'metadata.json')|ConvertFrom-Json
    if($oldMeta.modelDigest -ne $manifest.model.revision -or $oldMeta.inferenceRuntimeVersion -ne $runtime.version -or $oldMeta.dtype -ne $runtime.dtype){throw 'Original query cache mismatch'}
    foreach($file in Get-ChildItem -LiteralPath $oldQueryCache -Filter '*.bin'){$queryCache.ReadBatch($file.FullName)}
    foreach($file in Get-ChildItem -LiteralPath $queryCacheDir -Filter '*.bin'){$queryCache.ReadBatch($file.FullName)}
    $queries=@(foreach($mode in @('original','rewritten')){foreach($q in $data.questions){$text=$(if($mode -eq 'original'){$q.text}else{$rewriteMap[$q.id]});[pscustomobject]@{mode=$mode;question=$q;text=$text;key=[LegalDenseIndex]::InputKey($text)}}})
    $pending=@{};foreach($q in $queries){if(!$queryCache.Contains($q.key)){$pending[$q.key]=$q.text}}
    $keys=@($pending.Keys|Sort-Object)
    for($offset=0;$offset -lt $keys.Count;$offset+=16){$end=[math]::Min($offset+16,$keys.Count);$batch=[string[]]@($keys[$offset..($end-1)]);$response=Embed ([string[]]@($batch|ForEach-Object{$pending[$_]}));$queryCache.WriteBatch((Join-Path $queryCacheDir ([guid]::NewGuid().ToString('N')+'.bin')),$batch,[single[][]]@($response.embeddings|ForEach-Object{,[single[]]$_}));Write-Host "New query vectors $end/$($keys.Count)"}
    $results=@();$topRows=@();$stored=@();$context.collection=$collection;$index=0
    foreach($query in $queries){
        Check-Stop;$q=$query.question;$vector=$queryCache.GetVector($query.key)
        $groups=@(Invoke-QdrantLabGroups $context $vector 10 $true)
        if($groups.Count -ne 10 -or @($groups.id|Select-Object -Unique).Count -ne 10){throw 'Invalid grouped result'}
        $hits=@();for($i=0;$i -lt 10;$i++){$hit=[pscustomobject]@{rank=$i+1;unitId=$groups[$i].id;similarity=$groups[$i].hits[0].score;relevant=($groups[$i].id -in @($q.relevantUnitIds))};$hits+=$hit;$topRows+=[pscustomobject]@{queryId=$q.id;queryMode=$query.mode;cohort=$q.cohort;rank=$hit.rank;unitId=$hit.unitId;similarity=$hit.similarity;relevant=$hit.relevant;title=$groups[$i].hits[0].payload.title}}
        $metrics=$null;$rr=$null
        if($q.answerability -eq 'in_corpus'){$metrics=Get-RankingMetrics $hits $q.relevantUnitIds;$first=@($hits|Where-Object relevant|Select-Object -First 1);$rr=$(if($first.Count){1.0/$first[0].rank}else{0.0})}
        $threshold=[double]$manifest.thresholds.($query.mode)
        $row=[ordered]@{queryId=$q.id;queryMode=$query.mode;cohort=$q.cohort;lawId=$q.sourceLawId;style=$q.style;answerability=$q.answerability;originalText=$q.text;retrievalText=$query.text;top1Similarity=$hits[0].similarity;top1Relevant=$hits[0].relevant;accepted=($hits[0].similarity -ge $threshold);threshold=$threshold;mrrAt10=$rr}
        foreach($k in @('recallAt5','recallAt10','hitAt5','completeAt5','precisionAt5','ndcgAt5','ndcgAt10')){$row[$k]=$metrics.$k}
        $results+=[pscustomobject]$row
        $stored+=@{id=(Get-QdrantLabPointId "$queryCollection/$($q.id)/$($query.mode)");vector=@($vector);payload=@{query_id=$q.id;query_mode=$query.mode;cohort=$q.cohort;question=$q;retrieval_text=$query.text;gold_unit_ids=@($q.relevantUnitIds);metrics=$row;top10=$hits;freeze_sha256=$freezeHash;document_collection=$collection}}
        if($stored.Count -eq 16){$null=Invoke-QdrantLab PUT "collections/$queryCollection/points?wait=true" @{points=$stored};$stored=@()}
        $index++;if($index%20 -eq 0){Write-Host "Evaluated $index/540 query variants"}
    }
    if($stored.Count){$null=Invoke-QdrantLab PUT "collections/$queryCollection/points?wait=true" @{points=$stored}}
    if($results.Count -ne 540 -or $topRows.Count -ne 5400 -or (Invoke-QdrantLab POST "collections/$queryCollection/points/count" @{exact=$true}).result.count -ne 540){throw 'Incomplete query results'}
    $summary=@(foreach($mode in @('original','rewritten')){foreach($cohort in @('legacy_development','legacy_held_out','expanded_held_out')){
        $selected=@($results|Where-Object{$_.queryMode -eq $mode -and $_.cohort -eq $cohort});$positive=@($selected|Where-Object answerability -eq 'in_corpus');$negative=@($selected|Where-Object answerability -eq 'out_of_corpus')
        $row=[ordered]@{queryMode=$mode;cohort=$cohort;positiveCount=$positive.Count;negativeCount=$negative.Count;noAnswerFalseAcceptanceRate=@($negative|Where-Object accepted).Count/[double]$negative.Count;inCorpusAcceptanceRate=@($positive|Where-Object accepted).Count/[double]$positive.Count}
        foreach($k in @('recallAt5','recallAt10','hitAt5','completeAt5','precisionAt5','ndcgAt5','ndcgAt10','mrrAt10')){$row[$k]=($positive|Measure-Object $k -Average).Average};[pscustomobject]$row
    }})
    $new=@($summary|Where-Object{$_.queryMode -eq 'rewritten' -and $_.cohort -eq 'expanded_held_out'})[0]
    $raw=@($summary|Where-Object{$_.queryMode -eq 'original' -and $_.cohort -eq 'expanded_held_out'})[0]
    $legacy=@($summary|Where-Object{$_.queryMode -eq 'rewritten' -and $_.cohort -eq 'legacy_held_out'})[0]
    $checks=@{newRecallAt5=($new.recallAt5 -ge $manifest.criteria.newRewrittenRecallAt5Minimum);newCompleteAt5=($new.completeAt5 -ge $manifest.criteria.newRewrittenCompleteAt5Minimum);legacyRecallRetention=($legacy.recallAt5 -ge $manifest.criteria.legacyHeldOutRecallAt5Minimum);rewriteNoRegression=($new.recallAt5 -ge $raw.recallAt5)}
    $passed=@($checks.Values|Where-Object{!$_}).Count -eq 0
    $strata=@(foreach($g in $results|Where-Object cohort -eq 'expanded_held_out'|Group-Object queryMode,style){$a=$g.Group[0];$r=[ordered]@{queryMode=$a.queryMode;style=$a.style;count=$g.Count;recallAt5=$null;completeAt5=$null;falseAcceptanceRate=$null};if($a.answerability -eq 'in_corpus'){$r.recallAt5=($g.Group|Measure-Object recallAt5 -Average).Average;$r.completeAt5=($g.Group|Measure-Object completeAt5 -Average).Average}else{$r.falseAcceptanceRate=@($g.Group|Where-Object accepted).Count/[double]$g.Count};[pscustomobject]$r})
    $report=@{completedAtUtc=[datetime]::UtcNow.ToString('o');freezeSha256=$freezeHash;manifest=$manifest;runtime=$runtime;documentCollection=$collection;queryCollection=$queryCollection;summary=$summary;strata=$strata;checks=$checks;retrievalCriteriaPassed=$passed;serviceReady=$false;scope='Expanded retrieval validation only; no answer-generation evaluation; thresholds frozen; MRR@10 only'}
    if((Get-FileHash (Join-Path $dir 'manifest.json')).Hash -ne $freezeHash -or (Get-FileHash (Join-Path $dir 'questions.json')).Hash -ne $manifest.datasetSha256 -or (Get-FileHash (Join-Path $dir 'rewrites.json')).Hash -ne $manifest.rewritesSha256){throw 'Frozen inputs changed during evaluation'}
    $results|Export-Csv (Join-Path $target 'queries.csv') -NoTypeInformation -Encoding UTF8
    $topRows|Export-Csv (Join-Path $target 'top10.csv') -NoTypeInformation -Encoding UTF8
    $summary|Export-Csv (Join-Path $target 'summary.csv') -NoTypeInformation -Encoding UTF8
    $strata|Export-Csv (Join-Path $target 'strata.csv') -NoTypeInformation -Encoding UTF8
    Save-Json (Join-Path $target 'report.json') $report
    $catalog='molelaw_validation_final_v1_manifest_'+$freezeHash.Substring(0,8).ToLowerInvariant()
    if(@((Invoke-QdrantLab GET 'collections').result.collections|Where-Object name -eq $catalog).Count -eq 0){$null=Invoke-QdrantLab PUT "collections/$catalog" @{vectors=@{};metadata=$metadata}}
    $info=(Invoke-QdrantLab GET "collections/$catalog").result;foreach($k in $metadata.Keys){if($info.config.metadata.$k -ne $metadata[$k]){throw 'Final catalog ownership mismatch'}}
    $null=Invoke-QdrantLab PUT "collections/$catalog/points?wait=true" @{points=@(@{id=1;vector=@{};payload=@{dataset=$data;rewrites=$rewriteDoc;manifest=$manifest;report=$report}})}
    $lines=@('# KURE-v1 확대 최종 검증','','모델·1024차원·300자·중복 64자·기존 수용 기준값을 고정했다. 기존 110문항은 회귀 확인, 새 160문항은 신규 영역 확인으로 구분한다. 30개 주 법령과 기존 보충 근거를 포함한다.','','| 질문 | 범위 | 양성 | 음성 | Recall@5 | Complete@5 | MRR@10 | 음성 오수용률 |','| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |')
    foreach($r in $summary){$lines+=('| {0} | {1} | {2} | {3} | {4:F2}% | {5:F2}% | {6:F4} | {7:F2}% |' -f $r.queryMode,$r.cohort,$r.positiveCount,$r.negativeCount,(100*$r.recallAt5),(100*$r.completeAt5),$r.mrrAt10,(100*$r.noAnswerFalseAcceptanceRate))}
    $checkText="사전 고정 검색 기준 통과: **$passed**. 각 항목: "+($checks|ConvertTo-Json -Compress)
    $lines+=@('',$checkText,'','신규 양성은 단일 근거 100개와 다중 근거 20개다. 음성은 외국 관할 20개, 제공되지 않은 개인 사건 사실 20개다. 세부 분포는 strata.csv를 참고한다. MRR@10은 기존 전체 순위 MRR과 직접 비교하지 않는다. 질문과 정제본은 공식 원문을 본 어시스턴트가 작성해 독립 블라인드 검증은 아니다. 법령별 표본은 무작위가 아니며 신규 영역 점수와 기존 점수의 차이를 모델 변경 효과로 해석하지 않는다.','', '음성 오수용률은 검색 점수의 기준값 통과율이다. 실제 답변의 안전성이나 거절 성능을 검증한 것은 아니다. serviceReady=false를 유지한다. 결과가 미달해도 이 데이터로 질문·정답·청크·기준값을 다시 조정하지 않는다.','', '[선정 근거](../../../../model-comparison/review/model-selection.md) · [고정 입력](../../data/manifest.json)')
    [IO.File]::WriteAllText((Join-Path $target 'README.md'),($lines -join "`n")+"`n",[Text.UTF8Encoding]::new($false))
    $summary|Format-Table queryMode,cohort,recallAt5,completeAt5,mrrAt10,noAnswerFalseAcceptanceRate
    Write-Host "FINAL VALIDATION COMPLETE. Retrieval criteria passed: $passed. See reports/final-validation-v1/kure-v1/README.md" -ForegroundColor Green
} finally {
    try{$body=@{model='kure-v1:official';input=@();keep_alive=0}|ConvertTo-Json; $null=Invoke-RestMethod -Method Post http://127.0.0.1:11436/api/embed -ContentType application/json -Body $body -TimeoutSec 10}catch{}
    docker compose -f docker/kure/compose.yml stop kure
    Stop-Transcript
}
